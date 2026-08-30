package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.GroupCapacity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for {@code "globalOrchestrator"."groupCapacity"} — one row per
 * (groupId, region), foreign-keyed to {@code applicationGroup} with
 * {@code ON DELETE CASCADE}. The pool is the group's: every application in
 * the group launches against these rows (GROUP-CAPACITY, 2026-08-31).
 *
 * <p>The grid is orchestrator-owned and slow-moving, so the list reads are
 * cached and <b>every mutating method evicts the whole cache</b>: write-through
 * invalidation means a capacity change shows on the next read with no TTL wait.
 * The eviction is {@code allEntries} because one (group, region) write
 * invalidates both the per-group and the grouped entries, and writes are
 * infrequent enough that clearing wholesale is simpler and always correct.
 *
 * <p>{@link #find} and {@link #countActivePodsForGroupRegion} are deliberately not
 * cached; the latter reads live run state and must never be stale at run-launch.
 */
@Repository
public class GroupCapacityRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<GroupCapacity> rowMapper = (rs, n) -> new GroupCapacity(
            rs.getString("groupId"),
            rs.getString("region"),
            rs.getInt("maxAvailable"),
            OracleBind.instant(rs, "createdAt"),
            OracleBind.instant(rs, "updatedAt"));

    public GroupCapacityRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, key = "#groupId")
    public List<GroupCapacity> findByGroupId(String groupId) {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"groupCapacity\" "
                + "WHERE \"groupId\" = ? ORDER BY \"region\"",
                rowMapper, groupId);
    }

    public Optional<GroupCapacity> find(String groupId, String region) {
        List<GroupCapacity> rows = jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"groupCapacity\" "
                + "WHERE \"groupId\" = ? AND \"region\" = ?",
                rowMapper, groupId, region);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Upsert — insert if missing, update maxAvailable + updatedAt if present. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, allEntries = true)
    public void upsert(String groupId, String region, int maxAvailable) {
        jdbc.update(
                "MERGE INTO \"globalOrchestrator\".\"groupCapacity\" t "
                + "USING (SELECT ? AS \"groupId\", ? AS \"region\" FROM dual) s "
                + "ON (t.\"groupId\" = s.\"groupId\" AND t.\"region\" = s.\"region\") "
                + "WHEN MATCHED THEN UPDATE SET t.\"maxAvailable\" = ?, t.\"updatedAt\" = SYSTIMESTAMP "
                + "WHEN NOT MATCHED THEN INSERT (\"groupId\",\"region\",\"maxAvailable\") "
                + "VALUES (s.\"groupId\", s.\"region\", ?)",
                groupId, region, maxAvailable, maxAvailable);
    }

    /** Replace the entire capacity grid for a group. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, allEntries = true)
    public void replaceAll(String groupId, List<GroupCapacity> entries) {
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"groupCapacity\" WHERE \"groupId\" = ?", groupId);
        for (GroupCapacity c : entries) {
            jdbc.update(
                    "INSERT INTO \"globalOrchestrator\".\"groupCapacity\" "
                    + "(\"groupId\",\"region\",\"maxAvailable\") VALUES (?,?,?)",
                    groupId, c.region(), c.maxAvailable());
        }
    }

    /**
     * Bulk fetch — returns {groupId → list-of-capacity}. Used by the group
     * list so listing N groups is O(1) DB round-trips instead of N+1.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, key = "'all'")
    public Map<String, List<GroupCapacity>> findAllGroupedByGroup() {
        Map<String, List<GroupCapacity>> out = new java.util.LinkedHashMap<>();
        jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"groupCapacity\" "
                + "ORDER BY \"groupId\", \"region\"",
                (ResultSet rs) -> {
                    String groupId = rs.getString("groupId");
                    out.computeIfAbsent(groupId, k -> new java.util.ArrayList<>())
                       .add(new GroupCapacity(
                               groupId,
                               rs.getString("region"),
                               rs.getInt("maxAvailable"),
                               OracleBind.instant(rs, "createdAt"),
                               OracleBind.instant(rs, "updatedAt")));
                });
        return out;
    }

    /**
     * Pods currently allocated to in-flight runs of the group IN the given
     * region — the per-region ceiling check at run-launch. Counted by the
     * run's {@code metricsGroupId} (frozen at launch, indexed with the state),
     * so two applications in one group share one ceiling and an application
     * rename never resets the count.
     *
     * <p>Also filters on MEMBER state so that terminal members within a
     * still-non-terminal run (e.g. a DRAINED worker in a run that's still
     * RUNNING) don't count toward the cap. DRAINING does count — the pod is
     * still busy. The rule matches
     * {@link com.perf.globalorchestrator.domain.MemberState#isActiveForCapacity}.
     */
    public int countActivePodsForGroupRegion(String groupId, String region) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) "
                + "  FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "  JOIN \"globalOrchestrator\".\"run\" r ON m.\"runId\" = r.\"runId\" "
                + " WHERE r.\"metricsGroupId\" = ? "
                + "   AND m.\"region\"         = ? "
                + "   AND r.\"state\" NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "   AND m.\"state\" IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                Integer.class, groupId, region);
        return n == null ? 0 : n;
    }

    /** Capacity rows a group holds (any region) — the delete guard. */
    public int countByGroupId(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"globalOrchestrator\".\"groupCapacity\" WHERE \"groupId\" = ?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    @CacheEvict(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, allEntries = true)
    public boolean delete(String groupId, String region) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"groupCapacity\" "
                + "WHERE \"groupId\" = ? AND \"region\" = ?",
                groupId, region) > 0;
    }
}
