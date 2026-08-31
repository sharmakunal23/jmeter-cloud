package com.perf.globalorchestrator.config;

import com.perf.globalorchestrator.cache.CacheValueCodec;
import com.perf.globalorchestrator.cache.OracleCacheManager;
import com.perf.globalorchestrator.cache.OracleCacheStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configures the cache provider. The cache lives outside the process in the
 * {@code ORCH_CACHE} table so every orchestrator instance reads the same data,
 * and the caching itself is expressed with {@code @Cacheable} /
 * {@code @CacheEvict} elsewhere — this class only wires the provider and owns
 * the cache names and their TTLs.
 *
 * <p>{@code globalOrchestrator.cache.provider} selects it: {@code oracle} at
 * runtime, {@code simple} in tests (an in-process {@code ConcurrentMapCacheManager},
 * so no database is needed). The terminal-vs-active gating is provider-agnostic.
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
 *   <li>{@link #CACHE_MEMBER_LOGS} — 30 m. Log tails are the largest entries
 *       (up to 10k lines), so they turn over faster than the run defaults; the
 *       store's {@code maxValueBytes} guard is what actually bounds them.</li>
 * </ul>
 *
 * <p>Values are stored as gzipped JSON, not JDK serialization: the cached DTOs
 * are Java records, which are not {@code Serializable}. See
 * {@link CacheValueCodec}.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CacheConfig.class);

    /**
     * A cache outage degrades to the database, never to a 500: every cache
     * get/put/evict failure is logged and swallowed, so {@code @Cacheable}
     * methods fall through to their body when the store is unreachable.
     * {@link OracleCacheStore} already swallows its own JDBC failures — this is
     * the backstop for everything above it (serialization, key building).
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

    // TTL is a freshness/turnover bound. There is no LRU eviction underneath it,
    // so TTL plus the store's maxValueBytes guard are what keep ORCH_CACHE
    // small — and ORCH_CACHE_REAP_JOB is what reclaims the space, in bounded
    // chunks, ten minutes at a time.
    private static final Duration TERMINAL_RUN_TTL = Duration.ofHours(1);
    private static final Duration CAPACITY_TTL = Duration.ofMinutes(10);
    private static final Duration MEMBER_LOGS_TTL = Duration.ofMinutes(30);

    /** The declared caches and their TTLs — the one place either is stated. */
    static Map<String, Duration> cacheTtls() {
        Map<String, Duration> ttls = new LinkedHashMap<>();
        ttls.put(CACHE_RUN_TIMESERIES, TERMINAL_RUN_TTL);
        ttls.put(CACHE_RUN_ROLLUP, TERMINAL_RUN_TTL);
        ttls.put(CACHE_RUN_SUMMARY, TERMINAL_RUN_TTL);
        ttls.put(CACHE_RUN_METADATA, TERMINAL_RUN_TTL);
        ttls.put(CACHE_GROUP_CAPACITY, CAPACITY_TTL);
        ttls.put(CACHE_MEMBER_LOGS, MEMBER_LOGS_TTL);
        return ttls;
    }

    /**
     * The runtime provider: every entry is a row in {@code ORCH_CACHE}, written
     * through the run-state pool (the writer identity that owns the table) on
     * that pool's short-statement template — a cache lookup must never cost
     * more than the query it is saving.
     *
     * @param maxValueBytes largest compressed value that is stored at all; a
     *                      bigger one is skipped, so an oversized log tail
     *                      cannot push a multi-megabyte LOB through Oracle.
     */
    @Bean
    @ConditionalOnProperty(name = "globalOrchestrator.cache.provider",
                           havingValue = "oracle", matchIfMissing = true)
    CacheManager oracleCacheManager(
            @Qualifier("cacheJdbcTemplate") JdbcTemplate jdbc,
            @Value("${globalOrchestrator.cache.maxValueBytes:1048576}") int maxValueBytes) {
        return new OracleCacheManager(
                new OracleCacheStore(jdbc, maxValueBytes),
                new CacheValueCodec(),
                TERMINAL_RUN_TTL,
                cacheTtls());
    }

    /** Test provider — in-process, no database. */
    @Bean
    @ConditionalOnProperty(name = "globalOrchestrator.cache.provider", havingValue = "simple")
    CacheManager simpleCacheManager() {
        return new ConcurrentMapCacheManager(cacheTtls().keySet().toArray(new String[0]));
    }
}
