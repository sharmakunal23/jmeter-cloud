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

    /** The cached value for {@code key}, or the loader's — loaded once per key at a time, never cached when it throws. */
    public V get(K key, Function<K, V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> hit = map.get(key);
        if (hit != null && hit.expiresAt > now) {
            return hit.value;
        }
        Entry<V> fresh = map.compute(key, (k, current) -> {
            if (current != null && current.expiresAt > now) {
                return current;
            }
            return new Entry<>(loader.apply(k), now + ttlMillis);
        });
        if (map.size() > maxSize) {
            evict(now);
        }
        return fresh.value;
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
