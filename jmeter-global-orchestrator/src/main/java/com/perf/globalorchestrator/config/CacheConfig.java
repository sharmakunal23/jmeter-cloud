package com.perf.globalorchestrator.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Configures the cache provider. The cache itself lives outside the service in
 * Redis so every orchestrator instance reads the same data, and the caching is
 * expressed with {@code @Cacheable} / {@code @CacheEvict} elsewhere — this class
 * only wires the provider.
 *
 * <p>{@code spring.cache.type} selects it: {@code redis} at runtime,
 * {@code simple} in tests, where the customizer below is simply never invoked
 * so no Redis is needed. The terminal-vs-active gating is provider-agnostic.
 *
 * <p>TTLs differ by what the entry is:
 * <ul>
 *   <li>{@link #CACHE_RUN_TIMESERIES} / {@link #CACHE_RUN_ROLLUP} / {@link #CACHE_RUN_SUMMARY} — 1 h.
 *       Terminal-run metrics are immutable, so this bounds turnover (a later
 *       purge removing the rows), not memory.</li>
 *   <li>{@link #CACHE_GROUP_CAPACITY} — 10 m, but <b>evicted on every
 *       write</b>; the TTL is only a backstop for a write path someone forgot
 *       to annotate. The application <i>registry</i> is deliberately not cached
 *       at all — {@code ApplicationHealthPoller} rewrites it every 30 s.</li>
 * </ul>
 *
 * <p>Values serialize as JSON, not JDK serialization: the cached DTOs are Java
 * records, which are not {@code Serializable}. Default typing is
 * {@code EVERYTHING} so the {@code @class} tag is written even for {@code final}
 * records — without it a polymorphic round-trip cannot resolve its concrete
 * type on read.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CacheConfig.class);

    /**
     * A cache outage degrades to the database, never to a 500: every cache
     * get/put/evict failure is logged and swallowed, so {@code @Cacheable}
     * methods fall through to their body when Redis is down.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                LOG.warn("cache GET {}:{} failed — serving from the database: {}", cache.getName(), key, e.toString());
            }
            @Override public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                LOG.warn("cache PUT {}:{} failed — entry not cached: {}", cache.getName(), key, e.toString());
            }
            @Override public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                LOG.warn("cache EVICT {}:{} failed: {}", cache.getName(), key, e.toString());
            }
            @Override public void handleCacheClearError(RuntimeException e, Cache cache) {
                LOG.warn("cache CLEAR {} failed: {}", cache.getName(), e.toString());
            }
        };
    }

    /** Per-run timeseries response for a TERMINAL run (immutable). */
    public static final String CACHE_RUN_TIMESERIES = "runTimeseries";
    /** Per-label rollup for a TERMINAL run (immutable). */
    public static final String CACHE_RUN_ROLLUP = "runRollup";
    /** The headline stats for a terminal run (`GET /runs/{id}/summary`). */
    public static final String CACHE_RUN_SUMMARY = "runSummary";
    /** Run row + fleet members for a TERMINAL run (frozen; C-2). */
    public static final String CACHE_RUN_METADATA = "runMetadata";
    /** Per-(app, region) capacity grid (orchestrator-owned writes → evicted on update). */
    public static final String CACHE_GROUP_CAPACITY = "groupCapacity";
    /** Per-(run, worker, stream, tail) log tail for a TERMINAL member (frozen; C-5). */
    public static final String CACHE_MEMBER_LOGS = "memberLogs";

    // TTL is a freshness/turnover bound, NOT the OOM guard — the Redis
    // container is started with `--maxmemory 256mb --maxmemory-policy
    // allkeys-lru`, so Redis evicts LRU keys under pressure and cannot OOM
    // regardless of TTL. 1 h caps how long any entry (incl. a hypothetically
    // purged run) can linger; capacity is shorter still.
    private static final Duration TERMINAL_RUN_TTL = Duration.ofHours(1);
    private static final Duration CAPACITY_TTL = Duration.ofMinutes(10);
    // Log-tail entries are large (up to 10k lines), so a tighter bound than the
    // terminal-run default keeps the working set small; LRU+maxmemory is still
    // the hard cap.
    private static final Duration MEMBER_LOGS_TTL = Duration.ofMinutes(30);

    /**
     * Customizes the auto-configured {@code RedisCacheManager} (active only when
     * {@code spring.cache.type=redis}). Sets the JSON serializer, a per-service
     * key prefix (this Redis may be shared by other services later), and the
     * per-cache TTLs above. Statistics are enabled so Spring Boot publishes
     * {@code cache.gets{result=hit|miss}} / {@code cache.puts} to
     * {@code /actuator/prometheus}.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer cacheManagerCustomizer() {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()))
                // Cached methods never return null for a cache-eligible call, so
                // skip NullValue handling entirely (it would otherwise need its
                // own serializer wiring with the custom ObjectMapper).
                .disableCachingNullValues()
                // D-4 — distinct prefix so a Redis shared with the k8s
                // orchestrator (the local dev case) never serves its entries.
                .prefixCacheNameWith("jmeterCloud:globalOrchestrator:");
        return builder -> builder
                .enableStatistics()
                .cacheDefaults(base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_TIMESERIES, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_ROLLUP, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_SUMMARY, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_METADATA, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_GROUP_CAPACITY, base.entryTtl(CAPACITY_TTL))
                .withCacheConfiguration(CACHE_MEMBER_LOGS, base.entryTtl(MEMBER_LOGS_TTL));
    }

    /** Package-private so {@code CacheSerializationTest} can pin record round-trips. */
    static GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
