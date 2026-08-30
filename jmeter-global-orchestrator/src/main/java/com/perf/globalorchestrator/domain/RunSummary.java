package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The run's headline numbers over one time range, served by
 * {@code GET /api/v1/runs/{runId}/summary} — the hosted Grafana "Key Metrics"
 * stat row and its "Summary by Application" table from one statement
 * ({@code GROUP BY ROLLUP(LABEL.APPLICATION)}). Percentiles are
 * throughput-weighted means of the workers' 15-second values; {@code tps} is
 * samples over the range the rows actually span.
 *
 * @param fromSecond    first window in the range (epoch second); null when empty
 * @param toSecond      last window in the range; null when empty
 * @param total         every label folded; {@code samples} 0 when nothing landed
 * @param byApplication one row per {@code LABEL.APPLICATION}, busiest first
 */
public record RunSummary(String runId, Long fromSecond, Long toSecond, Stats total, List<Stats> byApplication) {

    public static RunSummary empty(String runId) {
        return new RunSummary(runId, null, null, Stats.EMPTY, List.of());
    }

    /** One aggregate; {@code application} is set on the per-application rows only. {@code errors} is HTTP 4xx + 5xx. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stats(String application, long samples, long errors, double tps, double errorPct,
                        double avgMs, double p90Ms, double p95Ms, double p99Ms, double maxMs,
                        long maxActiveThreads) {

        public static final Stats EMPTY = new Stats(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
