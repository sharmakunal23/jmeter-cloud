package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerRef;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.RunEventPayloads;
import com.perf.globalorchestrator.domain.RunEventType;
import com.perf.globalorchestrator.provision.RecycleEvaluator.RecycleReason;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.service.RunAuditWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodic recycle sweep: walks every application-bound pod, evaluates
 * {@link RecycleEvaluator} against the application's policy, and
 * drain-and-replaces the ones whose threshold fires. It is separate from
 * {@code PodReconciler} (idempotent drift repair) and {@code PodSweeper}
 * (stale → LOST) because recycle is policy-driven and state-changing, and its
 * cadence should be tunable without touching theirs.
 *
 * <p><b>A pod whose most-recent run is not yet globally terminal is never
 * recycled.</b> The check is
 * {@link PodRepository#isWorkerBoundToNonTerminalRun(String)}, which holds until
 * the whole RUN ends rather than until this member does — keying on the member
 * let a fan-out worker that finished its slice early get drained mid-run,
 * destroying its forensics and surfacing the member as unreachable.
 *
 * <p><b>{@link PodRepository#markDrainingForRecycle(String)} is the cut-over
 * point</b> against a concurrent claim: a guarded UPDATE that only moves
 * IDLE → DRAINING_FOR_RECYCLE and returns rowcount 0 if the claim won. On zero
 * we skip and let the next tick re-evaluate. The replacement spin reuses
 * {@link PodSpinService#spin} but bypasses the cap check, since the row just
 * removed was holding that slot.
 *
 * <p>Not wired under {@code PROVISIONING_MODE=STATIC} — destroying a worker and
 * creating a replacement is not ours to do when the operator owns the fleet.
 * Injectors treat it as optional and refuse or SKIP when absent.
 */
@Component
@ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
public class PodRecycler {

    private static final Logger LOG = LoggerFactory.getLogger(PodRecycler.class);

    private final PodRepository pods;
    private final ApplicationGroupRepository groups;
    private final PodProvisioner provisioner;
    private final PodSpinService spinService;
    private final LocalOrchestratorClient localClient;
    private final RecycleEvaluator evaluator;
    /** AUDIT-TRAIL — to attribute a recycle to the pod's most-recent run. */
    private final RunRepository runs;
    private final RunAuditWriter audit;

    public PodRecycler(
            PodRepository pods,
            ApplicationGroupRepository groups,
            PodProvisioner provisioner,
            PodSpinService spinService,
            LocalOrchestratorClient localClient,
            RecycleEvaluator evaluator,
            RunRepository runs,
            RunAuditWriter audit) {
        this.pods = pods;
        this.groups = groups;
        this.provisioner = provisioner;
        this.spinService = spinService;
        this.localClient = localClient;
        this.evaluator = evaluator;
        this.runs = runs;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${globalOrchestrator.pod.recycleIntervalMs:60000}",
               initialDelayString = "${globalOrchestrator.pod.recycleInitialDelayMs:60000}")
    public void sweep() {
        try {
            doSweep();
        } catch (Exception e) {
            LOG.warn("PodRecycler sweep failed: {}", e.toString());
        }
    }

    /** Test seam — exposes the sweep body so an IT can drive it deterministically. */
    public RecycleSummary doSweep() {
        RecycleSummary summary = new RecycleSummary();

        // Cache group lookups by id — a fleet is a handful of pools with many pods each.
        Map<String, ApplicationGroup> groupCache = new HashMap<>();

        for (Pod pod : pods.findAll()) {
            if (pod.groupId() == null) {
                continue; // never auto-recycle a pod whose pool is unknown
            }
            if (pod.state() != com.perf.globalorchestrator.domain.PodState.IDLE) {
                // LOST → operator decision. DRAINING_FOR_RECYCLE → already
                // in flight (or stuck — operator can force via admin).
                continue;
            }
            // Hold ALL recycling until the pod's run is globally terminal.
            // A fan-out worker that finishes (or fails) its slice early must
            // keep its container + logs until the whole run ends — draining it
            // mid-run tears the pod (and its forensics) down and the member
            // surfaces as "unreachable"/FAILED. This is broader than the old
            // member-level active-claim check (which let a pod be recycled once
            // its OWN member went terminal even though the run was still live)
            // and also stops IMAGE_MISMATCH / MAX_* from churning a pod that's
            // still serving a run. markDrainingForRecycle (below) remains the
            // final race cut-over against a concurrent claim.
            if (pods.isWorkerBoundToNonTerminalRun(pod.podId())) {
                continue;
            }
            ApplicationGroup group = groupCache.computeIfAbsent(pod.groupId(),
                    id -> groups.findById(id).orElse(null));
            if (group == null) {
                continue;
            }
            RecycleReason reason = evaluator.decide(pod, group, provisioner.currentImageDigest(pod.region()));
            if (reason == RecycleReason.NONE) {
                continue;
            }
            try {
                if (recycle(pod, group, reason)) {
                    summary.recycled.put(pod.podId(), reason);
                } else {
                    summary.skipped.add(pod.podId());
                }
            } catch (RuntimeException e) {
                summary.errors.put(pod.podId(), e.toString());
                LOG.warn("Recycle of {} (reason={}) failed mid-flight: {}",
                        pod.podId(), reason, e.toString());
            }
        }
        if (!summary.recycled.isEmpty() || !summary.errors.isEmpty()) {
            LOG.info("PodRecycler sweep: recycled={}, skipped={}, errors={}",
                    summary.recycled, summary.skipped.size(), summary.errors.size());
        }
        recordRecycleEvents(summary.recycled);
        return summary;
    }

    /**
     * Append a WORKERS_RECYCLED event to each affected run's
     * timeline (best-effort). A recycle is per-pod and system-driven, so we
     * attribute each recycled pod to its most-recent run and aggregate one
     * event per run (system actor). Pods that never served a run are skipped —
     * there is nothing to attribute them to.
     */
    private void recordRecycleEvents(Map<String, RecycleReason> recycled) {
        if (recycled.isEmpty()) return;
        Map<String, List<String>> podsByRun = new LinkedHashMap<>();
        Map<String, Set<String>> reasonsByRun = new LinkedHashMap<>();
        recycled.forEach((podId, reason) ->
                runs.findMostRecentRunIdForWorker(podId).ifPresent(runId -> {
                    podsByRun.computeIfAbsent(runId, k -> new ArrayList<>()).add(podId);
                    reasonsByRun.computeIfAbsent(runId, k -> new LinkedHashSet<>()).add(reason.name());
                }));
        podsByRun.forEach((runId, podIds) -> {
            String reason = String.join(",", reasonsByRun.get(runId));
            try {
                audit.record(runId, RunEventType.WORKERS_RECYCLED, Actor.system("recycler"),
                        new RunEventPayloads.WorkersRecycled(podIds.size(), podIds, reason), "ok");
            } catch (RuntimeException e) {
                // Audit is best-effort — never let it fail the recycle sweep.
                LOG.warn("Failed to record WORKERS_RECYCLED for run {}: {}", runId, e.toString());
            }
        });
    }

    /**
     * Public entry point for scheduled DRAIN_REGION /
     * PROVISION_REGION jobs to recycle one pod via the same handshake the
     * hygiene sweep uses (markDrainingForRecycle race guard → drain RPC →
     * container removal → optional replacement spin governed by
     * {@link RecycleReason#replacesPod()}). Returns false when the race
     * guard lost (the pod is no longer IDLE); true on a successful drain.
     */
    public boolean drainOne(Pod pod, ApplicationGroup group, RecycleReason reason) {
        return recycle(pod, group, reason);
    }

    /**
     * Performs the drain-and-replace handshake for one pod. Returns false
     * when the markDrainingForRecycle guard lost a race with a concurrent
     * claim (zero rowcount); true otherwise.
     */
    private boolean recycle(Pod pod, ApplicationGroup group, RecycleReason reason) {
        // (1) Atomic cut-over IDLE → DRAINING_FOR_RECYCLE. Claim queries
        // can no longer see this pod after this commits.
        int marked = pods.markDrainingForRecycle(pod.podId());
        if (marked == 0) {
            LOG.info("Recycle of {} skipped — pod is no longer IDLE (concurrent claim?)",
                    pod.podId());
            return false;
        }
        LOG.info("Recycling pod {} (app={}, region={}, reason={}, runsServed={}, " +
                "imageDigest={})",
                pod.podId(), group.groupId(), pod.region(), reason,
                pod.runsServed(), shorten(pod.imageDigest()));

        // (2) Drain — idempotent against an idle pod (returns 404
        // NO_ACTIVE_RUN, which we treat as success). Best-effort: if the
        // pod is unreachable, we still stop+remove below.
        try {
            // PodRecycler operates outside a specific run — pass null so
            // the X-Run-Id header is omitted (the local-orch's MdcEnrichmentFilter
            // is null-safe and skips the header when missing).
            localClient.drainTest(null, WorkerRef.of(pod));
        } catch (RuntimeException e) {
            LOG.warn("Drain call to {} failed; proceeding to stopAndRemove: {}",
                    pod.baseUrl(), e.toString());
        }

        // TEMPORARY forensics (RELIABILITY Round 8) — capture the worker's
        // JMeter log tail into the (retained, rotated) global-orch log BEFORE
        // the container is removed, so a worker that ended FAILED can still be
        // diagnosed after its pod is gone. Remove once the early-member-terminal
        // root cause is found and fixed.
        captureDrainedLogs(pod);

        // (3) Stop + remove the container.
        try {
            provisioner.stopAndRemove(pod.region(), pod.podId());
        } catch (RuntimeException e) {
            // Continue — even a partially-removed container should let us
            // delete the registry row and spin a replacement with a
            // distinct name (the allocator picks the next-free integer).
            LOG.warn("stopAndRemove({}) errored: {}", pod.podId(), e.toString());
        }

        // (4) Delete the registry row so the allocator can re-issue the
        // freed name to the replacement (or just free the slot under
        // DRAIN_AFTER_RUN, which spins nothing back up).
        pods.deleteByPodId(pod.podId());

        // (5) Spin replacement under the same (groupId, region) —
        // unless the policy is DRAIN_AFTER_RUN, which deliberately leaves
        // the slot empty (cost-saving; the operator re-provisions on demand).
        if (!reason.replacesPod()) {
            LOG.info("Drained {} (group={}, region={}) — no replacement (DRAIN_AFTER_RUN)",
                    pod.podId(), group.groupId(), pod.region());
            return true;
        }
        PodSpinService.SpinResult result = spinService.spin(pod.groupId(), pod.region());
        LOG.info("Recycled {} → replacement {} (digest={})",
                pod.podId(), result.podName(), shorten(result.imageDigest()));
        return true;
    }

    /**
     * TEMPORARY forensics aid — best-effort capture of a soon-to-be-removed
     * worker's JMeter log tail into the global-orch log. The pod is still IDLE
     * (reachable) at recycle time, so the HTTP fetch normally succeeds; if it
     * doesn't, we log the status and move on (never blocks the recycle). Remove
     * with the call site once the early-member-terminal bug is fixed.
     */
    private void captureDrainedLogs(Pod pod) {
        try {
            LocalOrchestratorClient.LogsResult logs =
                    localClient.getLogs(WorkerRef.of(pod), 200, "jmeter");
            if (logs.statusCode() == 200 && logs.body() != null && !logs.body().isBlank()) {
                LOG.info("[DRAINED-FORENSICS] pod={} group={} region={} runsServed={} — JMeter log tail (≤200 lines):\n{}",
                        pod.podId(), pod.groupId(), pod.region(), pod.runsServed(), logs.body());
            } else {
                LOG.info("[DRAINED-FORENSICS] pod={} — no JMeter log tail captured (status={})",
                        pod.podId(), logs.statusCode());
            }
        } catch (RuntimeException e) {
            LOG.warn("[DRAINED-FORENSICS] pod={} — log capture failed: {}", pod.podId(), e.toString());
        }
    }

    private static String shorten(String digest) {
        if (digest == null) return "(null)";
        return digest.length() > 19 ? digest.substring(0, 19) + "…" : digest;
    }

    /** Snapshot of one sweep — what was recycled, what was skipped, what failed. */
    public static final class RecycleSummary {
        public final Map<String, RecycleReason> recycled = new java.util.LinkedHashMap<>();
        public final java.util.List<String> skipped = new java.util.ArrayList<>();
        public final Map<String, String> errors = new java.util.LinkedHashMap<>();
    }
}
