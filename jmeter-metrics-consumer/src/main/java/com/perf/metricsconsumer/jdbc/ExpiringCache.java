package com.perf.metricsconsumer.jdbc;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A bounded map with per-entry expiry and single-flight loads: concurrent
 * misses on one key run the loader once, a loader failure caches nothing, and
 * once the map outgrows {@code maxSize} the expired entries go first, then the
 * soonest-expiring ones. Per JVM instance — replicas each hold their own copy.
 */
public final class ExpiringCache<K, V> {

    private record Entry<V>(V value, long expiresAt) { }

    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxSize;

    public ExpiringCache(long ttlMillis, int maxSize) {
        if (ttlMillis < 1 || maxSize < 1) {
            throw new IllegalArgumentException("ttl and maxSize must be positive: " + ttlMillis + ", " + maxSize);
        }
        this.ttlMillis = ttlMillis;
        this.maxSize = maxSize;
    }

    private final ConcurrentHashMap<K, java.util.concurrent.CompletableFuture<Entry<V>>> inFlight = new ConcurrentHashMap<>();

    /**
     * The cached value for {@code key}, or the loader's — loaded once per key at
     * a time, never cached when it throws. The loader runs OUTSIDE any map lock
     * (a slow database load must not stall unrelated keys in the same hash bin);
     * concurrent misses on one key wait on the same in-flight load.
     */
    public V get(K key, Function<K, V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> hit = map.get(key);
        if (hit != null && hit.expiresAt > now) {
            return hit.value;
        }
        var mine = new java.util.concurrent.CompletableFuture<Entry<V>>();
        var theirs = inFlight.putIfAbsent(key, mine);
        if (theirs != null) {
            try {
                return theirs.join().value;
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }
        try {
            Entry<V> current = map.get(key);
            if (current != null && current.expiresAt > now) {
                mine.complete(current);
                return current.value;
            }
            Entry<V> fresh = new Entry<>(loader.apply(key), now + ttlMillis);
            map.put(key, fresh);
            mine.complete(fresh);
            if (map.size() > maxSize) {
                evict(now);
            }
            return fresh.value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key);
        }
    }

    public void invalidate(K key) {
        map.remove(key);
    }

    public int size() {
        return map.size();
    }

    private void evict(long now) {
        map.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        int over = map.size() - maxSize;
        if (over <= 0) {
            return;
        }
        List<K> soonest = map.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().expiresAt))
                .limit(over)
                .map(Map.Entry::getKey)
                .toList();
        soonest.forEach(map::remove);
    }
}
