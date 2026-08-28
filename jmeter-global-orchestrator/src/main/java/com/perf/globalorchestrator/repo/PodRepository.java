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
 * CRUD for {@code globalOrchestrator.pod} — the worker registry.
 * Uses the run-state datasource (RW), same as {@link RunRepository}. The two
 * claim paths go through {@code "globalOrchestrator"."claims"}, which locks
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
     * <p>{@code applicationId} (Phase 1 capacity rework) may be {@code null}
     * during the migration window — legacy static pods register without it.
     * On re-register, NVL preserves a previously-set applicationId
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
    public void register(String podId, String region, String baseUrl, String applicationId) {
        // Preserve DRAINING_FOR_RECYCLE through
        // re-register. A pod that's mid-recycle is still alive enough to
        // re-register (heartbeats keep firing until the container is
        // stopped); we must not flip it back to IDLE or a concurrent
        // claim could grab a pod the recycler is about to kill.
        jdbc.update(
                "MERGE INTO \"globalOrchestrator\".\"pod\" t "
                + "USING (SELECT ? AS \"podId\", ? AS \"region\", ? AS \"baseUrl\", ? AS \"applicationId\" FROM dual) s "
                + "ON (t.\"podId\" = s.\"podId\") "
                + "WHEN MATCHED THEN UPDATE SET "
                + "  t.\"region\"=CASE WHEN t.\"source\"='STATIC' THEN t.\"region\" ELSE s.\"region\" END, "
                + "  t.\"baseUrl\"=CASE WHEN t.\"source\"='STATIC' THEN t.\"baseUrl\" ELSE s.\"baseUrl\" END, "
                + "  t.\"state\"=CASE WHEN t.\"state\"='DRAINING_FOR_RECYCLE' THEN 'DRAINING_FOR_RECYCLE' ELSE 'IDLE' END, "
                + "  t.\"lastHeartbeat\"=SYSTIMESTAMP, "
                + "  t.\"applicationId\"=NVL(s.\"applicationId\", t.\"applicationId\") "
                + "WHEN NOT MATCHED THEN INSERT "
                + "(\"podId\",\"region\",\"baseUrl\",\"state\",\"lastHeartbeat\",\"applicationId\") "
                + "VALUES (s.\"podId\", s.\"region\", s.\"baseUrl\", 'IDLE', SYSTIMESTAMP, s.\"applicationId\")",
                podId, region, baseUrl, OracleBind.typed(Types.VARCHAR, applicationId));
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
    public void declareStatic(String podId, String region, String baseUrl, String applicationId) {
        jdbc.update(
                "MERGE INTO \"globalOrchestrator\".\"pod\" t "
                + "USING (SELECT ? AS \"podId\", ? AS \"region\", ? AS \"baseUrl\", ? AS \"applicationId\" FROM dual) s "
                + "ON (t.\"podId\" = s.\"podId\") "
                + "WHEN MATCHED THEN UPDATE SET "
                + "  t.\"region\"=s.\"region\", t.\"baseUrl\"=s.\"baseUrl\", "
                + "  t.\"applicationId\"=s.\"applicationId\", t.\"source\"='STATIC' "
                + "WHEN NOT MATCHED THEN INSERT "
                + "(\"podId\",\"region\",\"baseUrl\",\"state\",\"lastHeartbeat\",\"applicationId\",\"source\") "
                + "VALUES (s.\"podId\", s.\"region\", s.\"baseUrl\", 'IDLE', SYSTIMESTAMP, s.\"applicationId\", 'STATIC')",
                podId, region, baseUrl, OracleBind.typed(Types.VARCHAR, applicationId));
    }

    /**
     * Every row with the given source. Drives
     * {@code StaticPodProbe}, which only probes what the operator declared:
     * a DYNAMIC row left over from before a mode flip belongs to the
     * (now absent) provisioner, not to the probe.
     */
    public List<Pod> findBySource(PodSource source) {
        return jdbc.query(
                "SELECT \"podId\", \"region\", \"baseUrl\", \"state\", "
                + "\"lastHeartbeat\", \"registeredAt\", \"applicationId\", "
                + "\"runsServed\", \"imageDigest\", \"provisionedAt\", \"source\" "
                + "FROM \"globalOrchestrator\".\"pod\" WHERE \"source\" = ? "
                + "ORDER BY \"podId\"",
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
    public void registerStarting(String podId, String region, String baseUrl, String applicationId) {
        // A plain INSERT on purpose: the primary key is what makes concurrent
        // spins (parallel provisioning, two operators) get distinct names —
        // a loser sees DuplicateKeyException and allocates again.
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"pod\" "
                + "(\"podId\",\"region\",\"baseUrl\",\"state\",\"lastHeartbeat\",\"applicationId\") "
                + "VALUES (?,?,?,'LOST', SYSTIMESTAMP, ?)",
                podId, region, baseUrl, applicationId);
    }

    /** Marks one pod LOST; returns 1 only on the transition, so callers act on it exactly once. */
    public int markLost(String podId) {
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" SET \"state\"='LOST' "
                + "WHERE \"podId\"=? AND \"state\" NOT IN ('LOST', 'DRAINING_FOR_RECYCLE')",
                podId);
    }

    public int heartbeat(String podId) {
        // DRAINING_FOR_RECYCLE is preserved
        // through the heartbeat (pod is mid-recycle; flipping back to
        // IDLE would re-expose it to claim).
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"lastHeartbeat\"=SYSTIMESTAMP, "
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
        if (excludedRegions == null || excludedRegions.isEmpty()) {
            return jdbc.update(
                    "UPDATE \"globalOrchestrator\".\"pod\" "
                    + "SET \"state\"='LOST' "
                    + "WHERE \"state\" NOT IN ('LOST', 'DRAINING_FOR_RECYCLE') "
                    + "  AND \"lastHeartbeat\" < ?",
                    OracleBind.ts(cutoff));
        }
        Object[] args = new Object[excludedRegions.size() + 1];
        args[0] = OracleBind.ts(cutoff);
        for (int i = 0; i < excludedRegions.size(); i++) args[i + 1] = excludedRegions.get(i);
        return jdbc.update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"state\"='LOST' "
                + "WHERE \"state\" NOT IN ('LOST', 'DRAINING_FOR_RECYCLE') "
                + "  AND \"lastHeartbeat\" < ? "
                + "  AND NOT (\"source\"='DYNAMIC' AND \"region\" IN (" + MetricsPurgeRepository.marks(excludedRegions) + "))",
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
     * {@code applicationId} in {@code region}, skipping any already held by a
     * non-terminal {@code runFleetMember}. Same lock semantics as
     * {@link #claimIdle(int)} — concurrent same-app launches split the
     * available pods rather than double-claiming.
     *
     * <p>The application-scoped capacity ceiling
     * ({@code applicationCapacity.maxAvailable}) is enforced upstream in
     * {@code RunService} BEFORE this runs. Returning fewer rows than
     * {@code limit} means the cap-check passed but the ready-pod count was
     * short — the operator needs to spin more pods (or, if a parallel run
     * grabbed them in the lock window, retry).
     */
    public List<Pod> claimIdleByRegionAndApp(String region, String applicationId, int limit) {
        return claim(region, applicationId, limit);
    }

    private List<Pod> claim(String region, String applicationId, int limit) {
        return OracleBind.refCursor(jdbc,
                "BEGIN \"globalOrchestrator\".\"claims\".\"claimIdlePods\"(?, ?, ?, ?); END;",
                cs -> { cs.setString(1, region); cs.setString(2, applicationId); cs.setInt(3, limit); },
                4, ROW_MAPPER);
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
                + "       SUM(CASE WHEN p.\"state\" = 'IDLE' "
                + "                 AND NOT EXISTS ("
                + "                   SELECT 1 FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "                   WHERE m.\"workerId\" = p.\"podId\" "
                + "                     AND m.\"state\" IN ('PENDING','REQUESTED','ACCEPTED','RUNNING','DRAINING')) "
                + "                THEN 1 ELSE 0 END) AS \"idlePods\", "
                + "       SUM(CASE WHEN p.\"state\" = 'LOST' THEN 1 ELSE 0 END) AS \"lostPods\" "
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
                + "       \"runsServed\", \"imageDigest\", \"provisionedAt\", \"source\" "
                + "FROM \"globalOrchestrator\".\"pod\" "
                + "WHERE \"applicationId\" = ? AND \"region\" = ? "
                + "ORDER BY \"podId\"",
                ROW_MAPPER, applicationId, region);
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
                "SELECT \"podId\", \"region\", \"baseUrl\", \"state\", "
                + "\"lastHeartbeat\", \"registeredAt\", \"applicationId\", "
                + "\"runsServed\", \"imageDigest\", \"provisionedAt\", \"source\" "
                + "FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\" = ?",
                ROW_MAPPER, podId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /**
     * Phase 2: counts every pod row bound to {@code (applicationId, region)}.
     * Used by capacity-enforcement checks when spinning up a new pod —
     * count(rows) + 1 must be ≤ {@code applicationCapacity.maxAvailable}.
     */
    public int countByApplicationAndRegion(String applicationId, String region) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"pod\" "
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
     * a plain foreign key, so the app row can't be dropped while
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
                + "FETCH FIRST 1 ROWS ONLY",
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
                "SELECT \"podId\", \"region\", \"baseUrl\", \"state\", "
                + "       \"lastHeartbeat\", \"registeredAt\", \"applicationId\", "
                + "       \"runsServed\", \"imageDigest\", \"provisionedAt\", \"source\" "
                + "FROM \"globalOrchestrator\".\"pod\" "
                + "ORDER BY \"lastHeartbeat\" DESC",
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
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"runsServed\" = \"runsServed\" + 1 "
                + "WHERE \"podId\" = ?",
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
                // CAST gives Oracle the type of a NULL bind; the non-null value
                // binds as an OffsetDateTime and keeps its offset. Binding it
                // with an explicit Types.TIMESTAMP instead drops the offset and
                // re-reads the wall-clock in the session zone (a 4 h shift in
                // the contract test) — never type a TIMESTAMP WITH TIME ZONE bind.
                + "SET \"imageDigest\" = COALESCE(?, \"imageDigest\"), "
                + "    \"provisionedAt\" = COALESCE(CAST(? AS TIMESTAMP WITH TIME ZONE), \"provisionedAt\") "
                + "WHERE \"podId\" = ?",
                OracleBind.typed(Types.VARCHAR, imageDigest),
                OracleBind.ts(provisionedAt),
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
            instant(rs, "provisionedAt"),
            podSource(rs));

    /** The CHECK constraint guarantees a known value; null is defensive only. */
    private static PodSource podSource(ResultSet rs) throws SQLException {
        String raw = rs.getString("source");
        return raw == null ? PodSource.DYNAMIC : PodSource.valueOf(raw);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        return OracleBind.instant(rs, col);
    }
}
