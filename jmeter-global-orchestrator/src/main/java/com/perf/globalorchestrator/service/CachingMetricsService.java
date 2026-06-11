package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.repo.MetricsRollupRepository;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * CACHE C-1 (2026-05-26) — caches the per-run metrics surfaces, but only for
 * <b>terminal</b> runs.
 *
 * <p>Why terminal-only: the per-second {@code metrics."workerMetric"} rows are
 * written by the separate {@code jmeter-metrics-consumer}, out-of-band from this
 * service. While a run is active those rows are appended every second and the
 * orchestrator never sees the write, so it cannot invalidate a cached entry —
 * caching active-run metrics would freeze the live chart (the worst failure
 * mode). Once a run reaches a terminal state the consumer stops writing and the
 * rows are immutable, so the aggregated response is too: cache it and skip the
 * six-table aggregate on every subsequent refresh.
 *
 * <p>The {@code condition = "#state != null && #state.terminal"} SpEL gates the
 * cache entirely: when it evaluates false (active run, or unknown state) Spring
 * does <b>no</b> cache lookup and <b>no</b> put — the call goes straight to the
 * repository (= Postgres) for fresh per-second data. {@code #state.terminal}
 * resolves to {@link RunState#isTerminal()}.
 *
 * <p>The timeseries cache is keyed on
 * {@code runId + ':' + byRegion + ':' + windowSeconds} (not state) so a run
 * cached while terminal is found regardless of the caller's state argument, and
 * the aggregate / region-split / per-time-window payloads each cache as distinct
 * entries (they're different shapes and sizes). Eviction is TTL-only (1 h, see
 * {@link CacheConfig}); terminal data is immutable so there is nothing to
 * invalidate short of a future run-purge. (The time-window selector mainly pays
 * off on active long runs, where caching is bypassed and the window prunes the
 * per-poll scan + aggregate to the recent slice.)
 *
 * <p>Caching takes effect only when invoked through the Spring proxy — callers
 * must inject this bean (as {@code RunController} does), not call the repository
 * directly.
 */
@Service
public class CachingMetricsService {

    private final MetricsTimeseriesRepository timeseriesRepo;
    private final MetricsRollupRepository rollupRepo;

    /**
     * Trailing seconds to drop from a <b>live</b> run's timeseries — the newest
     * data is still being aggregated by the workers (≈2 s window-close grace) and
     * ingested by the metrics-consumer (Kafka fetch-wait + batch INSERT), so it's
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
