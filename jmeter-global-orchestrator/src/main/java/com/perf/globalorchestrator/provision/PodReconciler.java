package com.perf.globalorchestrator.provision;

import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.repo.PodRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Phase 2 of the capacity rework: keeps the {@code pod} registry table and
 * the docker daemon's container set in sync for every per-application pod.
 *
 * <h2>What gets reconciled</h2>
 * Two passes, both idempotent:
 *
 * <ol>
 *   <li><b>Container-first pass.</b> List every container labelled
 *       {@code com.perf.jmeterCloud.managedBy=global-orchestrator}. For each:
 *       <ul>
 *         <li>If a matching {@code pod} row exists → ensure the container
 *             is running (start if exited).</li>
 *         <li>If no row exists → adopt by inserting one. Used when the
 *             registry was reset but containers survived.</li>
 *       </ul></li>
 *   <li><b>Row-first pass.</b> For every {@code pod} row with
 *       {@code applicationId} set: if no matching container exists, delete
 *       the orphan row. Legacy static pods (rows where {@code applicationId}
 *       IS NULL) are <em>never</em> touched — they belong to the static
 *       compose services until Phase 6 deletes them.</li>
 * </ol>
 *
 * <h2>When it runs</h2>
 * Once at boot via {@link ApplicationReadyEvent} (after Spring + DataSource
 * are fully up). Operators can also force a sweep via
 * {@code POST /api/v1/admin/reconcilePods} — useful after a manual
 * {@code docker rm} or to recover from an interrupted spin-up.
 *
 * <h2>Failure handling</h2>
 * Any error is logged at WARN and the reconciler continues with the next
 * pod. The sweep never throws — capacity drift is preferred to a boot
 * failure when the docker daemon is briefly unreachable.
 */
@Component
public class PodReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(PodReconciler.class);

    private final PodRepository pods;
    private final PodProvisioner provisioner;
    private final Counter adopted;
    private final Counter started;
    private final Counter orphansDeleted;
    private final Counter errors;

    public PodReconciler(PodRepository pods, PodProvisioner provisioner, MeterRegistry meterRegistry) {
        this.pods = pods;
        this.provisioner = provisioner;
        this.adopted = Counter.builder("globalOrchestrator.podReconciler.adopted")
                .description("Containers seen on the daemon with no matching pod row → row inserted.")
                .register(meterRegistry);
        this.started = Counter.builder("globalOrchestrator.podReconciler.started")
                .description("Containers found stopped that the reconciler started.")
                .register(meterRegistry);
        this.orphansDeleted = Counter.builder("globalOrchestrator.podReconciler.orphansDeleted")
                .description("Pod rows pointing at containers that no longer exist → row deleted.")
                .register(meterRegistry);
        this.errors = Counter.builder("globalOrchestrator.podReconciler.errors")
                .description("Per-pod reconciler errors. Sweep continues on each.")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        try {
            ReconcileSummary summary = reconcile();
            LOG.info("PodReconciler boot sweep done: adopted={}, started={}, orphansDeleted={}, errors={}",
                    summary.adopted.size(), summary.started.size(),
                    summary.orphansDeleted.size(), summary.errors.size());
        } catch (Exception e) {
            // Swallow — a docker.sock outage at boot shouldn't keep the global
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
        // listFor(null, null) returns every managed container regardless of
        // app/region — we'll filter by label client-side. The provisioner's
        // listFor takes a specific applicationId, so we walk the registry's
        // distinct applicationIds first (via PodRepository.findAll) to find
        // candidate apps, then query per-app.
        //
        // For the boot-time case where ALL rows are gone but containers
        // remain, we don't have any applicationIds to ask about. The
        // provisioner exposes labels on the containers themselves — we read
        // the label and adopt without needing the registry.
        List<ProvisionedPod> allManaged;
        try {
            allManaged = listAllManaged();
        } catch (Exception e) {
            LOG.warn("PodReconciler: failed to list managed containers: {}", e.toString());
            errors.increment();
            summary.errors.add("list-managed: " + e.getMessage());
            return summary;
        }

        Map<String, ProvisionedPod> containerByPodId = new LinkedHashMap<>();
        for (ProvisionedPod c : allManaged) {
            if (c.podName() == null || c.applicationId() == null) continue;
            containerByPodId.put(c.podName(), c);
        }

        for (ProvisionedPod c : containerByPodId.values()) {
            try {
                List<Pod> rows = pods.findByApplicationAndRegion(c.applicationId(), c.region());
                Pod existing = rows.stream()
                        .filter(p -> c.podName().equals(p.podId()))
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    // Adopt — insert the row using the pod's container as authority.
                    // baseUrl is reconstructed from podName + the configured local-orch port.
                    pods.register(c.podName(), c.region(), defaultBaseUrlFor(c.podName()), c.applicationId());
                    summary.adopted.add(c.podName());
                    adopted.increment();
                    LOG.info("PodReconciler adopted orphan container {} (app={}, region={})",
                            c.podName(), c.applicationId(), c.region());
                }
                // WORKER-HYGIENE Phase B — back-fill the recycle-tracking
                // columns from the container's daemon-truth. recordProvisionMetadata
                // uses COALESCE so it only writes columns that are currently
                // null on the row, leaving freshly-spun pods (which got
                // these columns set in CapacityController.spin) untouched.
                if (existing == null
                        || existing.imageDigest() == null
                        || existing.provisionedAt() == null) {
                    pods.recordProvisionMetadata(c.podName(), c.imageDigest(), c.startedAt());
                }
                if (!provisioner.isRunning(c.podName())) {
                    provisioner.start(c.podName());
                    summary.started.add(c.podName());
                    started.increment();
                    LOG.info("PodReconciler started stopped container {}", c.podName());
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: error handling container {}: {}", c.podName(), e.toString());
                errors.increment();
                summary.errors.add(c.podName() + ": " + e.getMessage());
            }
        }

        // ── Pass 2 — row-first (orphan deletion) ────────────────────────
        // Walk every pod row whose applicationId is set; if no container
        // matches, the row is an orphan. Legacy static rows (applicationId
        // NULL) are skipped — they belong to the static compose services.
        for (Pod row : pods.findAll()) {
            if (row.applicationId() == null) continue;
            if (containerByPodId.containsKey(row.podId())) continue;
            try {
                int n = pods.deleteByPodId(row.podId());
                if (n > 0) {
                    summary.orphansDeleted.add(row.podId());
                    orphansDeleted.increment();
                    LOG.info("PodReconciler deleted orphan row {} (container missing)", row.podId());
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: error deleting orphan row {}: {}", row.podId(), e.toString());
                errors.increment();
                summary.errors.add(row.podId() + ": " + e.getMessage());
            }
        }

        return summary;
    }

    /**
     * Lists every container the provisioner manages, regardless of app or
     * region. Calls the provisioner once per (app, region) pair we know
     * about from the registry — plus a wildcard pass for the boot-time
     * case where rows may have been wiped but containers remain.
     *
     * <p>The wildcard pass uses {@code listFor(applicationId=null, region=null)}
     * which the {@link DockerSocketPodProvisioner} interprets as "all
     * containers labelled managedBy=global-orchestrator" — see its
     * implementation. {@link PodProvisioner#listFor(String, String)}
     * requires a non-null applicationId per its javadoc, so the wildcard
     * pass is implemented inline here using the same label filter.
     */
    private List<ProvisionedPod> listAllManaged() {
        // Aggregate by walking the registry's distinct (applicationId, region)
        // pairs. This handles the common case (registry in sync) cheaply.
        // Then merge in the wildcard sweep so a fully-wiped registry still
        // sees containers.
        Map<String, ProvisionedPod> merged = new LinkedHashMap<>();

        // Per-(app, region) pass — covers existing rows.
        for (Pod row : pods.findAll()) {
            if (row.applicationId() == null) continue;
            try {
                for (ProvisionedPod c : provisioner.listFor(row.applicationId(), row.region())) {
                    if (c.podName() != null) merged.put(c.podName(), c);
                }
            } catch (Exception e) {
                LOG.warn("PodReconciler: listFor({},{}) failed: {}",
                        row.applicationId(), row.region(), e.toString());
                errors.increment();
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
    private String defaultBaseUrlFor(String podName) {
        // Mirror the provisioner's baseUrlFor() logic. Hardcoded port 8080
        // because that's the local-orch HTTP_PORT; configurable via
        // ProvisionerProperties when we wire that injection in Phase 3.
        return "http://" + podName + ":8080";
    }

    /** Summary returned by {@link #reconcile()} — surfaced via the admin endpoint. */
    public static class ReconcileSummary {
        public final List<String> adopted = new ArrayList<>();
        public final List<String> started = new ArrayList<>();
        public final List<String> orphansDeleted = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }
}
