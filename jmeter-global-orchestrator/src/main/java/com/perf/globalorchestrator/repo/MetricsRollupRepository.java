package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Read-only access to {@code metrics."workerMetric"} for the per-run
 * rollup endpoint. Connected via the {@code metricsReader} datasource
 * (read-only Hikari).
 *
 * <p>For Step 14 the rollup is a simple GROUP BY label across the fleet
 * for the given runId. Cross-worker percentile aggregation faithful to
 * JMeter's HDRHistogram model is a future enhancement.
 */
@Repository
public class MetricsRollupRepository {

    private final JdbcTemplate jdbc;

    public MetricsRollupRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> rollupByLabel(String runId) {
        return jdbc.queryForList(
                "SELECT \"label\", "
                + "       sum(\"throughput\")  AS \"totalThroughput\", "
                + "       sum(\"errorCount\")  AS \"totalErrors\", "
                + "       CASE WHEN sum(\"throughput\") > 0 "
                + "            THEN sum(\"errorCount\")::double precision / sum(\"throughput\") "
                + "            ELSE 0 END     AS \"errorRate\", "
                + "       avg(\"p50Ms\")       AS \"avgP50Ms\", "
                + "       avg(\"p95Ms\")       AS \"avgP95Ms\", "
                + "       avg(\"p99Ms\")       AS \"avgP99Ms\", "
                + "       max(\"maxMs\")       AS \"maxMs\", "
                + "       max(\"activeThreads\") AS \"maxActiveThreads\", "
                + "       min(\"windowSecond\") AS \"firstSecond\", "
                + "       max(\"windowSecond\") AS \"lastSecond\", "
                + "       count(*)             AS \"rowCount\" "
                + "FROM metrics.\"workerMetric\" "
                + "WHERE \"runId\" = ? "
                + "GROUP BY \"label\" "
                + "ORDER BY \"label\"",
                runId);
    }

    /**
     * AUTOMATION Phase F — a single run-level aggregate across the whole fleet
     * (all labels, all workers, all per-second windows) for the runTrend
     * snapshot. The percentiles are throughput-weighted means of the per-window
     * percentiles — a sample-count-aware approximation (a true cross-fleet
     * percentile needs the HDRHistograms, which aren't persisted). That's
     * appropriate for a trend baseline, not for exact SLO reporting. {@code
     * throughputRps} is total samples over the wall-clock span; {@code
     * errorRate} is total errors over total samples.
     *
     * <p>Always returns one row (count-only when the run has no metric rows
     * yet); the caller checks {@link RunAggregate#rowCount()} and skips the
     * snapshot for an empty run rather than recording a misleading zeros row.
     */
    public RunAggregate runAggregate(String runId) {
        RunAggregate agg = jdbc.queryForObject(
                "SELECT "
                + "  count(*)                       AS \"rowCount\", "
                + "  COALESCE(sum(\"throughput\"),0) AS \"totalThroughput\", "
                + "  COALESCE(sum(\"errorCount\"),0) AS \"totalErrors\", "
                + "  COALESCE(min(\"windowSecond\"),0) AS \"firstSecond\", "
                + "  COALESCE(max(\"windowSecond\"),0) AS \"lastSecond\", "
                + "  CASE WHEN sum(\"throughput\") > 0 "
                + "       THEN sum(\"p50Ms\" * \"throughput\") / sum(\"throughput\") "
                + "       ELSE COALESCE(avg(\"p50Ms\"),0) END AS \"p50Ms\", "
                + "  CASE WHEN sum(\"throughput\") > 0 "
                + "       THEN sum(\"p95Ms\" * \"throughput\") / sum(\"throughput\") "
                + "       ELSE COALESCE(avg(\"p95Ms\"),0) END AS \"p95Ms\", "
                + "  CASE WHEN sum(\"throughput\") > 0 "
                + "       THEN sum(\"p99Ms\" * \"throughput\") / sum(\"throughput\") "
                + "       ELSE COALESCE(avg(\"p99Ms\"),0) END AS \"p99Ms\" "
                + "FROM metrics.\"workerMetric\" "
                + "WHERE \"runId\" = ?",
                (rs, n) -> {
                    long rowCount = rs.getLong("rowCount");
                    long totalThroughput = rs.getLong("totalThroughput");
                    long totalErrors = rs.getLong("totalErrors");
                    long firstSecond = rs.getLong("firstSecond");
                    long lastSecond = rs.getLong("lastSecond");
                    double errorRate = totalThroughput > 0
                            ? (double) totalErrors / totalThroughput : 0.0;
                    long spanSeconds = Math.max(1, lastSecond - firstSecond + 1);
                    double throughputRps = (double) totalThroughput / spanSeconds;
                    return new RunAggregate(rowCount,
                            rs.getDouble("p50Ms"), rs.getDouble("p95Ms"), rs.getDouble("p99Ms"),
                            errorRate, throughputRps);
                },
                runId);
        // A single-row aggregate query never returns null, but guard anyway.
        return agg == null ? new RunAggregate(0, 0, 0, 0, 0, 0) : agg;
    }

    /** Run-level aggregate for the {@code runTrend} snapshot. {@code rowCount}
     *  is the number of per-second metric rows the run produced (0 ⇒ skip the
     *  snapshot). */
    public record RunAggregate(long rowCount, double p50Ms, double p95Ms, double p99Ms,
                               double errorRate, double throughputRps) {}
}
