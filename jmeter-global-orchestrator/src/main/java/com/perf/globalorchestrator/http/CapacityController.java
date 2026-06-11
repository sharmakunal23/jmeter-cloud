package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationCapacity;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.provision.PodNameAllocator;
import com.perf.globalorchestrator.provision.PodProvisioner;
import com.perf.globalorchestrator.provision.PodSpec;
import com.perf.globalorchestrator.provision.PodSpinService;
import com.perf.globalorchestrator.repo.ApplicationCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.PodRepository.ActiveRunBinding;
import com.perf.globalorchestrator.repo.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 3 of the capacity rework — operator-facing capacity surface.
 *
 * <p>All endpoints scoped to a single {@code (applicationId, region)}:
 *
 * <ul>
 *   <li>{@code PUT /api/v1/applications/{id}/capacity/{region}}
 *       — set {@code maxAvailable} directly. Replaces the Phase 2 admin
 *       {@code requestIncrease} stub. No sponsor approval (per the
 *       2026-05-12 design decision).</li>
 *   <li>{@code GET /api/v1/applications/{id}/capacity/{region}/pods}
 *       — list the pods currently provisioned for this (app, region) with
 *       per-pod state (READY / IN_USE / LOST).</li>
 *   <li>{@code POST /api/v1/applications/{id}/capacity/{region}/pods}
 *       — spin up one new Ready pod. 409 if would exceed
 *       {@code maxAvailable}.</li>
 *   <li>{@code POST /api/v1/applications/{id}/capacity/{region}/pods/{podName}/restart}
 *       — recycle one container in place.</li>
 *   <li>{@code DELETE /api/v1/applications/{id}/capacity/{region}/pods/{podName}}
 *       — drain. 409 + {@code blockedBy: { runId, ... }} if the pod is
 *       currently held by an in-flight run.</li>
 * </ul>
 *
 * <p>The Phase 2 {@code AdminController} variants ({@code POST /admin/spinPod},
 * {@code DELETE /admin/pods/{name}}) stay as escape hatches — they bypass
 * the capacity + in-use checks. Operators should always use the Phase 3
 * endpoints; admin endpoints only when something's stuck.
 */
@RestController
@RequestMapping("/api/v1/applications/{applicationId:" + Ulid.PATTERN + "}/capacity/{region}")
public class CapacityController {

    private static final int MAX_POD_BUDGET = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(CapacityController.class);

    private final ApplicationRepository apps;
    private final ApplicationCapacityRepository capacityRepo;
    private final PodRepository pods;
    private final RunRepository runs;
    private final PodProvisioner provisioner;
    private final PodNameAllocator allocator;
    private final PodSpinService spinService;

    public CapacityController(
            ApplicationRepository apps,
            ApplicationCapacityRepository capacityRepo,
            PodRepository pods,
            RunRepository runs,
            PodProvisioner provisioner,
            PodNameAllocator allocator,
            PodSpinService spinService) {
        this.apps         = apps;
        this.capacityRepo = capacityRepo;
        this.pods         = pods;
        this.runs         = runs;
        this.provisioner  = provisioner;
        this.allocator    = allocator;
        this.spinService  = spinService;
    }

    // ── PUT /capacity/{region} — set maxAvailable directly ────────────

    @PutMapping
    public ResponseEntity<ApplicationCapacity> setMax(
            @PathVariable String applicationId,
            @PathVariable String region,
            @RequestBody SetMaxRequest req) {
        requireApp(applicationId);
        if (req == null) {
            throw new CapacityValidationException("request body is required");
        }
        if (req.maxAvailable() < 0 || req.maxAvailable() > MAX_POD_BUDGET) {
            throw new CapacityValidationException(
                    "maxAvailable must be 0.." + MAX_POD_BUDGET + "; got " + req.maxAvailable());
        }
        // Sanity guard: don't let the operator shrink Max below the number of
        // currently-provisioned pods. Forces them to drain first, which keeps
        // the registry consistent with the budget at all times.
        int provisioned = pods.countByApplicationAndRegion(applicationId, region);
        if (req.maxAvailable() < provisioned) {
            throw new CapacityShrinkBelowProvisionedException(provisioned, req.maxAvailable());
        }
        capacityRepo.upsert(applicationId, region, req.maxAvailable());
        ApplicationCapacity updated = capacityRepo.find(applicationId, region)
                .orElseThrow(() -> new IllegalStateException("upsert produced no row"));
        return ResponseEntity.ok(updated);
    }

    // ── GET /capacity/{region}/pods — list pods with state ─────────────

    @GetMapping("/pods")
    public ResponseEntity<CapacitySnapshot> listPods(
            @PathVariable String applicationId,
            @PathVariable String region) {
        requireApp(applicationId);
        int max = capacityRepo.find(applicationId, region)
                .map(ApplicationCapacity::maxAvailable)
                .orElse(0);
        List<Pod> rows = pods.findByApplicationAndRegion(applicationId, region);
        List<PodView> views = new ArrayList<>(rows.size());
        int inUse = 0;
        for (Pod p : rows) {
            Optional<ActiveRunBinding> binding = pods.findActiveRunBindingFor(p.podId());
            // Map the registry's PodState (IDLE / LOST) to the operator-facing
            // capacity vocabulary (READY / IN_USE / LOST / UNKNOWN). IDLE pods
            // currently held by an active run are surfaced as IN_USE; the
            // distinction matters for the UI's drain-button enablement.
            // Phase F1 — surface DRAINING_FOR_RECYCLE as a distinct
            // "RECYCLING" state so the operator-facing chip can show
            // "Will recycle now (idle)" / "Will recycle after current run".
            String podState;
            if (p.state() == com.perf.globalorchestrator.domain.PodState.DRAINING_FOR_RECYCLE) {
                podState = "RECYCLING";
            } else if (binding.isPresent()) {
                podState = "IN_USE";
            } else if (p.state() == null) {
                podState = "UNKNOWN";
            } else switch (p.state()) {
                case IDLE -> podState = "READY";
                case LOST -> podState = "LOST";
                default   -> podState = p.state().name();
            }
            if (binding.isPresent()) inUse++;
            boolean containerRunning;
            try {
                containerRunning = provisioner.isRunning(p.podId());
            } catch (Exception e) {
                // Daemon unreachable — return false rather than 500'ing the whole list.
                containerRunning = false;
            }
            views.add(new PodView(
                    p.podId(),
                    podState,
                    containerRunning,
                    p.lastHeartbeat(),
                    binding.map(b -> new BlockedBy(b.runId(), b.state(), b.startedAt(), b.initiatedBy()))
                           .orElse(null),
                    // Phase F1 — WORKER-HYGIENE columns surfaced to the UI.
                    p.runsServed(),
                    p.imageDigest(),
                    p.provisionedAt()));
        }
        int ready = views.size() - inUse;
        int spinnable = Math.max(0, max - views.size());
        return ResponseEntity.ok(new CapacitySnapshot(
                applicationId, region, max, views.size(), ready, inUse, spinnable, views));
    }

    // ── POST /capacity/{region}/pods — spin a new Ready pod ────────────

    @PostMapping("/pods")
    public ResponseEntity<Map<String, Object>> spin(
            @PathVariable String applicationId,
            @PathVariable String region) {
        Application app = requireApp(applicationId);
        int max = capacityRepo.find(applicationId, region)
                .map(ApplicationCapacity::maxAvailable)
                .orElseThrow(() -> new CapacityRegionNotFoundException(applicationId, region));
        int provisioned = pods.countByApplicationAndRegion(applicationId, region);
        if (provisioned + 1 > max) {
            throw new CapacityExceededException(provisioned, max);
        }
        PodSpinService.SpinResult result = spinService.spin(applicationId, app.name(), region);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName",       result.podName());
        body.put("applicationId", applicationId);
        body.put("region",        region);
        body.put("baseUrl",       result.baseUrl());
        body.put("provisioned",   provisioned + 1);
        body.put("maxAvailable",  max);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ── POST /capacity/{region}/pods/{podName}/restart ─────────────────

    @PostMapping("/pods/{podName}/restart")
    public ResponseEntity<Map<String, Object>> restart(
            @PathVariable String applicationId,
            @PathVariable String region,
            @PathVariable String podName) {
        requireApp(applicationId);
        requirePodBoundToAppRegion(applicationId, region, podName);
        provisioner.restart(podName);
        return ResponseEntity.ok(Map.of("podName", podName, "restarted", true));
    }

    // ── DELETE /capacity/{region}/pods/{podName} — drain ───────────────

    @DeleteMapping("/pods/{podName}")
    public ResponseEntity<Map<String, Object>> drain(
            @PathVariable String applicationId,
            @PathVariable String region,
            @PathVariable String podName) {
        requireApp(applicationId);
        requirePodBoundToAppRegion(applicationId, region, podName);
        Optional<ActiveRunBinding> blocker = pods.findActiveRunBindingFor(podName);
        boolean staleBindingReleased = false;
        if (blocker.isPresent()) {
            // A binding to a still-non-terminal run normally blocks drain
            // (409 POD_IN_USE). But if the worker's container is no longer
            // running, the binding is STALE — the run row is a zombie (the
            // worker died and there's no heartbeat to flip it terminal).
            // Refusing here is exactly the "can't drain a stuck worker from
            // the UI" trap. Treat it as stale: release the dead member
            // binding (so a re-spun same-name pod won't re-bind to the zombie
            // run) and let the drain proceed. The proper way to terminate the
            // zombie run itself is POST /runs/{runId}/abort.
            boolean containerRunning;
            try {
                containerRunning = provisioner.isRunning(podName);
            } catch (Exception e) {
                // Daemon unreachable → can't be running → treat as stale.
                containerRunning = false;
            }
            if (containerRunning) {
                throw new PodInUseException(podName, blocker.get());
            }
            int released = runs.abortActiveMembersForWorker(
                    podName, "drainedStaleContainer:" + blocker.get().runId());
            staleBindingReleased = released > 0;
            LOG.warn("Drained pod {} despite a binding to run {} — container is not running, "
                    + "so the binding is stale; released {} member row(s). The zombie run is "
                    + "still {}; POST /api/v1/runs/{}/abort to terminate it.",
                    podName, blocker.get().runId(), released,
                    blocker.get().state(), blocker.get().runId());
        }
        provisioner.stopAndRemove(podName);
        pods.deleteByPodId(podName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName", podName);
        body.put("drained", true);
        if (staleBindingReleased) {
            body.put("staleBindingReleased", true);
        }
        return ResponseEntity.ok(body);
    }

    // ── DELETE /capacity/{region} — remove a region from the application ─

    /**
     * Removes a region (capacity row) from an application — the "deselect a
     * region" half of the Capacity tab's region picker. Drain-first: a region
     * with any provisioned worker is refused with 409 {@code REGION_NOT_EMPTY}
     * so its pod rows + containers can't be orphaned. 404 when the region
     * isn't configured for the app.
     */
    @DeleteMapping
    public ResponseEntity<Void> removeRegion(
            @PathVariable String applicationId,
            @PathVariable String region) {
        requireApp(applicationId);
        if (capacityRepo.find(applicationId, region).isEmpty()) {
            throw new CapacityRegionNotFoundException(applicationId, region);
        }
        int provisioned = pods.countByApplicationAndRegion(applicationId, region);
        if (provisioned > 0) {
            throw new RegionNotEmptyException(region, provisioned);
        }
        capacityRepo.delete(applicationId, region);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Application requireApp(String applicationId) {
        return apps.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    private void requirePodBoundToAppRegion(String applicationId, String region, String podName) {
        boolean bound = pods.findByApplicationAndRegion(applicationId, region).stream()
                .anyMatch(p -> podName.equals(p.podId()));
        if (!bound) {
            throw new PodNotBoundException(podName, applicationId, region);
        }
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SetMaxRequest(int maxAvailable) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodView(
            String podName,
            String state,                // READY / IN_USE / LOST / UNKNOWN / RECYCLING
            boolean containerRunning,
            Instant lastHeartbeat,
            BlockedBy blockedBy,
            /** Phase F1 — count of runs claimed against this pod (WORKER-HYGIENE Phase B). */
            long runsServed,
            /** Phase F1 — sha256 ID of the image the pod was created from; null for legacy rows. */
            String imageDigest,
            /** Phase F1 — wall-clock at container creation; null for legacy rows. */
            Instant provisionedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BlockedBy(String runId, String state, Instant startedAt, String initiatedBy) {}

    public record CapacitySnapshot(
            String applicationId,
            String region,
            int maxAvailable,
            int provisioned,            // == ready + inUse
            int ready,
            int inUse,
            int spinnable,              // == maxAvailable - provisioned (clamped at 0)
            List<PodView> pods) {}

    // ── Exceptions ─────────────────────────────────────────────────────

    static final class ApplicationNotFoundException extends RuntimeException {
        ApplicationNotFoundException(String id) { super("application not found: " + id); }
    }
    static final class CapacityRegionNotFoundException extends RuntimeException {
        CapacityRegionNotFoundException(String appId, String region) {
            super("no capacity row for applicationId=" + appId + " region=" + region
                    + "; PUT /capacity/" + region + " first");
        }
    }
    static final class CapacityValidationException extends RuntimeException {
        CapacityValidationException(String message) { super(message); }
    }
    static final class CapacityExceededException extends RuntimeException {
        final int provisioned, max;
        CapacityExceededException(int provisioned, int max) {
            super("would exceed maxAvailable: provisioned=" + provisioned + " + 1 > max=" + max);
            this.provisioned = provisioned;
            this.max = max;
        }
    }
    static final class CapacityShrinkBelowProvisionedException extends RuntimeException {
        final int provisioned, requested;
        CapacityShrinkBelowProvisionedException(int provisioned, int requested) {
            super("cannot shrink maxAvailable to " + requested
                    + " while " + provisioned + " pods are provisioned; drain first");
            this.provisioned = provisioned;
            this.requested = requested;
        }
    }
    static final class PodNotBoundException extends RuntimeException {
        PodNotBoundException(String podName, String appId, String region) {
            super("pod " + podName + " is not bound to applicationId=" + appId + " region=" + region);
        }
    }
    static final class PodInUseException extends RuntimeException {
        final ActiveRunBinding binding;
        final String podName;
        PodInUseException(String podName, ActiveRunBinding binding) {
            super("pod " + podName + " is held by run " + binding.runId() + " (state=" + binding.state() + ")");
            this.binding = binding;
            this.podName = podName;
        }
    }
    static final class RegionNotEmptyException extends RuntimeException {
        final int provisioned;
        RegionNotEmptyException(String region, int provisioned) {
            super("region " + region + " still has " + provisioned
                    + " provisioned worker(s); drain them before removing the region");
            this.provisioned = provisioned;
        }
    }

    // ── Exception → HTTP mapping ────────────────────────────────────────

    @ExceptionHandler(ApplicationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleAppNotFound(ApplicationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_NOT_FOUND", "message", e.getMessage()));
    }
    @ExceptionHandler(CapacityRegionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRegionNotFound(CapacityRegionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "CAPACITY_REGION_NOT_FOUND", "message", e.getMessage()));
    }
    @ExceptionHandler(CapacityValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(CapacityValidationException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }
    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> handleExceeded(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",        "APPLICATION_CAPACITY_EXCEEDED",
                "message",     e.getMessage(),
                "provisioned", e.provisioned,
                "maxAvailable", e.max));
    }
    @ExceptionHandler(CapacityShrinkBelowProvisionedException.class)
    public ResponseEntity<Map<String, Object>> handleShrink(CapacityShrinkBelowProvisionedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",        "CAPACITY_SHRINK_BELOW_PROVISIONED",
                "message",     e.getMessage(),
                "provisioned", e.provisioned,
                "requested",   e.requested));
    }
    @ExceptionHandler(PodNotBoundException.class)
    public ResponseEntity<Map<String, String>> handleNotBound(PodNotBoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "POD_NOT_FOUND", "message", e.getMessage()));
    }
    @ExceptionHandler(PodInUseException.class)
    public ResponseEntity<Map<String, Object>> handlePodInUse(PodInUseException e) {
        Map<String, Object> blocker = new LinkedHashMap<>();
        blocker.put("runId",       e.binding.runId());
        blocker.put("state",       e.binding.state());
        blocker.put("startedAt",   e.binding.startedAt());
        blocker.put("initiatedBy", e.binding.initiatedBy());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",      "POD_IN_USE",
                "message",   e.getMessage(),
                "podName",   e.podName,
                "blockedBy", blocker));
    }
    @ExceptionHandler(RegionNotEmptyException.class)
    public ResponseEntity<Map<String, Object>> handleRegionNotEmpty(RegionNotEmptyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",        "REGION_NOT_EMPTY",
                "message",     e.getMessage(),
                "provisioned", e.provisioned));
    }
}
