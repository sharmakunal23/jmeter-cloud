package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerRef;
import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.GroupCapacity;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.PodSource;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.provision.PodNameAllocator;
import com.perf.globalorchestrator.provision.PodProvisioner;
import com.perf.globalorchestrator.provision.PodSpec;
import com.perf.globalorchestrator.provision.PodSpinService;
import com.perf.globalorchestrator.provision.StaticPodDeclaration;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationGroupRepository;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.PodRepository.ActiveRunBinding;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.service.GroupReservationService;
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
 * {@code (groupId, region)} — the worker pool is the application group's
 * (GROUP-CAPACITY, 2026-08-30): set {@code maxAvailable}, list the pods
 * provisioned for that pair, and spin, restart or drain one. The routes and
 * their responses are specified in {@code api/openapi.yaml}.
 *
 * <p>Two guards are the reason to prefer these over {@link AdminController}'s
 * equivalents, which bypass both: spinning refuses with 409 when it would exceed
 * {@code maxAvailable}, and draining refuses with 409 plus
 * {@code blockedBy: { runId, ... }} when an in-flight run still holds the pod.
 */
@RestController
@RequestMapping("/api/v1/applicationGroups/{groupId:" + ApplicationGroupController.GROUP_ID_PATH + "}/capacity/{region}")
public class CapacityController {

    private static final int MAX_POD_BUDGET = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(CapacityController.class);

    private final ApplicationGroupRepository groups;
    private final GroupCapacityRepository capacityRepo;
    private final PodRepository pods;
    private final RunRepository runs;
    private final PodProvisioner provisioner;
    private final PodNameAllocator allocator;
    private final PodSpinService spinService;
    private final GroupReservationService reservations;
    /** STATIC-FLEET Phase 3 — declare-time reachability check. */
    private final LocalOrchestratorClient localOrchestrators;

    public CapacityController(
            ApplicationGroupRepository groups,
            GroupCapacityRepository capacityRepo,
            PodRepository pods,
            RunRepository runs,
            PodProvisioner provisioner,
            PodNameAllocator allocator,
            PodSpinService spinService,
            GroupReservationService reservations,
            LocalOrchestratorClient localOrchestrators) {
        this.localOrchestrators = localOrchestrators;
        this.groups       = groups;
        this.capacityRepo = capacityRepo;
        this.pods         = pods;
        this.runs         = runs;
        this.provisioner  = provisioner;
        this.allocator    = allocator;
        this.spinService  = spinService;
        this.reservations = reservations;
    }

    // ── PUT /capacity/{region} — set the group's reservation ───────────

    /**
     * {@code maxAvailable} is the group's <b>reservation</b> on the cluster
     * (CLUSTER-CAPACITY): spun and declared workers both count against it,
     * and {@link GroupReservationService} holds the cluster-level invariants
     * (registered cluster, ≤ maxClustersPerGroup, the sum of every group's
     * reservations under the cluster's maxWorkers) inside one serialised
     * transaction.
     */
    @PutMapping
    public ResponseEntity<GroupCapacity> setMax(
            @PathVariable String groupId,
            @PathVariable String region,
            @RequestBody SetMaxRequest req) {
        requireGroup(groupId);
        if (req == null) {
            throw new CapacityValidationException("request body is required");
        }
        if (req.maxAvailable() < 0 || req.maxAvailable() > MAX_POD_BUDGET) {
            throw new CapacityValidationException(
                    "maxAvailable must be 0.." + MAX_POD_BUDGET + "; got " + req.maxAvailable());
        }
        // Sanity guard: don't let the operator shrink the reservation below the
        // number of currently-provisioned pods. Forces them to drain first, which
        // keeps the registry consistent with the budget at all times.
        int provisioned = pods.countByGroupAndRegion(groupId, region);
        if (req.maxAvailable() < provisioned) {
            throw new CapacityShrinkBelowProvisionedException(provisioned, req.maxAvailable());
        }
        return ResponseEntity.ok(reservations.reserve(groupId, region, req.maxAvailable()));
    }

    // ── GET /capacity/{region}/pods — list pods with state ─────────────

    @GetMapping("/pods")
    public ResponseEntity<CapacitySnapshot> listPods(
            @PathVariable String groupId,
            @PathVariable String region) {
        requireGroup(groupId);
        int max = capacityRepo.find(groupId, region)
                .map(GroupCapacity::maxAvailable)
                .orElse(0);
        List<Pod> rows = pods.findByGroupAndRegion(groupId, region);
        // One substrate call for the whole list — per-pod isRunning would be
        // one HTTP round-trip to the region per row.
        java.util.Map<String, String> liveStatus = new java.util.HashMap<>();
        boolean substrateReachable = true;
        try {
            for (com.perf.globalorchestrator.provision.ProvisionedPod c : provisioner.listFor(groupId, region)) {
                liveStatus.put(c.podName(), c.status());
            }
        } catch (Exception e) {
            // Region unreachable — report every container as not running rather than 500'ing the list.
            substrateReachable = false;
        }
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
                // A freshly spun pod is LOST (unclaimable) until the kubelet
                // reports it ready — show that as STARTING, not a failure.
                case LOST -> podState = (p.runsServed() == 0 && p.provisionedAt() != null
                        && p.provisionedAt().isAfter(java.time.Instant.now().minusSeconds(180)))
                        ? "STARTING" : "LOST";
                default   -> podState = p.state().name();
            }
            if (binding.isPresent()) inUse++;
            // A declared worker never appears in the regional's managed-Pod
            // list — its registry state is the honest liveness evidence.
            boolean containerRunning = p.source() == PodSource.STATIC
                    ? p.state() != com.perf.globalorchestrator.domain.PodState.LOST
                    : substrateReachable && "running".equals(liveStatus.get(p.podId()));
            views.add(new PodView(
                    p.podId(),
                    podState,
                    p.source() == null ? PodSource.DYNAMIC.name() : p.source().name(),
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
                groupId, region, max, views.size(), ready, inUse, spinnable, views));
    }

    // ── POST /capacity/{region}/pods — spin a new Ready pod ────────────

    @PostMapping("/pods")
    public ResponseEntity<Map<String, Object>> spin(
            @PathVariable String groupId,
            @PathVariable String region) {
        requireGroup(groupId);
        int max = capacityRepo.find(groupId, region)
                .map(GroupCapacity::maxAvailable)
                .orElseThrow(() -> new CapacityRegionNotFoundException(groupId, region));
        int provisioned = pods.countByGroupAndRegion(groupId, region);
        if (provisioned + 1 > max) {
            throw new CapacityExceededException(provisioned, max);
        }
        PodSpinService.SpinResult result = spinService.spin(groupId, region);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName",       result.podName());
        body.put("groupId",       groupId);
        body.put("region",        region);
        body.put("baseUrl",       result.baseUrl());
        body.put("ready",         result.ready());
        body.put("provisioned",   provisioned + 1);
        body.put("maxAvailable",  max);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ── PUT /capacity/{region}/pods/{podName} — declare a worker ───────

    /**
     * Declares an operator-deployed worker against this (group, region) —
     * a {@code SOURCE=STATIC} row that coexists with spun workers in the same
     * pool (CLUSTER-CAPACITY). Declares count against the group's reservation
     * exactly like spins.
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
            @PathVariable String groupId,
            @PathVariable String region,
            @PathVariable String podName,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @RequestBody DeclarePodRequest req) {
        if (!com.perf.globalorchestrator.domain.PodNames.isValid(podName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_POD_NAME",
                    "message", "podName must be a DNS-1123 label: " + podName));
        }
        requireGroup(groupId);
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
        // True when this declare would newly occupy a slot in THIS region —
        // either a brand-new worker, or one moving in from another region
        // (declareStatic's MERGE rewrites REGION, so the move must be paid for
        // here or the target's reservation is silently overrun).
        boolean entersRegion = isNew || !region.equals(existing.get().region());
        if (existing.isPresent()) {
            Pod pod = existing.get();
            // Never let a declaration steal a Pod the control plane created:
            // the MERGE would flip SOURCE to STATIC, after which the reconciler
            // skips it, the recycler refuses it and teardown 409s — the cluster
            // leaks that worker forever.
            if (pod.source() == PodSource.DYNAMIC) {
                throw new PodSourceDynamicException(declaration.podName());
            }
            // Never let a declaration silently steal a worker from another
            // group — that would let two pools' runs land on one worker.
            if (!groupId.equals(pod.groupId())) {
                throw new PodBoundElsewhereException(
                        declaration.podName(), pod.groupId(), pod.region());
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

        // A declare consumes reservation headroom exactly like a spin
        // (CLUSTER-CAPACITY): the group must have attached the cluster and
        // reserved room. Re-declaring an existing worker consumes nothing.
        int maxAvailable = capacityRepo.find(groupId, region)
                .map(GroupCapacity::maxAvailable)
                .orElseThrow(() -> new CapacityRegionNotFoundException(groupId, region));
        if (entersRegion) {
            int provisioned = pods.countByGroupAndRegion(groupId, region);
            if (provisioned + 1 > maxAvailable) {
                throw new CapacityExceededException(provisioned, maxAvailable);
            }
        }

        boolean reachable = localOrchestrators.isHealthy(
                new WorkerRef(region, declaration.podName(), declaration.baseUrl()));
        if (!reachable && !force) {
            throw new WorkerUnreachableException(declaration.podName(), declaration.baseUrl());
        }

        pods.declareStatic(declaration.podName(), region, declaration.baseUrl(), groupId);

        LOG.info("Declared operator-managed worker {} at {} for groupId={} region={} "
                + "(new={}, reachable={}, reservation={})",
                declaration.podName(), declaration.baseUrl(), groupId, region,
                isNew, reachable, maxAvailable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName",       declaration.podName());
        body.put("groupId",       groupId);
        body.put("region",        region);
        body.put("baseUrl",       declaration.baseUrl());
        body.put("source",        PodSource.STATIC.name());
        body.put("reachable",     reachable);
        body.put("declared",      pods.countByGroupAndRegion(groupId, region));
        body.put("maxAvailable",  maxAvailable);
        return ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    // ── POST /capacity/{region}/pods/{podName}/restart ─────────────────

    @PostMapping("/pods/{podName}/restart")
    public ResponseEntity<Map<String, Object>> restart(
            @PathVariable String groupId,
            @PathVariable String region,
            @PathVariable String podName) {
        if (!com.perf.globalorchestrator.domain.PodNames.isValid(podName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_POD_NAME",
                    "message", "podName must be a DNS-1123 label: " + podName));
        }
        requireGroup(groupId);
        Pod pod = requirePodBoundToGroupRegion(groupId, region, podName);
        // Only the regional can restart a pod it created; a declared worker is
        // the operator's — restarting it is not the control plane's to do.
        if (pod.source() == PodSource.STATIC) {
            throw new PodSourceStaticException(podName, "restart");
        }
        provisioner.restart(region, podName);
        return ResponseEntity.ok(Map.of("podName", podName, "restarted", true));
    }

    // ── DELETE /capacity/{region}/pods/{podName} — drain ───────────────

    /**
     * Releases a worker from this (group, region).
     *
     * <p>For a spun worker ({@code SOURCE=DYNAMIC}) this is a full drain: the
     * Pod is stopped and removed through its regional, then the registry row
     * is deleted. For a declared one ({@code SOURCE=STATIC}) it is an
     * <b>undeclare</b> — the registry row is deleted and the operator's worker
     * keeps running, because the control plane does not own it. The in-use
     * guard, the stale-binding release and the response shape are identical;
     * only {@code containerStopped} in the body differs.
     *
     * <p>The stale-binding check answers per source: a declared worker's
     * liveness is its registry state (a worker swept to {@code LOST} reads as
     * not-running, so its zombie binding is released rather than blocking the
     * undeclare forever); a spun worker's is the regional's Pod state.
     */
    @DeleteMapping("/pods/{podName}")
    public ResponseEntity<Map<String, Object>> drain(
            @PathVariable String groupId,
            @PathVariable String region,
            @PathVariable String podName) {
        if (!com.perf.globalorchestrator.domain.PodNames.isValid(podName)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_POD_NAME",
                    "message", "podName must be a DNS-1123 label: " + podName));
        }
        requireGroup(groupId);
        Pod pod = requirePodBoundToGroupRegion(groupId, region, podName);
        boolean dynamicPod = pod.source() != PodSource.STATIC;
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
            if (dynamicPod) {
                try {
                    containerRunning = provisioner.isRunning(region, podName);
                } catch (Exception e) {
                    // Region unreachable → can't be confirmed running → treat as stale.
                    containerRunning = false;
                }
            } else {
                // Declared worker: the registry is the only honest evidence.
                containerRunning = pod.state() != com.perf.globalorchestrator.domain.PodState.LOST;
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
        boolean containerStopped = dynamicPod;
        if (containerStopped) {
            provisioner.stopAndRemove(region, podName);
        } else {
            LOG.info("Undeclared operator-managed worker {} from groupId={} region={} — "
                    + "registry row removed; the worker itself is left running.",
                    podName, groupId, region);
        }
        pods.deleteByPodId(podName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("podName", podName);
        body.put("drained", true);
        body.put("containerStopped", containerStopped);
        if (staleBindingReleased) {
            body.put("staleBindingReleased", true);
        }
        return ResponseEntity.ok(body);
    }

    // ── DELETE /capacity/{region} — remove a region from the application ─

    /**
     * Removes a region (capacity row) from a group — the "deselect a
     * region" half of the Capacity tab's region picker. Drain-first: a region
     * with any provisioned worker is refused with 409 {@code REGION_NOT_EMPTY}
     * so its pod rows + containers can't be orphaned. 404 when the region
     * isn't configured for the group.
     */
    @DeleteMapping
    public ResponseEntity<Void> removeRegion(
            @PathVariable String groupId,
            @PathVariable String region) {
        requireGroup(groupId);
        if (capacityRepo.find(groupId, region).isEmpty()) {
            throw new CapacityRegionNotFoundException(groupId, region);
        }
        int provisioned = pods.countByGroupAndRegion(groupId, region);
        if (provisioned > 0) {
            throw new RegionNotEmptyException(region, provisioned);
        }
        capacityRepo.delete(groupId, region);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private ApplicationGroup requireGroup(String groupId) {
        return groups.findById(groupId)
                .orElseThrow(() -> new ApplicationGroupController.GroupNotFoundException(groupId));
    }

    private Pod requirePodBoundToGroupRegion(String groupId, String region, String podName) {
        return pods.findByGroupAndRegion(groupId, region).stream()
                .filter(p -> podName.equals(p.podId()))
                .findFirst()
                .orElseThrow(() -> new PodNotBoundException(podName, groupId, region));
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
            String state,                // READY / IN_USE / LOST / UNKNOWN / RECYCLING / STARTING
            String source,               // DYNAMIC (spun) | STATIC (operator-declared) — CLUSTER-CAPACITY
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
            String groupId,
            String region,
            int maxAvailable,
            int provisioned,            // == ready + inUse
            int ready,
            int inUse,
            int spinnable,              // == maxAvailable - provisioned (clamped at 0)
            List<PodView> pods) {}

    // ── Exceptions ─────────────────────────────────────────────────────

    static final class CapacityRegionNotFoundException extends RuntimeException {
        CapacityRegionNotFoundException(String groupId, String region) {
            super("group " + groupId + " has no reservation on cluster " + region
                    + "; attach the cluster and reserve capacity first (PUT /capacity/" + region + ")");
        }
    }
    /** CLUSTER-CAPACITY — declaring over a worker the control plane created. */
    static final class PodSourceDynamicException extends RuntimeException {
        final String podName;
        PodSourceDynamicException(String podName) {
            super("worker " + podName + " was created by the platform (a spun worker) — declaring over it "
                    + "would orphan the Pod it manages; drain it first, or declare under a different name");
            this.podName = podName;
        }
    }
    /** CLUSTER-CAPACITY — a lifecycle verb the control plane does not own on a declared worker. */
    static final class PodSourceStaticException extends RuntimeException {
        final String podName;
        PodSourceStaticException(String podName, String action) {
            super("worker " + podName + " is operator-declared — the control plane cannot "
                    + action + " it; manage it where it is deployed");
            this.podName = podName;
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
        PodNotBoundException(String podName, String groupId, String region) {
            super("pod " + podName + " is not bound to groupId=" + groupId + " region=" + region);
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
    /** STATIC-FLEET Phase 3 — declaring a worker another group already owns. */
    static final class PodBoundElsewhereException extends RuntimeException {
        final String podName, boundGroupId, boundRegion;
        PodBoundElsewhereException(String podName, String boundGroupId, String boundRegion) {
            super("worker " + podName + " is already declared to groupId="
                    + boundGroupId + " region=" + boundRegion
                    + "; release it there before declaring it here");
            this.podName = podName;
            this.boundGroupId = boundGroupId;
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

    @ExceptionHandler(ApplicationGroupController.GroupNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleGroupNotFound(ApplicationGroupController.GroupNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "APPLICATION_GROUP_NOT_FOUND", "message", e.getMessage()));
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
    /** CLUSTER-CAPACITY — reservation targets an unregistered cluster. */
    @ExceptionHandler(GroupReservationService.ClusterNotRegisteredException.class)
    public ResponseEntity<Map<String, Object>> handleClusterNotRegistered(
            GroupReservationService.ClusterNotRegisteredException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "CLUSTER_NOT_REGISTERED", "message", e.getMessage(), "region", e.region));
    }
    /** CLUSTER-CAPACITY — a group holds at most maxClustersPerGroup clusters. */
    @ExceptionHandler(GroupReservationService.GroupClusterLimitException.class)
    public ResponseEntity<Map<String, Object>> handleGroupClusterLimit(
            GroupReservationService.GroupClusterLimitException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "GROUP_CLUSTER_LIMIT", "message", e.getMessage(), "maxClusters", e.maxClusters));
    }
    /** CLUSTER-CAPACITY — the cluster's ceiling cannot fit this reservation. */
    @ExceptionHandler(GroupReservationService.ClusterCapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> handleClusterCapacityExceeded(
            GroupReservationService.ClusterCapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "CLUSTER_CAPACITY_EXCEEDED", "message", e.getMessage(),
                "maxWorkers", e.maxWorkers, "reservedByOthers", e.reservedByOthers, "requested", e.requested));
    }
    /** CLUSTER-CAPACITY — declare aimed at a platform-created worker. */
    @ExceptionHandler(PodSourceDynamicException.class)
    public ResponseEntity<Map<String, Object>> handlePodSourceDynamic(PodSourceDynamicException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "POD_SOURCE_DYNAMIC", "message", e.getMessage(), "podName", e.podName));
    }
    /** CLUSTER-CAPACITY — restart aimed at an operator-declared worker. */
    @ExceptionHandler(PodSourceStaticException.class)
    public ResponseEntity<Map<String, Object>> handlePodSourceStatic(PodSourceStaticException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "POD_SOURCE_STATIC", "message", e.getMessage(), "podName", e.podName));
    }
    @ExceptionHandler(PodBoundElsewhereException.class)
    public ResponseEntity<Map<String, Object>> handleBoundElsewhere(PodBoundElsewhereException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code",               "POD_BOUND_ELSEWHERE",
                "message",            e.getMessage(),
                "podName",            e.podName,
                "boundGroupId",       e.boundGroupId,
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
