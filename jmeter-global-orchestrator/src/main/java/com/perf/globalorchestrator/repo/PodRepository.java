package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.PodState;
import com.perf.globalorchestrator.domain.RegionCapacity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * CRUD for {@code ORCH_POD} — the worker registry.
 * Uses the run-state datasource (RW), same as {@link RunRepository}. The two
 * claim paths go through {@code ORCH_CLAIMS}, which locks
 * one row at a time with {@code FOR UPDATE SKIP LOCKED}.
 */
@Repository
public class PodRepository {

    private final JdbcTemplate jdbc;

    public PodRepository(@Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent register-or-refresh — a MERGE, so a re-registering pod
     * (post-restart) gets its identity refreshed
     * (state ← IDLE, baseUrl ← whatever it sends, lastHeartbeat ← now).
     *
     * <p>{@code groupId} is the pool the worker belongs to (GROUP-CAPACITY);
     * the column is NOT NULL, so the caller has already refused a blank one.
     * On re-register, NVL preserves a previously-set groupId
     * so a pod that goes through a transient identity wobble doesn't lose
     * its app binding.
     *
     * <p>STATIC-FLEET Phase 3 — a self-registering worker never changes a
     * DECLARED row's identity. If the operator declared this pod
     * ({@code source='STATIC'}) its {@code region} and {@code baseUrl} are
     * the operator's statement of where the worker is, and they win: the
     * worker derives its own {@code baseUrl} from its hostname, which in a
     * private cloud is frequently not the address the control plane can
     * reach it at. Letting a self-registration overwrite the declared
     * address would break fan-out for a worker that had been declared
     * correctly. {@code source} itself is never touched on conflict, so a
     * declared worker that also self-registers stays declared — declaring
     * and self-registering converge on one row instead of fighting.
     */
    public void register(String podId, String region, String baseUrl, String groupId) {
        // Preserve DRAINING_FOR_RECYCLE through
        // re-register. A pod that's mid-recycle is still alive enough to
        // re-register (heartbeats keep firing until the container is
        // stopped); we must not flip it back to IDLE or a concurrent
        // claim could grab a pod the recycler is about to kill.
        jdbc.update(
                "MERGE INTO ORCH_POD t "
                + "USING (SELECT ? AS POD_ID, ? AS REGION, ? AS BASE_URL, ? AS GROUP_ID FROM dual) s "
                + "ON (t.POD_ID = s.POD_ID) "
                + "WHEN MATCHED THEN UPDATE SET "
                + "  t.REGION=CASE WHEN t.SOURCE='STATIC' THEN t.REGION ELSE s.REGION END, "
                + "  t.BASE_URL=CASE WHEN t.SOURCE='STATIC' THEN t.BASE_URL ELSE s.BASE_URL END, "
                + "  t.STATE=CASE WHEN t.STATE='DRAINING_FOR_RECYCLE' THEN 'DRAINING_FOR_RECYCLE' ELSE 'IDLE' END, "
                + "  t.LAST_HEARTBEAT=SYSTIMESTAMP, "
                + "  t.GROUP_ID=NVL(s.GROUP_ID, t.GROUP_ID) "
                + "WHEN NOT MATCHED THEN INSERT "
                + "(POD_ID,REGION,BASE_URL,STATE,LAST_HEARTBEAT,GROUP_ID) "
                + "VALUES (s.POD_ID, s.REGION, s.BASE_URL, 'IDLE', SYSTIMESTAMP, s.GROUP_ID)",
                podId, region, baseUrl, OracleBind.typed(Types.VARCHAR, groupId));
    }

    /**
     * Declares an operator-deployed worker.
     * Idempotent on {@code podId}: re-declaring updates the address and
     * re-binds nothing else, so an operator can correct a typo'd URL by
     * declaring again.
     *
     * <p>Unlike {@link #register}, this is the operator speaking, so the
     * supplied {@code region} / {@code baseUrl} DO overwrite. {@code state}
     * is left alone on conflict — a declared worker the sweeper has marked
     * {@code LOST} must not be flipped IDLE by a re-declare, or a claim
     * could grab a worker that is still unreachable. {@code StaticPodProbe}
     * is the only thing that resurrects it, and only on real evidence.
     *
     * <p>{@code lastHeartbeat} is seeded to now on INSERT so a
     * freshly declared worker is claimable immediately rather than being
     * swept LOST before the first probe tick.
     */
    public void declareStatic(String podId, String region, String baseUrl, String groupId) {
        jdbc.update(
                "MERGE INTO ORCH_POD t "
                + "USING (SELECT ? AS POD_ID, ? AS REGION, ? AS BASE_URL, ? AS GROUP_ID FROM dual) s "
                + "ON (t.POD_ID = s.POD_ID) "
                + "WHEN MATCHED THEN UPDATE SET "
                + "  t.REGION=s.REGION, t.BASE_URL=s.BASE_URL, "
                + "  t.GROUP_ID=s.GROUP_ID, t.SOURCE='STATIC' "
                + "WHEN NOT MATCHED THEN INSERT "
                + "(POD_ID,REGION,BASE_URL,STATE,LAST_HEARTBEAT,GROUP_ID,SOURCE) "
                + "VALUES (s.POD_ID, s.REGION, s.BASE_URL, 'IDLE', SYSTIMESTAMP, s.GROUP_ID, 'STATIC')",
                podId, region, baseUrl, OracleBind.typed(Types.VARCHAR, groupId));
    }

    /**
     * Every row with the given source. Drives
     * {@code StaticPodProbe}, which only probes what the operator declared:
     * a DYNAMIC row left over from before a mode flip belongs to the
     * (now absent) provisioner, not to the probe.
     */
    public List<Pod> findBySource(PodSource source) {
        return jdbc.query(
                "SELECT POD_ID, REGION, BASE_URL, STATE, "
                + "LAST_HEARTBEAT, REGISTERED_AT, GROUP_ID, "
                + "RUNS_SERVED, IMAGE_DIGEST, PROVISIONED_AT, SOURCE "
                + "FROM ORCH_POD WHERE SOURCE = ? "
                + "ORDER BY POD_ID",
                ROW_MAPPER, source.name());
    }

    /**
     * Refreshes a pod's heartbeat. Returns the rowcount so the controller
     * can 404 an unknown podId. A stale (LOST) pod heart-beating gets
     * flipped back to IDLE — the sweeper window only matters for
     * "everyone forgot about you" cases.
     */
    /**
     * Registers a pod the control plane has just asked a region to create.
     * It starts LOST — unclaimable — and becomes IDLE through {@link #heartbeat}
     * once the kubelet reports it ready, so a run can never be fanned out to a
     * worker whose HTTP is not up yet.
     */
    public void registerStarting(String podId, String region, String baseUrl, String groupId) {
        // A plain INSERT on purpose: the primary key is what makes concurrent
        // spins (parallel provisioning, two operators) get distinct names —
        // a loser sees DuplicateKeyException and allocates again.
        jdbc.update(
                "INSERT INTO ORCH_POD "
                + "(POD_ID,REGION,BASE_URL,STATE,LAST_HEARTBEAT,GROUP_ID) "
                + "VALUES (?,?,?,'LOST', SYSTIMESTAMP, ?)",
                podId, region, baseUrl, groupId);
    }

    /** Marks one pod LOST; returns 1 only on the transition, so callers act on it exactly once. */
    public int markLost(String podId) {
        return jdbc.update(
                "UPDATE ORCH_POD SET STATE='LOST' "
                + "WHERE POD_ID=? AND STATE NOT IN ('LOST', 'DRAINING_FOR_RECYCLE')",
                podId);
    }

    public int heartbeat(String podId) {
        // DRAINING_FOR_RECYCLE is preserved
        // through the heartbeat (pod is mid-recycle; flipping back to
        // IDLE would re-expose it to claim).
        return jdbc.update(
                "UPDATE ORCH_POD "
                + "SET LAST_HEARTBEAT=SYSTIMESTAMP, "
                + "    STATE=CASE WHEN STATE='DRAINING_FOR_RECYCLE' "
                + "                   THEN 'DRAINING_FOR_RECYCLE' ELSE 'IDLE' END "
                + "WHERE POD_ID=?",
                podId);
    }

    /**
     * Marks pods LOST whose lastHeartbeat is older than the cutoff.
     * Returns the number of pods flipped — useful as a metric.
     */
    public int markLostBefore(Instant cutoff) {
        return markLostBefore(cutoff, List.of());
    }

    /**
     * Heartbeat-age LOST, skipping {@code excludedRegions}: a routed region's
     * dynamic workers are judged by the kubelet ({@code WorkerLivenessProbe}),
     * never by silence — a regional that is down does not mean its workers are.
     */
    public int markLostBefore(Instant cutoff, List<String> excludedRegions) {
        // DRAINING_FOR_RECYCLE pods may go silent
        // while their container is being stopped. Don't relabel them LOST;
        // the recycle path is the authoritative driver for these rows.
        // The cutoff becomes an AGE compared against SYSTIMESTAMP — heartbeats
        // are written with the database's clock, so the database's clock must
        // judge them; hub/DB skew ≥ lostAfterMs would otherwise mass-LOST a
        // healthy fleet each sweep.
        double ageSeconds = Math.max(0L, java.time.Duration.between(cutoff, Instant.now()).toMillis()) / 1000.0;
        if (excludedRegions == null || excludedRegions.isEmpty()) {
            return jdbc.update(
                    "UPDATE ORCH_POD "
                    + "SET STATE='LOST' "
                    + "WHERE STATE NOT IN ('LOST', 'DRAINING_FOR_RECYCLE') "
                    + "  AND LAST_HEARTBEAT < SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND')",
                    ageSeconds);
        }
        Object[] args = new Object[excludedRegions.size() + 1];
        args[0] = ageSeconds;
        for (int i = 0; i < excludedRegions.size(); i++) args[i + 1] = excludedRegions.get(i);
        return jdbc.update(
                "UPDATE ORCH_POD "
                + "SET STATE='LOST' "
                + "WHERE STATE NOT IN ('LOST', 'DRAINING_FOR_RECYCLE') "
                + "  AND LAST_HEARTBEAT < SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND') "
                + "  AND NOT (SOURCE='DYNAMIC' AND REGION IN (" + MetricsPurgeRepository.marks(excludedRegions) + "))",
                args);
    }

    /**
     * Claims up to {@code limit} IDLE pods that don't have an active
     * fleet-member reservation, freshest heartbeat first. Each row is locked
     * {@code FOR UPDATE SKIP LOCKED} by the claims package so concurrent
     * run-launches don't double-claim. <strong>Must run inside a
     * transaction</strong> — the calling service annotates with
     * {@code @Transactional}; the locks are the reservation until the
     * {@code runFleetMember} rows are committed.
     */
    public List<Pod> claimIdle(int limit) {
        return claim(null, null, limit);
    }

    /**
     * Per-application + per-region claim: IDLE pods bound to
     * {@code groupId} in {@code region}, skipping any already held by a
     * non-terminal {@code runFleetMember}. Same lock semantics as
     * {@link #claimIdle(int)} — concurrent same-app launches split the
     * available pods rather than double-claiming.
     *
     * <p>The application-scoped capacity ceiling
     * ({@code ORCH_GROUP_CAPACITY.MAX_AVAILABLE}) is enforced upstream in
     * {@code RunService} BEFORE this runs. Returning fewer rows than
     * {@code limit} means the cap-check passed but the ready-pod count was
     * short — the operator needs to spin more pods (or, if a parallel run
     * grabbed them in the lock window, retry).
     */
    public List<Pod> claimIdleByGroup(String region, String groupId, int limit) {
        return claim(region, groupId, limit);
    }

    private List<Pod> claim(String region, String groupId, int limit) {
        return OracleBind.refCursor(jdbc,
                "BEGIN ORCH_CLAIMS.CLAIM_IDLE_PODS(?, ?, ?, ?); END;",
                cs -> { cs.setString(1, region); cs.setString(2, groupId); cs.setInt(3, limit); },
                4, ROW_MAPPER);
    }

    /**
     * Pod counts for every (group, region) at once — what the Capacity list
     * shows, without a single call to a region's Kubernetes API.
     *
     * <p>One pass over {@code ORCH_POD}, which is bounded by the fleet (tens of
     * rows), so the aggregate is cheaper than the N round-trips it replaces
     * even before the substrate calls are counted. {@code IN_USE} is
     * "bound to a live fleet member of a live run", the same test
     * {@link #findActiveRunBindingFor} applies per pod for
     * {@code CapacityController.listPods} — expressed once as a semi-join
     * rather than once per row. <b>Both halves of that test are load-bearing.</b>
     * A member row can outlive its run in a non-terminal state (a launch that
     * fails after fan-out); without the run-state half such a row would pin its
     * pod as IN_USE for good, and the list would report a worker busy that the
     * drill-in — and the claim path — call free.
     */
    public List<GroupRegionPods> groupRegionPods() {
        return jdbc.query(
                "SELECT p.GROUP_ID, p.REGION, "
                + "       COUNT(*) AS TOTAL_PODS, "
                + "       SUM(CASE WHEN EXISTS ("
                + "                   SELECT 1 FROM ORCH_RUN_FLEET_MEMBER m "
                + "                   JOIN ORCH_RUN r ON m.RUN_ID = r.RUN_ID "
                + "                   WHERE m.WORKER_ID = p.POD_ID "
                + "                     AND m.STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING') "
                + "                     AND r.STATE NOT IN ('COMPLETED','FAILED','ABORTED')) "
                + "                THEN 1 ELSE 0 END) AS IN_USE_PODS, "
                + "       MAX(p.LAST_HEARTBEAT) AS LAST_ACTIVITY_AT "
                + "FROM ORCH_POD p "
                + "GROUP BY p.GROUP_ID, p.REGION "
                + "ORDER BY p.GROUP_ID, p.REGION",
                (rs, n) -> new GroupRegionPods(
                        rs.getString("GROUP_ID"),
                        rs.getString("REGION"),
                        rs.getLong("TOTAL_PODS"),
                        rs.getLong("IN_USE_PODS"),
                        OracleBind.instant(rs, "LAST_ACTIVITY_AT")));
    }

    /** Row of {@link #groupRegionPods()}. */
    public record GroupRegionPods(String groupId, String region, long provisioned,
                                  long inUse, java.time.Instant lastActivityAt) {}

    /**
     * Track F: per-region capacity rollup. {@code idlePods} excludes
     * pods already claimed by an active {@code runFleetMember} so the
     * UI sees true availability, not raw registration count.
     */
    public List<RegionCapacity> regionCapacities() {
        return jdbc.query(
                "SELECT p.REGION, "
                + "       COUNT(*) AS TOTAL_PODS, "
                + "       SUM(CASE WHEN p.STATE = 'IDLE' "
                + "                 AND NOT EXISTS ("
                + "                   SELECT 1 FROM ORCH_RUN_FLEET_MEMBER m "
                + "                   WHERE m.WORKER_ID = p.POD_ID "
                + "                     AND m.STATE IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')) "
                + "                THEN 1 ELSE 0 END) AS IDLE_PODS, "
                + "       SUM(CASE WHEN p.STATE = 'LOST' THEN 1 ELSE 0 END) AS LOST_PODS "
                + "FROM ORCH_POD p "
                + "GROUP BY p.REGION "
                + "ORDER BY p.REGION",
                (rs, n) -> new RegionCapacity(
                        rs.getString("REGION"),
                        rs.getLong("TOTAL_PODS"),
                        rs.getLong("IDLE_PODS"),
                        rs.getLong("LOST_PODS")));
    }

    /**
     * Distinct regions that have ever had a pod registered. Used to
     * 400-UNKNOWN_REGION a {@code fleetAllocation} entry naming a region
     * the registry has never seen — distinguishing "you typo'd a region"
     * from "all pods in that region are LOST."
     */
    public List<String> findKnownRegions() {
        return jdbc.queryForList(
                "SELECT DISTINCT REGION FROM ORCH_POD "
                + "ORDER BY REGION",
                String.class);
    }

    /**
     * Phase 2 of the capacity rework: returns the {@code podId}s of every
     * pod row currently bound to {@code (groupId, region)}. Used by
     * {@link com.perf.globalorchestrator.provision.PodNameAllocator} to
     * pick the lowest-free integer suffix when allocating a new pod name.
     *
     * <p>Empty list when the app has no pods in this region yet.
     */
    public List<String> findPodIdsByGroupAndRegion(String groupId, String region) {
        return jdbc.queryForList(
                "SELECT POD_ID FROM ORCH_POD "
                + "WHERE GROUP_ID = ? AND REGION = ? "
                + "ORDER BY POD_ID",
                String.class, groupId, region);
    }

    /**
     * Phase 2: returns every pod row bound to {@code (groupId, region)}
     * — full record, not just the ID. Used by {@code PodReconciler} to
     * cross-check registry rows against actual containers seen by
     * {@link com.perf.globalorchestrator.provision.PodProvisioner}.
     */
    public List<Pod> findByGroupAndRegion(String groupId, String region) {
        return jdbc.query(
                "SELECT POD_ID, REGION, BASE_URL, STATE, "
                + "       LAST_HEARTBEAT, REGISTERED_AT, GROUP_ID, "
                + "       RUNS_SERVED, IMAGE_DIGEST, PROVISIONED_AT, SOURCE "
                + "FROM ORCH_POD "
                + "WHERE GROUP_ID = ? AND REGION = ? "
                + "ORDER BY POD_ID",
                ROW_MAPPER, groupId, region);
    }

    /**
     * Single-row lookup by primary key. Added for STATIC-FLEET Phase 1:
     * {@code StaticPodProvisioner} answers {@code exists} / {@code isRunning}
     * / {@code baseUrlFor} from the registry, and those are called once per
     * pod while rendering a capacity page — a {@link #findAll()} scan per
     * call would make that quadratic.
     */
    public java.util.Optional<Pod> findByPodId(String podId) {
        if (podId == null || podId.isBlank()) {
            return java.util.Optional.empty();
        }
        List<Pod> rows = jdbc.query(
                "SELECT POD_ID, REGION, BASE_URL, STATE, "
                + "LAST_HEARTBEAT, REGISTERED_AT, GROUP_ID, "
                + "RUNS_SERVED, IMAGE_DIGEST, PROVISIONED_AT, SOURCE "
                + "FROM ORCH_POD WHERE POD_ID = ?",
                ROW_MAPPER, podId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /**
     * Phase 2: counts every pod row bound to {@code (groupId, region)}.
     * Used by capacity-enforcement checks when spinning up a new pod —
     * count(rows) + 1 must be ≤ {@code ORCH_GROUP_CAPACITY.MAX_AVAILABLE}.
     */
    public int countByGroupAndRegion(String groupId, String region) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ORCH_POD "
                + "WHERE GROUP_ID = ? AND REGION = ?",
                Integer.class, groupId, region);
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
                "DELETE FROM ORCH_POD WHERE POD_ID = ?",
                podId);
    }

    /**
     * HARD-DELETE / purge Phase 2 — deletes every pod row bound to
     * {@code groupId}. Called by the application purge BEFORE the
     * application row is removed: {@code ORCH_POD.GROUP_ID} is
     * a plain foreign key, so the app row can't be dropped while
     * its pods linger. A hidden app has no active runs (the hide guard ensures
     * it), so its pods are idle registry rows; this clears them. Idempotent —
     * returns the rowcount (0 when the app had no pods). Container teardown is a
     * separate concern (the provisioner / operator); this removes the registry
     * rows only.
     */
    public int deleteByGroupId(String groupId) {
        return jdbc.update(
                "DELETE FROM ORCH_POD WHERE GROUP_ID = ?",
                groupId);
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
                "SELECT r.RUN_ID, r.ORIGIN_REGION, r.STATE, r.STARTED_AT, "
                + "       r.INITIATED_BY "
                + "FROM ORCH_RUN_FLEET_MEMBER m "
                + "JOIN ORCH_RUN r ON m.RUN_ID = r.RUN_ID "
                + "WHERE m.WORKER_ID = ? "
                + "  AND r.STATE NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "  AND m.STATE NOT IN ('COMPLETED','FAILED','ABORTED','DRAINED') "
                + "ORDER BY r.CREATED_AT DESC "
                + "FETCH FIRST 1 ROWS ONLY",
                (rs, n) -> new ActiveRunBinding(
                        rs.getString("RUN_ID"),
                        rs.getString("ORIGIN_REGION"),
                        rs.getString("STATE"),
                        instant(rs, "STARTED_AT"),
                        rs.getString("INITIATED_BY")),
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
                + "FROM ORCH_RUN_FLEET_MEMBER m "
                + "JOIN ORCH_RUN r ON m.RUN_ID = r.RUN_ID "
                + "WHERE m.WORKER_ID = ? "
                + "  AND r.STATE NOT IN ('COMPLETED','FAILED','ABORTED') "
                + "FETCH FIRST 1 ROWS ONLY",
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
                "SELECT POD_ID, REGION, BASE_URL, STATE, "
                + "       LAST_HEARTBEAT, REGISTERED_AT, GROUP_ID, "
                + "       RUNS_SERVED, IMAGE_DIGEST, PROVISIONED_AT, SOURCE "
                + "FROM ORCH_POD "
                + "ORDER BY LAST_HEARTBEAT DESC",
                ROW_MAPPER);
    }

    /**
     * Bumps {@code runsServed} for one pod inside
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
                "UPDATE ORCH_POD "
                + "SET RUNS_SERVED = RUNS_SERVED + 1 "
                + "WHERE POD_ID = ?",
                podId);
    }

    /**
     * Records the image digest + provisionedAt
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
     * Flips a pod from IDLE → DRAINING_FOR_RECYCLE.
     * Guarded on the current state being IDLE so a concurrent
     * {@code claimIdleByGroup} can't race us: if the claim has
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
                "UPDATE ORCH_POD "
                + "SET STATE = 'DRAINING_FOR_RECYCLE' "
                + "WHERE POD_ID = ? AND STATE = 'IDLE'",
                podId);
    }

    public int recordProvisionMetadata(String podId, String imageDigest, Instant provisionedAt) {
        return jdbc.update(
                "UPDATE ORCH_POD "
                // CAST gives Oracle the type of a NULL bind; the non-null value
                // binds as an OffsetDateTime and keeps its offset. Binding it
                // with an explicit Types.TIMESTAMP instead drops the offset and
                // re-reads the wall-clock in the session zone (a 4 h shift in
                // the contract test) — never type a TIMESTAMP WITH TIME ZONE bind.
                + "SET IMAGE_DIGEST = COALESCE(?, IMAGE_DIGEST), "
                + "    PROVISIONED_AT = COALESCE(CAST(? AS TIMESTAMP WITH TIME ZONE), PROVISIONED_AT) "
                + "WHERE POD_ID = ?",
                OracleBind.typed(Types.VARCHAR, imageDigest),
                OracleBind.ts(provisionedAt),
                podId);
    }

    private static final RowMapper<Pod> ROW_MAPPER = (rs, n) -> new Pod(
            rs.getString("POD_ID"),
            rs.getString("REGION"),
            rs.getString("BASE_URL"),
            PodState.valueOf(rs.getString("STATE")),
            instant(rs, "LAST_HEARTBEAT"),
            instant(rs, "REGISTERED_AT"),
            rs.getString("GROUP_ID"),
            rs.getLong("RUNS_SERVED"),
            rs.getString("IMAGE_DIGEST"),
            instant(rs, "PROVISIONED_AT"),
            podSource(rs));

    /** The CHECK constraint guarantees a known value; null is defensive only. */
    private static PodSource podSource(ResultSet rs) throws SQLException {
        String raw = rs.getString("SOURCE");
        return raw == null ? PodSource.DYNAMIC : PodSource.valueOf(raw);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return OracleBind.instant(rs, col);
    }
}
