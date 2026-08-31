package com.perf.globalorchestrator.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * One named cache stored in {@code ORCH_CACHE}.
 *
 * <p>Null values are not stored — every {@code @Cacheable} on this service
 * already carries an {@code unless} that drops them, and "absent" and "cached
 * null" answer the same question here.
 *
 * <p>{@link #get(Object, Callable)} deliberately does <b>not</b> lock: two
 * instances computing the same terminal-run aggregate at once is cheaper, and
 * far less fragile, than holding a row lock across an aggregate query. The
 * result is identical either way, because only immutable values are cached.
 */
public class OracleCache implements Cache {

    private final String name;
    private final Duration ttl;
    private final OracleCacheStore store;
    private final CacheValueCodec codec;

    public OracleCache(String name, Duration ttl, OracleCacheStore store, CacheValueCodec codec) {
        this.name = name;
        this.ttl = ttl;
        this.store = store;
        this.codec = codec;
    }

    @Override public String getName() { return name; }

    @Override public Object getNativeCache() { return store; }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        Object value = lookup(key);
        return value == null ? null : new SimpleValueWrapper(value);
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, @Nullable Class<T> type) {
        Object value = lookup(key);
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "cached value for key '" + key + "' is not of required type " + type.getName());
        }
        return (T) value;
    }

    @Override
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object cached = lookup(key);
        if (cached != null) {
            @SuppressWarnings("unchecked") T hit = (T) cached;
            return hit;
        }
        T loaded;
        try {
            loaded = valueLoader.call();
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
        put(key, loaded);
        return loaded;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        if (value == null) return;
        store.put(OracleCacheStore.cacheKey(name, key), name, codec.encode(value), ttl);
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = get(key);
        if (existing != null) return existing;
        put(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        store.evict(OracleCacheStore.cacheKey(name, key));
    }

    @Override
    public void clear() {
        store.clear(name);
    }

    @Nullable
    private Object lookup(Object key) {
        byte[] stored = store.get(OracleCacheStore.cacheKey(name, key));
        if (stored == null) return null;
        try {
            return codec.decode(stored);
        } catch (RuntimeException e) {
            // A value this instance cannot read is a value no instance should
            // keep serving — most likely a shape that changed across a deploy.
            // Drop it and answer as a miss; the caller recomputes.
            evict(key);
            return null;
        }
    }
}
