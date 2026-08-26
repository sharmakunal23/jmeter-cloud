package com.perf.k8sorchestrator.repo;

import com.perf.k8sorchestrator.domain.RunTrend;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC access for {@code globalOrchestrator.runTrend}
 * (Flyway V24). Uses the run-state (writer) JdbcTemplate: global-orch both
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
        Timestamp completedAt = rs.getTimestamp("completedAt");
        return new RunTrend(
                rs.getString("runId"),
                rs.getString("applicationName"),
                rs.getDouble("p50Ms"),
                rs.getDouble("p95Ms"),
                rs.getDouble("p99Ms"),
                rs.getDouble("errorRate"),
                rs.getDouble("throughputRps"),
                completedAt == null ? null : completedAt.toInstant());
    }

    /**
     * Insert one snapshot. {@code ON CONFLICT (runId) DO NOTHING} makes a
     * repeat emit (e.g. a status re-poll racing the terminal fence) a no-op,
     * so the caller never has to reason about exactly-once at the write.
     */
    public void insert(RunTrend t) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"runTrend\" "
                + "(\"runId\",\"applicationName\",\"p50Ms\",\"p95Ms\",\"p99Ms\","
                + " \"errorRate\",\"throughputRps\",\"completedAt\") "
                + "VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (\"runId\") DO NOTHING",
                t.runId(), t.applicationName(), t.p50Ms(), t.p95Ms(), t.p99Ms(),
                t.errorRate(), t.throughputRps(),
                t.completedAt() == null ? null : Timestamp.from(t.completedAt()));
    }

    /**
     * HARD-DELETE / purge — drop a run's frozen baseline row. Needs the V27
     * DELETE grant (V24 created the table SELECT/INSERT-only). Idempotent:
     * returns 0 when the run had no snapshot (e.g. it FAILED/ABORTED, or
     * metrics-consumer lag meant no rows existed at the terminal moment).
     */
    public int deleteByRunId(String runId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"runTrend\" WHERE \"runId\"=?",
                runId);
    }

    /** This application's snapshots completed at or after {@code since}, newest first. */
    public List<RunTrend> findByApplicationSince(String applicationName, Instant since) {
        return jdbc.query(
                "SELECT \"runId\",\"applicationName\",\"p50Ms\",\"p95Ms\",\"p99Ms\","
                + " \"errorRate\",\"throughputRps\",\"completedAt\" "
                + "FROM \"globalOrchestrator\".\"runTrend\" "
                + "WHERE \"applicationName\"=? AND \"completedAt\" >= ? "
                + "ORDER BY \"completedAt\" DESC",
                rowMapper, applicationName, Timestamp.from(since));
    }
}
