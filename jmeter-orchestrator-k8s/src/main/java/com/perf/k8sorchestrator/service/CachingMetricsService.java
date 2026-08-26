package com.perf.k8sorchestrator.service;

import com.perf.k8sorchestrator.config.CacheConfig;
import com.perf.k8sorchestrator.domain.MetricsTimeseries;
import com.perf.k8sorchestrator.domain.RunState;
import com.perf.k8sorchestrator.repo.MetricsRollupRepository;
import com.perf.k8sorchestrator.repo.MetricsTimeseriesRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Caches the per-run metrics surfaces, but only for <b>terminal</b> runs.
 *
 * <p>That restriction is the whole design. The per-second rows are written by
 * the metrics-consumer, out of band from this service, so while a run is active
 * this service never sees the write and cannot invalidate an entry — caching a
 * live run would freeze its chart, the worst failure mode available here. Once
 * a run is terminal the rows are immutable and so is the aggregate.
 *
 * <p>The {@code condition} SpEL gates the cache entirely: when it is false
 * Spring does no lookup and no put, and the call goes straight to Postgres for
 * fresh data. The timeseries key is
 * {@code runId + ':' + byRegion + ':' + windowSeconds} and deliberately excludes
 * state, so an entry cached while terminal is found whatever the caller passes;
 * the aggregate, region-split and per-window payloads are different shapes and
 * cache separately. Eviction is TTL-only, since immutable data has nothing to
 * invalidate short of a purge.
 *
 * <p>Caching only applies through the Spring proxy: callers must inject this
 * bean, not the repository.
 */
@Service
public class CachingMetricsService {

    private final MetricsTimeseriesRepository timeseriesRepo;
    private final MetricsRollupRepository rollupRepo;

    /**
     * Trailing seconds to drop from a <b>live</b> run's timeseries — the newest
     * data is still being aggregated by the workers (≈2 s window-close grace) and
     * ingested by the metrics-consumer (ingest + batch INSERT), so it's
     * partially filled and wobbles every poll. 5 s covers that worst-case
     * end-to-end lag with headroom for cross-worker skew. Terminal runs ignore
     * this (their rows are immutable). See {@link MetricsTimeseriesRepository
     * #timeseries(String, boolean, Long, int)}.
     */
    private final int settleSeconds;

    public CachingMetricsService(MetricsTimeseriesRepository timeseriesRepo,
                                 MetricsRollupRepository rollupRepo,
                                 @Value("${metrics.timeseries.settleSeconds:5}") int settleSeconds) {
        this.timeseriesRepo = timeseriesRepo;
        this.rollupRepo = rollupRepo;
        this.settleSeconds = settleSeconds;
    }

    /**
     * Per-second timeseries for {@code GET /runs/{runId}/timeseries} (and each
     * id in the comparison batch). Cached for terminal runs; bypassed for
     * active runs.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_TIMESERIES,
               key = "#runId + ':' + #byRegion + ':' + (#windowSeconds == null ? 'all' : #windowSeconds)",
               condition = "#state != null && #state.terminal")
    public MetricsTimeseries timeseries(String runId, RunState state, boolean byRegion, Long windowSeconds) {
        // Live runs bypass the cache (the condition above) and hit Postgres fresh
        // on every 5 s poll — trim the unsettled trailing edge so the chart is
        // stable poll-to-poll. Terminal runs are immutable: settle 0 shows the
        // true final second, and that complete response is what gets cached.
        boolean live = state == null || !state.isTerminal();
        return timeseriesRepo.timeseries(runId, byRegion, windowSeconds, live ? settleSeconds : 0);
    }

    /**
     * Per-label rollup for {@code GET /runs/{runId}/metrics}. Same terminal-only
     * gating as {@link #timeseries}.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_ROLLUP,
               key = "#runId",
               condition = "#state != null && #state.terminal")
    public List<Map<String, Object>> rollupByLabel(String runId, RunState state) {
        return rollupRepo.rollupByLabel(runId);
    }
}
