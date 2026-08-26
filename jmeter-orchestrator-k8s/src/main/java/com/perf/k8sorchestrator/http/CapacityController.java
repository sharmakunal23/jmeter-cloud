package com.perf.k8sorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.perf.k8sorchestrator.client.LocalOrchestratorClient;
import com.perf.k8sorchestrator.domain.Application;
import com.perf.k8sorchestrator.domain.ApplicationCapacity;
import com.perf.k8sorchestrator.domain.Pod;
import com.perf.k8sorchestrator.domain.PodSource;
import com.perf.k8sorchestrator.domain.Ulid;
import com.perf.k8sorchestrator.provision.PodNameAllocator;
import com.perf.k8sorchestrator.provision.PodProvisioner;
import com.perf.k8sorchestrator.provision.PodSpec;
import com.perf.k8sorchestrator.provision.PodSpinService;
import com.perf.k8sorchestrator.provision.ProvisioningDisabledException;
import com.perf.k8sorchestrator.provision.ProvisioningProperties;
import com.perf.k8sorchestrator.provision.ProvisioningRequiresStaticException;
import com.perf.k8sorchestrator.provision.StaticPodDeclaration;
import com.perf.k8sorchestrator.repo.ApplicationCapacityRepository;
import com.perf.k8sorchestrator.repo.ApplicationRepository;
import com.perf.k8sorchestrator.repo.PodRepository;
import com.perf.k8sorchestrator.repo.PodRepository.ActiveRunBinding;
import com.perf.k8sorchestrator.repo.RunRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The operator-facing capacity surface, every route scoped to one
 * {@code (applicationId, region)}: set {@code maxAvailable}, list the pods
 * provisioned for that pair, and spin, restart or drain one. The routes and
 * their responses are specified in {@code api/openapi.yaml}.
 *
 * <p>Two guards are the reason to prefer these over {@link AdminController}'s
 * equivalents, which bypass both: spinning refuses with 409 when it would exceed
 * {@code maxAvailable}, and draining refuses with 409 plus
 * {@code blockedBy: { runId, ... }} when an in-flight run still holds the pod.
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
    private final ProvisioningProperties provisioning;
    /** STATIC-FLEET Phase 3 — declare-time reachability check. */
    private final LocalOrchestratorClient localOrchestrators;

    public CapacityController(
            ApplicationRepository apps,
            ApplicationCapacityRepository capacityRepo,
            PodRepository pods,
            RunRepository runs,
            PodProvisioner provisioner,
            PodNameAllocator allocator,
            PodSpinService spinService,
            ProvisioningProperties provisioning,
            LocalOrchestratorClient localOrchestrators) {
        this.localOrchestrators = localOrchestrators;
        this.apps         = apps;
        this.capacityRepo = capacityRepo;
        this.pods         = pods;
        this.runs         = runs;
        this.provisioner  = provisioner;
        this.allocator    = allocator;
        this.spinService  = spinService;
        this.provisioning = provisioning;
    }

    // ── PUT /capacity/{region} — set maxAvailable directly ────────────

    @PutMapping
    public ResponseEntity<ApplicationCapacity> setMax(
            @PathVariable String applicationId,
            @PathVariable String region,
            @RequestBody SetMaxRequest req) {
        // Capacity is DERIVED from the declared worker
        // count in static mode (D8). The operator already controls the count
        // directly by declaring and undeclaring; a second editable knob would
        // only be a way to make Max and reality disagree, and the next declare
        // would silently overwrite whatever was set here.
        provisioning.requireDynamic("set maxAvailable manually",
                "capacity is derived from the declared worker count — declare or "
                + "release workers instead");
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
            if (p.state() == com.perf.k8sorchestrator.domain.PodState.DRAINING_FOR_RECYCLE) {
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
        // Guard BEFORE the capacity lookups so the
        // operator gets "provisioning is disabled", not "no capacity row".
        provisioning.requireDynamic("spin a worker");
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

    // ── PUT /capacity/{region}/pods/{podName} — declare a worker ───────

    /**
     * Declares an operator-deployed worker against
     * this (application, region). Static mode only; {@code 409
     * PROVISIONING_REQUIRES_STATIC} otherwise.
     *
     * <p>PUT rather than a parallel {@code /staticPods} collection: the
     * operator names the resource (they deployed it and know its name), the
     * call is idempotent, and it keeps ONE worker resource with the verbs
     * carrying the meaning — {@code POST .../pods} allocates a name and
     * spins (dynamic), {@code PUT .../pods/{name}} declares an existing one
     * (static), {@code DELETE .../pods/{name}} releases either. A second
     * collection would have duplicated the release path for no gain.
     *
     * <p>Reachability is checked before accepting so a typo'd address fails
     * here, where the operator is looking, instead of at the next run
     * launch. {@code ?force=true} declares anyway — for a worker that is
     * deployed but not up yet.
     *
     * @return {@code 201} when the worker was newly declared, {@code 200}
     *         when an existing declaration was updated
     */
    @PutMapping("/pods/{podName}")
    public ResponseEntity<Map<String, Object>> declare(
            @PathVariable String applicationId,
            @PathVariable String region,
            @PathVariable String podName,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @RequestBody DeclarePodRequest req) {
        provisioning.requireStatic("declare worker " + podName);
        requireApp(applicationId);
        if (req == null) {
            throw new CapacityValidationException("request body is required");
        }
        StaticPodDeclaration declaration;
        try {
            declaration = StaticPodDeclaration.of(podName, req.baseUrl());
        } catch (IllegalArgumentException e) {
            throw new CapacityValidationException(e.getMessage());
        }

        Optional<Pod> existing = pods.findByPodId(declaration.podName());
        boolean isNew = existing.isEmpty();
        if (existing.isPresent()) {
            Pod pod = existing.get();
            // Never let a declaration silently steal a worker from another
            // application — that would let two apps' runs land on one worker.
            if (!applicationId.equals(pod.applicationId())) {
                throw new PodBoundElsewhereException(
                        declaration.podName(), pod.applicationId(), pod.region());
            }
            // Re-addressing a worker mid-run would break every follow-up call
            // the run makes to it (status polls, drain, abort).
            boolean addressChanged = !declaration.baseUrl().equals(pod.baseUrl())
                    || !region.equals(pod.region());
            if (addressChanged) {
                pods.findActiveRunBindingFor(declaration.podName())
                        .ifPresent(b -> { throw new PodInUseException(declaration.podName(), b); });
            }
        }

        boolean reachable = localOrchestrators.isHealthy(declaration.baseUrl());
        if (!reachable && !force) {
            throw new WorkerUnreachableException(declaration.podName(), declaration.baseUrl());
        }

        pods.declareStatic(declaration.podName(), region, declaration.baseUrl(), applicationId);
        int maxAvailable = syncDerivedCapacity(applicationId, region);

        LOG.info("Declared operator-managed worker {} at {} for applicationId={} region={} "
                + "(new={}, reachable={}, derived maxAvailable={})",
                declaration.podName(), declaration.baseUrl(), applicationId, region,
                isNew, reachable, maxAvailable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName",       declaration.podName());
        body.put("applicationId", applicationId);
        body.put("region",        region);
        body.put("baseUrl",       declaration.baseUrl());
        body.put("source",        PodSource.STATIC.name());
        body.put("reachable",     reachable);
        body.put("declared",      pods.countByApplicationAndRegion(applicationId, region));
        body.put("maxAvailable",  maxAvailable);
        return ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    /**
     * STATIC-FLEET Phase 3 (D8) — in static mode {@code maxAvailable} is
     * DERIVED: it always equals the number of declared workers for this
     * (application, region). The operator controls the count by declaring
     * and releasing, so there is nothing to approve and no second knob to
     * drift. Keeping the row in sync means every downstream capacity check
     * (run-launch cap, the capacity snapshot, the shortfall message) keeps
     * working untouched.
     *
     * @return the value written
     */
    private int syncDerivedCapacity(String applicationId, String region) {
        int declared = pods.countByApplicationAndRegion(applicationId, region);
        capacityRepo.upsert(applicationId, region, declared);
        return declared;
    }

    // ── POST /capacity/{region}/pods/{podName}/restart ─────────────────

    @PostMapping("/pods/{podName}/restart")
    public ResponseEntity<Map<String, Object>> restart(
            @PathVariable String applicationId,
            @PathVariable String region,
            @PathVariable String podName) {
        provisioning.requireDynamic("restart worker " + podName);
        requireApp(applicationId);
        requirePodBoundToAppRegion(applicationId, region, podName);
        provisioner.restart(podName);
        return ResponseEntity.ok(Map.of("podName", podName, "restarted", true));
    }

    // ── DELETE /capacity/{region}/pods/{podName} — drain ───────────────

    /**
     * Releases a worker from this (application, region).
     *
     * <p>Under {@code PROVISIONING_MODE=DYNAMIC} this is a full drain: the
     * container is stopped and removed, then the registry row is deleted.
     * Under {@code STATIC} it is an <b>undeclare</b> — the registry row is
     * deleted and the operator's worker keeps running, because the control
     * plane does not own it. The in-use guard, the stale-binding release
     * and the response shape are identical in both modes; only
     * {@code containerStopped} in the body differs, so existing clients
     * keep working unchanged.
     *
     * <p>The stale-binding path stays correct in static mode because
     * {@code StaticPodProvisioner.isRunning} answers from the registry: a
     * worker swept to {@code LOST} reads as not-running, so its zombie
     * binding is released rather than blocking the undeclare forever.
     */
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
        boolean containerStopped = provisioning.isDynamic();
        if (containerStopped) {
            provisioner.stopAndRemove(podName);
        } else {
            LOG.info("Undeclared operator-managed worker {} from applicationId={} region={} — "
                    + "registry row removed; the worker itself is left running.",
                    podName, applicationId, region);
        }
        pods.deleteByPodId(podName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName", podName);
        body.put("drained", true);
        body.put("containerStopped", containerStopped);
        if (provisioning.isStatic()) {
            // D8 — Max tracks the declared count, so releasing lowers it.
            body.put("maxAvailable", syncDerivedCapacity(applicationId, region));
        }
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

    /**
     * Body of the declare PUT. Only the address is
     * supplied; the worker's name is the path variable (it IS the resource)
     * and the application + region come from the path too.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeclarePodRequest(String baseUrl) {}

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
    /** STATIC-FLEET Phase 3 — declaring a worker another application already owns. */
    static final class PodBoundElsewhereException extends RuntimeException {
        final String podName, boundApplicationId, boundRegion;
        PodBoundElsewhereException(String podName, String boundApplicationId, String boundRegion) {
            super("worker " + podName + " is already declared to applicationId="
                    + boundApplicationId + " region=" + boundRegion
                    + "; release it there before declaring it here");
            this.podName = podName;
            this.boundApplicationId = boundApplicationId;
            this.boundRegion = boundRegion;
        }
    }
    /** STATIC-FLEET Phase 3 — declare-time reachability check failed. */
    static final class WorkerUnreachableException extends RuntimeException {
        final String podName, baseUrl;
        WorkerUnreachableException(String podName, String baseUrl) {
            super("worker " + podName + " did not answer at " + baseUrl
                    + "/actuator/health; check the address, or declare with ?force=true "
                    + "if the worker is deployed but not up yet");
            this.podName = podName;
            this.baseUrl = baseUrl;
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
    /** STATIC-FLEET Phase 2 — spin / restart refused on an operator-managed fleet. */
    @ExceptionHandler(ProvisioningDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleProvisioningDisabled(
            ProvisioningDisabledException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.toBody());
    }
    /** STATIC-FLEET Phase 3 — declare refused on a self-provisioning deployment. */
    @ExceptionHandler(ProvisioningRequiresStaticException.class)
    public ResponseEntity<Map<String, Object>> handleRequiresStatic(
            ProvisioningRequiresStaticException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.toBody());
    }
    @ExceptionHandler(PodBoundElsewhereException.class)
    public ResponseEntity<Map<String, Object>> handleBoundElsewhere(PodBoundElsewhereException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",               "POD_BOUND_ELSEWHERE",
                "message",            e.getMessage(),
                "podName",            e.podName,
                "boundApplicationId", e.boundApplicationId,
                "boundRegion",        e.boundRegion));
    }
    /**
     * 400, not 5xx: the request is what's wrong (a bad address), and the fix
     * is the operator's — correct it, or re-issue with {@code ?force=true}.
     */
    @ExceptionHandler(WorkerUnreachableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreachable(WorkerUnreachableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code",    "WORKER_UNREACHABLE",
                "message", e.getMessage(),
                "podName", e.podName,
                "baseUrl", e.baseUrl));
    }
}
