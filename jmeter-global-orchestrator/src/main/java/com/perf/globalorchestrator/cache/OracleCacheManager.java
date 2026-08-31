package com.perf.globalorchestrator.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the {@link OracleCache} instances declared in
 * {@code config.CacheConfig}. Every cache is known up front — a name the
 * configuration does not declare gets the default TTL rather than no cache at
 * all, so a new {@code @Cacheable} still works before its TTL is chosen.
 */
public class OracleCacheManager implements CacheManager {

    private final OracleCacheStore store;
    private final CacheValueCodec codec;
    private final Duration defaultTtl;
    private final Map<String, Duration> ttls;
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();

    public OracleCacheManager(OracleCacheStore store, CacheValueCodec codec,
                              Duration defaultTtl, Map<String, Duration> ttls) {
        this.store = store;
        this.codec = codec;
        this.defaultTtl = defaultTtl;
        this.ttls = new LinkedHashMap<>(ttls);
        for (String name : this.ttls.keySet()) {
            caches.put(name, build(name));
        }
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, this::build);
    }

    @Override
    public Collection<String> getCacheNames() {
        return java.util.Set.copyOf(caches.keySet());
    }

    private Cache build(String name) {
        return new OracleCache(name, ttls.getOrDefault(name, defaultTtl), store, codec);
    }
}
