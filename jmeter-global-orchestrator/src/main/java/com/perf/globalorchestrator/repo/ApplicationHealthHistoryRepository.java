package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.ApplicationHealthHistory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only access for
 * {@code globalOrchestrator.applicationHealthHistory}. Written by
 * {@code ApplicationHealthPoller} on a status change; read by
 * {@code InfraReadinessComposer} to compute 24h downtime windows.
 */
@Repository
public class ApplicationHealthHistoryRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<ApplicationHealthHistory> rowMapper = ApplicationHealthHistoryRepository::mapRow;

    public ApplicationHealthHistoryRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ApplicationHealthHistory mapRow(ResultSet rs, int n) throws SQLException {
        return new ApplicationHealthHistory(
                rs.getString("historyId"),
                rs.getString("applicationId"),
                rs.getString("status"),
                OracleBind.instant(rs, "changedAt"));
    }

    public void insert(ApplicationHealthHistory h) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "(\"historyId\",\"applicationId\",\"status\",\"changedAt\") VALUES (?,?,?,?)",
                h.historyId(), h.applicationId(), h.status(),
                OracleBind.ts(h.changedAt()));
    }

    /** Transitions for one app at/after {@code since}, oldest first (for window walking). */
    public List<ApplicationHealthHistory> findSince(String applicationId, Instant since) {
        return jdbc.query(
                "SELECT \"historyId\",\"applicationId\",\"status\",\"changedAt\" "
                + "FROM \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "WHERE \"applicationId\"=? AND \"changedAt\" >= ? "
                + "ORDER BY \"changedAt\" ASC",
                rowMapper, applicationId, OracleBind.ts(since));
    }

    /**
     * HARD-DELETE / purge Phase 2 — drop an application's entire health-transition
     * log. Idempotent: returns the rowcount (0 when the app never logged a transition).
     */
    public int deleteByApplicationId(String applicationId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "WHERE \"applicationId\"=?",
                applicationId);
    }

    /** The most recent transition strictly before {@code ts} — the app's status
     *  as the 24h window opened (null when the app has no earlier transition). */
    public Optional<ApplicationHealthHistory> findLatestBefore(String applicationId, Instant ts) {
        return jdbc.query(
                "SELECT \"historyId\",\"applicationId\",\"status\",\"changedAt\" "
                + "FROM \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "WHERE \"applicationId\"=? AND \"changedAt\" < ? "
                + "ORDER BY \"changedAt\" DESC FETCH FIRST 1 ROWS ONLY",
                rowMapper, applicationId, OracleBind.ts(ts)).stream().findFirst();
    }
}
