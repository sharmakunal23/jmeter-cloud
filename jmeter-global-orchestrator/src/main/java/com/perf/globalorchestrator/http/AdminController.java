package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.PodRepository;
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
 * Operator escape hatches under {@code /api/v1/admin} — forced reconcile and
 * recycle sweeps, plus a raw pod spin and teardown. The routes and their
 * responses are specified in {@code api/openapi.yaml}.
 *
 * <p><b>The spin and teardown routes deliberately skip the capacity and in-use
 * checks</b> that {@link CapacityController}'s equivalents enforce. They exist
 * for when something is stuck; normal operation should never use them. The
 * sweeps themselves are scoped to {@code SOURCE=DYNAMIC} rows, so a declared
 * fleet is never touched (CLUSTER-CAPACITY).
 *
 * <p>Only authenticated operators should reach these in cloud mode; the local
 * profile leaves auth off.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final PodReconciler reconciler;
    private final PodRecycler recycler;
    private final PodProvisioner provisioner;
    private final PodNameAllocator nameAllocator;
    private final ApplicationGroupRepository groups;
    private final PodRepository pods;

    public AdminController(
            PodReconciler reconciler,
            PodRecycler recycler,
            PodProvisioner provisioner,
            PodNameAllocator nameAllocator,
            ApplicationGroupRepository groups,
            PodRepository pods) {
        this.reconciler    = reconciler;
        this.recycler      = recycler;
        this.provisioner   = provisioner;
        this.nameAllocator = nameAllocator;
        this.groups = groups;
        this.pods = pods;
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
     * Forces an immediate recycle sweep. Same body
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
        if (req == null || req.groupId() == null || req.groupId().isBlank()
                || req.region() == null || req.region().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", "INVALID_REQUEST",
                    "message", "groupId and region are required"));
        }
        if (groups.findById(req.groupId()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", "APPLICATION_GROUP_NOT_FOUND",
                    "message", "groupId=" + req.groupId() + " is not registered"));
        }
        String podName = nameAllocator.allocate(req.groupId(), req.region());
        PodSpec spec = new PodSpec(podName, req.groupId(), req.region());
        com.perf.globalorchestrator.provision.ProvisionResult result = provisioner.createAndStart(spec);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName", podName);
        body.put("groupId", req.groupId());
        body.put("region",        req.region());
        body.put("baseUrl",       result.baseUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/pods/{podName}")
    public ResponseEntity<Map<String, Object>> tearDownPod(@PathVariable String podName) {
        if (!com.perf.globalorchestrator.domain.PodNames.isValid(podName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_POD_NAME",
                    "message", "podName must be a DNS-1123 label: " + podName));
        }
        Optional<com.perf.globalorchestrator.domain.Pod> row = pods.findByPodId(podName);
        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", "POD_NOT_REGISTERED",
                    "message", "worker " + podName + " is not in the registry, so its region is unknown"));
        }
        // An operator-declared worker is not ours to destroy — asking the
        // regional to delete a Pod of that name would tear down something the
        // platform never created, and the reconciler (DYNAMIC-scoped) would
        // never GC the row either. Release it through the capacity endpoint.
        if (row.get().source() == com.perf.globalorchestrator.domain.PodSource.STATIC) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "POD_SOURCE_STATIC",
                    "message", "worker " + podName + " is operator-declared — the control plane cannot tear it "
                            + "down; release it with DELETE /api/v1/applicationGroups/"
                            + row.get().groupId() + "/capacity/" + row.get().region() + "/pods/" + podName,
                    "podName", podName));
        }
        provisioner.stopAndRemove(row.get().region(), podName);
        // Registry row will be GC'd by the next reconciler sweep — caller
        // can also POST /admin/reconcilePods immediately to force it.
        return ResponseEntity.ok(Map.of("podName", podName, "stopped", true));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpinPodRequest(String groupId, String region) {}
}
