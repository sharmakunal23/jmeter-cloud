package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.repo.PodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the {@code pod} registry table and the provisioner's live workers in
 * sync, in two idempotent passes.
 *
 * <p>The <b>worker-first</b> pass lists everything this control plane manages
 * and, for each, either ensures it is running or — when no registry row exists —
 * adopts it by inserting one, which is what recovers a reset registry whose
 * workers survived. The <b>row-first</b> pass then deletes registry rows whose
 * worker the provisioner cannot see.
 *
 * <p>Runs once at boot on {@link ApplicationReadyEvent}, and on demand via
 * {@code POST /api/v1/admin/reconcilePods} — useful after a manual delete or an
 * interrupted spin-up. Errors are logged at WARN and the sweep continues to the
 * next pod; it never throws, because capacity drift beats a boot failure when
 * the substrate is briefly unreachable.
 *
 * <p><b>Not wired under {@code PROVISIONING_MODE=STATIC}</b>, and the bean's
 * absence is the point rather than an in-method guard: "the reconciler does not
 * exist in static mode" is a structural guarantee where "every entry point
 * remembers to check a flag" is only a promise. The row-first pass deletes any
 * row whose worker the provisioner cannot see, and with an operator-managed
 * fleet there is nothing to ask — so it would read the entire declared fleet as
 * orphaned and delete it on the next boot. {@code AdminController} injects it
 * optionally and answers {@code 409 PROVISIONING_DISABLED} when absent.
 */
@Component
@ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
public class PodReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(PodReconciler.class);

    private final PodRepository pods;
    private final PodProvisioner provisioner;

    private final com.perf.globalorchestrator.region.RegionRegistry regions;

    public PodReconciler(PodRepository pods, PodProvisioner provisioner,
                         com.perf.globalorchestrator.region.RegionRegistry regions) {
        this.regions = regions;
        this.pods = pods;
        this.provisioner = provisioner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        try {
            ReconcileSummary summary = reconcile();
            LOG.info("PodReconciler boot sweep done: adopted={}, started={}, orphansDeleted={}, errors={}",
                    summary.adopted.size(), summary.started.size(),
                    summary.orphansDeleted.size(), summary.errors.size());
        } catch (Exception e) {
            // Swallow — a cluster-API outage at boot shouldn't keep the global
            // from accepting traffic. The capacity REST endpoints (Phase 3)
            // will surface "container missing" lazily.
            LOG.warn("PodReconciler boot sweep failed (will retry on next admin trigger): {}", e.toString());
        }
    }

    /**
     * Runs both passes and returns a summary of what changed. Idempotent —
     * safe to call repeatedly; subsequent calls are no-ops if the daemon
     * and registry are already in sync.
     */
    public ReconcileSummary reconcile() {
        ReconcileSummary summary = new ReconcileSummary();

        // ── Pass 1 — container-first (adopt + start) ────────────────────
        // The provisioner's listFor takes a specific groupId, so we walk the
        // registry's distinct (group, region) pairs first (via
        // PodRepository.findAll) to find candidate pools, then query per pool.
        //
        // For the boot-time case where ALL rows are gone but containers
        // remain, we don't have any groupIds to ask about. The provisioner
        // exposes labels on the containers themselves — we read the label and
        // adopt without needing the registry.
        List<ProvisionedPod> allManaged;
        try {
            allManaged = listAllManaged();
        } catch (Exception e) {
            LOG.warn("PodReconciler: failed to list managed containers: {}", e.toString());
            summary.errors.add("list-managed: " + e.getMessage());
            return summary;
        }

        Map<String, ProvisionedPod> containerByPodId = new LinkedHashMap<>();
        for (ProvisionedPod c : allManaged) {
            if (c.podName() == null || c.groupId() == null) continue;
            containerByPodId.put(c.podName(), c);
        }

        for (ProvisionedPod c : containerByPodId.values()) {
            try {
                List<Pod> rows = pods.findByGroupAndRegion(c.groupId(), c.region());
                Pod existing = rows.stream()
                        .filter(p -> c.podName().equals(p.podId()))
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    // Adopt — insert the row using the pod's container as authority.
                    // baseUrl is reconstructed from podName + the configured local-orch port.
                    // LOST-until-ready, like a spun pod: the liveness probe admits it.
                    pods.registerStarting(c.podName(), c.region(), defaultBaseUrlFor(c.region(), c.podName()), c.groupId());
                    summary.adopted.add(c.podName());
                    LOG.info("PodReconciler adopted orphan container {} (group={}, region={})",
                            c.podName(), c.groupId(), c.region());
                }
                // Back-fill the recycle-tracking
                // columns from the container's daemon-truth. recordProvisionMetadata
                // uses COALESCE so it only writes columns that are currently
                // null on the row, leaving freshly-spun pods (which got
                // these columns set in CapacityController.spin) untouched.
                if (existing == null
                        || existing.imageDigest() == null
                        || existing.provisionedAt() == null) {
                    pods.recordProvisionMetadata(c.podName(), c.imageDigest(), c.startedAt());
                }
                if (!provisioner.isRunning(c.region(), c.podName())) {
                    provisioner.start(c.region(), c.podName());
                    summary.started.add(c.podName());
                    LOG.info("PodReconciler started stopped container {}", c.podName());
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: error handling container {}: {}", c.podName(), e.toString());
                summary.errors.add(c.podName() + ": " + e.getMessage());
            }
        }

        // ── Pass 2 — row-first (orphan deletion) ────────────────────────
        // Walk every pod row; if no container matches, the row is an orphan.
        for (Pod row : pods.findAll()) {
            if (row.groupId() == null) continue;
            if (containerByPodId.containsKey(row.podId())) continue;
            try {
                int n = pods.deleteByPodId(row.podId());
                if (n > 0) {
                    summary.orphansDeleted.add(row.podId());
                    LOG.info("PodReconciler deleted orphan row {} (container missing)", row.podId());
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: error deleting orphan row {}: {}", row.podId(), e.toString());
                summary.errors.add(row.podId() + ": " + e.getMessage());
            }
        }

        return summary;
    }

    /**
     * Every pod the provisioner manages: once per (group, region) pair the
     * registry knows, plus each routed region's whole Pod list — which is what
     * lets a wiped registry adopt its fleet back.
     */
    private List<ProvisionedPod> listAllManaged() {
        Map<String, ProvisionedPod> merged = new LinkedHashMap<>();
        java.util.Set<String> pairs = new java.util.HashSet<>();
        for (Pod row : pods.findAll()) {
            if (row.groupId() == null || !pairs.add(row.groupId() + "|" + row.region())) continue;
            try {
                for (ProvisionedPod c : provisioner.listFor(row.groupId(), row.region())) {
                    if (c.podName() != null) merged.put(c.podName(), c);
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: listFor({},{}) failed: {}",
                        row.groupId(), row.region(), e.toString());
            }
        }
        for (String region : regions.routedIds()) {
            try {
                for (ProvisionedPod c : provisioner.listAll(region)) {
                    if (c.podName() != null) merged.putIfAbsent(c.podName(), c);
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: listAll({}) failed: {}", region, e.toString());
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Default {@code baseUrl} used when adopting an orphan container — the
     * container will eventually re-register with its own POST /registerPod
     * carrying the authoritative URL, but until then this lets the global
     * route fan-out to the pod.
     */
    private String defaultBaseUrlFor(String region, String podName) {
        return provisioner.baseUrlFor(region, podName);
    }

    /** Summary returned by {@link #reconcile()} — surfaced via the admin endpoint. */
    public static class ReconcileSummary {
        public final List<String> adopted = new ArrayList<>();
        public final List<String> started = new ArrayList<>();
        public final List<String> orphansDeleted = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }
}
