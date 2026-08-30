package com.perf.globalorchestrator.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for {@code ORCH_RUN} and
 * {@code ORCH_RUN_FLEET_MEMBER}. Uses the runState datasource
 * (RW) — see {@link com.perf.globalorchestrator.config.DataSourceConfig}.
 */
@Repository
public class RunRepository {

    private static final TypeReference<Map<String, String>> PROPERTIES_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<RunFleetMember> memberRowMapper;

    public RunRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc,
                         ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.memberRowMapper = buildMemberRowMapper(json);
    }

    private static RowMapper<RunFleetMember> buildMemberRowMapper(ObjectMapper json) {
        return (rs, n) -> {
            // properties JSON → Map<String,String>; {} when absent.
            String propsRaw = OracleBind.json(rs, "PROPERTIES");
            Map<String, String> props;
            if (propsRaw == null || propsRaw.isBlank()) {
                props = Map.of();
            } else {
                try {
                    props = json.readValue(propsRaw, PROPERTIES_TYPE);
                } catch (Exception e) {
                    throw new SQLException("failed to deserialise runFleetMember.properties", e);
                }
            }
            // joinedAtSecond → NULL for original-fleet members. Read through
            // getLong + wasNull: Oracle surfaces NUMBER as BigDecimal from
            // getObject, so a cast to Long would throw.
            Long joinedAtSecond = nullableLong(rs, "JOINED_AT_SECOND");
            // runsServed is joined from the pod table at SELECT time (see
            // findMembers). Null when the pod row is gone (drained pod whose
            // member row outlived it) or when the read path didn't include
            // the join (insert codepaths).
            Long runsServed = hasColumn(rs, "POD_RUNS_SERVED")
                    ? nullableLong(rs, "POD_RUNS_SERVED")
                    : null;
            return new RunFleetMember(
                    rs.getString("RUN_ID"),
                    rs.getString("WORKER_ID"),
                    rs.getString("REGION"),
                    MemberState.valueOf(rs.getString("STATE")),
                    rs.getString("STATE_REASON"),
                    nullableInt(rs, "FANOUT_STATUS_CODE"),
                    rs.getString("POD_BASE_URL"),
                    instant(rs, "CREATED_AT"),
                    instant(rs, "STARTED_AT"),
                    instant(rs, "COMPLETED_AT"),
                    props,
                    joinedAtSecond,
                    runsServed);
        };
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    /** True iff the row's metadata has a column by this name. Cheap; used
     *  for opt-in joined columns (pod.runsServed) that aren't on every
     *  SELECT. */
    private static boolean hasColumn(java.sql.ResultSet rs, String name) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        for (int i = 1; i <= n; i++) {
            if (name.equalsIgnoreCase(md.getColumnLabel(i))) return true;
        }
        return false;
    }

    public void insertRun(Run run) {
        jdbc.update(
                "INSERT INTO ORCH_RUN "
                + "(RUN_ID,ORIGIN_REGION,TEST_PLAN_BLOB_ID,DATA_FILES_BLOB_ID,"
                + " APPLICATION,INITIATED_BY,STATE,CREATED_AT,SAVE_RESULTS,METRICS_GROUP_ID) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                run.runId(), run.originRegion(), run.testPlanBlobId(),
                run.dataFilesBlobId(), run.application(),
                OracleBind.text(run.initiatedBy(), OracleBind.NAME_CHARS), run.state().name(),
                OracleBind.ts(run.createdAt()), run.saveResults(), run.metricsGroupId());
    }

    public void insertFleetMember(RunFleetMember m) {
        // properties → CLOB; the column's IS JSON check rejects malformed
        // input server-side. The properties-validation contract is upstream
        // (StartTestRequest's compact constructor on the local-orch),
        // so a write here only fails on a programming error.
        String propsJson;
        try {
            propsJson = json.writeValueAsString(
                    m.properties() == null ? Map.of() : m.properties());
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise fleet-member properties", e);
        }
        jdbc.update(
                "INSERT INTO ORCH_RUN_FLEET_MEMBER "
                + "(RUN_ID,WORKER_ID,REGION,STATE,POD_BASE_URL,"
                + " CREATED_AT,PROPERTIES,JOINED_AT_SECOND) "
                + "VALUES (?,?,?,?,?,?, ?, ?)",
                m.runId(), m.workerId(), m.region(), m.state().name(),
                m.podBaseUrl(), OracleBind.ts(m.createdAt()), OracleBind.clob(propsJson),
                m.joinedAtSecond());
    }

    /**
     * Save Results — flip the run's {@code saveResults} flag off. Called when a
     * run is aborted: a force-terminated run never produces a clean upload (the
     * local-orchestrator skips the JTL upload on a non-COMPLETE stop), so the
     * UI must stop advertising a "Download results" that would 404, and
     * {@link RunService#refreshAndGet}'s terminal fast-path must re-engage
     * (it's gated on {@code !saveResults}) so we stop polling the dead workers.
     */
    public void clearSaveResults(String runId) {
        jdbc.update(
                "UPDATE ORCH_RUN SET SAVE_RESULTS=0 WHERE RUN_ID=?",
                runId);
    }

    /**
     * Save Results reconciliation — run IDs that COMPLETED with {@code
     * saveResults} on, completed at or after {@code completedAfter}, and still
     * have a <em>clean-exit</em> fleet member (COMPLETED / DRAINED — the states
     * that upload; FAILED / ABORTED never do) <em>without</em> a {@code
     * RESULTS_SAVED} audit event. The background sweeper drives {@link
     * RunService#refreshAndGet} on each so the per-worker JTL upload (which
     * finishes AFTER the run goes terminal, once no UI is polling) is observed
     * and recorded. Bounded by {@code completedAfter} so a run whose upload
     * never lands (e.g. a worker died post-completion) stops being polled
     * instead of being chased forever, and the 200-row fetch caps a tick.
     */
    public List<String> runIdsAwaitingResultsSaved(Instant completedAfter) {
        return jdbc.queryForList(
                "SELECT r.RUN_ID FROM ORCH_RUN r "
                + "WHERE r.STATE='COMPLETED' AND r.SAVE_RESULTS=1 "
                + "  AND r.COMPLETED_AT >= ? "
                + "  AND (SELECT count(*) FROM ORCH_RUN_FLEET_MEMBER m "
                + "         WHERE m.RUN_ID=r.RUN_ID AND m.POD_BASE_URL IS NOT NULL "
                + "           AND m.STATE IN ('COMPLETED','DRAINED')) "
                + "      > (SELECT count(DISTINCT JSON_VALUE(e.PAYLOAD, '$.workerId')) "
                + "           FROM ORCH_RUN_EVENT e "
                + "           WHERE e.RUN_ID=r.RUN_ID AND e.EVENT_TYPE='RESULTS_SAVED') "
                + "ORDER BY r.COMPLETED_AT "
                + "FETCH FIRST 200 ROWS ONLY",
                String.class, OracleBind.ts(completedAfter));
    }

    /**
     * Soft-delete — mark a run hidden (stamps {@code hiddenAt}) so it drops
     * out of the default listing. Reversible: the row, fleet members, and audit
     * trail are retained; {@link #listRuns(ListRunsCriteria)} filters
     * {@code hiddenAt IS NULL} unless {@code includeHidden}. The
     * {@code hiddenAt IS NULL} guard makes a repeat hide a no-op (idempotent).
     */
    public void markHidden(String runId) {
        jdbc.update(
                "UPDATE ORCH_RUN SET HIDDEN_AT=SYSTIMESTAMP "
                + "WHERE RUN_ID=? AND HIDDEN_AT IS NULL",
                runId);
    }

    /**
     * HARD-DELETE / purge — true iff the run exists AND is hidden
     * ({@code hiddenAt IS NOT NULL}). The purge path requires a run to be hidden
     * (trashed) first, so this gates the "trash → empty trash" two-tier flow: an
     * un-hidden run can only be hidden, never purged directly.
     */
    public boolean isRunHidden(String runId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ORCH_RUN "
                + "WHERE RUN_ID=? AND HIDDEN_AT IS NOT NULL",
                Integer.class, runId);
        return n != null && n > 0;
    }

    /**
     * HARD-DELETE / purge — how many OTHER runs (any run but {@code excludeRunId})
     * still reference {@code blobId} as their testPlan or dataFiles blob. The
     * purge deletes a run's testPlan/dataFiles blob only when this is 0; the same
     * uploaded plan is commonly reused across many runs (templates, re-launches),
     * so deleting it while a sibling still points at it would break that sibling's
     * download / re-run. Result blobs are per-(runId, workerId) and need no such
     * guard. {@code blobId} carries no LIKE wildcards (it's a ULID), so the
     * equality match is exact.
     */
    public int countOtherRunsReferencingBlob(String blobId, String excludeRunId) {
        if (blobId == null || blobId.isBlank()) return 0;
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ORCH_RUN "
                + "WHERE RUN_ID <> ? "
                + "  AND (TEST_PLAN_BLOB_ID = ? OR DATA_FILES_BLOB_ID = ?)",
                Integer.class, excludeRunId, blobId, blobId);
        return n == null ? 0 : n;
    }

    /**
     * HARD-DELETE / purge — physically removes the run row. The FK
     * {@code ON DELETE CASCADE} on {@code runFleetMember} and {@code runEvent}
     * takes those child rows with it (the cascade runs with the table
     * owner's privileges, so no extra grant is needed). Idempotent: a re-run of a
     * partially-completed purge matches zero rows. Returns the rowcount (1 when
     * the run existed, 0 when already gone). NOT to be confused with
     * {@link #markHidden} (the reversible soft delete).
     */
    public int deleteRunRow(String runId) {
        return jdbc.update(
                "DELETE FROM ORCH_RUN WHERE RUN_ID=?",
                runId);
    }

    /** Compare-and-set on the run state; 0 rows means the run has moved on (an abort landed). */
    public int updateRunStateFrom(String runId, RunState from, RunState to, String reason) {
        return jdbc.update(
                "UPDATE ORCH_RUN SET STATE=?, STATE_REASON=? "
                + "WHERE RUN_ID=? AND STATE=?",
                to.name(), OracleBind.text(reason, OracleBind.TEXT_CHARS), runId, from.name());
    }

    public void updateRunState(String runId, RunState state, String reason) {
        jdbc.update(
                "UPDATE ORCH_RUN "
                + "SET STATE=?, STATE_REASON=?, "
                + "STARTED_AT=COALESCE(STARTED_AT, "
                + "  CASE WHEN ? = 'RUNNING' THEN SYSTIMESTAMP ELSE NULL END), "
                + "COMPLETED_AT=COALESCE(COMPLETED_AT, "
                + "  CASE WHEN ? IN ('COMPLETED','FAILED','ABORTED') THEN SYSTIMESTAMP ELSE NULL END) "
                + "WHERE RUN_ID=?",
                state.name(), OracleBind.text(reason, OracleBind.TEXT_CHARS), state.name(), state.name(), runId);
    }

    /**
     * Claim the transition into a terminal
     * state. Flips the run only when it is not already terminal and reports
     * whether THIS caller won (rowcount 1). {@code refreshAndGet} runs
     * concurrently across replicas (UI polls + ResultsSavedSweeper +
     * PodSweeper reap on every instance); the winner-only contract is what
     * keeps the terminal audit event and the runTrend snapshot single-shot.
     * {@code completedAt} COALESCEs so the first stamp wins.
     */
    /**
     * FAILs PREPARING runs created before {@code cutoff}; returns their ids.
     * Select-then-update (Oracle has no multi-row RETURNING in JDBC): a run that
     * becomes stale between the two statements is caught by the next sweep.
     */
    public List<String> failStalePreparing(Instant cutoff, String reason) {
        List<String> ids = jdbc.queryForList(
                "SELECT RUN_ID FROM ORCH_RUN "
                + "WHERE STATE='PREPARING' AND CREATED_AT < ?",
                String.class, OracleBind.ts(cutoff));
        List<String> failed = new ArrayList<>();
        for (String runId : ids) {
            int n = jdbc.update(
                    "UPDATE ORCH_RUN "
                    + "SET STATE='FAILED', STATE_REASON=?, COMPLETED_AT=COALESCE(COMPLETED_AT, SYSTIMESTAMP) "
                    + "WHERE RUN_ID=? AND STATE='PREPARING'",
                    OracleBind.text(reason, OracleBind.TEXT_CHARS), runId);
            if (n > 0) failed.add(runId);
        }
        return failed;
    }

    public int updateRunStateClaimingTerminal(String runId, RunState state, String reason) {
        return jdbc.update(
                "UPDATE ORCH_RUN "
                + "SET STATE=?, STATE_REASON=?, "
                + "COMPLETED_AT=COALESCE(COMPLETED_AT, SYSTIMESTAMP) "
                + "WHERE RUN_ID=? "
                + "  AND STATE NOT IN ('COMPLETED','FAILED','ABORTED')",
                state.name(), OracleBind.text(reason, OracleBind.TEXT_CHARS), runId);
    }

    public void updateMemberState(String runId, String workerId,
                                  MemberState state, String reason,
                                  Integer fanoutStatusCode) {
        jdbc.update(
                "UPDATE ORCH_RUN_FLEET_MEMBER "
                + "SET STATE=?, STATE_REASON=?, FANOUT_STATUS_CODE=COALESCE(?,FANOUT_STATUS_CODE), "
                + "STARTED_AT=COALESCE(STARTED_AT, "
                + "  CASE WHEN ? IN ('RUNNING','ACCEPTED') THEN SYSTIMESTAMP ELSE NULL END), "
                + "COMPLETED_AT=COALESCE(COMPLETED_AT, "
                + "  CASE WHEN ? IN ('COMPLETED','FAILED','ABORTED') THEN SYSTIMESTAMP ELSE NULL END) "
                + "WHERE RUN_ID=? AND WORKER_ID=?",
                state.name(), OracleBind.text(reason, OracleBind.TEXT_CHARS), OracleBind.typed(Types.INTEGER, fanoutStatusCode),
                state.name(), state.name(),
                runId, workerId);
    }

    /**
     * Forces every still-active fleet-member row for {@code workerId} to
     * ABORTED. "Active" matches the same states the claim/capacity paths
     * treat as occupying a pod ({@code PENDING / REQUESTED / ACCEPTED /
     * RUNNING / DRAINING}). Used by two paths:
     * <ul>
     *   <li>run-abort — releases all of a run's bindings as the run goes
     *       terminal (per-member call from {@code RunService.commitAbort});</li>
     *   <li>stale-drain — when the operator drains a pod whose container is
     *       gone but whose run still shows it active, this releases the dead
     *       binding so the pod can be drained and a re-spun same-name pod
     *       won't re-bind to the zombie run.</li>
     * </ul>
     * Returns the rowcount (0 when the pod held no active binding).
     */
    public int abortActiveMembersForWorker(String workerId, String reason) {
        return jdbc.update(
                "UPDATE ORCH_RUN_FLEET_MEMBER "
                + "SET STATE='ABORTED', STATE_REASON=?, "
                + "    COMPLETED_AT=COALESCE(COMPLETED_AT, SYSTIMESTAMP) "
                + "WHERE WORKER_ID=? "
                + "  AND STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                OracleBind.text(reason, OracleBind.TEXT_CHARS), workerId);
    }

    /**
     * Reliability — fail every still-active fleet member whose worker's pod has
     * been marked {@code LOST} by the heartbeat sweeper. A killed worker's pod
     * flips to LOST after the heartbeat threshold, but nothing else transitions
     * its member row, so without this the member sticks at RUNNING forever (the
     * status poller silently no-ops on an unreachable worker). FAILED — not
     * ABORTED — because the worker died unexpectedly; ABORTED is reserved for an
     * operator's deliberate stop.
     *
     * <p>Set-based + idempotent: safe to run every sweep tick; it self-heals a
     * member a prior tick missed and matches zero rows in steady state. Returns
     * the {@code runId} of each member it failed (with duplicates when a run had
     * several members on lost pods) so the caller can roll those runs up.
     */
    /** Per-worker form of {@link #failActiveMembersOnLostPods}: the kubelet said why this one died. Returns the affected runIds. */
    public List<String> failActiveMembersForWorker(String workerId, String reason) {
        List<String> runIds = jdbc.queryForList(
                "SELECT RUN_ID FROM ORCH_RUN_FLEET_MEMBER "
                + "WHERE WORKER_ID=? "
                + "  AND STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                String.class, workerId);
        List<String> failed = new ArrayList<>();
        for (String runId : runIds) {
            int n = jdbc.update(
                    "UPDATE ORCH_RUN_FLEET_MEMBER "
                    + "SET STATE='FAILED', STATE_REASON=?, "
                    + "    COMPLETED_AT=COALESCE(COMPLETED_AT, SYSTIMESTAMP) "
                    + "WHERE RUN_ID=? AND WORKER_ID=? "
                    + "  AND STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                    OracleBind.text(reason, OracleBind.TEXT_CHARS), runId, workerId);
            if (n > 0) failed.add(runId);
        }
        return failed;
    }

    public List<String> failActiveMembersOnLostPods(String reason) {
        List<Map<String, Object>> members = jdbc.queryForList(
                "SELECT m.RUN_ID, m.WORKER_ID "
                + "FROM ORCH_RUN_FLEET_MEMBER m "
                + "WHERE m.STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING') "
                + "  AND EXISTS (SELECT 1 FROM ORCH_POD p "
                + "              WHERE p.POD_ID = m.WORKER_ID AND p.STATE = 'LOST')");
        List<String> failed = new ArrayList<>();
        for (Map<String, Object> m : members) {
            String runId = (String) m.get("RUN_ID");
            int n = jdbc.update(
                    "UPDATE ORCH_RUN_FLEET_MEMBER "
                    + "SET STATE='FAILED', STATE_REASON=?, "
                    + "    COMPLETED_AT=COALESCE(COMPLETED_AT, SYSTIMESTAMP) "
                    + "WHERE RUN_ID=? AND WORKER_ID=? "
                    + "  AND STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')",
                    OracleBind.text(reason, OracleBind.TEXT_CHARS), runId, (String) m.get("WORKER_ID"));
            if (n > 0) failed.add(runId);
        }
        return failed;
    }

    public Optional<Run> findByRunId(String runId) {
        try {
            Run run = jdbc.queryForObject(
                    "SELECT * FROM ORCH_RUN WHERE RUN_ID=?",
                    RUN_ROW_MAPPER_NO_MEMBERS, runId);
            return Optional.of(withMembers(run, findMembers(runId)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<RunFleetMember> findMembers(String runId) {
        // Phase F2 — LEFT JOIN pod so each member row carries its pod's
        // current runsServed (or NULL if the pod has been drained and
        // its row removed since the member was inserted). The column
        // alias `podRunsServed` keeps the join non-ambiguous and lets
        // the rowMapper's `hasColumn` gate stay clean.
        return jdbc.query(
                "SELECT m.*, p.RUNS_SERVED AS POD_RUNS_SERVED "
                + "FROM ORCH_RUN_FLEET_MEMBER m "
                + "LEFT JOIN ORCH_POD p ON p.POD_ID = m.WORKER_ID "
                + "WHERE m.RUN_ID=? "
                + "ORDER BY m.WORKER_ID",
                memberRowMapper, runId);
    }

    /**
     * The most-recent run a worker (pod) served, by {@code
     * createdAt}. Drives PodRecycler's best-effort attribution of a recycle to
     * a run (a pod can serve many runs over its life, so "most recent" is the
     * pragmatic choice). Empty when the pod never served a run. Backed by the
     * {@code ORCH_RUN_FLEET_MEMBER_WORKER_ID_IDX} index.
     */
    public Optional<String> findMostRecentRunIdForWorker(String workerId) {
        try {
            String runId = jdbc.queryForObject(
                    "SELECT RUN_ID FROM ORCH_RUN_FLEET_MEMBER "
                    + "WHERE WORKER_ID=? ORDER BY CREATED_AT DESC FETCH FIRST 1 ROWS ONLY",
                    String.class, workerId);
            return Optional.ofNullable(runId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Run> listRuns(boolean activeOnly, int limit) {
        return listRuns(new ListRunsCriteria(activeOnly, null, false, false, 0, limit)).runs();
    }

    /**
     * UI-D3 — paginated + application-filtered listing. Returns the page of
     * rows plus the total count so the UI can render a {@code <Paginator>}
     * without a second round-trip.
     *
     * <p>{@code activeOnly} narrows to states not in COMPLETED/FAILED/ABORTED.
     * {@code application}, when non-null, narrows to rows where
     * {@code run.application = ?} (NULL rows are excluded — the app filter
     * is a positive predicate, not "show me untagged"). {@code offset} and
     * {@code limit} drive pagination; {@code limit} is clamped to a sane cap
     * upstream by the controller.
     */
    public ListRunsPage listRuns(ListRunsCriteria c) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (c.activeOnly()) {
            where.append("STATE NOT IN ('COMPLETED','FAILED','ABORTED')");
        }
        if (c.application() != null) {
            if (where.length() > 0) where.append(" AND ");
            where.append("APPLICATION = ?");
            args.add(c.application());
        }
        // Soft-delete visibility. Three modes:
        //   • hiddenOnly  → the "Archived" view: ONLY hidden runs (the purge
        //     surface — every row here is a hidden run eligible for hard delete).
        //   • includeHidden → visible + hidden mixed (admin escape hatch).
        //   • default      → visible only (hidden runs drop out).
        if (c.hiddenOnly()) {
            if (where.length() > 0) where.append(" AND ");
            where.append("HIDDEN_AT IS NOT NULL");
        } else if (!c.includeHidden()) {
            if (where.length() > 0) where.append(" AND ");
            where.append("HIDDEN_AT IS NULL");
        }

        String whereClause = where.length() == 0 ? "" : "WHERE " + where + " ";

        // Total count first — needed for the X-Total-Count header even when
        // the page itself is empty.
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_RUN " + whereClause,
                Integer.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        String sql = "SELECT * FROM ORCH_RUN "
                + whereClause
                + "ORDER BY CREATED_AT DESC "
                + "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        Object[] pageArgs = new Object[args.size() + 2];
        for (int i = 0; i < args.size(); i++) pageArgs[i] = args.get(i);
        pageArgs[args.size()]     = c.offset();
        pageArgs[args.size() + 1] = c.limit();

        List<Run> bare = jdbc.query(sql, RUN_ROW_MAPPER_NO_MEMBERS, pageArgs);
        List<Run> hydrated = new ArrayList<>(bare.size());
        for (Run r : bare) {
            hydrated.add(withMembers(r, findMembers(r.runId())));
        }
        return new ListRunsPage(hydrated, totalCount);
    }

    /**
     * Count this application's active (non-terminal) runs. Used by the
     * application soft-delete guard — an app with live runs can't be hidden
     * (they'd be orphaned from navigation). Uses the same terminal-state set
     * as {@link #listRuns(ListRunsCriteria)}'s {@code activeOnly}; hidden runs
     * are terminal by construction, so no extra predicate is needed.
     */
    public int countActiveByApplication(String application) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_RUN "
                + "WHERE APPLICATION = ? "
                + "AND STATE NOT IN ('COMPLETED','FAILED','ABORTED')",
                Integer.class, application);
        return n == null ? 0 : n;
    }

    /**
     * Count ALL active (non-terminal) runs across every application. Backs the
     * SECURITY S-0 {@code security.concurrentRuns} gauge — a cheap, indexed
     * count sampled at Prometheus scrape cadence (~15 s), surfacing a run-launch
     * flood (or a stuck fleet) as an abuse/health signal. Same terminal-state
     * set as {@link #countActiveByApplication}.
     */
    public int countActive() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORCH_RUN "
                + "WHERE STATE NOT IN ('COMPLETED','FAILED','ABORTED')",
                Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Counts of this application's runs created since
     * {@code since}, grouped by terminal/active state (state name → count).
     * Drives the daily perf-report's "launched / completed / failed" line:
     * launched = sum of all values; completed = COMPLETED; failed = FAILED +
     * ABORTED. Counts the run by {@code createdAt} (when it was launched) so a
     * run started in-window but still running is included in "launched".
     */
    public Map<String, Long> countByStateForApplicationSince(String application, Instant since) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT STATE, count(*) AS N "
                + "FROM ORCH_RUN "
                + "WHERE APPLICATION = ? AND CREATED_AT >= ? "
                + "GROUP BY STATE",
                application, OracleBind.ts(since));
        Map<String, Long> byState = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            byState.put((String) r.get("STATE"), ((Number) r.get("N")).longValue());
        }
        return byState;
    }

    /**
     * Re-tag every run from {@code fromApplication} to {@code toApplication}.
     * Used when an application is soft-deleted: its runs move to the app's
     * archived (hidden) name so the original name is freed for a fresh app
     * without that new app inheriting this one's history — and so a future
     * purge job can find the hidden app's runs by its archived name. Returns
     * the number of rows re-tagged. Caller scopes this to terminal runs only
     * (the soft-delete guard rejects apps with active runs).
     */
    public int reassignApplication(String fromApplication, String toApplication) {
        return jdbc.update(
                "UPDATE ORCH_RUN SET APPLICATION=? WHERE APPLICATION=?",
                toApplication, fromApplication);
    }

    /**
     * HARD-DELETE / purge Phase 2 — every run id tagged to {@code application},
     * hidden or not. When an app is soft-deleted its runs are re-tagged to the
     * archived name (see {@link #reassignApplication}), so the application purge
     * passes that archived name here to collect the runs to purge. No {@code
     * hiddenAt} filter — a purge must sweep ALL of the app's runs, including any
     * individually-hidden ones.
     */
    public List<String> findRunIdsByApplication(String application) {
        return jdbc.queryForList(
                "SELECT RUN_ID FROM ORCH_RUN WHERE APPLICATION=?",
                String.class, application);
    }

    /**
     * Filter + page criteria for {@link #listRuns(ListRunsCriteria)}.
     * {@code hiddenOnly} (the Archive / purge view) takes precedence over
     * {@code includeHidden}.
     */
    public record ListRunsCriteria(boolean activeOnly, String application,
                                   boolean includeHidden, boolean hiddenOnly,
                                   int offset, int limit) {}

    /** Page result for {@link #listRuns(ListRunsCriteria)} — runs + total count. */
    public record ListRunsPage(List<Run> runs, long total) {}

    private static final RowMapper<Run> RUN_ROW_MAPPER_NO_MEMBERS = (rs, n) -> new Run(
            rs.getString("RUN_ID"),
            rs.getString("ORIGIN_REGION"),
            rs.getString("TEST_PLAN_BLOB_ID"),
            rs.getString("DATA_FILES_BLOB_ID"),
            rs.getString("APPLICATION"),
            rs.getString("INITIATED_BY"),
            RunState.valueOf(rs.getString("STATE")),
            rs.getString("STATE_REASON"),
            instant(rs, "CREATED_AT"),
            instant(rs, "STARTED_AT"),
            instant(rs, "COMPLETED_AT"),
            rs.getBoolean("SAVE_RESULTS"),
            null,
            rs.getString("METRICS_GROUP_ID"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return OracleBind.instant(rs, col);
    }

    private static Run withMembers(Run base, List<RunFleetMember> members) {
        return new Run(
                base.runId(), base.originRegion(), base.testPlanBlobId(),
                base.dataFilesBlobId(), base.application(), base.initiatedBy(),
                base.state(), base.stateReason(), base.createdAt(),
                base.startedAt(), base.completedAt(), base.saveResults(), members, base.metricsGroupId());
    }
}
