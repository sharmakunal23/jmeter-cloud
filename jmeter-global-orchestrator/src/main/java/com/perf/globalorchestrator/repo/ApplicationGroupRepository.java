package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for {@code "globalOrchestrator"."applicationGroup"}. Groups are
 * hard-deleted; the {@code application.metricsGroupId} FK (no ON DELETE action)
 * refuses the delete while any application — visible or archived — still
 * points at the group, and {@link #countApplications} lets the controller say
 * so before Oracle does.
 */
@Repository
public class ApplicationGroupRepository {

    private static final RowMapper<ApplicationGroup> ROW = (rs, n) -> new ApplicationGroup(
            rs.getString("groupId"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("grafanaLiveUrl"),
            rs.getString("grafanaHistoryUrl"),
            rs.getInt("hotDays"),
            OracleBind.instant(rs, "createdAt"),
            null);

    private final JdbcTemplate jdbc;

    public ApplicationGroupRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApplicationGroup insert(ApplicationGroup group) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"applicationGroup\" "
                + "(\"groupId\",\"name\",\"description\",\"grafanaLiveUrl\",\"grafanaHistoryUrl\",\"hotDays\",\"createdAt\") "
                + "VALUES (?,?,?,?,?,?,?)",
                group.groupId(), group.name(), group.description(), group.grafanaLiveUrl(), group.grafanaHistoryUrl(),
                group.hotDays() == null ? ApplicationGroup.DEFAULT_HOT_DAYS : group.hotDays(),
                OracleBind.ts(group.createdAt()));
        return findById(group.groupId()).orElseThrow();
    }

    public Optional<ApplicationGroup> findById(String groupId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM \"globalOrchestrator\".\"applicationGroup\" WHERE \"groupId\"=?",
                    ROW, groupId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ApplicationGroup> findAll() {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"applicationGroup\" ORDER BY \"name\"", ROW);
    }

    public ApplicationGroup update(String groupId, String name, String description,
                                   String grafanaLiveUrl, String grafanaHistoryUrl, int hotDays) {
        int updated = jdbc.update(
                "UPDATE \"globalOrchestrator\".\"applicationGroup\" "
                + "SET \"name\"=?, \"description\"=?, \"grafanaLiveUrl\"=?, \"grafanaHistoryUrl\"=?, \"hotDays\"=? "
                + "WHERE \"groupId\"=?",
                name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays, groupId);
        if (updated == 0) {
            throw new EmptyResultDataAccessException("application group not found: " + groupId, 1);
        }
        return findById(groupId).orElseThrow();
    }

    /** Hard delete. {@code true} when a row was removed; the FK raises when applications remain. */
    public boolean delete(String groupId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"applicationGroup\" WHERE \"groupId\"=?", groupId) > 0;
    }

    /** Applications in the group, archived ones included — they hold the FK too. */
    public int countApplications(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"globalOrchestrator\".\"application\" WHERE \"metricsGroupId\"=?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    /** {@code groupId → application count} for every group that has at least one. */
    public Map<String, Integer> applicationCounts() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query(
                "SELECT \"metricsGroupId\", COUNT(*) AS n FROM \"globalOrchestrator\".\"application\" "
                + "WHERE \"metricsGroupId\" IS NOT NULL GROUP BY \"metricsGroupId\"",
                rs -> { out.put(rs.getString("metricsGroupId"), rs.getInt("n")); });
        return out;
    }
}
