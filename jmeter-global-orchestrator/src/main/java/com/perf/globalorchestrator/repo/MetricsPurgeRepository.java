package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * The only writer to {@code metrics."workerMetric"} and the rollups from this
 * service, using the dedicated {@code metricsPurger} role so the hot metrics
 * read pool can stay {@code setReadOnly(true)}.
 *
 * <p><b>The DELETE carries {@code windowSecond} bounds, and must.</b>
 * {@code runId} is not the partition key, so a run-scoped DELETE cannot prune on
 * its own and would probe every weekly partition's index. The purge first reads
 * the run's bounds from {@code metrics."runLabel"}, cutting the work to the one
 * or two partitions the run actually touched.
 *
 * <p>Those bounds are exact, not estimated — the rollup maintains them with
 * LEAST/GREATEST over the very rows being deleted, so they can never be narrower
 * than the data. A run with no rollup rows yields null bounds and falls back to
 * the unbounded DELETE, so a run can never leave raw rows behind.
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
     * application purge). One {@code IN (…)} statement per table per chunk of
     * {@value #IN_CHUNK} ids — Oracle caps an IN-list at 1,000 — so the work is
     * per-batch, not per-run.
     *
     * @return the total number of raw {@code workerMetric} rows deleted.
     */
    public long deleteByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return 0L;

        Bounds bounds = boundsFor(runIds);

        long rawDeleted = 0;
        for (List<String> chunk : chunks(runIds)) {
            if (bounds == null) {
                // No rollup coverage for any of these runs — fall back to the
                // unpruned DELETE rather than guess at bounds.
                rawDeleted += jdbc.update(
                        "DELETE FROM metrics.\"workerMetric\" WHERE \"runId\" IN (" + marks(chunk) + ")",
                        chunk.toArray());
                jdbc.update(
                        "DELETE FROM metrics.\"workerMetricStatus\" WHERE \"runId\" IN (" + marks(chunk) + ")",
                        chunk.toArray());
            } else {
                Object[] args = withBounds(chunk, bounds);
                rawDeleted += jdbc.update(
                        "DELETE FROM metrics.\"workerMetric\" "
                        + "WHERE \"runId\" IN (" + marks(chunk) + ") "
                        + "  AND \"windowSecond\" BETWEEN ? AND ?", args);
                jdbc.update(
                        "DELETE FROM metrics.\"workerMetricStatus\" "
                        + "WHERE \"runId\" IN (" + marks(chunk) + ") "
                        + "  AND \"windowSecond\" BETWEEN ? AND ?", args);
            }
        }

        // Rollups after raw: the bounds above are read from runLabel, so it has
        // to still be there when the raw DELETE is planned.
        deleteRollups("runSecond", runIds);
        deleteRollups("runSecondStatus", runIds);
        deleteRollups("runLabel", runIds);

        return rawDeleted;
    }

    private void deleteRollups(String table, List<String> runIds) {
        for (List<String> chunk : chunks(runIds)) {
            jdbc.update(
                    "DELETE FROM metrics.\"" + table + "\" WHERE \"runId\" IN (" + marks(chunk) + ")",
                    chunk.toArray());
        }
    }

    /**
     * The {@code windowSecond} range these runs occupy, or {@code null} when none
     * of them has rollup rows to read it from.
     */
    private Bounds boundsFor(List<String> runIds) {
        Bounds all = null;
        for (List<String> chunk : chunks(runIds)) {
            Bounds b = jdbc.query(
                    "SELECT min(\"firstSecond\") AS lo, max(\"lastSecond\") AS hi "
                    + "FROM metrics.\"runLabel\" WHERE \"runId\" IN (" + marks(chunk) + ")",
                    rs -> {
                        if (!rs.next()) return null;
                        long lo = rs.getLong("lo");
                        if (rs.wasNull()) return null;   // aggregate over zero rows
                        long hi = rs.getLong("hi");
                        return rs.wasNull() ? null : new Bounds(lo, hi);
                    },
                    chunk.toArray());
            if (b == null) continue;
            all = all == null ? b : new Bounds(Math.min(all.from(), b.from()), Math.max(all.to(), b.to()));
        }
        return all;
    }

    /** Oracle caps an IN-list at 1,000 elements before 23ai; half that keeps the statement text small. */
    static final int IN_CHUNK = 500;

    static List<List<String>> chunks(List<String> ids) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            out.add(ids.subList(i, Math.min(i + IN_CHUNK, ids.size())));
        }
        return out;
    }

    static String marks(List<String> chunk) {
        return String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
    }

    private static Object[] withBounds(List<String> chunk, Bounds bounds) {
        Object[] args = new Object[chunk.size() + 2];
        for (int i = 0; i < chunk.size(); i++) args[i] = chunk.get(i);
        args[chunk.size()] = bounds.from();
        args[chunk.size() + 1] = bounds.to();
        return args;
    }

    /** Inclusive {@code windowSecond} range covering a set of runs. */
    private record Bounds(long from, long to) { }
}
