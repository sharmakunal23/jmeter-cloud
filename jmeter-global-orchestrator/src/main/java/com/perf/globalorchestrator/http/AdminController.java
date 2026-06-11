package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.provision.PodNameAllocator;
import com.perf.globalorchestrator.provision.PodProvisioner;
import com.perf.globalorchestrator.provision.PodReconciler;
import com.perf.globalorchestrator.provision.PodReconciler.ReconcileSummary;
import com.perf.globalorchestrator.provision.PodRecycler;
import com.perf.globalorchestrator.provision.PodSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Operator-initiated admin endpoints. Only authenticated operators should
 * reach these in cloud mode — the local profile leaves auth off.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/admin/reconcilePods} — Phase 2 of the capacity
 *       rework. Forces a {@link PodReconciler} sweep without restarting the
 *       global-orchestrator. Useful after a manual {@code docker rm}, an
 *       interrupted spin-up, or any operator-initiated drift.</li>
 *   <li>{@code POST /api/v1/admin/spinPod} — Phase 2 smoke endpoint. Allocates
 *       the next free name + creates + starts a container for an
 *       {@code (applicationId, region)} pair. <strong>No capacity check</strong>
 *       — the proper {@code POST /capacity/{region}/pods} endpoint in Phase 3
 *       wraps this with the {@code applicationCapacity.maxAvailable} guard.
 *       Kept around as an admin escape hatch.</li>
 *   <li>{@code DELETE /api/v1/admin/pods/{podName}} — Phase 2 teardown
 *       counterpart. Stops + removes the container + deletes the registry
 *       row. <strong>No in-use check</strong> — the proper drain endpoint
 *       in Phase 3 refuses 409 when an active run is using the pod.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final PodReconciler reconciler;
    private final PodRecycler recycler;
    private final PodProvisioner provisioner;
    private final PodNameAllocator nameAllocator;
    private final ApplicationRepository applications;

    public AdminController(
            PodReconciler reconciler,
            PodRecycler recycler,
            PodProvisioner provisioner,
            PodNameAllocator nameAllocator,
            ApplicationRepository applications) {
        this.reconciler    = reconciler;
        this.recycler      = recycler;
        this.provisioner   = provisioner;
        this.nameAllocator = nameAllocator;
        this.applications  = applications;
    }

    @PostMapping("/reconcilePods")
    public ResponseEntity<Map<String, Object>> reconcilePods() {
        ReconcileSummary summary = reconciler.reconcile();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adopted",        summary.adopted);
        body.put("started",        summary.started);
        body.put("orphansDeleted", summary.orphansDeleted);
        body.put("errors",         summary.errors);
        return ResponseEntity.ok(body);
    }

    /**
     * WORKER-HYGIENE Phase D — forces an immediate recycle sweep. Same body
     * shape as the scheduled tick. Useful for smoke testing the threshold
     * policies without waiting the 60s default cadence.
     */
    @PostMapping("/recyclePods")
    public ResponseEntity<Map<String, Object>> recyclePods() {
        PodRecycler.RecycleSummary summary = recycler.doSweep();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recycled", summary.recycled);
        body.put("skipped",  summary.skipped);
        body.put("errors",   summary.errors);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/spinPod")
    public ResponseEntity<Map<String, Object>> spinPod(@RequestBody SpinPodRequest req) {
        if (req == null || req.applicationId() == null || req.applicationId().isBlank()
                || req.region() == null || req.region().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "INVALID_REQUEST",
                    "message", "applicationId and region are required"));
        }
        Optional<Application> app = applications.findById(req.applicationId());
        if (app.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", "APPLICATION_NOT_FOUND",
                    "message", "applicationId=" + req.applicationId() + " is not registered"));
        }
        String podName = nameAllocator.allocate(req.applicationId(), app.get().name(), req.region());
        PodSpec spec = new PodSpec(podName, req.applicationId(), app.get().name(), req.region());
        com.perf.globalorchestrator.provision.ProvisionResult result = provisioner.createAndStart(spec);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName",       podName);
        body.put("applicationId", req.applicationId());
        body.put("region",        req.region());
        body.put("baseUrl",       result.baseUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/pods/{podName}")
    public ResponseEntity<Map<String, Object>> tearDownPod(@PathVariable String podName) {
        provisioner.stopAndRemove(podName);
        // Registry row will be GC'd by the next reconciler sweep — caller
        // can also POST /admin/reconcilePods immediately to force it.
        return ResponseEntity.ok(Map.of("podName", podName, "stopped", true));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpinPodRequest(String applicationId, String region) {}
}
