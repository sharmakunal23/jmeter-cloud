package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Whole-run aggregates from the run's group fact table — the per-label table
 * behind {@code GET /api/v1/runs/{runId}/metrics} and the one-row aggregate the
 * {@code runTrend} snapshot records at completion. Same shape as
 * {@link MetricsTimeseriesRepository}: {@code RUN_ID} + a {@code WINDOW_SECOND}
 * range, hot rows {@code UNION ALL} the archived day's, throughput-weighted
 * percentiles (a sample-count-aware approximation — the histograms are not
 * persisted), division at the end.
 */
@Repository
public class RunMetricsRepository {

    /** Run-level aggregate for the {@code runTrend} snapshot; {@code rowCount} 0 ⇒ nothing landed yet. */
    public record RunAggregate(long rowCount, double p50Ms, double p95Ms, double p99Ms,
                               double errorRate, double throughputRps) { }

    private static final String COLS =
            "WINDOW_SECOND, LABEL_ID, THROUGHPUT, ERROR_COUNT, P50_MS, P95_MS, P99_MS, MAX_MS, ACTIVE_THREADS";

    private final JdbcTemplate jdbc;

    public RunMetricsRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per label with the keys the UI's aggregate table and the AI digest read. */
    public List<Map<String, Object>> rollupByLabel(MetricsTarget t, RunWindow w) {
        return jdbc.queryForList(
                "SELECT l.LABEL_KEY AS \"label\", l.APPLICATION AS \"application\", "
                + "       SUM(x.THROUGHPUT) AS \"totalThroughput\", SUM(x.ERROR_COUNT) AS \"totalErrors\", "
                + "       CASE WHEN SUM(x.THROUGHPUT) > 0 THEN SUM(x.ERROR_COUNT) / SUM(x.THROUGHPUT) ELSE 0 END AS \"errorRate\", "
                + "       SUM(x.P50_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS \"avgP50Ms\", "
                + "       SUM(x.P95_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS \"avgP95Ms\", "
                + "       SUM(x.P99_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS \"avgP99Ms\", "
                + "       MAX(x.MAX_MS) AS \"maxMs\", MAX(x.ACTIVE_THREADS) AS \"maxActiveThreads\", "
                + "       MIN(x.WINDOW_SECOND) AS \"firstSecond\", MAX(x.WINDOW_SECOND) AS \"lastSecond\", "
                + "       COUNT(*) AS \"rowCount\" "
                + "FROM (" + factRows(t) + ") x JOIN LABEL l ON l.LABEL_ID = x.LABEL_ID "
                + "GROUP BY l.LABEL_KEY, l.APPLICATION ORDER BY l.LABEL_KEY",
                args(t, w));
    }

    /** Always one row; {@code rowCount} 0 when the run has no rows in its range yet. */
    public RunAggregate runAggregate(MetricsTarget t, RunWindow w) {
        RunAggregate agg = jdbc.queryForObject(
                "SELECT COUNT(*) AS n, COALESCE(SUM(x.THROUGHPUT), 0) AS samples, COALESCE(SUM(x.ERROR_COUNT), 0) AS errors, "
                + "       COALESCE(MIN(x.WINDOW_SECOND), 0) AS first_sec, COALESCE(MAX(x.WINDOW_SECOND), 0) AS last_sec, "
                + "       COALESCE(SUM(x.P50_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0), 0) AS p50, "
                + "       COALESCE(SUM(x.P95_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0), 0) AS p95, "
                + "       COALESCE(SUM(x.P99_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0), 0) AS p99 "
                + "FROM (" + factRows(t) + ") x",
                (rs, n) -> {
                    long rowCount = rs.getLong("n");
                    long samples = rs.getLong("samples");
                    long errors = rs.getLong("errors");
                    long span = Math.max(1, rs.getLong("last_sec") - rs.getLong("first_sec") + MetricsTimeseriesRepository.WINDOW_SECONDS);
                    return new RunAggregate(rowCount, rs.getDouble("p50"), rs.getDouble("p95"), rs.getDouble("p99"),
                            samples > 0 ? (double) errors / samples : 0.0, (double) samples / span);
                },
                args(t, w));
        return agg == null ? new RunAggregate(0, 0, 0, 0, 0, 0) : agg;
    }

    static String factRows(MetricsTarget t) {
        String hot = "SELECT " + COLS + " FROM " + t.metricsTable() + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?";
        if (t.historyTable() == null) {
            return hot;
        }
        return hot + " UNION ALL SELECT " + COLS + " FROM " + t.historyTable()
                + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?";
    }

    private static Object[] args(MetricsTarget t, RunWindow w) {
        List<Object> a = new ArrayList<>(List.of(t.runId(), w.lo(), w.hi()));
        if (t.historyTable() != null) {
            a.addAll(List.of(t.runId(), w.lo(), w.hi()));
        }
        return a.toArray();
    }
}
