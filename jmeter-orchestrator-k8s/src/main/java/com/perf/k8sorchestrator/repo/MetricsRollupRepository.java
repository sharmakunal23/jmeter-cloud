package com.perf.k8sorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Read-only access to {@code metrics."runLabel"} for the per-run
 * rollup endpoint. Connected via the {@code metricsReader} datasource
 * (read-only Hikari).
 *
 * <p>The rollup is a per-label view across the fleet for the given runId.
 * Cross-worker percentile aggregation faithful to JMeter's HDRHistogram model
 * is still a future enhancement — see the note on {@link #runAggregate}.
 *
 * <h2>Why this reads a rollup table</h2>
 * SCHEMA-OPT Phase 1. Both queries here are whole-test aggregates, so against
 * {@code metrics."workerMetric"} they scanned every row a run ever produced
 * (~154M at 20 workers × 200 labels × 15 h) and crossed the read pool's 30 s
 * {@code statement_timeout} — meaning {@code GET /runs/{id}/metrics}, the
 * {@code runTrend} snapshot taken at completion and AI insights did not merely
 * slow down, they failed. {@code metrics."runLabel"} holds one already-folded
 * row per (runId, label): 200 rows for that same run.
 *
 * <p>It stores BOTH unweighted and throughput-weighted percentile numerators
 * because this class reads percentiles both ways — {@link #rollupByLabel}
 * reports an unweighted mean per label, {@link #runAggregate} a
 * throughput-weighted one. Carrying both reproduces the pre-rollup numbers
 * exactly rather than quietly changing a figure an operator has been reading.
 */
@Repository
public class MetricsRollupRepository {

    private final JdbcTemplate jdbc;

    public MetricsRollupRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> rollupByLabel(String runId) {
        // One row per label already, so no GROUP BY — the divisions reproduce
        // exactly what the pre-rollup query computed: avg("pNN") over the run's
        // raw rows is sum("pNN") / count(*), i.e. "sumPNN" / "rowCount".
        return jdbc.queryForList(
                "SELECT \"label\", "
                + "       \"samples\"          AS \"totalThroughput\", "
                + "       \"errors\"           AS \"totalErrors\", "
                + "       CASE WHEN \"samples\" > 0 "
                + "            THEN \"errors\"::double precision / \"samples\" "
                + "            ELSE 0 END      AS \"errorRate\", "
                + "       \"sumP50\" / \"rowCount\" AS \"avgP50Ms\", "
                + "       \"sumP95\" / \"rowCount\" AS \"avgP95Ms\", "
                + "       \"sumP99\" / \"rowCount\" AS \"avgP99Ms\", "
                + "       \"maxMs\", "
                + "       \"maxActiveThreads\", "
                + "       \"firstSecond\", "
                + "       \"lastSecond\", "
                + "       \"rowCount\" "
                + "FROM metrics.\"runLabel\" "
                + "WHERE \"runId\" = ? "
                + "ORDER BY \"label\"",
                runId);
    }

    /**
     * A single run-level aggregate across the whole fleet
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
        // Folds the per-label rollup rows into one run-level row. Each expression
        // is the same arithmetic the pre-rollup query ran against raw rows:
        // sum("rowCount") is count(*), and the ELSE branch's
        // sum("sumPNN")/sum("rowCount") is avg("pNN"). NULLIF guards the
        // zero-row case, which COALESCE then reports as 0 so the caller sees
        // rowCount = 0 and skips the snapshot.
        RunAggregate agg = jdbc.queryForObject(
                "SELECT "
                + "  COALESCE(sum(\"rowCount\"),0)   AS \"rowCount\", "
                + "  COALESCE(sum(\"samples\"),0)    AS \"totalThroughput\", "
                + "  COALESCE(sum(\"errors\"),0)     AS \"totalErrors\", "
                + "  COALESCE(min(\"firstSecond\"),0) AS \"firstSecond\", "
                + "  COALESCE(max(\"lastSecond\"),0)  AS \"lastSecond\", "
                + "  CASE WHEN sum(\"samples\") > 0 "
                + "       THEN sum(\"sumP50Weighted\") / sum(\"samples\") "
                + "       ELSE COALESCE(sum(\"sumP50\") / NULLIF(sum(\"rowCount\"),0),0) "
                + "       END AS \"p50Ms\", "
                + "  CASE WHEN sum(\"samples\") > 0 "
                + "       THEN sum(\"sumP95Weighted\") / sum(\"samples\") "
                + "       ELSE COALESCE(sum(\"sumP95\") / NULLIF(sum(\"rowCount\"),0),0) "
                + "       END AS \"p95Ms\", "
                + "  CASE WHEN sum(\"samples\") > 0 "
                + "       THEN sum(\"sumP99Weighted\") / sum(\"samples\") "
                + "       ELSE COALESCE(sum(\"sumP99\") / NULLIF(sum(\"rowCount\"),0),0) "
                + "       END AS \"p99Ms\" "
                + "FROM metrics.\"runLabel\" "
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
