package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.repo.MetricsTarget;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository.Query;
import com.perf.globalorchestrator.repo.RunMetricsRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-run metrics reads, resolved to the run's group table by
 * {@link MetricsGroupResolver} and cached for <b>terminal</b> runs only: the
 * rows are written by the metrics-consumer out of band, so a live run is never
 * cached (its chart would freeze), while a finished run's rows are immutable
 * and so is the aggregate. A run without a resolvable group answers with empty
 * series — the friendly shape the polling UI expects before the first row.
 *
 * <p>Caching only applies through the Spring proxy: callers inject this bean,
 * not the repositories.
 */
@Service
public class CachingMetricsService {

    private final MetricsTimeseriesRepository timeseriesRepo;
    private final RunMetricsRepository runMetricsRepo;
    private final MetricsGroupResolver resolver;
    private final RunRepository runs;

    /**
     * Trailing seconds dropped from a <b>live</b> run's series: a 15-second
     * window is published by each worker once it closes (plus grace), so the
     * newest window is still filling for ~20 s and would wobble every poll.
     */
    private final int settleSeconds;

    public CachingMetricsService(MetricsTimeseriesRepository timeseriesRepo,
                                 RunMetricsRepository runMetricsRepo,
                                 MetricsGroupResolver resolver,
                                 RunRepository runs,
                                 @Value("${metrics.timeseries.settleSeconds:20}") int settleSeconds) {
        this.timeseriesRepo = timeseriesRepo;
        this.runMetricsRepo = runMetricsRepo;
        this.resolver = resolver;
        this.runs = runs;
        this.settleSeconds = settleSeconds;
    }

    /** The aggregate / region-split / windowed read the UI and the AI digest make. */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_TIMESERIES,
               key = "#runId + ':' + #byRegion + ':' + (#windowSeconds == null ? 'all' : #windowSeconds)",
               condition = "#state != null && #state.terminal",
               // An empty series is not a fact about the run: its rows may not have
               // landed yet or its group may be unresolved — never pin that in Redis.
               unless = "#result == null || #result.series().tps().isEmpty()")
    public MetricsTimeseries timeseries(String runId, RunState state, boolean byRegion, Long windowSeconds) {
        return read(runId, state, new Query(byRegion, false, null, windowSeconds));
    }

    /** The full read: region and application splits, a forced granularity, a window. */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_TIMESERIES,
               key = "#runId + ':' + #byRegion + ':' + #byApplication + ':' + (#granularity == null ? 'auto' : #granularity) "
                     + "+ ':' + (#windowSeconds == null ? 'all' : #windowSeconds)",
               condition = "#state != null && #state.terminal",
               // An empty series is not a fact about the run: its rows may not have
               // landed yet or its group may be unresolved — never pin that in Redis.
               unless = "#result == null || #result.series().tps().isEmpty()")
    public MetricsTimeseries timeseries(String runId, RunState state, boolean byRegion, boolean byApplication,
                                        Integer granularity, Long windowSeconds) {
        return read(runId, state, new Query(byRegion, byApplication, granularity, windowSeconds));
    }

    /** Per-label table for {@code GET /runs/{runId}/metrics}; same terminal-only gating. */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_ROLLUP, key = "#runId",
               condition = "#state != null && #state.terminal",
               unless = "#result == null || #result.isEmpty()")
    public List<Map<String, Object>> rollupByLabel(String runId, RunState state) {
        Optional<Run> run = runs.findByRunId(runId);
        if (run.isEmpty()) {
            return List.of();
        }
        Optional<MetricsTarget> target = resolver.resolve(run.get());
        return target.map(t -> runMetricsRepo.rollupByLabel(t, RunWindow.of(run.get()))).orElse(List.of());
    }

    private MetricsTimeseries read(String runId, RunState state, Query q) {
        Optional<Run> run = runs.findByRunId(runId);
        if (run.isEmpty()) {
            return empty(runId);
        }
        Optional<MetricsTarget> target = resolver.resolve(run.get());
        if (target.isEmpty()) {
            return empty(runId);
        }
        boolean live = state == null || !state.isTerminal();
        return timeseriesRepo.timeseries(runId, target.get(), RunWindow.of(run.get()), q, live ? settleSeconds : 0);
    }

    private static MetricsTimeseries empty(String runId) {
        return new MetricsTimeseries(runId, MetricsTimeseriesRepository.WINDOW_SECONDS, null, null, Series.empty());
    }
}
