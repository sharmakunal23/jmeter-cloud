package com.perf.globalorchestrator.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * CACHE C-0 (2026-05-26) — the shared-cache substrate.
 *
 * <p>Per the operator's direction, the cache lives <b>outside</b> the service
 * in Redis so every
 * global-orchestrator instance reads the same data. Caching is expressed with
 * Spring's {@code @Cacheable} / {@code @CacheEvict} annotations (see
 * {@code CachingMetricsService} and {@code ApplicationController}); this class
 * only configures the provider.
 *
 * <h2>Provider selection</h2>
 * The provider is chosen by {@code spring.cache.type}: {@code redis} at runtime
 * (see {@code application.yml}), {@code simple} in tests (a
 * {@code ConcurrentMapCacheManager}, wired by the test-only
 * {@code application-local.yml}). The terminal-vs-active gating is
 * provider-agnostic, so tests need no Redis. When {@code simple} is active the
 * {@link RedisCacheManagerBuilderCustomizer} bean below is simply never invoked.
 *
 * <h2>Per-cache TTLs</h2>
 * <ul>
 *   <li>{@link #CACHE_RUN_TIMESERIES} / {@link #CACHE_RUN_ROLLUP} — 1 h.
 *       Terminal-run metrics are immutable, so the TTL is only a freshness /
 *       turnover bound (e.g. a future run-purge that removes the underlying
 *       rows), not the memory guard — see the note below.</li>
 *   <li>{@link #CACHE_APPLICATION_CAPACITY} — 10 m. The per-(app, region)
 *       capacity grid is mutable but orchestrator-owned and slow-moving, so
 *       it is <b>evicted on every write</b> ({@code @CacheEvict} on the
 *       repository's upsert / replaceAll / delete, plus the app-delete DB
 *       cascade); the short TTL is only a backstop in case a write path is
 *       ever missed. (The application <i>registry</i> itself is deliberately
 *       NOT cached — {@code ApplicationHealthPoller} rewrites it every 30 s,
 *       making it fast-changing.)</li>
 * </ul>
 *
 * <h2>Serialization</h2>
 * Values serialize as JSON ({@link GenericJackson2JsonRedisSerializer}) rather
 * than JDK serialization — the cached DTOs are Java records, which are not
 * {@code Serializable}, and JSON is human-inspectable in Redis. Default typing
 * is set to {@code EVERYTHING} so the {@code @class} type tag is written even
 * for {@code final} record types (without it, polymorphic round-trips of
 * records fail to resolve their concrete type on read).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Per-run timeseries response for a TERMINAL run (immutable). */
    public static final String CACHE_RUN_TIMESERIES = "runTimeseries";
    /** Per-label rollup for a TERMINAL run (immutable). */
    public static final String CACHE_RUN_ROLLUP = "runRollup";
    /** Run row + fleet members for a TERMINAL run (frozen; C-2). */
    public static final String CACHE_RUN_METADATA = "runMetadata";
    /** Per-(app, region) capacity grid (orchestrator-owned writes → evicted on update). */
    public static final String CACHE_APPLICATION_CAPACITY = "applicationCapacity";
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
                .prefixCacheNameWith("jmeterCloud:globalOrchestrator:");
        return builder -> builder
                .enableStatistics()
                .cacheDefaults(base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_TIMESERIES, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_ROLLUP, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_RUN_METADATA, base.entryTtl(TERMINAL_RUN_TTL))
                .withCacheConfiguration(CACHE_APPLICATION_CAPACITY, base.entryTtl(CAPACITY_TTL))
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
