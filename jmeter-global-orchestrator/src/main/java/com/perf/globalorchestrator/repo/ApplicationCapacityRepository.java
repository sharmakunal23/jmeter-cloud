package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.domain.ApplicationCapacity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D-Capacity v2 — persistence for {@code globalOrchestrator.applicationCapacity}.
 *
 * <p>One row per (applicationId, region). Foreign-keyed back to
 * {@code application} with ON DELETE CASCADE so deleting an app
 * automatically clears its capacity grants.
 *
 * <h2>Caching (CACHE C-EVICT, 2026-05-26)</h2>
 * The capacity grid is orchestrator-owned and slow-moving, so the two
 * list-returning reads are cached ({@link CacheConfig#CACHE_APPLICATION_CAPACITY})
 * and <b>every mutating method evicts the whole cache</b> — write-through
 * invalidation, so a capacity change is reflected on the next read with no TTL
 * wait. {@code allEntries = true} because a single (app, region) write
 * invalidates both the per-app entry ({@link #findByApplicationId}) and the
 * grouped-by-app entry ({@link #findAllGroupedByApp}); capacity writes are
 * infrequent, so clearing the small cache wholesale is simpler and always
 * correct. Annotating at the repository level means every caller —
 * {@code CapacityController}, {@code ApplicationController}, {@code RunService},
 * and any future one — is covered automatically. The <b>one</b> write that
 * bypasses this class is app retirement: {@code ApplicationController.delete}
 * soft-deletes the app and deliberately RETAINS its capacity rows (no
 * {@code ON DELETE CASCADE} fires), so it carries its own matching
 * {@code @CacheEvict} to clear the cached entry for the retired app.
 *
 * <p>{@link #find} (single row) and {@link #countActivePodsForAppRegion} (reads
 * the live pod/run tables) are deliberately NOT cached — the latter is
 * fast-changing and must never be stale at run-launch.
 */
@Repository
public class ApplicationCapacityRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<ApplicationCapacity> rowMapper = (rs, n) -> new ApplicationCapacity(
            rs.getString("applicationId"),
            rs.getString("region"),
            rs.getInt("maxAvailable"),
            instant(rs, "createdAt"),
            instant(rs, "updatedAt"));

    public ApplicationCapacityRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = CacheConfig.CACHE_APPLICATION_CAPACITY, key = "#applicationId")
    public List<ApplicationCapacity> findByApplicationId(String applicationId) {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"applicationCapacity\" "
                + "WHERE \"applicationId\" = ? ORDER BY \"region\"",
                rowMapper, applicationId);
    }

    public Optional<ApplicationCapacity> find(String applicationId, String region) {
        List<ApplicationCapacity> rows = jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"applicationCapacity\" "
                + "WHERE \"applicationId\" = ? AND \"region\" = ?",
                rowMapper, applicationId, region);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Upsert — insert if missing, update maxAvailable + updatedAt if present. */
    @CacheEvict(cacheNames = CacheConfig.CACHE_APPLICATION_CAPACITY, allEntries = true)
    public void upsert(String applicationId, String region, int maxAvailable) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"applicationCapacity\" "
                + "(\"applicationId\",\"region\",\"maxAvailable\") VALUES (?,?,?) "
                + "ON CONFLICT (\"applicationId\",\"region\") DO UPDATE "
                + "SET \"maxAvailable\" = EXCLUDED.\"maxAvailable\", \"updatedAt\" = now()",
                applicationId, region, maxAvailable);
    }

    /** Replace the entire capacity grid for an application (used by PUT /applications/{id}). */
    @CacheEvict(cacheNames = CacheConfig.CACHE_APPLICATION_CAPACITY, allEntries = true)
    public void replaceAll(String applicationId, List<ApplicationCapacity> entries) {
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"applicationCapacity\" WHERE \"applicationId\" = ?",
                applicationId);
        for (ApplicationCapacity c : entries) {
            jdbc.update(
                    "INSERT INTO \"globalOrchestrator\".\"applicationCapacity\" "
                    + "(\"applicationId\",\"region\",\"maxAvailable\") VALUES (?,?,?)",
                    applicationId, c.region(), c.maxAvailable());
        }
    }

    /**
     * Bulk fetch by app id — returns {applicationId → list-of-capacity}.
     * Used by ApplicationController.list() so listing N apps is O(1) DB
     * round-trips instead of N+1.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_APPLICATION_CAPACITY, key = "'all'")
    public Map<String, List<ApplicationCapacity>> findAllGroupedByApp() {
        Map<String, List<ApplicationCapacity>> out = new java.util.LinkedHashMap<>();
        jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"applicationCapacity\" "
                + "ORDER BY \"applicationId\", \"region\"",
                (ResultSet rs) -> {
                    String appId = rs.getString("applicationId");
                    out.computeIfAbsent(appId, k -> new java.util.ArrayList<>())
                       .add(new ApplicationCapacity(
                               appId,
                               rs.getString("region"),
                               rs.getInt("maxAvailable"),
                               instant(rs, "createdAt"),
                               instant(rs, "updatedAt")));
                });
        return out;
    }

    /**
     * D-Capacity v2 — pods currently allocated to in-flight runs of the
     * given application IN the given region. Drives the per-region
     * enforcement check at run-launch.
     *
     * <p>MID-TEST-SCALING Phase B — also filters on MEMBER state so that
     * terminal members within a still-non-terminal run (e.g., a DRAINED
     * worker in a run that's still RUNNING) don't count toward the cap.
     * DRAINING does count — the pod is still busy. The rule matches
     * {@link com.perf.globalorchestrator.domain.MemberState#isActiveForCapacity}.
     */
    public int countActivePodsForAppRegion(String application, String region) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) "
                + "  FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "  JOIN \"globalOrchestrator\".\"run\" r ON m.\"runId\" = r.\"runId\" "
                + " WHERE r.\"application\" = ? "
                + "   AND m.\"region\"      = ? "
                + "   AND r.\"state\" NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "   AND m.\"state\" IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                Integer.class, application, region);
        return n == null ? 0 : n;
    }

    @CacheEvict(cacheNames = CacheConfig.CACHE_APPLICATION_CAPACITY, allEntries = true)
    public boolean delete(String applicationId, String region) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"applicationCapacity\" "
                + "WHERE \"applicationId\" = ? AND \"region\" = ?",
                applicationId, region) > 0;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
