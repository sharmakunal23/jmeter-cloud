package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.domain.RegionCapacity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * CRUD for {@code globalOrchestrator.pod} — the Step 15 pod registry.
 * Uses the run-state datasource (RW), same as {@link RunRepository}.
 */
@Repository
public class PodRepository {

    private final JdbcTemplate jdbc;

    public PodRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent register-or-refresh. INSERT … ON CONFLICT DO UPDATE so
     * a re-registering pod (post-restart) gets its identity refreshed
     * (state ← IDLE, baseUrl ← whatever it sends, lastHeartbeat ← now).
     *
     * <p>{@code applicationId} (Phase 1 capacity rework) may be {@code null}
     * during the migration window — legacy static pods register without it.
     * On re-register, COALESCE preserves a previously-set applicationId
     * so a pod that goes through a transient identity wobble doesn't lose
     * its app binding.
     */
    public void register(String podId, String region, String baseUrl, String applicationId) {
        // WORKER-HYGIENE Phase D — preserve DRAINING_FOR_RECYCLE through
        // re-register. A pod that's mid-recycle is still alive enough to
        // re-register (heartbeats keep firing until the container is
        // stopped); we must not flip it back to IDLE or a concurrent
        // claim could grab a pod the recycler is about to kill.
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"pod\" "
                + "(\"podId\",\"region\",\"baseUrl\",\"state\",\"lastHeartbeat\",\"applicationId\") "
                + "VALUES (?,?,?,'IDLE', now(), ?) "
                + "ON CONFLICT (\"podId\") DO UPDATE SET "
                + "  \"region\"=EXCLUDED.\"region\", "
                + "  \"baseUrl\"=EXCLUDED.\"baseUrl\", "
                + "  \"state\"=CASE WHEN \"globalOrchestrator\".\"pod\".\"state\"='DRAINING_FOR_RECYCLE' "
                + "                 THEN 'DRAINING_FOR_RECYCLE' ELSE 'IDLE' END, "
                + "  \"lastHeartbeat\"=now(), "
                + "  \"applicationId\"=COALESCE(EXCLUDED.\"applicationId\", \"globalOrchestrator\".\"pod\".\"applicationId\")",
                podId, region, baseUrl, applicationId);
    }

    /**
     * Refreshes a pod's heartbeat. Returns the rowcount so the controller
     * can 404 an unknown podId. A stale (LOST) pod heart-beating gets
     * flipped back to IDLE — the sweeper window only matters for
     * "everyone forgot about you" cases.
     */
    public int heartbeat(String podId) {
        // WORKER-HYGIENE Phase D — DRAINING_FOR_RECYCLE is preserved
        // through the heartbeat (pod is mid-recycle; flipping back to
        // IDLE would re-expose it to claim).
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"lastHeartbeat\"=now(), "
                + "    \"state\"=CASE WHEN \"state\"='DRAINING_FOR_RECYCLE' "
                + "                   THEN 'DRAINING_FOR_RECYCLE' ELSE 'IDLE' END "
                + "WHERE \"podId\"=?",
                podId);
    }

    /**
     * Marks pods LOST whose lastHeartbeat is older than the cutoff.
     * Returns the number of pods flipped — useful as a metric.
     */
    public int markLostBefore(Instant cutoff) {
        // WORKER-HYGIENE Phase D — DRAINING_FOR_RECYCLE pods may go silent
        // while their container is being stopped. Don't relabel them LOST;
        // the recycle path is the authoritative driver for these rows.
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"state\"='LOST' "
                + "WHERE \"state\" NOT IN ('LOST', 'DRAINING_FOR_RECYCLE') "
                + "  AND \"lastHeartbeat\" < ?",
                Timestamp.from(cutoff));
    }

    /**
     * Claims up to {@code limit} IDLE pods that don't have an active
     * fleet-member reservation. Wraps the SELECT in
     * {@code FOR UPDATE OF p SKIP LOCKED} so concurrent run-launches
     * don't double-claim. <strong>Must run inside a transaction</strong>
     * — the calling service annotates with {@code @Transactional}.
     */
    public List<Pod> claimIdle(int limit) {
        return jdbc.query(
                "SELECT p.\"podId\", p.\"region\", p.\"baseUrl\", "
                + "       p.\"state\", p.\"lastHeartbeat\", p.\"registeredAt\", "
                + "       p.\"applicationId\", p.\"runsServed\", "
                + "       p.\"imageDigest\", p.\"provisionedAt\" "
                + "FROM \"globalOrchestrator\".\"pod\" p "
                + "WHERE p.\"state\" = 'IDLE' "
                + "  AND NOT EXISTS ("
                + "    SELECT 1 FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "    WHERE m.\"workerId\" = p.\"podId\" "
                + "      AND m.\"state\" IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')) "
                + "ORDER BY p.\"lastHeartbeat\" DESC "
                + "LIMIT ? "
                + "FOR UPDATE OF p SKIP LOCKED",
                ROW_MAPPER, limit);
    }

    /**
     * Phase 4 of the capacity rework — per-application + per-region claim.
     * Picks IDLE pods bound to {@code applicationId} in {@code region},
     * skipping any already held by a non-terminal {@code runFleetMember}.
     *
     * <p>As of Phase 6b this is the <em>only</em> per-region claim path —
     * the legacy {@code claimIdleByRegion} null-app fallback was removed
     * once the static {@code orchestrator-1} / {@code -2} pods were gone
     * and {@code pod.applicationId} became NOT NULL.
     *
     * <p>Same {@code FOR UPDATE … SKIP LOCKED} lock semantics as
     * {@link #claimIdle(int)} — must run inside a
     * transaction; concurrent same-app run launches split the available
     * pods rather than double-claiming.
     *
     * <p>The application-scoped capacity ceiling
     * ({@code applicationCapacity.maxAvailable}) is enforced upstream in
     * {@code RunService} BEFORE this query runs. Returning fewer rows
     * than {@code limit} means the cap-check passed (Max accommodates
     * the request) but Ready-pod count was short — the operator needs
     * to spin more pods (or, if a parallel run grabbed them in the
     * lock window, retry).
     */
    public List<Pod> claimIdleByRegionAndApp(String region, String applicationId, int limit) {
        return jdbc.query(
                "SELECT p.\"podId\", p.\"region\", p.\"baseUrl\", "
                + "       p.\"state\", p.\"lastHeartbeat\", p.\"registeredAt\", "
                + "       p.\"applicationId\", p.\"runsServed\", "
                + "       p.\"imageDigest\", p.\"provisionedAt\" "
                + "FROM \"globalOrchestrator\".\"pod\" p "
                + "WHERE p.\"state\" = 'IDLE' "
                + "  AND p.\"region\" = ? "
                + "  AND p.\"applicationId\" = ? "
                + "  AND NOT EXISTS ("
                + "    SELECT 1 FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "    WHERE m.\"workerId\" = p.\"podId\" "
                + "      AND m.\"state\" IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')) "
                + "ORDER BY p.\"lastHeartbeat\" DESC "
                + "LIMIT ? "
                + "FOR UPDATE OF p SKIP LOCKED",
                ROW_MAPPER, region, applicationId, limit);
    }

    /**
     * Track F: per-region capacity rollup. {@code idlePods} excludes
     * pods already claimed by an active {@code runFleetMember} so the
     * UI sees true availability, not raw registration count.
     */
    public List<RegionCapacity> regionCapacities() {
        return jdbc.query(
                "SELECT p.\"region\", "
                + "       COUNT(*) AS \"totalPods\", "
                + "       COUNT(*) FILTER ("
                + "         WHERE p.\"state\" = 'IDLE' "
                + "           AND NOT EXISTS ("
                + "             SELECT 1 FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "             WHERE m.\"workerId\" = p.\"podId\" "
                + "               AND m.\"state\" IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING'))"
                + "       ) AS \"idlePods\", "
                + "       COUNT(*) FILTER (WHERE p.\"state\" = 'LOST') AS \"lostPods\" "
                + "FROM \"globalOrchestrator\".\"pod\" p "
                + "GROUP BY p.\"region\" "
                + "ORDER BY p.\"region\"",
                (rs, n) -> new RegionCapacity(
                        rs.getString("region"),
                        rs.getLong("totalPods"),
                        rs.getLong("idlePods"),
                        rs.getLong("lostPods")));
    }

    /**
     * Distinct regions that have ever had a pod registered. Used to
     * 400-UNKNOWN_REGION a {@code fleetAllocation} entry naming a region
     * the registry has never seen — distinguishing "you typo'd a region"
     * from "all pods in that region are LOST."
     */
    public List<String> findKnownRegions() {
        return jdbc.queryForList(
                "SELECT DISTINCT \"region\" FROM \"globalOrchestrator\".\"pod\" "
                + "ORDER BY \"region\"",
                String.class);
    }

    /**
     * Phase 2 of the capacity rework: returns the {@code podId}s of every
     * pod row currently bound to {@code (applicationId, region)}. Used by
     * {@link com.perf.globalorchestrator.provision.PodNameAllocator} to
     * pick the lowest-free integer suffix when allocating a new pod name.
     *
     * <p>Empty list when the app has no pods in this region yet.
     */
    public List<String> findPodIdsByApplicationAndRegion(String applicationId, String region) {
        return jdbc.queryForList(
                "SELECT \"podId\" FROM \"globalOrchestrator\".\"pod\" "
                + "WHERE \"applicationId\" = ? AND \"region\" = ? "
                + "ORDER BY \"podId\"",
                String.class, applicationId, region);
    }

    /**
     * Phase 2: returns every pod row bound to {@code (applicationId, region)}
     * — full record, not just the ID. Used by {@code PodReconciler} to
     * cross-check registry rows against actual containers seen by
     * {@link com.perf.globalorchestrator.provision.PodProvisioner}.
     */
    public List<Pod> findByApplicationAndRegion(String applicationId, String region) {
        return jdbc.query(
                "SELECT \"podId\", \"region\", \"baseUrl\", \"state\", "
                + "       \"lastHeartbeat\", \"registeredAt\", \"applicationId\", "
                + "       \"runsServed\", \"imageDigest\", \"provisionedAt\" "
                + "FROM \"globalOrchestrator\".\"pod\" "
                + "WHERE \"applicationId\" = ? AND \"region\" = ? "
                + "ORDER BY \"podId\"",
                ROW_MAPPER, applicationId, region);
    }

    /**
     * Phase 2: counts every pod row bound to {@code (applicationId, region)}.
     * Used by capacity-enforcement checks when spinning up a new pod —
     * count(rows) + 1 must be ≤ {@code applicationCapacity.maxAvailable}.
     */
    public int countByApplicationAndRegion(String applicationId, String region) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*)::int FROM \"globalOrchestrator\".\"pod\" "
                + "WHERE \"applicationId\" = ? AND \"region\" = ?",
                Integer.class, applicationId, region);
        return n == null ? 0 : n;
    }

    /**
     * Phase 2: deletes a pod row by ID. Idempotent — drain calls this after
     * the container has been stopped + removed by the provisioner. Returns
     * the rowcount so the caller can distinguish "drained" from "wasn't
     * registered to begin with."
     */
    public int deleteByPodId(String podId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\" = ?",
                podId);
    }

    /**
     * HARD-DELETE / purge Phase 2 — deletes every pod row bound to
     * {@code applicationId}. Called by the application purge BEFORE the
     * application row is removed: {@code pod.applicationId} is
     * {@code ON DELETE RESTRICT} (V10), so the app row can't be dropped while
     * its pods linger. A hidden app has no active runs (the hide guard ensures
     * it), so its pods are idle registry rows; this clears them. Idempotent —
     * returns the rowcount (0 when the app had no pods). Container teardown is a
     * separate concern (the provisioner / operator); this removes the registry
     * rows only.
     */
    public int deleteByApplicationId(String applicationId) {
        return jdbc.update(
                "DELETE FROM \"globalOrchestrator\".\"pod\" WHERE \"applicationId\" = ?",
                applicationId);
    }

    /**
     * Phase 3: returns the active run currently using {@code podId}, or
     * empty if the pod is idle. Drives the drain-block 409 response —
     * the {@code DELETE /capacity/{region}/pods/{podName}} endpoint
     * refuses to drain a pod held by an in-flight run, returning the
     * blocker so the UI can show "cannot drain — running test {runId}".
     *
     * <p>"Active" requires BOTH that the parent run is non-terminal
     * ({@code PREPARING / STARTING / RUNNING / DRAINING}) AND that this
     * worker's member row is itself non-terminal. Anchoring on the run's
     * state matches the operator's mental model of "is this still going";
     * the member-state guard is what lets a drained-away worker (its member
     * ABORTED/DRAINED while the run keeps running with other workers) read
     * as READY rather than IN_USE, and is what stops a re-spun same-name pod
     * from re-binding to a zombie run after a stale-drain released its
     * member (see {@code RunRepository.abortActiveMembersForWorker}). The
     * inverse race the old comment worried about — run terminal while a
     * member lingers RUNNING — is still handled: a terminal run is excluded
     * by the run-state guard regardless of member state.
     */
    public java.util.Optional<ActiveRunBinding> findActiveRunBindingFor(String podId) {
        List<ActiveRunBinding> rows = jdbc.query(
                "SELECT r.\"runId\", r.\"originRegion\", r.\"state\", r.\"startedAt\", "
                + "       r.\"initiatedBy\" "
                + "FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "JOIN \"globalOrchestrator\".\"run\" r ON m.\"runId\" = r.\"runId\" "
                + "WHERE m.\"workerId\" = ? "
                + "  AND r.\"state\" NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "  AND m.\"state\" NOT IN ('COMPLETED','FAILED','ABORTED','DRAINED') "
                + "ORDER BY r.\"createdAt\" DESC "
                + "LIMIT 1",
                (rs, n) -> new ActiveRunBinding(
                        rs.getString("runId"),
                        rs.getString("originRegion"),
                        rs.getString("state"),
                        instant(rs, "startedAt"),
                        rs.getString("initiatedBy")),
                podId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /**
     * True when {@code workerId} is a fleet member of any run that is not yet
     * terminal — <b>regardless of the member's own state</b>. Distinct from
     * {@link #findActiveRunBindingFor} (which also requires the member to be
     * non-terminal): the recycler uses this broader check to hold ALL recycling
     * (drain or replace) until the whole run is globally terminal, so a fan-out
     * worker that finishes (or fails) its slice early keeps its container +
     * logs until run end instead of being torn down mid-run (which surfaces the
     * member as "unreachable"/FAILED and destroys its forensics).
     */
    public boolean isWorkerBoundToNonTerminalRun(String workerId) {
        List<Integer> rows = jdbc.query(
                "SELECT 1 "
                + "FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "JOIN \"globalOrchestrator\".\"run\" r ON m.\"runId\" = r.\"runId\" "
                + "WHERE m.\"workerId\" = ? "
                + "  AND r.\"state\" NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "LIMIT 1",
                (rs, n) -> 1,
                workerId);
        return !rows.isEmpty();
    }

    /** Lightweight view of an active run, returned by {@link #findActiveRunBindingFor}. */
    public record ActiveRunBinding(
            String runId,
            String originRegion,
            String state,
            Instant startedAt,
            String initiatedBy) {}

    public List<Pod> findAll() {
        return jdbc.query(
                "SELECT \"podId\", \"region\", \"baseUrl\", \"state\", "
                + "       \"lastHeartbeat\", \"registeredAt\", \"applicationId\", "
                + "       \"runsServed\", \"imageDigest\", \"provisionedAt\" "
                + "FROM \"globalOrchestrator\".\"pod\" "
                + "ORDER BY \"lastHeartbeat\" DESC",
                ROW_MAPPER);
    }

    /**
     * WORKER-HYGIENE Phase B — bumps {@code runsServed} for one pod inside
     * the caller's transaction. Returns the rowcount so the caller can
     * detect a vanished pod (race with drain) and fail the run-claim if
     * the bump would have been against a deleted row.
     *
     * <p>This INSERT-time bump is the load-bearing way Phase D's reconciler
     * knows "this pod has done N runs" — counting historical
     * {@code runFleetMember} rows works in theory but rots fast once those
     * rows get archived. The counter on the pod row stays accurate for as
     * long as the pod row lives.
     */
    public int incrementRunsServed(String podId) {
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"runsServed\" = \"runsServed\" + 1 "
                + "WHERE \"podId\" = ?",
                podId);
    }

    /**
     * WORKER-HYGIENE Phase B — records the image digest + provisionedAt
     * captured by the provisioner at container-create time. Idempotent on
     * {@code podId}; called after {@link #register} once the container is
     * actually running and the digest is known. Leaves the existing
     * placeholder row's other fields untouched.
     *
     * <p>The reconciler's adoption path calls this too — when it sees a
     * managed container with no registry row, it inserts via
     * {@link #register} and then back-fills the digest via this method.
     */
    /**
     * WORKER-HYGIENE Phase D — flips a pod from IDLE → DRAINING_FOR_RECYCLE.
     * Guarded on the current state being IDLE so a concurrent
     * {@code claimIdleByRegionAndApp} can't race us: if the claim has
     * already locked the row, it observes IDLE and grabs it; our UPDATE
     * then no-ops (zero rowcount). Returns the rowcount so the caller
     * skips the recycle when the pod slipped into an active claim.
     *
     * <p>Conversely, if WE update first and the claim runs after, it sees
     * DRAINING_FOR_RECYCLE and skips this row (claim's WHERE clause is
     * {@code state = 'IDLE'}).
     */
    public int markDrainingForRecycle(String podId) {
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"state\" = 'DRAINING_FOR_RECYCLE' "
                + "WHERE \"podId\" = ? AND \"state\" = 'IDLE'",
                podId);
    }

    public int recordProvisionMetadata(String podId, String imageDigest, Instant provisionedAt) {
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"imageDigest\" = COALESCE(?, \"imageDigest\"), "
                + "    \"provisionedAt\" = COALESCE(?, \"provisionedAt\") "
                + "WHERE \"podId\" = ?",
                imageDigest,
                provisionedAt == null ? null : Timestamp.from(provisionedAt),
                podId);
    }

    private static final RowMapper<Pod> ROW_MAPPER = (rs, n) -> new Pod(
            rs.getString("podId"),
            rs.getString("region"),
            rs.getString("baseUrl"),
            PodState.valueOf(rs.getString("state")),
            instant(rs, "lastHeartbeat"),
            instant(rs, "registeredAt"),
            rs.getString("applicationId"),
            rs.getLong("runsServed"),
            rs.getString("imageDigest"),
            instant(rs, "provisionedAt"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
