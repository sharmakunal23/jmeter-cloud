package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.ApplicationHealthHistory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AUTOMATION Phase E — append-only access for
 * {@code globalOrchestrator.applicationHealthHistory} (Flyway V23). Written by
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
        Timestamp t = rs.getTimestamp("changedAt");
        return new ApplicationHealthHistory(
                rs.getString("historyId"),
                rs.getString("applicationId"),
                rs.getString("status"),
                t == null ? null : t.toInstant());
    }

    public void insert(ApplicationHealthHistory h) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "(\"historyId\",\"applicationId\",\"status\",\"changedAt\") VALUES (?,?,?,?)",
                h.historyId(), h.applicationId(), h.status(),
                h.changedAt() == null ? null : Timestamp.from(h.changedAt()));
    }

    /** Transitions for one app at/after {@code since}, oldest first (for window walking). */
    public List<ApplicationHealthHistory> findSince(String applicationId, Instant since) {
        return jdbc.query(
                "SELECT \"historyId\",\"applicationId\",\"status\",\"changedAt\" "
                + "FROM \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "WHERE \"applicationId\"=? AND \"changedAt\" >= ? "
                + "ORDER BY \"changedAt\" ASC",
                rowMapper, applicationId, Timestamp.from(since));
    }

    /**
     * HARD-DELETE / purge Phase 2 — drop an application's entire health-transition
     * log. Needs the V28 DELETE grant (V23 created the table SELECT/INSERT-only).
     * Idempotent: returns the rowcount (0 when the app never logged a transition).
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
                + "ORDER BY \"changedAt\" DESC LIMIT 1",
                rowMapper, applicationId, Timestamp.from(ts)).stream().findFirst();
    }
}
