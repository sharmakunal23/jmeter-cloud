package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one path that deletes from the metrics schema, on the dedicated
 * {@code metricsPurger} pool: a run's rows in its group's hot and archived-day
 * tables — each {@code DELETE} bounded by {@code RUN_ID} and the run's
 * {@code WINDOW_SECOND} range so it prunes to the run's own daily partitions —
 * then its run-scoped dimensions ({@code WORKER}, {@code RUN}). {@code LABEL}
 * is shared across runs and is never deleted.
 */
@Repository
public class MetricsPurgeRepository {

    private final JdbcTemplate jdbc;

    public MetricsPurgeRepository(@Qualifier("metricsPurgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the fact rows deleted (hot + archived), the purge audit's comparable figure */
    public long deleteRun(MetricsTarget t, RunWindow w) {
        long rows = jdbc.update("DELETE FROM " + t.metricsTable() + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?",
                t.runId(), w.lo(), w.hi());
        if (t.historyTable() != null) {
            rows += jdbc.update("DELETE FROM " + t.historyTable() + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?",
                    t.runId(), w.lo(), w.hi());
        }
        jdbc.update("DELETE FROM WORKER WHERE RUN_ID = ?", t.runId());
        jdbc.update("DELETE FROM RUN WHERE RUN_ID = ?", t.runId());
        return rows;
    }

    // ── IN-list helpers shared with the control-plane repositories ──────

    /** Oracle caps an IN-list at 1,000 elements; half that keeps the statement text small. */
    static final int IN_CHUNK = 500;

    static java.util.List<java.util.List<String>> chunks(java.util.List<String> ids) {
        java.util.List<java.util.List<String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            out.add(ids.subList(i, Math.min(i + IN_CHUNK, ids.size())));
        }
        return out;
    }

    static String marks(java.util.List<String> chunk) {
        return String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
    }
}
