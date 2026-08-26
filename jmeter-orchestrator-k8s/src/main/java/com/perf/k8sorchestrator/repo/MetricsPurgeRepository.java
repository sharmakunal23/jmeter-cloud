package com.perf.k8sorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * The only writer to {@code metrics."workerMetric"} and the rollups from this
 * service, using the dedicated {@code metricsPurger} role so the hot metrics
 * read pool can stay {@code setReadOnly(true)}.
 *
 * <p><b>The DELETE carries {@code windowSecond} bounds, and must.</b>
 * {@code runId} is not the partition key, so a run-scoped DELETE cannot prune on
 * its own and Postgres scans every weekly partition, including the empty future
 * ones. The purge first reads the run's bounds from {@code metrics."runLabel"},
 * cutting the scan to the one or two partitions the run actually touched.
 *
 * <p>Those bounds are exact, not estimated — the rollup maintains them with
 * LEAST/GREATEST over the very rows being deleted, so they can never be narrower
 * than the data. A run with no rollup rows yields null bounds and falls back to
 * the unbounded DELETE. <b>That fallback is why the migration backfills every
 * run unconditionally:</b> complete coverage or none is safe, while partial
 * coverage would silently orphan raw rows outside the bounds.
 *
 * <p>Rollup rows go too. Reclaiming the raw space while leaving every chart and
 * per-label table intact would be worse than not purging.
 */
@Repository
public class MetricsPurgeRepository {

    private final JdbcTemplate jdbc;

    public MetricsPurgeRepository(@Qualifier("metricsPurgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Delete every per-second metric row and rollup row for one run.
     *
     * @return the number of raw {@code workerMetric} rows deleted — unchanged in
     *         meaning from before the rollups existed, so the purge audit trail
     *         stays comparable across the change.
     */
    public long deleteByRunId(String runId) {
        return deleteByRunIds(List.of(runId));
    }

    /**
     * Delete every per-second metric row and rollup row for a batch of runs (an
     * application purge). One {@code = ANY(?)} statement per table so the scan
     * happens once for the whole batch instead of per-run.
     *
     * @return the total number of raw {@code workerMetric} rows deleted.
     */
    public long deleteByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return 0L;

        Bounds bounds = boundsFor(runIds);

        long rawDeleted;
        if (bounds == null) {
            // No rollup coverage for any of these runs — fall back to the
            // unpruned DELETE rather than guess at bounds.
            rawDeleted = jdbc.update(
                    "DELETE FROM metrics.\"workerMetric\" WHERE \"runId\" = ANY(?)",
                    (PreparedStatement ps) -> ps.setArray(1, textArray(ps, runIds)));
        } else {
            rawDeleted = jdbc.update(
                    "DELETE FROM metrics.\"workerMetric\" "
                    + "WHERE \"runId\" = ANY(?) "
                    + "  AND \"windowSecond\" BETWEEN ? AND ?",
                    (PreparedStatement ps) -> {
                        ps.setArray(1, textArray(ps, runIds));
                        ps.setLong(2, bounds.from());
                        ps.setLong(3, bounds.to());
                    });
        }

        // Rollups after raw: the bounds above are read from runLabel, so it has
        // to still be there when the raw DELETE is planned.
        deleteRollups("runSecond", runIds);
        deleteRollups("runSecondStatus", runIds);
        deleteRollups("runLabel", runIds);

        return rawDeleted;
    }

    private void deleteRollups(String table, List<String> runIds) {
        jdbc.update(
                "DELETE FROM metrics.\"" + table + "\" WHERE \"runId\" = ANY(?)",
                (PreparedStatement ps) -> ps.setArray(1, textArray(ps, runIds)));
    }

    /**
     * The {@code windowSecond} range these runs occupy, or {@code null} when none
     * of them has rollup rows to read it from.
     */
    private Bounds boundsFor(List<String> runIds) {
        return jdbc.query(
                "SELECT min(\"firstSecond\") AS lo, max(\"lastSecond\") AS hi "
                + "FROM metrics.\"runLabel\" WHERE \"runId\" = ANY(?)",
                (PreparedStatement ps) -> ps.setArray(1, textArray(ps, runIds)),
                rs -> {
                    if (!rs.next()) return null;
                    long lo = rs.getLong("lo");
                    if (rs.wasNull()) return null;   // aggregate over zero rows
                    long hi = rs.getLong("hi");
                    return rs.wasNull() ? null : new Bounds(lo, hi);
                });
    }

    private static Array textArray(PreparedStatement ps, List<String> values)
            throws java.sql.SQLException {
        return ps.getConnection().createArrayOf("text", values.toArray());
    }

    /** Inclusive {@code windowSecond} range covering a set of runs. */
    private record Bounds(long from, long to) { }
}
