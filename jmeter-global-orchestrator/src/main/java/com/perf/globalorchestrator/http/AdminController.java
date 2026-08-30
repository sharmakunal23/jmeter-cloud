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
import com.perf.globalorchestrator.provision.ProvisioningDisabledException;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * for when something is stuck; normal operation should never use them.
 *
 * <p>Every route here mutates worker lifecycle, so all four answer
 * {@code 409 PROVISIONING_DISABLED} under {@code PROVISIONING_MODE=STATIC}.
 * {@link PodReconciler} and {@link PodRecycler} are not beans in that mode —
 * their sweeps would destroy an operator-managed fleet — which is why they are
 * injected via {@link ObjectProvider}: absence is the expected state, not a
 * wiring failure.
 *
 * <p>Only authenticated operators should reach these in cloud mode; the local
 * profile leaves auth off.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ObjectProvider<PodReconciler> reconciler;
    private final ObjectProvider<PodRecycler> recycler;
    private final PodProvisioner provisioner;
    private final PodNameAllocator nameAllocator;
    private final ApplicationGroupRepository groups;
    private final ProvisioningProperties provisioning;
    private final PodRepository pods;

    public AdminController(
            ObjectProvider<PodReconciler> reconciler,
            ObjectProvider<PodRecycler> recycler,
            PodProvisioner provisioner,
            PodNameAllocator nameAllocator,
            ApplicationGroupRepository groups,
            ProvisioningProperties provisioning,
            PodRepository pods) {
        this.reconciler    = reconciler;
        this.recycler      = recycler;
        this.provisioner   = provisioner;
        this.nameAllocator = nameAllocator;
        this.groups = groups;
        this.provisioning  = provisioning;
        this.pods = pods;
    }

    @PostMapping("/reconcilePods")
    public ResponseEntity<Map<String, Object>> reconcilePods() {
        ReconcileSummary summary = requireBean(reconciler, "reconcile workers").reconcile();
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
        PodRecycler.RecycleSummary summary =
                requireBean(recycler, "recycle workers").doSweep();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recycled", summary.recycled);
        body.put("skipped",  summary.skipped);
        body.put("errors",   summary.errors);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/spinPod")
    public ResponseEntity<Map<String, Object>> spinPod(@RequestBody SpinPodRequest req) {
        provisioning.requireDynamic("spin a worker");
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
        provisioning.requireDynamic("tear down worker " + podName);
        Optional<com.perf.globalorchestrator.domain.Pod> row = pods.findByPodId(podName);
        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", "POD_NOT_REGISTERED",
                    "message", "worker " + podName + " is not in the registry, so its region is unknown"));
        }
        provisioner.stopAndRemove(row.get().region(), podName);
        // Registry row will be GC'd by the next reconciler sweep — caller
        // can also POST /admin/reconcilePods immediately to force it.
        return ResponseEntity.ok(Map.of("podName", podName, "stopped", true));
    }

    /**
     * Resolves a bean that only exists under {@code PROVISIONING_MODE=DYNAMIC},
     * translating absence into the same {@code 409} an explicit mode guard
     * produces. Absence and static mode are the same fact here — the beans are
     * conditional on exactly that property — so this can't mask a real wiring
     * failure.
     */
    private static <T> T requireBean(ObjectProvider<T> provider, String action) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new ProvisioningDisabledException(action);
        }
        return bean;
    }

    @ExceptionHandler(ProvisioningDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleProvisioningDisabled(
            ProvisioningDisabledException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.toBody());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpinPodRequest(String groupId, String region) {}
}
