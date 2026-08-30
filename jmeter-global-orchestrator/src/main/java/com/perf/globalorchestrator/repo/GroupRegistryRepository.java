package com.perf.globalorchestrator.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The metrics schema's routing table and run dimension, read through the
 * read-only metrics pool (unqualified names — the pool sets
 * {@code CURRENT_SCHEMA}). Exactly the two lookups the hosted consumer makes
 * before it writes, so a reader lands on the same table the writer used.
 */
@Repository
public class GroupRegistryRepository {

    /** A group's fact tables as registered ({@code GROUP_REGISTRY}). */
    public record GroupRow(String groupId, String prefix, String metricsTable, String historyTable) { }

    private final JdbcTemplate jdbc;

    public GroupRegistryRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GroupRow> findGroup(String groupId) {
        List<GroupRow> rows = jdbc.query(
                "SELECT GROUP_ID, TABLE_PREFIX, METRICS_TABLE, METRICS_HIST_TABLE "
                + "FROM GROUP_REGISTRY WHERE GROUP_ID = ? AND ENABLED = 1",
                (rs, n) -> new GroupRow(rs.getString("GROUP_ID"), rs.getString("TABLE_PREFIX"),
                        rs.getString("METRICS_TABLE"), rs.getString("METRICS_HIST_TABLE")),
                groupId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The run's surrogate key in the group, once the consumer has created it. */
    public Optional<Long> findRunId(String prefix, String runKey) {
        List<Long> ids = jdbc.queryForList(
                "SELECT RUN_ID FROM RUN WHERE GROUP_ID = ? AND RUN_KEY = ?", Long.class, prefix, runKey);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
