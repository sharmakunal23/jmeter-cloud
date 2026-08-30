package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.RunSummary;
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
 * not the repositories — and there are no convenience overloads here on
 * purpose, since one method calling another inside the bean would bypass it.
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

    /** Every split the Metrics tab can ask for; the per-label split takes an optional exact prefix and a cap. */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_TIMESERIES,
               key = "#runId + ':' + #byRegion + ':' + #byApplication + ':' + #byLabel + ':' + (#labelPrefix == null ? '' : #labelPrefix) "
                     + "+ ':' + (#labelLimit == null ? '' : #labelLimit) + ':' + (#granularity == null ? 'auto' : #granularity) "
                     + "+ ':' + (#windowSeconds == null ? 'all' : #windowSeconds)",
               condition = "#state != null && #state.terminal",
               // An empty series is not a fact about the run: its rows may not have
               // landed yet or its group may be unresolved — never pin that in Redis.
               unless = "#result == null || #result.series().tps().isEmpty()")
    public MetricsTimeseries timeseries(String runId, RunState state, boolean byRegion, boolean byApplication,
                                        boolean byLabel, String labelPrefix, Integer labelLimit,
                                        Integer granularity, Long windowSeconds) {
        return read(runId, state, new Query(byRegion, byApplication, byLabel, labelPrefix, labelLimit, granularity, windowSeconds));
    }

    /**
     * The aggregate report for {@code GET /runs/{runId}/metrics} over a trailing
     * window (null = the whole run), narrowed to a label prefix (null = every
     * label) and to the {@code labelLimit} busiest labels
     * ({@link MetricsTimeseriesRepository#LABELS_ALL} = every label); same
     * terminal-only gating.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_ROLLUP,
               key = "#runId + ':' + (#windowSeconds == null ? 'all' : #windowSeconds) + ':' + (#labelPrefix == null ? '' : #labelPrefix) + ':' + #labelLimit",
               condition = "#state != null && #state.terminal",
               unless = "#result == null || #result.isEmpty()")
    public List<Map<String, Object>> rollupByLabel(String runId, RunState state, Long windowSeconds, String labelPrefix, int labelLimit) {
        Optional<Run> run = runs.findByRunId(runId);
        if (run.isEmpty()) {
            return List.of();
        }
        Optional<MetricsTarget> target = resolver.resolve(run.get());
        if (target.isEmpty()) {
            return List.of();
        }
        RunWindow w = range(run.get(), state, target.get(), windowSeconds);
        if (w == null) {
            return List.of();
        }
        return runMetricsRepo.rollupByLabel(target.get(), w, MetricsTimeseriesRepository.likePrefix(labelPrefix), labelLimit);
    }

    /** The headline stats for {@code GET /runs/{runId}/summary}; same terminal-only gating. */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_SUMMARY,
               key = "#runId + ':' + (#windowSeconds == null ? 'all' : #windowSeconds)",
               condition = "#state != null && #state.terminal",
               unless = "#result == null || #result.total().samples() == 0")
    public RunSummary summary(String runId, RunState state, Long windowSeconds) {
        Optional<Run> run = runs.findByRunId(runId);
        if (run.isEmpty()) {
            return RunSummary.empty(runId);
        }
        Optional<MetricsTarget> target = resolver.resolve(run.get());
        if (target.isEmpty()) {
            return RunSummary.empty(runId);
        }
        RunWindow w = range(run.get(), state, target.get(), windowSeconds);
        if (w == null) {
            return RunSummary.empty(runId);
        }
        return runMetricsRepo.summary(runId, target.get(), w);
    }

    /**
     * The slice every panel reads: the whole run when nothing narrows it,
     * otherwise the same trailing window (and live settle) the charts use.
     */
    private RunWindow range(Run run, RunState state, MetricsTarget t, Long windowSeconds) {
        boolean live = state == null || !state.isTerminal();
        RunWindow whole = RunWindow.of(run);
        if (windowSeconds == null && !live) {
            return whole;
        }
        return timeseriesRepo.resolveRange(t, whole, windowSeconds, live ? settleSeconds : 0);
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
