package com.perf.globalorchestrator.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.Application.HealthStatus;
import com.perf.globalorchestrator.domain.RecyclePolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D-AppRegistry — persistence for {@code globalOrchestrator.application}.
 * Reads + writes via the runState datasource (RW). The JSON columns are
 * round-tripped through Jackson; their {@code IS JSON} checks validate them
 * server-side.
 */
@Repository
public class ApplicationRepository {

    private static final TypeReference<List<String>> URLS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> DETAILS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Application> rowMapper;

    public ApplicationRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc,
                                 ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = buildRowMapper(json);
    }

    private static RowMapper<Application> buildRowMapper(ObjectMapper json) {
        return (rs, n) -> new Application(
                rs.getString("applicationId"),
                rs.getString("name"),
                rs.getString("sealId"),
                rs.getString("description"),
                jsonbList(json, OracleBind.json(rs, "healthEndpoints"), URLS_TYPE, List.of()),
                null,                                     // capacity hydrated separately
                instant(rs, "createdAt"),
                instant(rs, "lastHealthCheckedAt"),
                statusOrNull(rs.getString("lastHealthStatus")),
                OracleBind.json(rs, "lastHealthDetails") == null
                        ? null
                        : jsonbList(json, OracleBind.json(rs, "lastHealthDetails"), DETAILS_TYPE, List.of()),
                RecyclePolicy.valueOf(rs.getString("recyclePolicy")),
                nullableInt(rs, "maxRunsPerPod"),
                nullableInt(rs, "podMaxAgeHours"),
                rs.getBoolean("alwaysOn"),
                rs.getString("metricsGroupId"),
                rs.getString("metricsApplication"),
                rs.getString("grafanaLiveUrl"),
                rs.getString("grafanaHistoryUrl"));
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public Application insert(Application app) {
        String endpointsJson = serialise(app.healthEndpoints());
        // The constructor defaults a null policy
        // to REUSE, so this is always non-null. Thresholds may be null
        // per the cross-field CHECK constraint.
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"application\" "
                + "(\"applicationId\",\"name\",\"sealId\",\"description\","
                + " \"healthEndpoints\",\"createdAt\","
                + " \"recyclePolicy\",\"maxRunsPerPod\",\"podMaxAgeHours\","
                + " \"alwaysOn\",\"metricsGroupId\",\"metricsApplication\",\"grafanaLiveUrl\",\"grafanaHistoryUrl\") "
                + "VALUES (?,?,?,?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                app.applicationId(), app.name(), app.sealId(), app.description(),
                OracleBind.clob(endpointsJson), OracleBind.ts(app.createdAt()),
                app.recyclePolicy().name(), app.maxRunsPerPod(), app.podMaxAgeHours(),
                app.alwaysOn(), app.metricsGroupId(), app.metricsApplication(),
                app.grafanaLiveUrl(), app.grafanaHistoryUrl());
        return findById(app.applicationId()).orElseThrow();
    }

    /**
     * Visible (non-hidden) lookup by id. Mirrors {@link #findAll}'s
     * {@code hiddenAt IS NULL} predicate so a soft-deleted app is a 404 by id
     * on every read surface (GET/PUT/capacity/admin), not just on the list —
     * a retired app must not be reachable by guessing its id. The internal
     * {@code create()}/{@code update()} re-reads only ever target a row they
     * just wrote (always visible), so the filter is transparent to them; a
     * DELETE on an already-hidden app sees {@code empty()} and short-circuits
     * to its idempotent 204.
     */
    public Optional<Application> findById(String applicationId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM \"globalOrchestrator\".\"application\" "
                    + "WHERE \"applicationId\"=? AND \"hiddenAt\" IS NULL",
                    rowMapper, applicationId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * HARD-DELETE / purge Phase 2 — lookup by id restricted to HIDDEN apps
     * ({@code hiddenAt IS NOT NULL}). Returns the archived row (its
     * {@code name} is the {@code __deleted__} archived name). The purge requires
     * an app to be hidden first; the controller uses this (plus {@link #findById},
     * which returns only visible apps) to tell "unknown" (404) from "not hidden
     * yet" (409). Empty for an unknown OR still-visible app.
     */
    public Optional<Application> findHiddenById(String applicationId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM \"globalOrchestrator\".\"application\" "
                    + "WHERE \"applicationId\"=? AND \"hiddenAt\" IS NOT NULL",
                    rowMapper, applicationId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Application> findByName(String name) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM \"globalOrchestrator\".\"application\" WHERE \"name\"=?",
                    rowMapper, name));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Visible (non-hidden) applications only. Soft-deleted rows
     * ({@code hiddenAt IS NOT NULL}) are excluded so they drop out of the
     * applications list, launcher picker, capacity matrix, and health poller
     * — every surface reads through here.
     */
    public List<Application> findAll() {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"application\" "
                + "WHERE \"hiddenAt\" IS NULL ORDER BY \"name\"",
                rowMapper);
    }

    /**
     * HARD-DELETE / purge Phase 3 (UI) — HIDDEN apps only
     * ({@code hiddenAt IS NOT NULL}), newest-hidden first. Backs the "Archived
     * applications" view where the operator can permanently purge a retired app.
     * Names are the archived {@code __deleted__} form; the UI strips that suffix
     * for display.
     */
    public List<Application> findHidden() {
        return jdbc.query(
                "SELECT * FROM \"globalOrchestrator\".\"application\" "
                + "WHERE \"hiddenAt\" IS NOT NULL ORDER BY \"hiddenAt\" DESC",
                rowMapper);
    }

    public Application update(String applicationId, String name, String sealId,
                              String description, List<String> healthEndpoints,
                              RecyclePolicy recyclePolicy, Integer maxRunsPerPod,
                              Integer podMaxAgeHours, boolean alwaysOn,
                              String metricsGroupId, String metricsApplication,
                              String grafanaLiveUrl, String grafanaHistoryUrl) {
        String endpointsJson = serialise(healthEndpoints == null ? List.of() : healthEndpoints);
        // RecyclePolicy may be null on the caller
        // boundary (operator omitted the field on PUT); the controller
        // applies REUSE in that case before reaching here. Repo treats
        // null as "no change" defensively so a future internal caller
        // can update non-recycle metadata without disturbing the policy.
        int updated;
        if (recyclePolicy == null) {
            updated = jdbc.update(
                    "UPDATE \"globalOrchestrator\".\"application\" "
                    + "SET \"name\"=?, \"sealId\"=?, \"description\"=?, "
                    + "    \"healthEndpoints\"=?, \"alwaysOn\"=?, "
                    + "    \"metricsGroupId\"=?, \"metricsApplication\"=?, \"grafanaLiveUrl\"=?, \"grafanaHistoryUrl\"=? "
                    + "WHERE \"applicationId\"=?",
                    name, sealId, description, OracleBind.clob(endpointsJson), alwaysOn,
                    metricsGroupId, metricsApplication, grafanaLiveUrl, grafanaHistoryUrl, applicationId);
        } else {
            updated = jdbc.update(
                    "UPDATE \"globalOrchestrator\".\"application\" "
                    + "SET \"name\"=?, \"sealId\"=?, \"description\"=?, "
                    + "    \"healthEndpoints\"=?, "
                    + "    \"recyclePolicy\"=?, \"maxRunsPerPod\"=?, \"podMaxAgeHours\"=?, "
                    + "    \"alwaysOn\"=?, "
                    + "    \"metricsGroupId\"=?, \"metricsApplication\"=?, \"grafanaLiveUrl\"=?, \"grafanaHistoryUrl\"=? "
                    + "WHERE \"applicationId\"=?",
                    name, sealId, description, OracleBind.clob(endpointsJson),
                    recyclePolicy.name(), maxRunsPerPod, podMaxAgeHours,
                    alwaysOn, metricsGroupId, metricsApplication, grafanaLiveUrl, grafanaHistoryUrl, applicationId);
        }
        if (updated == 0) {
            throw new EmptyResultDataAccessException("application not found: " + applicationId, 1);
        }
        return findById(applicationId).orElseThrow();
    }

    /**
     * Update the health snapshot — written by ApplicationHealthPoller
     * after each poll cycle. Does NOT touch operator-managed fields.
     */
    public void updateHealth(String applicationId, HealthStatus status,
                             Instant checkedAt, List<Map<String, Object>> details) {
        String detailsJson = details == null ? null : serialise(details);
        jdbc.update(
                "UPDATE \"globalOrchestrator\".\"application\" "
                + "SET \"lastHealthStatus\"=?, \"lastHealthCheckedAt\"=?, "
                + "    \"lastHealthDetails\"=? "
                + "WHERE \"applicationId\"=?",
                status.name(), OracleBind.ts(checkedAt), OracleBind.clob(detailsJson), applicationId);
    }

    /**
     * Hard delete — physically removes the row (capacity rows go with it via
     * {@code ON DELETE CASCADE}). Reserved for the {@code create()}
     * topic-provision-failure rollback, where a just-inserted row must vanish
     * entirely. Operator-facing retirement uses {@link #softDelete} instead.
     */
    public boolean delete(String applicationId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"application\" WHERE \"applicationId\"=?",
                applicationId) > 0;
    }

    /**
     * Soft delete ("hide") — stamps {@code hiddenAt} so the app drops out of
     * {@link #findAll}, and RENAMES it to {@code archivedName} so the original
     * name is freed for re-registration (the {@code UNIQUE(name)} constraint
     * would otherwise keep it reserved). The row, run history, metrics, audit
     * events, and blobs are RETAINED under the archived identity. Idempotent:
     * already-hidden or unknown ids update zero rows and return {@code false}.
     * Returns {@code true} only when this call performed the hide.
     */
    public boolean softDelete(String applicationId, String archivedName) {
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"application\" "
                + "SET \"name\"=?, \"hiddenAt\"=SYSTIMESTAMP "
                + "WHERE \"applicationId\"=? AND \"hiddenAt\" IS NULL",
                archivedName, applicationId) > 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise application JSON column", e);
        }
    }

    private static <T> T jsonbList(ObjectMapper json, String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialise application JSON column", e);
        }
    }

    private static HealthStatus statusOrNull(String s) {
        return s == null ? null : HealthStatus.valueOf(s);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return OracleBind.instant(rs, col);
    }
}
