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
 * Persistence for {@code ORCH_GROUP_CAPACITY} — one row per
 * (groupId, region), foreign-keyed to {@code applicationGroup} with
 * {@code ON DELETE CASCADE}. The pool is the group's: every application in
 * the group launches against these rows (GROUP-CAPACITY, 2026-08-30).
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
            rs.getString("GROUP_ID"),
            rs.getString("REGION"),
            rs.getInt("MAX_AVAILABLE"),
            OracleBind.instant(rs, "CREATED_AT"),
            OracleBind.instant(rs, "UPDATED_AT"));

    public GroupCapacityRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, key = "#groupId")
    public List<GroupCapacity> findByGroupId(String groupId) {
        return jdbc.query(
                "SELECT * FROM ORCH_GROUP_CAPACITY "
                + "WHERE GROUP_ID = ? ORDER BY REGION",
                rowMapper, groupId);
    }

    public Optional<GroupCapacity> find(String groupId, String region) {
        List<GroupCapacity> rows = jdbc.query(
                "SELECT * FROM ORCH_GROUP_CAPACITY "
                + "WHERE GROUP_ID = ? AND REGION = ?",
                rowMapper, groupId, region);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Upsert — insert if missing, update maxAvailable + updatedAt if present. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, allEntries = true)
    public void upsert(String groupId, String region, int maxAvailable) {
        jdbc.update(
                "MERGE INTO ORCH_GROUP_CAPACITY t "
                + "USING (SELECT ? AS GROUP_ID, ? AS REGION FROM dual) s "
                + "ON (t.GROUP_ID = s.GROUP_ID AND t.REGION = s.REGION) "
                + "WHEN MATCHED THEN UPDATE SET t.MAX_AVAILABLE = ?, t.UPDATED_AT = SYSTIMESTAMP "
                + "WHEN NOT MATCHED THEN INSERT (GROUP_ID,REGION,MAX_AVAILABLE) "
                + "VALUES (s.GROUP_ID, s.REGION, ?)",
                groupId, region, maxAvailable, maxAvailable);
    }

    /** Replace the entire capacity grid for a group. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, allEntries = true)
    public void replaceAll(String groupId, List<GroupCapacity> entries) {
        jdbc.update("DELETE FROM ORCH_GROUP_CAPACITY WHERE GROUP_ID = ?", groupId);
        for (GroupCapacity c : entries) {
            jdbc.update(
                    "INSERT INTO ORCH_GROUP_CAPACITY "
                    + "(GROUP_ID,REGION,MAX_AVAILABLE) VALUES (?,?,?)",
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
                "SELECT * FROM ORCH_GROUP_CAPACITY "
                + "ORDER BY GROUP_ID, REGION",
                (ResultSet rs) -> {
                    String groupId = rs.getString("GROUP_ID");
                    out.computeIfAbsent(groupId, k -> new java.util.ArrayList<>())
                       .add(new GroupCapacity(
                               groupId,
                               rs.getString("REGION"),
                               rs.getInt("MAX_AVAILABLE"),
                               OracleBind.instant(rs, "CREATED_AT"),
                               OracleBind.instant(rs, "UPDATED_AT")));
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
                + "  FROM ORCH_RUN_FLEET_MEMBER m "
                + "  JOIN ORCH_RUN r ON m.RUN_ID = r.RUN_ID "
                + " WHERE r.METRICS_GROUP_ID = ? "
                + "   AND m.REGION         = ? "
                + "   AND r.STATE NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "   AND m.STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                Integer.class, groupId, region);
        return n == null ? 0 : n;
    }

    /**
     * Other groups' reservations on one cluster — the oversubscription check
     * (CLUSTER-CAPACITY). Call after {@link RegionRepository#lockMaxWorkers}
     * in the same transaction: the region-row lock serialises writers.
     */
    public int sumReservedForRegionExcluding(String region, String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(MAX_AVAILABLE), 0) FROM ORCH_GROUP_CAPACITY "
                + "WHERE REGION = ? AND GROUP_ID <> ?",
                Integer.class, region, groupId);
        return n == null ? 0 : n;
    }

    /** region → SUM of every group's reservation — the Clusters page's "Reserved" column. */
    public Map<String, Integer> reservedByRegion() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        jdbc.query(
                "SELECT REGION, SUM(MAX_AVAILABLE) AS RESERVED FROM ORCH_GROUP_CAPACITY "
                + "GROUP BY REGION ORDER BY REGION",
                (ResultSet rs) -> { out.put(rs.getString("REGION"), rs.getInt("RESERVED")); });
        return out;
    }

    /** Capacity rows referencing one cluster — the cluster-delete guard. */
    public int countByRegion(String region) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_GROUP_CAPACITY WHERE REGION = ?",
                Integer.class, region);
        return n == null ? 0 : n;
    }

    /** Capacity rows a group holds (any region) — the delete guard. */
    public int countByGroupId(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_GROUP_CAPACITY WHERE GROUP_ID = ?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    @CacheEvict(cacheNames = CacheConfig.CACHE_GROUP_CAPACITY, allEntries = true)
    public boolean delete(String groupId, String region) {
        return jdbc.update(
                "DELETE FROM ORCH_GROUP_CAPACITY "
                + "WHERE GROUP_ID = ? AND REGION = ?",
                groupId, region) > 0;
    }
}
