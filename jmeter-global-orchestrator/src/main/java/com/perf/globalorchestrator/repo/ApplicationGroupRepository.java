package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.RecyclePolicy;
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
 * Persistence for {@code ORCH_APPLICATION_GROUP} — the group's
 * identity, dashboards and the pool's recycle policy. Groups are hard-deleted;
 * the {@code ORCH_APPLICATION.METRICS_GROUP_ID} and {@code ORCH_POD.GROUP_ID} FKs (no ON
 * DELETE action) refuse the delete while an application — visible or
 * archived — or a worker still points at the group, and
 * {@link #countApplications} / {@link #countPods} let the controller say so
 * before Oracle does ({@code groupCapacity} cascades).
 */
@Repository
public class ApplicationGroupRepository {

    private static final RowMapper<ApplicationGroup> ROW = (rs, n) -> new ApplicationGroup(
            rs.getString("GROUP_ID"),
            rs.getString("NAME"),
            rs.getString("DESCRIPTION"),
            rs.getString("GRAFANA_LIVE_URL"),
            rs.getString("GRAFANA_HISTORY_URL"),
            rs.getInt("HOT_DAYS"),
            RecyclePolicy.valueOf(rs.getString("RECYCLE_POLICY")),
            nullableInt(rs, "MAX_RUNS_PER_POD"),
            nullableInt(rs, "POD_MAX_AGE_HOURS"),
            rs.getInt("ALWAYS_ON") == 1,
            OracleBind.instant(rs, "CREATED_AT"),
            null,
            null);

    private static Integer nullableInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private final JdbcTemplate jdbc;

    public ApplicationGroupRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApplicationGroup insert(ApplicationGroup group) {
        jdbc.update(
                "INSERT INTO ORCH_APPLICATION_GROUP "
                + "(GROUP_ID,NAME,DESCRIPTION,GRAFANA_LIVE_URL,GRAFANA_HISTORY_URL,HOT_DAYS,"
                + " RECYCLE_POLICY,MAX_RUNS_PER_POD,POD_MAX_AGE_HOURS,ALWAYS_ON,CREATED_AT) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                group.groupId(), group.name(), group.description(), group.grafanaLiveUrl(), group.grafanaHistoryUrl(),
                group.hotDays() == null ? ApplicationGroup.DEFAULT_HOT_DAYS : group.hotDays(),
                group.recyclePolicy().name(), group.maxRunsPerPod(), group.podMaxAgeHours(), group.alwaysOn() ? 1 : 0,
                OracleBind.ts(group.createdAt()));
        return findById(group.groupId()).orElseThrow();
    }

    public Optional<ApplicationGroup> findById(String groupId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM ORCH_APPLICATION_GROUP WHERE GROUP_ID=?",
                    ROW, groupId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ApplicationGroup> findAll() {
        return jdbc.query(
                "SELECT * FROM ORCH_APPLICATION_GROUP ORDER BY NAME", ROW);
    }

    public ApplicationGroup update(String groupId, String name, String description,
                                   String grafanaLiveUrl, String grafanaHistoryUrl, int hotDays,
                                   RecyclePolicy recyclePolicy, Integer maxRunsPerPod, Integer podMaxAgeHours,
                                   boolean alwaysOn) {
        int updated = jdbc.update(
                "UPDATE ORCH_APPLICATION_GROUP "
                + "SET NAME=?, DESCRIPTION=?, GRAFANA_LIVE_URL=?, GRAFANA_HISTORY_URL=?, HOT_DAYS=?, "
                + "    RECYCLE_POLICY=?, MAX_RUNS_PER_POD=?, POD_MAX_AGE_HOURS=?, ALWAYS_ON=? "
                + "WHERE GROUP_ID=?",
                name, description, grafanaLiveUrl, grafanaHistoryUrl, hotDays,
                (recyclePolicy == null ? RecyclePolicy.REUSE : recyclePolicy).name(), maxRunsPerPod, podMaxAgeHours,
                alwaysOn ? 1 : 0, groupId);
        if (updated == 0) {
            throw new EmptyResultDataAccessException("application group not found: " + groupId, 1);
        }
        return findById(groupId).orElseThrow();
    }

    /** Hard delete. {@code true} when a row was removed; the FK raises when applications remain. */
    public boolean delete(String groupId) {
        return jdbc.update(
                "DELETE FROM ORCH_APPLICATION_GROUP WHERE GROUP_ID=?", groupId) > 0;
    }

    /** Workers in the group's pool — a group with pods (any state) cannot be deleted. */
    public int countPods(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_POD WHERE GROUP_ID=?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    /** Applications in the group, archived ones included — they hold the FK too. */
    public int countApplications(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_APPLICATION WHERE METRICS_GROUP_ID=?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    /** {@code groupId → application count} for every group that has at least one. */
    public Map<String, Integer> applicationCounts() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query(
                "SELECT METRICS_GROUP_ID, COUNT(*) AS n FROM ORCH_APPLICATION "
                + "GROUP BY METRICS_GROUP_ID",
                rs -> { out.put(rs.getString("METRICS_GROUP_ID"), rs.getInt("N")); });
        return out;
    }
}
