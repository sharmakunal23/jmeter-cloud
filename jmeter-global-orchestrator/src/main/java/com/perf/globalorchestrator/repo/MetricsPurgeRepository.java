package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * HARD-DELETE / purge — the ONLY writer to
 * {@code metrics."workerMetric"} from the global-orchestrator. Uses the
 * dedicated {@code metricsPurgeJdbcTemplate} (the {@code metricsPurger} role,
 * SELECT + DELETE) so the hot metrics READ pool stays {@code setReadOnly(true)}.
 *
 * <p>{@code runId} is not the partition key ({@code windowSecond} is), so a
 * run-scoped DELETE can't be pruned to one partition — Postgres scans every
 * weekly partition and leaves dead tuples that autovacuum reclaims. That's the
 * accepted cost of a targeted operator purge; long-term growth is still bounded
 * by {@code metrics."dropOldPartitions"}. The purge pool carries a generous
 * statement_timeout (120 s) for this reason.
 */
@Repository
public class MetricsPurgeRepository {

    private final JdbcTemplate jdbc;

    public MetricsPurgeRepository(@Qualifier("metricsPurgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Delete every per-second metric row for one run. Returns the rowcount. */
    public long deleteByRunId(String runId) {
        return jdbc.update(
                "DELETE FROM metrics.\"workerMetric\" WHERE \"runId\"=?",
                runId);
    }

    /**
     * Delete every per-second metric row for a batch of runs (an application
     * purge). One {@code = ANY(?)} statement so the all-partition scan happens
     * once for the whole batch instead of per-run. Returns the total rowcount.
     */
    public long deleteByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return 0L;
        return jdbc.update(
                "DELETE FROM metrics.\"workerMetric\" WHERE \"runId\" = ANY(?)",
                (java.sql.PreparedStatement ps) ->
                        ps.setArray(1, ps.getConnection().createArrayOf(
                                "text", runIds.toArray())));
    }
}
