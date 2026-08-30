package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.RunTrend;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * JDBC access for {@code ORCH_RUN_TREND}. Uses the run-state (writer) JdbcTemplate: global-orch both
 * writes the snapshot (on a run-terminal transition) and reads the 7-day
 * baseline (for the daily perf-test report). The metrics datasource can't be
 * used — it's read-only — and the metrics-consumer can't write it (it never
 * observes the run-terminal transition).
 */
@Repository
public class RunTrendRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<RunTrend> rowMapper = RunTrendRepository::mapRow;

    public RunTrendRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RunTrend mapRow(ResultSet rs, int n) throws SQLException {
        return new RunTrend(
                rs.getString("RUN_ID"),
                rs.getString("APPLICATION_NAME"),
                rs.getDouble("P50_MS"),
                rs.getDouble("P95_MS"),
                rs.getDouble("P99_MS"),
                rs.getDouble("ERROR_RATE"),
                rs.getDouble("THROUGHPUT_RPS"),
                OracleBind.instant(rs, "COMPLETED_AT"));
    }

    /**
     * Insert one snapshot; a repeat emit (e.g. a status re-poll racing the
     * terminal fence) is a no-op, so the caller never has to reason about
     * exactly-once at the write.
     */
    public void insert(RunTrend t) {
        jdbc.update(
                "MERGE INTO ORCH_RUN_TREND t "
                + "USING (SELECT ? AS RUN_ID FROM dual) s ON (t.RUN_ID = s.RUN_ID) "
                + "WHEN NOT MATCHED THEN INSERT "
                + "(RUN_ID,APPLICATION_NAME,P50_MS,P95_MS,P99_MS,"
                + " ERROR_RATE,THROUGHPUT_RPS,COMPLETED_AT) "
                + "VALUES (s.RUN_ID,?,?,?,?,?,?,?)",
                t.runId(), t.applicationName(), t.p50Ms(), t.p95Ms(), t.p99Ms(),
                t.errorRate(), t.throughputRps(), OracleBind.ts(t.completedAt()));
    }

    /**
     * HARD-DELETE / purge — drop a run's frozen baseline row. Idempotent:
     * returns 0 when the run had no snapshot (e.g. it FAILED/ABORTED, or
     * metrics-consumer lag meant no rows existed at the terminal moment).
     */
    public int deleteByRunId(String runId) {
        return jdbc.update(
                "DELETE FROM ORCH_RUN_TREND WHERE RUN_ID=?",
                runId);
    }

    /** This application's snapshots completed at or after {@code since}, newest first. */
    public List<RunTrend> findByApplicationSince(String applicationName, Instant since) {
        return jdbc.query(
                "SELECT RUN_ID,APPLICATION_NAME,P50_MS,P95_MS,P99_MS,"
                + " ERROR_RATE,THROUGHPUT_RPS,COMPLETED_AT "
                + "FROM ORCH_RUN_TREND "
                + "WHERE APPLICATION_NAME=? AND COMPLETED_AT >= ? "
                + "ORDER BY COMPLETED_AT DESC",
                rowMapper, applicationName, OracleBind.ts(since));
    }
}
