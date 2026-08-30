package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.WorkerRef;
import com.perf.globalorchestrator.client.WorkerStatusFetcher;
import com.perf.globalorchestrator.client.LocalOrchestratorClient.StartTestResult;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.MemberState;
import com.perf.globalorchestrator.domain.Pod;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunEvent;
import com.perf.globalorchestrator.domain.RunEventPayloads;
import com.perf.globalorchestrator.domain.RunEventPayloads.RegionCount;
import com.perf.globalorchestrator.domain.RunEventType;
import com.perf.globalorchestrator.domain.RunFleetMember;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.http.FleetAllocationEntry;
import com.perf.globalorchestrator.http.ScaleDownRunRequest;
import com.perf.globalorchestrator.http.ScaleDownRunResponse;
import com.perf.globalorchestrator.http.ScaleUpRunRequest;
import com.perf.globalorchestrator.http.ScaleUpRunResponse;
import com.perf.globalorchestrator.http.StartRunRequest;
import com.perf.globalorchestrator.observability.ErrorContext;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.GroupCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.RunEventRepository;
import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.repo.MetricsTarget;
import com.perf.globalorchestrator.repo.RunMetricsRepository;
import com.perf.globalorchestrator.repo.RunWindow;
import com.perf.globalorchestrator.repo.PluginRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunTrendRepository;
import com.perf.globalorchestrator.domain.PluginRef;
import com.perf.globalorchestrator.domain.RunTrend;
import org.springframework.cache.annotation.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Coordinates the run lifecycle: ULID gen → INSERT run → claim pods
 * (Step 15: from the registry, with FOR UPDATE SKIP LOCKED) →
 * INSERT runFleetMember → fan-out POST /api/v1/test → roll up.
 *
 * <p>The pod-claim phase runs inside a single transaction so the
 * SELECT-for-update + INSERT-reservation pair is atomic — concurrent
 * runs can't double-claim the same pod.
 *
 * <p>Track F (Step 26) added per-region claim semantics: a
 * {@code fleetAllocation} array of {@code (region, count)} entries lets
 * a single run span regions. Strict mode (default) rolls back on any
 * region-level shortfall; {@code bestEffort=true} accepts a partial
 * claim and tags the run's {@code stateReason} with the deficit.
 */
@Service
public class RunService {

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    private final RunRepository runs;
    /** AUDIT-TRAIL — append-only per-action event log (read path: getRunEvents). */
    private final RunEventRepository auditEvents;
    /** AUDIT-TRAIL — write path; shared with PodRecycler. */
    private final RunAuditWriter audit;
    /**
     * Self-reference so the {@code @Transactional} claim+insert
     * methods are invoked through the Spring proxy. A plain {@code this.}
     * self-invocation bypasses the transaction interceptor (proxy-mode
     * limitation), which would leave the audit-event write non-atomic with
     * the mutation it records. {@code @Lazy} breaks the construction-time
     * self-dependency cycle.
     */
    @Autowired
    @Lazy
    private RunService self;
    private final PodRepository pods;
    /** D-Capacity — looked up by name to enforce per-(app, region) maxAvailable. */
    private final ApplicationRepository applications;

    /**
     * Window width every worker aggregates at ({@code metrics.windowSeconds},
     * default 15 — the readers' finest bucket). Field-injected so the many
     * hand-constructed test instances keep the default.
     */
    @org.springframework.beans.factory.annotation.Value("${metrics.windowSeconds:15}")
    private int metricsWindowSeconds = 15;
    private final GroupCapacityRepository groupCapacity;
    private final LocalOrchestratorClient localClient;
    private final WorkerStatusFetcher statusFetcher;
    /** Async launches: one virtual thread per provisioning launch, nothing pooled. */
    private final java.util.concurrent.ExecutorService launchPool =
            java.util.concurrent.Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("globalOrch-launch-", 0).factory());
    // RunTrend snapshot on the run-terminal transition.
    private final RunMetricsRepository runMetrics;
    private final MetricsGroupResolver metricsGroups;
    private final RunTrendRepository runTrends;
    /** UX-DYNAMICS T3 — resolves pluginIds into launch snapshots. */
    private final PluginRepository pluginRepo;
    /** WORKER-HYGIENE Phase E — spin-to-fill on shortfall. Optional so tests can omit it. */
    private final com.perf.globalorchestrator.provision.PodSpinService spinService;
    /** STATIC-FLEET Phase 2 — gates spin-to-fill; workers are operator-managed in STATIC mode. */
    private final com.perf.globalorchestrator.provision.ProvisioningProperties provisioning;
    private final String region;
    private final int maxFleetSizePerRun;
    private final long spinHealthTimeoutMs;
    private final ExecutorService fanoutPool;

    /**
     * Save Results — in-memory dedup so a worker's {@code RESULTS_SAVED} audit
     * event is emitted exactly once even though {@link #refreshAndGet} polls
     * the worker's {@code uploadState} repeatedly. Keyed {@code runId|workerId}.
     * This guards <em>intra-process</em> concurrency (e.g. two browser tabs, or
     * a UI poll racing the reconciliation sweeper). Cross-restart dedup is
     * handled durably by {@link RunEventRepository#resultsSavedWorkerIds}, read
     * at the top of {@code refreshAndGet} — so a restart no longer re-emits.
     */
    private final java.util.Set<String> resultsSavedEmitted =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RunService(
            RunRepository runs,
            RunEventRepository auditEvents,
            RunAuditWriter audit,
            PodRepository pods,
            ApplicationRepository applications,
            GroupCapacityRepository groupCapacity,
            LocalOrchestratorClient localClient,
            WorkerStatusFetcher statusFetcher,
            RunMetricsRepository runMetrics,
            MetricsGroupResolver metricsGroups,
            RunTrendRepository runTrends,
            PluginRepository pluginRepo,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.perf.globalorchestrator.provision.PodSpinService spinService,
            com.perf.globalorchestrator.provision.ProvisioningProperties provisioning,
            @Value("${globalOrchestrator.region:us-east-1}") String region,
            @Value("${globalOrchestrator.fanoutThreads:8}") int fanoutThreads,
            @Value("${globalOrchestrator.maxFleetSizePerRun:100}") int maxFleetSizePerRun,
            @Value("${globalOrchestrator.spinShortfall.healthTimeoutMs:60000}") long spinHealthTimeoutMs) {
        this.runs = runs;
        this.auditEvents = auditEvents;
        this.audit = audit;
        this.pods = pods;
        this.applications = applications;
        this.groupCapacity = groupCapacity;
        this.localClient = localClient;
        this.statusFetcher = statusFetcher;
        this.runMetrics = runMetrics;
        this.metricsGroups = metricsGroups;
        this.runTrends = runTrends;
        this.pluginRepo = pluginRepo;
        this.spinService = spinService;
        this.provisioning = provisioning;
        this.region = region;
        this.maxFleetSizePerRun = maxFleetSizePerRun;
        this.spinHealthTimeoutMs = spinHealthTimeoutMs;
        this.fanoutPool = Executors.newFixedThreadPool(Math.max(1, fanoutThreads),
                r -> {
                    Thread t = new Thread(r, "globalOrch-fanout");
                    t.setDaemon(true);
                    return t;
                });
    }

    public Run startRun(StartRunRequest request, boolean bestEffort, Actor actor) {
        validate(request);
        StartTransactionResult started;
        try {
            started = self.openRunAndClaimPods(request, bestEffort, actor, null);
        } catch (InsufficientCapacityException shortfallEx) {
            if (!request.isSpinShortfall() || spinService == null) {
                throw shortfallEx; // strict mode (or no provisioner wired) — propagate
            }
            if (provisioning.isStatic()) {
                LOG.info("spinShortfall ignored for application={} — {}=STATIC; "
                        + "operator must declare more workers for {}",
                        request.application(),
                        com.perf.globalorchestrator.provision.ProvisioningMode.PROPERTY,
                        shortfallEx.shortfall().keySet());
                throw shortfallEx;
            }
            return launchAsync(request, bestEffort, actor, shortfallEx.shortfall());
        }
        return completeLaunch(started, request);
    }

    /**
     * Fans the claimed members out and settles the run state: RUNNING when every
     * member accepted, FAILED when none did, RUNNING with a partial reason
     * otherwise.
     */
    private Run completeLaunch(StartTransactionResult started, StartRunRequest request) {
        Map<String, FanoutOutcome> outcomes = fanOut(
                started.runId(), started.members(),
                request.testPlanBlobId(), request.dataFilesBlobId(),
                request.application(), request.isSaveResults(), started.plugins());

        long accepted = outcomes.values().stream().filter(o -> o.state() == MemberState.ACCEPTED).count();
        if (accepted == started.members().size()) {
            runs.updateRunState(started.runId(), RunState.RUNNING, started.stateReason());
        } else if (accepted == 0) {
            int claimed = runs.updateRunStateClaimingTerminal(
                    started.runId(), RunState.FAILED, "all fan-outs rejected");
            runs.clearSaveResults(started.runId());
            if (claimed == 1) {
                recordRunTerminal(started.runId(), RunState.FAILED, "all fan-outs rejected");
            }
        } else {
            String reason = "partial fan-out: " + accepted + "/" + started.members().size() + " accepted";
            if (started.stateReason() != null) {
                reason = started.stateReason() + "; " + reason;
            }
            runs.updateRunState(started.runId(), RunState.RUNNING, reason);
        }
        return runs.findByRunId(started.runId()).orElseThrow();
    }

    /**
     * A launch that must provision workers first returns at once with the run
     * in PREPARING — provisioning is long (pod schedule + JVM boot; minutes
     * with an image pull) and must never hold an HTTP request open. A
     * background task spins the shortfall in parallel, updating
     * {@code stateReason} as workers become ready, then claims and fans out
     * exactly as a synchronous launch would; any failure lands as FAILED with
     * the reason. Abort during PREPARING wins: the task re-reads the run before
     * claiming and stands down if it is no longer PREPARING.
     */
    private Run launchAsync(StartRunRequest request, boolean bestEffort, Actor actor,
                            Map<String, RegionShortfall> shortfall) {
        String runId = Ulid.generate();
        Instant now = Instant.now();
        int total = shortfall.values().stream().mapToInt(g -> g.requested() - g.claimed()).sum();
        String reason = provisioningReason(total, 0, shortfall);
        Run preparing = new Run(
                runId, region,
                request.testPlanBlobId(), request.dataFilesBlobId(),
                (request.application() == null || request.application().isBlank()) ? null : request.application(),
                (request.initiatedBy() != null && !request.initiatedBy().isBlank()) ? request.initiatedBy() : actor.name(),
                RunState.PREPARING, reason, now, null, null,
                request.isSaveResults(), List.of(), metricsGroupOf(request.application()),
                resolvePlugins(request.pluginIds()));
        runs.insertRun(preparing);
        runs.updateRunState(runId, RunState.PREPARING, reason);
        LOG.info("run {} PREPARING — {}", runId, reason);
        launchPool.submit(() -> provisionThenLaunch(runId, request, bestEffort, actor, shortfall, total));
        return runs.findByRunId(runId).orElseThrow();
    }

    private void provisionThenLaunch(String runId, StartRunRequest request, boolean bestEffort, Actor actor,
                                     Map<String, RegionShortfall> shortfall, int total) {
        try {
            spinToFillShortfall(request.application(), shortfall,
                    ready -> runs.updateRunState(runId, RunState.PREPARING, provisioningReason(total, ready, shortfall)));
            Run current = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
            if (current.state() != RunState.PREPARING) {
                LOG.info("run {} left PREPARING ({}) while provisioning — not launching", runId, current.state());
                return;
            }
            StartTransactionResult started = self.openRunAndClaimPods(request, bestEffort, actor, runId);
            completeLaunch(started, request);
        } catch (RunNotPreparingException gone) {
            LOG.info("run {} was aborted while its workers were provisioned — claim rolled back", runId);
        } catch (RuntimeException e) {
            String why = "provisioning failed: " + e.getMessage();
            int claimed = runs.updateRunStateClaimingTerminal(runId, RunState.FAILED, why);
            runs.clearSaveResults(runId);
            if (claimed == 1) {
                recordRunTerminal(runId, RunState.FAILED, why);
            }
            ErrorContext.logWarn(LOG, "launch runId=" + runId, "async launch failed", e);
        }
    }

    /** The run left PREPARING (an abort) before its async claim could land; the claim transaction rolls back. */
    public static final class RunNotPreparingException extends RuntimeException {
        RunNotPreparingException(String runId) {
            super("run " + runId + " is no longer PREPARING");
        }
    }

    static String provisioningReason(int total, int ready, Map<String, RegionShortfall> shortfall) {
        String gaps = shortfall.entrySet().stream()
                .map(e -> e.getKey() + " " + (e.getValue().requested() - e.getValue().claimed()))
                .collect(Collectors.joining(", "));
        return "provisioning " + total + " worker(s) (" + gaps + ") — " + ready + "/" + total + " ready";
    }

    /**
     * PREPARING runs older than {@code olderThan} are launches whose provisioning
     * task is gone — an orchestrator restart, or a spin that never came back.
     * Fails them with a reason so they stop looking alive.
     */
    public int failStalePreparingRuns(Duration olderThan) {
        List<String> stale = runs.failStalePreparing(Instant.now().minus(olderThan),
                "provisioning did not complete within " + olderThan.toMinutes() + " min (orchestrator restarted?)");
        for (String runId : stale) {
            runs.clearSaveResults(runId);
            recordRunTerminal(runId, RunState.FAILED, "provisioning did not complete");
        }
        return stale.size();
    }

    /**
     * Spins enough pods to fill each region's
     * shortfall, then polls the new pods' actuator/health until they're
     * reachable. The caller (startRun) then retries the claim.
     *
     * <p>Cap-check: per region, if {@code provisioned + gap > maxAvailable},
     * surface {@link GroupCapacityExceededException} so the UI can
     * tell the operator "raise the ceiling first." Bypasses the spin —
     * partial filling would leave the operator confused and the cap rule
     * is sponsor-mandated.
     */
    private void spinToFillShortfall(String applicationName,
                                     Map<String, RegionShortfall> shortfall) {
        spinToFillShortfall(applicationName, shortfall, ready -> { });
    }

    private void spinToFillShortfall(String applicationName,
                                     Map<String, RegionShortfall> shortfall,
                                     java.util.function.IntConsumer progress) {
        if (applicationName == null || applicationName.isBlank()) {
            // Spin-to-fill is only meaningful for registered apps — the
            // legacy (any) bucket has no application context to spin into.
            throw new IllegalStateException(
                    "spinShortfall requires a registered application; got null/blank");
        }
        com.perf.globalorchestrator.domain.Application boundApp =
                applications.findByName(applicationName)
                        .orElseThrow(() -> new IllegalStateException(
                                "application '" + applicationName + "' not registered"));

        // (1) Cap-check every region BEFORE spinning anything — atomic
        // pre-flight so a partial spin doesn't land us in a half-filled
        // state when a ceiling is breached late in the loop.
        for (Map.Entry<String, RegionShortfall> e : shortfall.entrySet()) {
            String regionName = e.getKey();
            int gap = e.getValue().requested() - e.getValue().claimed();
            int provisioned = pods.countByGroupAndRegion(boundApp.metricsGroupId(), regionName);
            int max = groupCapacity.find(boundApp.metricsGroupId(), regionName)
                    .map(c -> c.maxAvailable())
                    .orElse(0);
            if (provisioned + gap > max) {
                throw new GroupCapacityExceededException(
                        boundApp.metricsGroupId(), regionName, max, provisioned, gap,
                        "spinShortfall would exceed maxAvailable — operator must raise the group's ceiling first");
            }
            // Track 8: the region's namespace quota, as it last reported it —
            // refuse before reserving names and creating Pods that would only Pend.
            spinService.assertCapacity(regionName, gap);
        }

        // (2) Spin per region. Collect the new pods' baseUrls so we can
        // gate the retry on their readiness.
        // All pods at once — a spin waits for its pod's readiness probe, so
        // serial spins would cost N × (schedule + JVM boot).
        List<WorkerRef> newWorkers = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger ready = new java.util.concurrent.atomic.AtomicInteger();
        // Names first, serially — the allocator reads the registry, so
        // reservations must land one at a time; the slow part is parallel.
        List<Map.Entry<String, String>> reserved = new ArrayList<>();
        for (Map.Entry<String, RegionShortfall> e : shortfall.entrySet()) {
            int gap = e.getValue().requested() - e.getValue().claimed();
            for (int i = 0; i < gap; i++) {
                reserved.add(Map.entry(e.getKey(),
                        spinService.reserve(boundApp.metricsGroupId(), e.getKey())));
            }
        }
        List<java.util.concurrent.Future<?>> spins = new ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<String, String> entry : reserved) {
                String regionName = entry.getKey();
                String podName = entry.getValue();
                {
                    spins.add(pool.submit(() -> {
                        com.perf.globalorchestrator.provision.PodSpinService.SpinResult r =
                                spinService.start(boundApp.metricsGroupId(), regionName, podName);
                        newWorkers.add(new WorkerRef(regionName, r.podName(), r.baseUrl()));
                        LOG.info("spinShortfall: spun {} for group={} app={} region={} ready={}",
                                r.podName(), boundApp.metricsGroupId(), boundApp.name(), regionName, r.ready());
                        if (r.ready()) progress.accept(ready.incrementAndGet());
                    }));
                }
            }
            RuntimeException first = null;
            for (java.util.concurrent.Future<?> f : spins) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    RuntimeException cause = ex.getCause() instanceof RuntimeException re
                            ? re : new IllegalStateException(ex.getCause());
                    if (first == null) first = cause;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while provisioning", ie);
                }
            }
            if (first != null) throw first;
        }

        // A pod that reported ready is IDLE and claimable; one that did not is
        // still LOST and will not be claimed, so the retry's shortfall fails the
        // launch with the reason. No second health wait — readiness was the wait.
    }


    /**
     * Adds workers to a RUNNING run.
     *
     * <p>Validations (outside the transaction):
     * <ul>
     *   <li>Run exists; otherwise 404 {@link RunNotFoundException}.</li>
     *   <li>Run state is RUNNING; PREPARING/STARTING throws
     *       {@link RunNotInScalableStateException} (let the original fan-out
     *       finish first); terminal states throw the same.</li>
     *   <li>Run has a non-null {@code application} so the per-(app, region)
     *       capacity gate has something to gate against. Legacy untagged
     *       runs throw {@link RunNotScalableNoApplicationException}.</li>
     *   <li>Allocations non-empty, regions non-blank, counts > 0, no
     *       duplicate regions, current member count + new requested ≤
     *       {@code maxFleetSizePerRun}.</li>
     * </ul>
     *
     * <p>The claim + INSERT phase runs inside one transaction (same shape
     * as {@link #openRunAndClaimPods}). New {@code runFleetMember} rows
     * are stamped with {@code joinedAtSecond = now - run.startedAt} so
     * the consumer + UI can distinguish original-fleet members from
     * mid-test joiners.
     *
     * <p>Fan-out happens outside the transaction. Run state stays RUNNING
     * regardless of fan-out outcome — failed members are individually
     * marked FAILED but the run itself continues with whatever joined
     * successfully + the original fleet.
     */
    public ScaleUpRunResponse scaleUpRun(String runId, ScaleUpRunRequest request,
                                         boolean bestEffort, boolean spinShortfall, Actor actor) {
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        // The run exists from here on, so a rejected
        // action gets a SCALE_UP event with result "rejected:CODE". (A
        // RunNotFound above does NOT: there is nothing to FK the event to,
        // and an action against a non-existent run isn't auditable.) Only
        // the known business rejections are caught + recorded; an unexpected
        // failure propagates uncaught so the rolled-back-mutation invariant
        // (decision #7) holds — zero events.
        try {
            if (run.state() != RunState.RUNNING) {
                throw new RunNotInScalableStateException(runId, run.state());
            }
            if (run.application() == null || run.application().isBlank()) {
                throw new RunNotScalableNoApplicationException(runId);
            }
            validateScaleUpAllocations(request.allocations());
            int requestedTotal = request.allocations().stream().mapToInt(FleetAllocationEntry::count).sum();
            int currentMembers = run.fleetMembers().size();
            if (currentMembers + requestedTotal > maxFleetSizePerRun) {
                throw new FleetSizeExceededException(currentMembers + requestedTotal, maxFleetSizePerRun);
            }

            // joinedAtSecond is computed at request-time, not per-member. All
            // members claimed in this scaleUp share the same epoch — they
            // were all "asked for" at the same wall-clock instant; their
            // individual fan-out timing is reflected by createdAt + startedAt.
            Instant runStart = run.startedAt() != null ? run.startedAt() : run.createdAt();
            long joinedAtSecond = Math.max(0L,
                    (Instant.now().toEpochMilli() - runStart.toEpochMilli()) / 1000L);

            // The SCALE_UP success event is written INSIDE this transactional
            // method (atomic with the new member rows). Routed via self so the
            // @Transactional proxy actually applies.
            ScaleUpTransactionResult opened;
            try {
                opened = self.openMembersInExistingRun(
                        run, request.allocations(), joinedAtSecond, bestEffort, actor);
            } catch (InsufficientCapacityException shortfallEx) {
                // Not enough IDLE pods to satisfy the request. With
                // spinShortfall the operator accepted provisioning the gap —
                // spin the missing pods (subject to the per-(app, region)
                // capacity ceiling), wait for health, then retry the claim.
                // Same machinery + cap-check as startRun's spin path. The
                // failed claim was @Transactional → already rolled back (no
                // member rows, no SCALE_UP event), so the retry is clean.
                if (!spinShortfall || spinService == null) {
                    throw shortfallEx; // strict mode (or no provisioner wired) — propagate
                }
                spinToFillShortfall(run.application(), shortfallEx.shortfall());
                opened = self.openMembersInExistingRun(
                        run, request.allocations(), joinedAtSecond, bestEffort, actor);
            }

            // Fan-out — same machinery as initial start, sourced from the
            // run row's persisted blob IDs.
            Map<String, FanoutOutcome> outcomes = fanOut(
                    runId, opened.members(),
                    run.testPlanBlobId(), run.dataFilesBlobId(),
                    run.application(), run.saveResults(), run.plugins());

            long accepted = outcomes.values().stream().filter(o -> o.state() == MemberState.ACCEPTED).count();
            // Append a one-line scale-up note to stateReason so the run-detail
            // page surfaces "+N joined at +Xs (M accepted)" without parsing
            // member rows. Keeps existing reason if one was set.
            String scaleUpNote = "scaleUp at +" + joinedAtSecond + "s: requested " + requestedTotal
                    + ", granted " + opened.members().size()
                    + ", accepted " + accepted;
            if (opened.stateReason() != null) {
                scaleUpNote = opened.stateReason() + "; " + scaleUpNote;
            }
            String existingReason = run.stateReason();
            String composed = existingReason == null ? scaleUpNote : existingReason + " | " + scaleUpNote;
            runs.updateRunState(runId, RunState.RUNNING, composed);

            Run updated = runs.findByRunId(runId).orElseThrow();
            boolean partial = opened.members().size() < requestedTotal;
            return new ScaleUpRunResponse(updated, requestedTotal, opened.members().size(),
                    partial, opened.stateReason());
        } catch (RunNotInScalableStateException e) {
            recordScaleUpRejected(runId, request, bestEffort, actor, "RUN_NOT_SCALABLE");
            throw e;
        } catch (RunNotScalableNoApplicationException e) {
            recordScaleUpRejected(runId, request, bestEffort, actor, "RUN_NOT_SCALABLE_NO_APPLICATION");
            throw e;
        } catch (FleetSizeExceededException e) {
            recordScaleUpRejected(runId, request, bestEffort, actor, "FLEET_SIZE_EXCEEDED");
            throw e;
        } catch (GroupCapacityExceededException e) {
            recordScaleUpRejected(runId, request, bestEffort, actor, "APPLICATION_CAPACITY_EXCEEDED");
            throw e;
        } catch (InsufficientCapacityException e) {
            recordScaleUpRejected(runId, request, bestEffort, actor, "INSUFFICIENT_CAPACITY");
            throw e;
        } catch (IllegalArgumentException e) {
            recordScaleUpRejected(runId, request, bestEffort, actor, "INVALID_REQUEST");
            throw e;
        }
    }

    /**
     * Atomic claim-and-write phase. Resolves the {@code fleetAllocation}
     * (or its legacy fallback), validates region identifiers, applies
     * the total-fleet cap, then claims per-region IDLE pods inside a
     * single transaction. Strict mode throws on any shortfall;
     * best-effort persists the partial claim and surfaces the deficit
     * on the run's {@code stateReason}.
     */
    @Transactional("transactionManager")
    protected StartTransactionResult openRunAndClaimPods(StartRunRequest request, boolean bestEffort, Actor actor) {
        return openRunAndClaimPods(request, bestEffort, actor, null);
    }

    /**
     * {@code existingRunId} non-null: the run row already exists in PREPARING
     * (an async launch) — claim into it instead of inserting a new one.
     */
    @Transactional("transactionManager")
    protected StartTransactionResult openRunAndClaimPods(StartRunRequest request, boolean bestEffort, Actor actor,
                                                         String existingRunId) {
        List<FleetAllocationEntry> allocation = resolveAllocation(request);
        // Resolve BEFORE any claim so an unknown pluginId rejects the launch
        // with 400 while nothing is held; the resolved refs are the run's
        // permanent snapshot (immune to later registry deletes).
        List<PluginRef> plugins = resolvePlugins(request.pluginIds());
        boolean ignoredFleetSize = !request.fleetAllocation().isEmpty() && request.fleetSize() > 0;

        int totalRequested = allocation.isEmpty()
                ? request.fleetSize()
                : allocation.stream().mapToInt(FleetAllocationEntry::count).sum();
        if (totalRequested > maxFleetSizePerRun) {
            throw new FleetSizeExceededException(totalRequested, maxFleetSizePerRun);
        }

        // D-Capacity v2 — per-(app, region) max-pod budget. Enforced
        // BEFORE pod claim so a rejected run never holds capacity.
        // Apps not present in the registry skip enforcement entirely
        // (backwards compat for API callers that haven't migrated).
        // For registered apps with allocation entries, every region in
        // the allocation must have a configured capacity row; missing
        // → 409 with "no capacity configured for this app in <region>".
        //
        // Phase 4/6b capacity rework: hoist the application lookup so the
        // resolved Application is reusable downstream. As of Phase 6b every
        // allocation-based claim goes through claimIdleByRegionAndApp (the
        // per-app pod pool) — the legacy null-app pool and its
        // claimIdleByRegion fallback were removed in Phase 6 / 6b.
        com.perf.globalorchestrator.domain.Application boundApp = null;
        if (request.application() != null && !request.application().isBlank()) {
            boundApp = applications.findByName(request.application()).orElse(null);
        }
        if (!allocation.isEmpty()) {
            // An allocation-based run claims from its application's GROUP pool
            // (GROUP-CAPACITY), so it MUST resolve to a registered application.
            // A run naming no app (or an unregistered one) has no pool to claim
            // from — reject it up front with the same 409 surface as a
            // capacity miss. For a registered app the per-region capacity-grid
            // check below is the authoritative region validator (a
            // never-configured region yields a clearer 409 "no capacity
            // configured ... in region X").
            if (boundApp == null) {
                String name = (request.application() == null || request.application().isBlank())
                        ? "(none)" : request.application();
                throw new GroupCapacityExceededException(
                        name, allocation.get(0).region(), 0, 0, allocation.get(0).count(),
                        "run targets unregistered application '" + name + "'; allocation-based "
                        + "runs require a registered application whose group has configured capacity");
            }
            String groupId = boundApp.metricsGroupId();
            for (FleetAllocationEntry e : allocation) {
                var capacityRow = groupCapacity.find(groupId, e.region());
                if (capacityRow.isEmpty()) {
                    throw new GroupCapacityExceededException(
                            groupId, e.region(), 0, 0, e.count(),
                            "no capacity configured for group '" + groupId + "' in region '" + e.region() + "'");
                }
                int max = capacityRow.get().maxAvailable();
                int active = groupCapacity.countActivePodsForGroupRegion(groupId, e.region());
                if (active + e.count() > max) {
                    throw new GroupCapacityExceededException(groupId, e.region(), max, active, e.count(), null);
                }
            }
        }

        List<Pod> claimed = new ArrayList<>();
        // Track G (Step 31) — properties tagged onto each pod by index
        // within its region's allocation. Map keyed by podId so the
        // fan-out + member-row INSERT can both read it without re-
        // computing the position.
        Map<String, Map<String, String>> propertiesByPodId = new LinkedHashMap<>();
        Map<String, RegionShortfall> shortfall = new LinkedHashMap<>();

        if (allocation.isEmpty()) {
            // Legacy cross-region claim: pick freshest-heartbeat pods
            // regardless of region. Matches Step 14/15 behavior.
            List<Pod> regionClaim = pods.claimIdle(request.fleetSize());
            claimed.addAll(regionClaim);
            if (regionClaim.size() < request.fleetSize()) {
                shortfall.put("(any)",
                        new RegionShortfall(request.fleetSize(), regionClaim.size()));
            }
        } else {
            for (FleetAllocationEntry e : allocation) {
                // Every allocation-based run claims from its application's
                // group pool. boundApp is guaranteed non-null here — the
                // validation above rejects an allocation run that names no
                // registered application.
                List<Pod> regionClaim = pods.claimIdleByGroup(
                        e.region(), boundApp.metricsGroupId(), e.count());
                for (int i = 0; i < regionClaim.size(); i++) {
                    Pod p = regionClaim.get(i);
                    Map<String, String> props = e.propertiesFor(i);
                    if (!props.isEmpty()) {
                        propertiesByPodId.put(p.podId(), props);
                    }
                }
                claimed.addAll(regionClaim);
                if (regionClaim.size() < e.count()) {
                    shortfall.put(e.region(),
                            new RegionShortfall(e.count(), regionClaim.size()));
                }
            }
        }

        if (!shortfall.isEmpty()) {
            // Strict mode → roll back. Best-effort with zero claimed
            // also rolls back — a run with no pods is not useful.
            if (!bestEffort || claimed.isEmpty()) {
                throw new InsufficientCapacityException(shortfall);
            }
        }

        String stateReason = null;
        if (!shortfall.isEmpty() && bestEffort) {
            stateReason = "bestEffort claim — " + InsufficientCapacityException.formatShortfall(shortfall);
        }
        if (ignoredFleetSize) {
            String note = "ignored legacy fleetSize in favor of fleetAllocation";
            stateReason = stateReason == null ? note : stateReason + "; " + note;
        }

        String runId = existingRunId != null ? existingRunId : Ulid.generate();
        Instant now = Instant.now();
        Run run = new Run(
                runId, region,
                request.testPlanBlobId(),
                request.dataFilesBlobId(),
                // UI-D3 — empty/blank `application` is normalised to NULL so
                // the LIKE filter doesn't accidentally match an empty string.
                (request.application() == null || request.application().isBlank())
                        ? null : request.application(),
                // `initiatedBy` is the actor (X-Actor / cached
                // operator name) by default; the launcher no longer asks for
                // it separately. An explicit body value still wins for
                // programmatic callers that set their own.
                (request.initiatedBy() != null && !request.initiatedBy().isBlank())
                        ? request.initiatedBy()
                        : actor.name(),
                RunState.PREPARING,
                stateReason,
                now, null, null,
                request.isSaveResults(),
                List.of(),
                metricsGroupOf(request.application()),
                plugins);
        if (existingRunId == null) {
            runs.insertRun(run);
        }

        List<RunFleetMember> initialMembers = new ArrayList<>(claimed.size());
        for (Pod p : claimed) {
            Map<String, String> props = propertiesByPodId.getOrDefault(p.podId(), Map.of());
            RunFleetMember m = new RunFleetMember(
                    runId, p.podId(), p.region(), MemberState.PENDING,
                    null, null, p.baseUrl(), now, null, null, props);
            runs.insertFleetMember(m);
            initialMembers.add(m);
            // Bump the per-pod run counter inside
            // the run-claim transaction so the value is consistent with
            // runFleetMember insert rate. Phase D's reconciler reads this
            // to decide "this pod has done N runs; recycle it on idle."
            pods.incrementRunsServed(p.podId());
        }
        if (existingRunId != null) {
            // The launch is async: an abort may have landed while the workers were
            // provisioned. Only a run still PREPARING may become STARTING; anything
            // else rolls this claim back.
            if (runs.updateRunStateFrom(runId, RunState.PREPARING, RunState.STARTING, stateReason) == 0) {
                throw new RunNotPreparingException(runId);
            }
        } else {
            runs.updateRunState(runId, RunState.STARTING, stateReason);
        }

        // One RUN_START event, written inside this
        // transaction so it commits atomically with the run + member rows. A
        // strict-mode shortfall threw above (before insertRun), so this line
        // is only reached when the run was actually opened; a forced failure
        // here rolls the whole thing back → zero events (decision #7).
        int granted = claimed.size();
        List<RegionCount> allocView = allocation.isEmpty()
                ? List.of(new RegionCount("(any)", request.fleetSize()))
                : allocation.stream().map(e -> new RegionCount(e.region(), e.count())).toList();
        recordEvent(runId, RunEventType.RUN_START, actor,
                new RunEventPayloads.RunStart(run.application(), allocView, totalRequested, granted),
                granted >= totalRequested ? "ok" : "partial");

        return new StartTransactionResult(runId, initialMembers, stateReason, plugins);
    }

    /**
     * Reduces the request to a list of {@code (region, count)} entries
     * or an empty list to signal cross-region legacy behavior.
     *
     * <ul>
     *   <li>{@code fleetAllocation} present (non-empty) → use it as-is
     *       after validating duplicates / non-positive counts / blank
     *       region names.</li>
     *   <li>{@code fleetAllocation} absent but {@code regions} has
     *       exactly one entry → fold into a single allocation entry
     *       with {@code fleetSize} as the count.</li>
     *   <li>Otherwise → empty list (legacy cross-region
     *       {@code claimIdle(fleetSize)} path).</li>
     * </ul>
     */
    private static List<FleetAllocationEntry> resolveAllocation(StartRunRequest request) {
        if (!request.fleetAllocation().isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (FleetAllocationEntry e : request.fleetAllocation()) {
                if (e.region() == null || e.region().isBlank()) {
                    throw new IllegalArgumentException(
                            "fleetAllocation entry is missing region");
                }
                if (e.count() <= 0) {
                    throw new IllegalArgumentException(
                            "fleetAllocation count must be > 0 (region=" + e.region() + ")");
                }
                if (!seen.add(e.region())) {
                    throw new IllegalArgumentException(
                            "duplicate region in fleetAllocation: " + e.region());
                }
            }
            return request.fleetAllocation();
        }
        if (request.regions().size() == 1) {
            int count = Math.max(1, request.fleetSize());
            return List.of(new FleetAllocationEntry(request.regions().get(0), count));
        }
        return List.of();
    }

    /**
     * Drains workers from a RUNNING run.
     *
     * <p>Validations:
     * <ul>
     *   <li>Run exists; otherwise 404 {@link RunNotFoundException}.</li>
     *   <li>Run state is RUNNING; otherwise 409
     *       {@link RunNotInScalableStateException} (same gate as scaleUp).</li>
     *   <li>Request supplies exactly one of {@code workerIds} or
     *       {@code allocations}; otherwise 400.</li>
     *   <li>For {@code workerIds}: every id must reference a member of
     *       this run; non-members → 400. Already-terminal members are
     *       silently skipped (recorded in response.skipped).</li>
     *   <li>For {@code allocations}: per-region count > 0; the service
     *       picks the N most-recently-created (youngest-first) RUNNING
     *       members in that region. Capacity-style shortfall — fewer
     *       drainable members than requested — surfaces as a
     *       {@code skipped} entry with reason {@code "insufficient_workers"}.</li>
     * </ul>
     *
     * <p>For each target: PATCH the local-orch's drain endpoint, set
     * member state to DRAINING immediately. The local poll path
     * ({@link #refreshAndGet}) is sticky on DRAINING so the operator
     * sees the state until the eventual terminal arrives.
     *
     * <p>Run state stays RUNNING — drain doesn't end the run on its own.
     */
    public ScaleDownRunResponse scaleDownRun(String runId, ScaleDownRunRequest request, Actor actor) {
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        // The run exists, so each rejection below records
        // a rejected event before throwing (RunNotFound above does not — there
        // is nothing to FK the event to). scaleDownRun is not transactional, so
        // the SCALE_DOWN / DRAIN_WORKER outcome event is written post-hoc at the
        // return sites with the actual drained/skipped ledger.
        if (run.state() != RunState.RUNNING) {
            recordScaleDownRejected(runId, request, actor, "RUN_NOT_SCALABLE");
            throw new RunNotInScalableStateException(runId, run.state());
        }
        if (!request.isExclusive()) {
            recordScaleDownRejected(runId, request, actor, "INVALID_REQUEST");
            throw new IllegalArgumentException(
                    "scaleDown requires exactly one of workerIds or allocations");
        }

        // Resolve which members to drain.
        List<RunFleetMember> targets = new ArrayList<>();
        List<ScaleDownRunResponse.SkippedTarget> skipped = new ArrayList<>();

        if (!request.workerIds().isEmpty()) {
            for (String workerId : request.workerIds()) {
                RunFleetMember m = run.fleetMembers().stream()
                        .filter(x -> workerId.equals(x.workerId()))
                        .findFirst()
                        .orElse(null);
                if (m == null) {
                    recordScaleDownRejected(runId, request, actor, "INVALID_REQUEST");
                    throw new IllegalArgumentException(
                            "workerId '" + workerId + "' is not a member of run " + runId);
                }
                if (m.state().isTerminal()) {
                    skipped.add(new ScaleDownRunResponse.SkippedTarget(workerId,
                            "already terminal: " + m.state()));
                    continue;
                }
                if (m.state() == MemberState.DRAINING) {
                    skipped.add(new ScaleDownRunResponse.SkippedTarget(workerId,
                            "already DRAINING"));
                    continue;
                }
                targets.add(m);
            }
        } else {
            // allocations path — youngest-by-default per region.
            for (FleetAllocationEntry e : request.allocations()) {
                if (e.region() == null || e.region().isBlank() || e.count() <= 0) {
                    recordScaleDownRejected(runId, request, actor, "INVALID_REQUEST");
                    throw new IllegalArgumentException(
                            "scaleDown allocation entries must have non-blank region + count > 0");
                }
                List<RunFleetMember> drainable = run.fleetMembers().stream()
                        .filter(m -> e.region().equals(m.region()))
                        .filter(m -> m.state() == MemberState.RUNNING || m.state() == MemberState.ACCEPTED)
                        .sorted(Comparator.comparing(
                                RunFleetMember::createdAt,
                                Comparator.nullsFirst(Comparator.reverseOrder())))
                        .toList();
                int picked = Math.min(drainable.size(), e.count());
                if (picked < e.count()) {
                    skipped.add(new ScaleDownRunResponse.SkippedTarget(e.region(),
                            "insufficient_workers: requested " + e.count()
                                    + ", drainable " + picked));
                }
                targets.addAll(drainable.subList(0, picked));
            }
        }

        if (targets.isEmpty()) {
            // Nothing to do — but not an error; surface the skipped list so
            // the caller knows why. Still audited: the operator did attempt a
            // drain, and the skipped reasons explain why it was a no-op.
            recordScaleDownOutcome(runId, request, actor, List.of(), skipped);
            return new ScaleDownRunResponse(runs.findByRunId(runId).orElseThrow(),
                    List.of(), skipped);
        }

        // Mark DRAINING + fire drain RPCs in parallel via the existing
        // fan-out pool. The drain endpoint is async on the local-orch
        // side (returns 202 immediately), so we don't block on terminal
        // convergence here.
        //
        // Operator-found 2026-05-15 timing-bug fix: do NOT pre-mark
        // DRAINING on every target before the RPC. Original behavior
        // claimed DRAINING optimistically and revealed a stale-image
        // failure mode — when the local-orch pod was running an older
        // image without the /test/drain endpoint, the RPC 404'd, the
        // operator's drain "succeeded" visually (state=DRAINING), but
        // the test actually ran to natural completion → state quietly
        // flipped to COMPLETED via the next status poll. Confusing.
        // Now: fire the RPCs first, then update state ONLY for targets
        // whose RPC was accepted. Failed targets surface in skipped[]
        // with their state untouched (operator can see they're still
        // RUNNING and re-attempt or take other action).
        List<String> drained = new ArrayList<>(targets.size());
        Map<String, java.util.concurrent.Future<LocalOrchestratorClient.DrainTestResult>> futures =
                new LinkedHashMap<>();
        for (RunFleetMember m : targets) {
            final WorkerRef worker = WorkerRef.of(m);
            futures.put(m.workerId(), fanoutPool.submit(() -> localClient.drainTest(runId, worker)));
        }
        futures.forEach((workerId, f) -> {
            try {
                LocalOrchestratorClient.DrainTestResult r = f.get();
                if (r.accepted() || r.ok()) {
                    // Drain endpoint accepted — NOW we can claim DRAINING.
                    runs.updateMemberState(runId, workerId, MemberState.DRAINING, "scaleDown", null);
                    drained.add(workerId);
                } else if (r.statusCode() == 404) {
                    // Local-orch says NO_ACTIVE_RUN — could be either
                    // (a) race with natural end of test on that pod, or
                    // (b) the pod runs an older image without the drain
                    //     endpoint at all. Either way: state stays as-is
                    //     (typically RUNNING) so the operator sees the
                    //     real situation in the table.
                    skipped.add(new ScaleDownRunResponse.SkippedTarget(workerId,
                            "local-orch returned 404 (drain endpoint missing on this pod, or test already ended)"));
                } else {
                    skipped.add(new ScaleDownRunResponse.SkippedTarget(workerId,
                            "drain RPC failed: status=" + r.statusCode()));
                }
            } catch (Exception e) {
                ErrorContext.logWarn(LOG,
                        "scaleDownDrain runId=" + runId + " workerId=" + workerId,
                        "drain future threw",
                        e);
                skipped.add(new ScaleDownRunResponse.SkippedTarget(workerId,
                        "drain RPC threw: " + e.getClass().getSimpleName()));
            }
        });

        Run updated = runs.findByRunId(runId).orElseThrow();
        recordScaleDownOutcome(runId, request, actor, drained, skipped);
        return new ScaleDownRunResponse(updated, drained, skipped);
    }

    /**
     * Force-terminates a run and releases every fleet-member binding, freeing
     * its pods for re-claim — the operator's "stop now" and the way a zombie run
     * gets cleaned up. Unlike {@link #scaleDownRun}, the whole run goes ABORTED.
     *
     * <p>Aborting an already-terminal run is a 409 rather than a no-op, so a
     * double-click cannot emit a second ABORT event. The per-member
     * {@code POST /api/v1/test/abort} calls fire outside the transaction; a
     * zombie run's workers are gone, so those fail into the skipped ledger and
     * the run is force-aborted regardless. The state change itself — members
     * ABORTED, run ABORTED, one audit event — is a single transaction, so a
     * rollback emits nothing.
     *
     * <p><b>Marking the members terminal is load-bearing, not cosmetic.</b> The
     * pod-claim {@code NOT EXISTS} guard, {@code countActivePodsForAppRegion}
     * and {@link #rollUp} all key on member state. A run left ABORTED with
     * members still RUNNING leaves its pods unclaimable, and can self-downgrade
     * back to RUNNING on the next status poll of a saveResults run when rollUp
     * sees a live member.
     */
    public Run abortRun(String runId, Actor actor, String reason) {
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        if (run.state().isTerminal()) {
            throw new RunNotAbortableException(runId, run.state());
        }

        // Targets = members not yet terminal. Fire the hard-kill RPC to each in
        // parallel; the ledger records who acked vs. who was unreachable. The
        // run is force-aborted either way — the RPC is a courtesy so a live
        // worker stops its JMeter child promptly.
        List<RunFleetMember> targets = run.fleetMembers().stream()
                .filter(m -> m.state() == null || !m.state().isTerminal())
                .toList();
        // A PREPARING launch has no members yet: mark it ABORTED and the
        // provisioning task stands down when it re-reads the state.
        List<String> aborted = new ArrayList<>();
        List<RunEventPayloads.Skipped> skipped = new ArrayList<>();
        Map<String, Future<LocalOrchestratorClient.AbortTestResult>> futures = new LinkedHashMap<>();
        for (RunFleetMember m : targets) {
            if (m.podBaseUrl() == null) {
                skipped.add(new RunEventPayloads.Skipped(m.workerId(), "no podBaseUrl recorded"));
                continue;
            }
            final WorkerRef worker = WorkerRef.of(m);
            futures.put(m.workerId(), fanoutPool.submit(() -> localClient.abortTest(runId, worker)));
        }
        futures.forEach((workerId, f) -> {
            try {
                LocalOrchestratorClient.AbortTestResult r = f.get();
                // 404 = local-orch has no active run (already stopped). For an
                // abort that's success (the worker is not running), not a skip.
                if (r.accepted() || r.ok() || r.statusCode() == 404) {
                    aborted.add(workerId);
                } else {
                    skipped.add(new RunEventPayloads.Skipped(workerId,
                            "abort RPC unreachable/failed: status=" + r.statusCode()));
                }
            } catch (Exception e) {
                ErrorContext.logWarn(LOG,
                        "abortRun runId=" + runId + " workerId=" + workerId,
                        "abort future threw", e);
                skipped.add(new RunEventPayloads.Skipped(workerId,
                        "abort RPC threw: " + e.getClass().getSimpleName()));
            }
        });

        // Commit the terminal transition + audit event atomically, through the
        // self proxy so @Transactional actually applies (proxy-mode limitation).
        self.commitAbort(runId,
                targets.stream().map(RunFleetMember::workerId).toList(),
                reason, actor, aborted, skipped);
        return runs.findByRunId(runId).orElseThrow();
    }

    /**
     * Transactional tail of {@link #abortRun}: marks the given members ABORTED,
     * rolls the run to ABORTED, and appends the single ABORT event. One
     * transaction so a forced failure rolls back the state change AND the event
     * together. The ABORT event is attributed to the operator ({@code actor}) —
     * the manual abort is the cause of the terminal transition, so we do NOT
     * also emit a system {@code RUN_ABORTED} (that's reserved for
     * platform-detected terminal transitions in {@link #refreshAndGet}).
     */
    @Transactional("transactionManager")
    protected void commitAbort(String runId, List<String> memberWorkerIds, String reason,
                               Actor actor, List<String> aborted,
                               List<RunEventPayloads.Skipped> skipped) {
        String memberReason = "runAborted" + (reason == null || reason.isBlank() ? "" : ": " + reason);
        for (String workerId : memberWorkerIds) {
            runs.updateMemberState(runId, workerId, MemberState.ABORTED, memberReason, null);
        }
        String runReason = "aborted by " + actor.name()
                + (reason == null || reason.isBlank() ? "" : ": " + reason);
        runs.updateRunState(runId, RunState.ABORTED, runReason);
        // Save Results — an aborted run never produced a clean upload (the
        // local-orch skips the JTL upload on a non-COMPLETE stop), so clear the
        // flag in the same tx: the UI stops offering a Download-that-404s and
        // refreshAndGet's terminal fast-path stops polling the now-dead workers.
        runs.clearSaveResults(runId);
        recordEvent(runId, RunEventType.ABORT, actor,
                new RunEventPayloads.Abort(reason, aborted, skipped),
                skipped.isEmpty() ? "ok" : "partial");
    }

    /**
     * Soft-delete ("hide") a run so it drops out of the default listing — the
     * declutter primitive ("let users only see the runs that are important").
     * Only TERMINAL runs can be hidden (an active run is, by definition, still
     * important and pins live pods); a non-terminal run raises
     * {@link RunNotDeletableException} (409). This is reversible — the row,
     * fleet members, and audit trail are RETAINED; the DELETE audit event
     * recorded here captures who hid it, when, and why.
     *
     * <p>404 {@code RUN_NOT_FOUND} for an unknown run. Returns the (now-hidden)
     * run so the caller can confirm the prior state.
     */
    public Run deleteRun(String runId, Actor actor, String reason) {
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        if (!run.state().isTerminal()) {
            throw new RunNotDeletableException(runId, run.state());
        }
        // Commit the audit event + hide atomically through the self proxy so
        // @Transactional applies (proxy-mode self-invocation limitation) — a
        // failure rolls back BOTH (the "rolled-back mutation emits zero events"
        // invariant).
        self.commitDelete(runId, actor, reason);
        return runs.findByRunId(runId).orElseThrow();
    }

    /**
     * Transactional tail of {@link #deleteRun}: append the {@code DELETE} audit
     * event, then flip {@code hiddenAt}. Recorded BEFORE the hide so the event's
     * {@code runId} FK is valid; soft-delete keeps the {@code runEvent} rows
     * intact (a hard delete would cascade them away, defeating the audit).
     */
    @Transactional("transactionManager")
    protected void commitDelete(String runId, Actor actor, String reason) {
        recordEvent(runId, RunEventType.DELETE, actor,
                new RunEventPayloads.Delete(reason), "ok");
        runs.markHidden(runId);
    }

    /**
     * Reliability — reconcile fleet members whose worker died. The heartbeat
     * {@link com.perf.globalorchestrator.sweep.PodSweeper} flips a silent pod to
     * {@code LOST}, but nothing else transitions the member rows bound to it, so
     * a killed worker's member would otherwise stick at RUNNING forever (the
     * status poller silently no-ops on an unreachable worker). This fails every
     * still-active member on a LOST pod, then rolls up each affected run so an
     * all-dead fleet finalises promptly even with no UI polling it.
     *
     * <p>The LOST detection itself is deliberately not an audit event (see the
     * {@link RunEventType} javadoc); the run-terminal rollUp it triggers is the
     * SAME event {@link #refreshAndGet} emits, so we finalise through that path
     * — which also skips polling the now-terminal dead members (no blocking on
     * unreachable workers).
     *
     * <p>Invoked every sweep tick. The member UPDATE is idempotent + set-based,
     * so a transient failure self-heals on the next tick; in steady state it
     * matches zero rows and does no further work.
     */
    /** Fails one LOST worker's active members with the kubelet's reason, then rolls its runs up. */
    public void failMembersOnLostWorker(String podId, String reason) {
        List<String> affected;
        try {
            affected = runs.failActiveMembersForWorker(podId, reason);
        } catch (RuntimeException e) {
            LOG.warn("failMembersOnLostWorker({}): {}", podId, e.toString());
            return;
        }
        for (String runId : affected.stream().distinct().toList()) {
            try {
                refreshAndGet(runId);
            } catch (RuntimeException e) {
                LOG.warn("failMembersOnLostWorker: rollUp of run {} failed: {}", runId, e.toString());
            }
        }
    }

    public void reapLostWorkerMembers(String reason) {
        List<String> affected;
        try {
            affected = runs.failActiveMembersOnLostPods(reason);
        } catch (RuntimeException e) {
            LOG.warn("reapLostWorkerMembers: failing members on LOST pods failed: {}", e.toString());
            return;
        }
        if (affected.isEmpty()) return;
        List<String> distinctRuns = affected.stream().distinct().toList();
        LOG.info("Lost-worker reaper failed {} active member(s) across {} run(s); finalising",
                affected.size(), distinctRuns.size());
        for (String runId : distinctRuns) {
            try {
                refreshAndGet(runId); // rolls up + emits the terminal event if now terminal
            } catch (RuntimeException e) {
                LOG.warn("reapLostWorkerMembers: rollUp of run {} failed: {}", runId, e.toString());
            }
        }
    }

    /**
     * Sibling of {@link #openRunAndClaimPods} for mid-test scale-up.
     * Reuses the per-region capacity gate + claim SQL but threads through
     * the existing run + application instead of creating a fresh row.
     *
     * <p>Differences from {@code openRunAndClaimPods}:
     * <ul>
     *   <li>No {@code run} INSERT — caller owns the existing row.</li>
     *   <li>No state transition to STARTING — run stays RUNNING.</li>
     *   <li>Stamps {@code joinedAtSecond} on every new member.</li>
     *   <li>Application is sourced from the run row, not the request, so
     *       capacity is gated against the run's existing app binding.
     *       Caller has already verified {@code run.application() != null}.</li>
     *   <li>Claims always go through {@code claimIdleByRegionAndApp} —
     *       legacy null-app pool is unreachable here by construction.</li>
     * </ul>
     */
    @Transactional("transactionManager")
    protected ScaleUpTransactionResult openMembersInExistingRun(
            Run run, List<FleetAllocationEntry> allocation,
            long joinedAtSecond, boolean bestEffort, Actor actor) {
        // Look up the application by name to get its group for the claim
        // path. The run row carries the app *name*, but the pod table is
        // keyed on the group.
        com.perf.globalorchestrator.domain.Application boundApp = applications
                .findByName(run.application())
                .orElseThrow(() -> new IllegalStateException(
                        "run " + run.runId() + " references unknown application '" + run.application() + "'"));

        // Per-(group, region) capacity gate — same shape as
        // openRunAndClaimPods. Applied BEFORE claim so a rejected scale-up
        // never holds capacity. The active count INCLUDES the run's own
        // existing members (they're counted by countActivePodsForGroupRegion
        // because they're in active member states), so the gate naturally
        // honors the cumulative-fleet cap across the run's lifetime.
        String groupId = boundApp.metricsGroupId();
        for (FleetAllocationEntry e : allocation) {
            var capacityRow = groupCapacity.find(groupId, e.region());
            if (capacityRow.isEmpty()) {
                throw new GroupCapacityExceededException(
                        groupId, e.region(), 0, 0, e.count(),
                        "no capacity configured for group '" + groupId + "' in region '" + e.region() + "'");
            }
            int max = capacityRow.get().maxAvailable();
            int active = groupCapacity.countActivePodsForGroupRegion(groupId, e.region());
            if (active + e.count() > max) {
                throw new GroupCapacityExceededException(groupId, e.region(), max, active, e.count(), null);
            }
        }

        List<String> known = pods.findKnownRegions();
        List<String> unknown = allocation.stream()
                .map(FleetAllocationEntry::region)
                .filter(r -> !known.contains(r))
                .toList();
        if (!unknown.isEmpty()) {
            throw new UnknownRegionException(unknown);
        }

        List<Pod> claimed = new ArrayList<>();
        Map<String, Map<String, String>> propertiesByPodId = new LinkedHashMap<>();
        Map<String, RegionShortfall> shortfall = new LinkedHashMap<>();

        for (FleetAllocationEntry e : allocation) {
            List<Pod> regionClaim = pods.claimIdleByGroup(
                    e.region(), boundApp.metricsGroupId(), e.count());
            for (int i = 0; i < regionClaim.size(); i++) {
                Pod p = regionClaim.get(i);
                Map<String, String> props = e.propertiesFor(i);
                if (!props.isEmpty()) {
                    propertiesByPodId.put(p.podId(), props);
                }
            }
            claimed.addAll(regionClaim);
            if (regionClaim.size() < e.count()) {
                shortfall.put(e.region(),
                        new RegionShortfall(e.count(), regionClaim.size()));
            }
        }

        if (!shortfall.isEmpty()) {
            // Strict mode → roll back. Best-effort with zero claimed
            // also rolls back — a scale-up that adds zero workers is
            // not useful and the operator should learn about it.
            if (!bestEffort || claimed.isEmpty()) {
                throw new InsufficientCapacityException(shortfall);
            }
        }

        String stateReason = null;
        if (!shortfall.isEmpty() && bestEffort) {
            stateReason = "bestEffort scaleUp — " + InsufficientCapacityException.formatShortfall(shortfall);
        }

        Instant now = Instant.now();
        List<RunFleetMember> newMembers = new ArrayList<>(claimed.size());
        for (Pod p : claimed) {
            Map<String, String> props = propertiesByPodId.getOrDefault(p.podId(), Map.of());
            RunFleetMember m = new RunFleetMember(
                    run.runId(), p.podId(), p.region(), MemberState.PENDING,
                    null, null, p.baseUrl(), now, null, null, props, joinedAtSecond);
            runs.insertFleetMember(m);
            newMembers.add(m);
            // Same per-pod counter bump as the
            // initial-claim path. A scale-up member is a fresh run-claim
            // for that pod, even though the parent run is mid-flight.
            pods.incrementRunsServed(p.podId());
        }

        // One SCALE_UP event, atomic with the new
        // member rows. A capacity rejection threw above (before any claim),
        // so this is only reached on a real add; a forced failure here rolls
        // back the members AND the event together (decision #7).
        int requested = allocation.stream().mapToInt(FleetAllocationEntry::count).sum();
        int granted = claimed.size();
        boolean partial = granted < requested;
        List<RegionCount> allocView =
                allocation.stream().map(e -> new RegionCount(e.region(), e.count())).toList();
        recordEvent(run.runId(), RunEventType.SCALE_UP, actor,
                new RunEventPayloads.ScaleUp(allocView, bestEffort, requested, granted, partial),
                partial ? "partial" : "ok");

        return new ScaleUpTransactionResult(newMembers, stateReason);
    }

    private static void validateScaleUpAllocations(List<FleetAllocationEntry> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalArgumentException(
                    "scaleUp requires a non-empty allocations array");
        }
        Set<String> seen = new HashSet<>();
        for (FleetAllocationEntry e : allocations) {
            if (e.region() == null || e.region().isBlank()) {
                throw new IllegalArgumentException(
                        "scaleUp allocation entry is missing region");
            }
            if (e.count() <= 0) {
                throw new IllegalArgumentException(
                        "scaleUp allocation count must be > 0 (region=" + e.region() + ")");
            }
            if (!seen.add(e.region())) {
                throw new IllegalArgumentException(
                        "duplicate region in scaleUp allocations: " + e.region());
            }
        }
    }

    /** Resolves pluginIds against the registry into launch snapshots; unknown ids → 400. */
    private List<PluginRef> resolvePlugins(List<String> pluginIds) {
        if (pluginIds == null || pluginIds.isEmpty()) {
            return List.of();
        }
        List<PluginRef> out = new ArrayList<>(pluginIds.size());
        List<String> unknown = new ArrayList<>();
        for (String id : new LinkedHashSet<>(pluginIds)) {
            pluginRepo.findById(id).ifPresentOrElse(
                    p -> out.add(PluginRef.of(p)),
                    () -> unknown.add(id));
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown pluginId(s): " + String.join(", ", unknown));
        }
        return List.copyOf(out);
    }

    /** The fan-out body's plugin entries — blobId + fileName only (what the worker stages by). */
    static List<Map<String, String>> pluginWireRefs(List<PluginRef> plugins) {
        return plugins.stream()
                .map(p -> Map.of("blobId", p.blobId(), "fileName", p.fileName()))
                .toList();
    }

    private static void validate(StartRunRequest request) {
        if (request.testPlanBlobId() == null || request.testPlanBlobId().isBlank()) {
            throw new IllegalArgumentException("testPlanBlobId is required");
        }
        // fleetSize is only required when fleetAllocation is absent and
        // regions doesn't carry a single-region shortcut. Validate the
        // resolved allocation has at least one pod targeted.
        if (request.fleetAllocation().isEmpty() && request.fleetSize() < 1) {
            throw new IllegalArgumentException(
                    "fleetSize must be >= 1 (or supply fleetAllocation)");
        }
    }

    /**
     * Refreshes each non-terminal member's stored state by querying its
     * local orchestrator's GET /api/v1/test. Step 15 leaves this as a
     * lazy-on-read refresh; in the future, heartbeats could carry the
     * test state too and we'd serve from cached state.
     */
    public Run refreshAndGet(String runId) {
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        boolean terminal = run.state().isTerminal();
        // Fast path unchanged for terminal runs that didn't opt into Save
        // Results. For terminal saveResults runs we keep polling each worker's
        // uploadState so we can emit one RESULTS_SAVED audit event per worker
        // once the upload (which happens AFTER the run goes terminal) lands.
        if (terminal && !run.saveResults()) {
            return run;
        }
        // Save Results — which workers ALREADY have a RESULTS_SAVED event, read
        // durably from the audit trail so a restart or the background sweeper
        // never re-emits one a prior poll wrote (the in-memory set below only
        // dedups within a single process lifetime).
        Set<String> resultsSaved = run.saveResults()
                ? auditEvents.resultsSavedWorkerIds(runId)
                : Set.of();
        // Terminal + saveResults: the only work left is observing each worker's
        // post-completion upload. Once every clean-exit worker (COMPLETED /
        // DRAINED — the states that upload; FAILED / ABORTED never do) is
        // recorded there is nothing to poll — re-engage the terminal fast-path
        // so the sweeper (and any lingering UI poll) stops hitting the idle pods.
        if (terminal && run.saveResults()
                && run.fleetMembers().stream()
                        .filter(m -> m.podBaseUrl() != null
                                && (m.state() == MemberState.COMPLETED
                                        || m.state() == MemberState.DRAINED))
                        .allMatch(m -> resultsSaved.contains(m.workerId()))) {
            return run;
        }
        // One status call per routed region for every member still worth
        // polling, direct calls elsewhere — never one WAN round-trip per member.
        List<WorkerRef> toPoll = new ArrayList<>();
        for (RunFleetMember m : run.fleetMembers()) {
            if (m.podBaseUrl() == null) continue;
            boolean pollState = !terminal && !m.state().isTerminal();
            if (!pollState && !run.saveResults()) continue;
            toPoll.add(WorkerRef.of(m));
        }
        Map<String, Map<String, Object>> snapshots = toPoll.isEmpty() ? Map.of() : statusFetcher.fetch(toPoll);
        for (RunFleetMember m : run.fleetMembers()) {
            if (m.podBaseUrl() == null) continue;
            // State mapping only matters while the run is live; a terminal
            // member's state never changes, but its uploadState still might.
            boolean pollState = !terminal && !m.state().isTerminal();
            if (!pollState && !run.saveResults()) continue;
            Optional.ofNullable(snapshots.get(m.workerId())).ifPresent(snap -> {
                if (pollState) {
                    MemberState mapped = mapLocalState(snap.get("state"));
                    if (mapped != null && mapped != m.state()
                            // DRAINING is sticky.
                            // The local-orch keeps reporting RUNNING until
                            // JMeter exits; don't downgrade until a true
                            // terminal (DRAINED / ABORTED / FAILED) arrives.
                            && !(m.state() == MemberState.DRAINING && !mapped.isTerminal())) {
                        runs.updateMemberState(runId, m.workerId(), mapped, null, null);
                    }
                }
                if (run.saveResults()) {
                    maybeRecordResultsSaved(runId, m.workerId(), snap, resultsSaved);
                }
            });
        }
        Run refreshed = runs.findByRunId(runId).orElseThrow();
        RunState rolled = rollUp(refreshed);
        if (rolled != refreshed.state()) {
            if (rolled.isTerminal()) {
                // AUDIT-TRAIL + MULTI-INSTANCE (2026-07-24) — claim the terminal
                // transition with a guarded UPDATE (state NOT IN terminal).
                // refreshAndGet runs concurrently across replicas (UI polls,
                // ResultsSavedSweeper, PodSweeper reap on every instance) and
                // even across threads of one instance; the old in-memory
                // comparison alone let two callers both observe the transition
                // and double-emit. Only the claim winner (rowcount 1) does the
                // terminal bookkeeping.
                int claimed = runs.updateRunStateClaimingTerminal(runId, rolled, null);
                if (claimed == 1) {
                    // Save Results — only a clean COMPLETED produces a combined
                    // upload (failed/aborted workers skip the JTL upload on a
                    // non-COMPLETE exit). Clear saveResults on any non-COMPLETED
                    // terminal so the UI's Download button (gated on
                    // saveResults) never offers a 404 and the terminal fast-path
                    // above stops chasing dead workers. COMPLETED keeps it — that's
                    // the only state that uploads + offers a download.
                    if (rolled != RunState.COMPLETED) {
                        runs.clearSaveResults(runId);
                    }
                    recordRunTerminal(runId, rolled, refreshed.stateReason());
                    // Snapshot the run's aggregate into
                    // runTrend, but only for a clean COMPLETED (a failed/aborted
                    // run's partial metrics aren't a meaningful baseline). The
                    // claim above makes this the single terminal-transition tick.
                    if (rolled == RunState.COMPLETED) {
                        recordRunTrend(refreshed);
                    }
                }
            } else {
                runs.updateRunState(runId, rolled, null);
            }
            return runs.findByRunId(runId).orElseThrow();
        }
        return refreshed;
    }

    /**
     * Save Results reconciliation — driven by {@code ResultsSavedSweeper}.
     * Finds COMPLETED, {@code saveResults} runs (completed within {@code
     * lookback}) that still have a worker missing its {@code RESULTS_SAVED}
     * audit event, and runs {@link #refreshAndGet} on each so the post-completion
     * upload is observed and recorded even when no UI is polling.
     *
     * <p>This closes the gap that the per-worker JTL upload finishes AFTER the
     * run goes terminal — at which point the run-detail page stops polling
     * {@code GET /status}, so without this sweep the {@code uploadState=UPLOADED}
     * transition (and thus the audit event) would never be seen. {@code
     * refreshAndGet}'s durable dedup keeps each event exactly-once.
     *
     * @return the number of runs reconciled this pass (for logging / metrics)
     */
    public int reconcileResultsSaved(Duration lookback) {
        List<String> runIds = runs.runIdsAwaitingResultsSaved(Instant.now().minus(lookback));
        for (String id : runIds) {
            try {
                refreshAndGet(id);
            } catch (RuntimeException e) {
                // Best-effort — one bad run must not stall the rest of the sweep.
                LOG.warn("RESULTS_SAVED reconcile for run {} failed (non-fatal): {}", id, e.toString());
            }
        }
        return runIds.size();
    }

    public List<Run> listRuns(boolean activeOnly, int limit) {
        return runs.listRuns(activeOnly, Math.max(1, Math.min(limit, 200)));
    }

    /**
     * UI-D3 — paginated + application-filtered listing. {@code limit} is
     * clamped to [1, 200]; {@code offset} is clamped to >= 0. Empty/blank
     * {@code application} is treated as "no filter" (returns all rows).
     */
    public RunRepository.ListRunsPage listRuns(boolean activeOnly, String application,
                                               boolean includeHidden, boolean hiddenOnly,
                                               int offset, int limit) {
        String app = (application == null || application.isBlank()) ? null : application;
        int safeLimit  = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        return runs.listRuns(new RunRepository.ListRunsCriteria(
                activeOnly, app, includeHidden, hiddenOnly, safeOffset, safeLimit));
    }

    /**
     * The run's metadata (row + fleet members).
     * Cached <b>only for terminal runs</b> ({@code unless} drops the cache put
     * when the fetched run isn't terminal), because a terminal run's row and
     * members are frozen — no further writes occur, so no eviction is needed
     * (TTL is the only bound). Active runs always re-read (their state +
     * members change per poll). This is the existence-validation read every
     * per-run GET endpoint makes, so a finished run that's revisited skips the
     * row+members query fleet-wide.
     *
     * <p>Note: callers that need a live refresh use {@link #refreshAndGet}
     * (uncached) — it already short-circuits for terminal runs without fanning
     * out to pods.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_RUN_METADATA, key = "#runId",
               unless = "#result == null || !#result.state().isTerminal()")
    public Run getRun(String runId) {
        return runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
    }

    /**
     * One page of the reverse-chronological audit timeline for a
     * run, plus the total count. 404 {@link RunNotFoundException} if the run is
     * unknown (so the UI's error path matches the other per-run endpoints).
     * A long-running test can accumulate many events, so the timeline is
     * paged: {@code limit} clamped to [1, 200] (default applied by the
     * controller), {@code offset} clamped to >= 0.
     */
    public RunEventRepository.RunEventsPage getRunEvents(String runId, int offset, int limit) {
        runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        int safeLimit  = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        long total = auditEvents.countByRunId(runId);
        List<RunEvent> page = auditEvents.findByRunId(runId, safeOffset, safeLimit);
        return new RunEventRepository.RunEventsPage(page, total);
    }

    // ── internals ───────────────────────────────────────────────────────

    private record StartTransactionResult(String runId, List<RunFleetMember> members,
                                          String stateReason, List<PluginRef> plugins) {}

    /** MID-TEST-SCALING Phase A — return shape of {@link #openMembersInExistingRun}. */
    private record ScaleUpTransactionResult(List<RunFleetMember> members, String stateReason) {}

    private record FanoutOutcome(MemberState state, int statusCode, String reason) {}

    /**
     * The application's metrics group at launch, stamped on the run row: the
     * run's rows land in that group's fact table for good, so the readers keep
     * finding them even if the operator later moves the application.
     */
    private String metricsGroupOf(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) return null;
        try {
            return applications.findByName(applicationName).map(a -> a.metricsGroupId()).orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("could not resolve the metrics group of application {}: {}", applicationName, e.toString());
            return null;
        }
    }

    private Map<String, FanoutOutcome> fanOut(String runId, List<RunFleetMember> members,
                                              String testPlanBlobId, String dataFilesBlobId,
                                              String application, boolean saveResults,
                                              List<PluginRef> plugins) {
        // Metrics routing: every worker POSTs straight to the metrics-consumer
        // (its METRICS_INGEST_URL env) and appends the run's application group
        // as ?groupId= — the consumer routes the rows to <GROUP>_METRICS. Every
        // member gets the same window width so their windowSeconds line up.
        String metricsGroupId = (application == null || application.isBlank()) ? null
                : applications.findByName(application).map(a -> a.metricsGroupId()).orElse(null);
        Map<String, Future<FanoutOutcome>> futures = new LinkedHashMap<>();
        for (RunFleetMember m : members) {
            runs.updateMemberState(runId, m.workerId(), MemberState.REQUESTED, null, null);
            Map<String, Object> body = new HashMap<>();
            body.put("runId", runId);
            body.put("windowSeconds", metricsWindowSeconds);
            if (metricsGroupId != null) {
                body.put("metricsGroupId", metricsGroupId);
            }
            // Each worker must stamp ITS OWN region onto every WorkerMetric it
            // publishes — the metrics "region" column drives the UI's split-by-
            // region view. Previously this sent the
            // orchestrator's own service-wide `region` (default us-east-1) for
            // EVERY member, so a multi-region fleet's us-west-2 workers reported
            // their metrics as us-east-1 and the run looked single-region.
            // `m.region()` is the member's real region (from the claimed pod).
            // Fall back to the orchestrator region only for legacy rows that
            // somehow lack one, preserving the prior default.
            String memberRegion = (m.region() != null && !m.region().isBlank())
                    ? m.region() : region;
            body.put("region", memberRegion);
            body.put("testPlanBlobId", testPlanBlobId);
            if (dataFilesBlobId != null) {
                body.put("dataFilesBlobId", dataFilesBlobId);
            }
            // UX-DYNAMICS T3 — the run's plugin snapshot; the worker stages
            // each blob under ${BASE_DIR}/plugins and adds it to search_paths.
            if (plugins != null && !plugins.isEmpty()) {
                body.put("plugins", pluginWireRefs(plugins));
            }
            // Only include joinedAtSecond on
            // scale-up members (it's NULL for original-fleet rows;
            // the local-orch defaults to 0 when omitted, which is the
            // intended semantic for original-fleet members).
            if (m.joinedAtSecond() != null) {
                body.put("joinedAtSecond", m.joinedAtSecond());
            }
            // Track G (Step 31) — only include `properties` when the
            // pod has any. Older local-orch versions ignore unknown
            // body fields, so this is wire-compatible either way.
            if (m.properties() != null && !m.properties().isEmpty()) {
                body.put("properties", m.properties());
            }
            // Save Results — the worker files its JTL under this app for the
            // download-all-by-run flow, so forward the app name + flip the
            // per-run autoUpload flag. Only when the run opted in.
            if (saveResults) {
                body.put("autoUploadResults", true);
                if (application != null && !application.isBlank()) {
                    body.put("application", application);
                }
            }
            futures.put(m.workerId(), fanoutPool.submit(() -> {
                StartTestResult r = localClient.startTest(runId, WorkerRef.of(m), body);
                if (r.accepted() || r.ok()) {
                    return new FanoutOutcome(MemberState.ACCEPTED, r.statusCode(), null);
                }
                String reason = r.body() == null ? "no body" : truncate(r.body(), 240);
                // The worker refused because it is
                // already busy. Ask WHOSE run holds it and say so, instead of
                // handing the operator a raw 409 body.
                if (r.statusCode() == CONFLICT_STATUS) {
                    String busy = foreignRunReason(runId, WorkerRef.of(m));
                    if (busy != null) reason = busy;
                }
                return new FanoutOutcome(MemberState.FAILED, r.statusCode(), reason);
            }));
        }
        Map<String, FanoutOutcome> outcomes = new LinkedHashMap<>();
        futures.forEach((workerId, f) -> {
            try {
                FanoutOutcome o = f.get();
                outcomes.put(workerId, o);
                runs.updateMemberState(runId, workerId, o.state(), o.reason(), o.statusCode());
            } catch (Exception e) {
                ErrorContext.logWarn(LOG,
                        "startFanout runId=" + runId + " workerId=" + workerId,
                        "fan-out future threw",
                        e);
                outcomes.put(workerId, new FanoutOutcome(MemberState.FAILED, 0, e.toString()));
                runs.updateMemberState(runId, workerId, MemberState.FAILED, e.toString(), 0);
            }
        });
        return outcomes;
    }

    /**
     * Names the run actually holding a worker that refused fan-out with a 409.
     * The claim path only knows about runs this control plane started, so on a
     * shared fleet a worker can be busy with work we never launched — a hand-run
     * JMeter, or a second environment pointing at the same workers.
     *
     * <p><b>This deliberately runs after the refusal, not as a pre-flight.</b>
     * The pre-flight version was built first and rejected: the worker's
     * synchronised {@code start()} is the authority, so a pre-flight GET is only
     * advisory — the worker can go busy between it and the POST — and therefore
     * prevents nothing the 409 doesn't. But it *can* reject a worker that would
     * have accepted, on a stale or ambiguous snapshot. That is a new failure
     * mode bought for nothing. Running it on the 409 path costs one GET only
     * after a launch has already failed, and yields the same diagnostic.
     *
     * @return null when the snapshot shows nothing useful, in which case the
     *         caller keeps the raw response body
     */
    private String foreignRunReason(String runId, WorkerRef worker) {
        java.util.Optional<Map<String, Object>> snapshot = localClient.getTestStatus(worker);
        if (snapshot.isEmpty()) return null;
        Object stateObj = snapshot.get().get("state");
        if (stateObj == null) return null;
        String localState = stateObj.toString();
        if (!LOCAL_BUSY_STATES.contains(localState)) return null;

        Object holderRunId = snapshot.get().get("runId");
        if (holderRunId != null && runId.equals(holderRunId.toString())) {
            // Busy with OUR run — a duplicate fan-out, not a foreign holder.
            // The raw body is the more honest reason there.
            return null;
        }
        return "worker is already running a test this run did not start (state=" + localState
                + ", runId=" + (holderRunId == null ? "unknown" : holderRunId)
                + ") — launched outside this control plane, or by another environment "
                + "sharing this worker";
    }

    /**
     * Local-orchestrator lifecycle states that mean "a JMeter test is in
     * flight". The terminal ones (COMPLETED / FAILED / ABORTED / DRAINED)
     * and IDLE are free — a worker keeps its last run's snapshot on file
     * after it finishes, so treating any snapshot as busy would read every
     * reused worker as occupied.
     */
    private static final java.util.Set<String> LOCAL_BUSY_STATES =
            java.util.Set.of("PREPARING", "STARTING", "RUNNING", "DRAINING");

    /** The worker's "a test is already in progress" refusal (409 TEST_RUNNING). */
    private static final int CONFLICT_STATUS = 409;

    private static MemberState mapLocalState(Object stateObj) {
        if (stateObj == null) return null;
        String s = stateObj.toString();
        return switch (s) {
            case "IDLE", "PREPARING", "STARTING" -> MemberState.ACCEPTED;
            case "RUNNING", "DRAINING"           -> MemberState.RUNNING;
            case "COMPLETED" -> MemberState.COMPLETED;
            case "FAILED"    -> MemberState.FAILED;
            case "ABORTED"   -> MemberState.ABORTED;
            // Local DRAINED → global DRAINED.
            // Local "DRAINING" stays mapped to RUNNING because local's
            // DRAINING is the brief post-exit pipeline-flush state, not
            // operator-initiated drain. The global side carries its own
            // DRAINING (set when scaleDown fires); the refresh path
            // refuses to downgrade DRAINING → RUNNING (see refreshAndGet).
            case "DRAINED"   -> MemberState.DRAINED;
            default -> null;
        };
    }

    private static RunState rollUp(Run run) {
        List<RunFleetMember> members = run.fleetMembers();
        if (members.isEmpty()) return run.state();
        boolean allTerminal = members.stream().allMatch(m -> m.state().isTerminal());
        if (allTerminal) {
            boolean anyAbort   = members.stream().anyMatch(m -> m.state() == MemberState.ABORTED);
            // DRAINED counts as a successful
            // terminal alongside COMPLETED. A run with members in any mix
            // of {COMPLETED, DRAINED} (no FAILED, no ABORTED) rolls up to
            // RunState.COMPLETED — the operator chose to drain some
            // workers, others ran to natural end; the run as a whole
            // still succeeded.
            boolean anySuccess = members.stream().anyMatch(
                    m -> m.state() == MemberState.COMPLETED || m.state() == MemberState.DRAINED);
            if (anyAbort) return RunState.ABORTED;
            return anySuccess ? RunState.COMPLETED : RunState.FAILED;
        }
        // DRAINING members are live; the run
        // stays RUNNING while any member is still RUNNING or DRAINING.
        if (members.stream().anyMatch(
                m -> m.state() == MemberState.RUNNING || m.state() == MemberState.DRAINING)) {
            return RunState.RUNNING;
        }
        return run.state();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── AUDIT-TRAIL Phase C — event writers ──────────────────────────────

    /**
     * Serialise a type-safe payload record (one of {@link RunEventPayloads})
     * to the JSONB map and append one audit event. eventId is a fresh ULID;
     * the repository's {@code ON CONFLICT DO NOTHING} makes a same-id retry a
     * no-op (decision #10).
     */
    private void recordEvent(String runId, RunEventType type, Actor actor,
                             Object payloadRecord, String result) {
        audit.record(runId, type, actor, payloadRecord, result);
    }

    /**
     * A platform-detected run-terminal event (RUN_COMPLETED /
     * RUN_FAILED / RUN_ABORTED), attributed to the system actor since no
     * operator hit a button — the orchestrator observed the run end. Callers
     * must invoke this exactly on the transition into a terminal state.
     */
    private void recordRunTerminal(String runId, RunState state, String reason) {
        RunEventType type = switch (state) {
            case COMPLETED -> RunEventType.RUN_COMPLETED;
            case FAILED    -> RunEventType.RUN_FAILED;
            case ABORTED   -> RunEventType.RUN_ABORTED;
            default        -> null; // non-terminal — nothing to record
        };
        if (type == null) return;
        String result = switch (state) {
            case FAILED  -> "failed";
            case ABORTED -> "aborted";
            default      -> "ok";
        };
        recordEvent(runId, type, Actor.system("orchestrator"),
                new RunEventPayloads.RunEnd(state.name(), reason), result);
    }

    /**
     * Snapshot one COMPLETED run's aggregate into
     * {@code runTrend} for the daily perf-report baseline. Best-effort: any
     * failure (metrics DB hiccup, aggregate error) is logged and swallowed so
     * it can never disrupt the status poll that detected the terminal
     * transition. A run with no metric rows yet (metrics-consumer lag at the
     * exact terminal moment) is skipped rather than snapshotted as a misleading
     * all-zeros baseline. The repository's {@code ON CONFLICT DO NOTHING} makes
     * a re-emit a no-op (belt-and-suspenders on top of the once-only fence).
     */
    private void recordRunTrend(Run run) {
        try {
            java.util.Optional<MetricsTarget> target = metricsGroups.resolve(run);
            if (target.isEmpty()) {
                LOG.debug("run {} COMPLETED with no metrics group or no rows yet — skipping runTrend snapshot", run.runId());
                return;
            }
            RunMetricsRepository.RunAggregate agg = runMetrics.runAggregate(target.get(), RunWindow.of(run));
            if (agg.rowCount() == 0) {
                LOG.debug("run {} COMPLETED with no metric rows yet — skipping runTrend snapshot",
                        run.runId());
                return;
            }
            runTrends.insert(new RunTrend(
                    run.runId(), run.application(),
                    agg.p50Ms(), agg.p95Ms(), agg.p99Ms(),
                    agg.errorRate(), agg.throughputRps(),
                    run.completedAt() != null ? run.completedAt() : Instant.now()));
        } catch (RuntimeException e) {
            LOG.warn("runTrend snapshot for {} failed (non-fatal): {}", run.runId(), e.toString());
        }
    }

    /**
     * Save Results — emit one {@code RESULTS_SAVED} audit event per worker when
     * the platform observes that worker's {@code uploadState} reach UPLOADED.
     * Guards on the snapshot's runId matching (the worker is reused across
     * runs) and on the in-memory dedup set so repeated polls emit at most once.
     */
    @SuppressWarnings("unchecked")
    private void maybeRecordResultsSaved(String runId, String workerId, Object snapObj,
                                         Set<String> alreadySaved) {
        if (alreadySaved.contains(workerId)) return; // durable dedup (survives restart)
        if (!(snapObj instanceof Map)) return;
        Map<String, Object> snap = (Map<String, Object>) snapObj;
        Object snapRunId = snap.get("runId");
        if (snapRunId != null && !runId.equals(snapRunId.toString())) return; // a later run on this worker
        Object uploadState = snap.get("uploadState");
        if (uploadState == null || !"UPLOADED".equals(uploadState.toString())) return;
        if (!resultsSavedEmitted.add(runId + "|" + workerId)) return; // intra-process concurrency dedup
        Object target = snap.get("uploadTarget");
        try {
            // MULTI-INSTANCE (2026-07-24): deterministic eventId — every replica
            // computes the same id for the same (run, worker) upload fact, so
            // the runEvent PK's ON CONFLICT DO NOTHING dedups across instances
            // too, closing the check-then-insert race between two concurrent
            // sweepers that both read the durable set before either wrote.
            audit.record("resultsSaved:" + runId + ":" + workerId,
                    runId, RunEventType.RESULTS_SAVED, Actor.system("orchestrator"),
                    new RunEventPayloads.ResultsSaved(workerId, target == null ? null : target.toString()),
                    "ok");
        } catch (RuntimeException e) {
            // Best-effort audit — never let it disrupt status polling. Allow a
            // retry on the next poll by clearing the dedup marker.
            resultsSavedEmitted.remove(runId + "|" + workerId);
        }
    }

    /** SCALE_UP rejection — written outside the (rolled-back) claim transaction. */
    private void recordScaleUpRejected(String runId, ScaleUpRunRequest request,
                                       boolean bestEffort, Actor actor, String code) {
        int requested = request.allocations().stream().mapToInt(FleetAllocationEntry::count).sum();
        List<RegionCount> allocView =
                request.allocations().stream().map(e -> new RegionCount(e.region(), e.count())).toList();
        recordEvent(runId, RunEventType.SCALE_UP, actor,
                new RunEventPayloads.ScaleUp(allocView, bestEffort, requested, 0, false),
                "rejected:" + code);
    }

    /** SCALE_DOWN / DRAIN_WORKER outcome — "ok" if nothing was skipped, else "partial". */
    private void recordScaleDownOutcome(String runId, ScaleDownRunRequest request, Actor actor,
                                        List<String> drained,
                                        List<ScaleDownRunResponse.SkippedTarget> skipped) {
        List<RunEventPayloads.Skipped> skips = skipped.stream()
                .map(s -> new RunEventPayloads.Skipped(s.workerId(), s.reason())).toList();
        recordEvent(runId, scaleDownType(request), actor,
                scaleDownPayload(request, drained, skips),
                skips.isEmpty() ? "ok" : "partial");
    }

    /** SCALE_DOWN / DRAIN_WORKER rejection. */
    private void recordScaleDownRejected(String runId, ScaleDownRunRequest request,
                                         Actor actor, String code) {
        recordEvent(runId, scaleDownType(request), actor,
                scaleDownPayload(request, List.of(), List.of()), "rejected:" + code);
    }

    private static Object scaleDownPayload(ScaleDownRunRequest request, List<String> drained,
                                           List<RunEventPayloads.Skipped> skipped) {
        if (scaleDownType(request) == RunEventType.DRAIN_WORKER) {
            return new RunEventPayloads.DrainWorker(request.workerIds().get(0), drained, skipped);
        }
        List<RegionCount> allocView =
                request.allocations().stream().map(e -> new RegionCount(e.region(), e.count())).toList();
        return new RunEventPayloads.ScaleDown(request.workerIds(), allocView, drained, skipped);
    }

    /**
     * A drain that targets exactly one explicit workerId is recorded as
     * {@link RunEventType#DRAIN_WORKER} (the UI's per-worker control);
     * region-based or multi-worker drains are {@link RunEventType#SCALE_DOWN}.
     * The payload carries the full ledger either way — the distinction is only
     * the timeline label.
     */
    private static RunEventType scaleDownType(ScaleDownRunRequest request) {
        return (request.workerIds().size() == 1 && request.allocations().isEmpty())
                ? RunEventType.DRAIN_WORKER
                : RunEventType.SCALE_DOWN;
    }

    /** Per-region shortfall detail surfaced on INSUFFICIENT_CAPACITY responses. */
    public record RegionShortfall(int requested, int claimed) {}

    /** Surface as 404 NOT_FOUND in the controller's error handler. */
    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String runId) {
            super("run not found: " + runId);
        }
    }

    /**
     * Surface as 409 CONFLICT.
     * scaleUp can only target a RUNNING run. PREPARING/STARTING means
     * the original fan-out hasn't completed yet; terminal means the run
     * is over and adding workers makes no sense.
     */
    public static class RunNotInScalableStateException extends RuntimeException {
        private final String runId;
        private final RunState currentState;

        public RunNotInScalableStateException(String runId, RunState currentState) {
            super("run " + runId + " is in state " + currentState
                    + "; scaleUp requires state RUNNING");
            this.runId = runId;
            this.currentState = currentState;
        }

        public String runId() { return runId; }
        public RunState currentState() { return currentState; }
    }

    /**
     * Surface as 409 CONFLICT — abort was requested against a run that is
     * already terminal (COMPLETED / FAILED / ABORTED). There is nothing to
     * abort; its pods are already released. Idempotency guard so a repeated
     * abort (double-click, retry) doesn't emit a second ABORT event.
     */
    public static class RunNotAbortableException extends RuntimeException {
        private final String runId;
        private final RunState currentState;

        public RunNotAbortableException(String runId, RunState currentState) {
            super("run " + runId + " is in terminal state " + currentState
                    + "; nothing to abort");
            this.runId = runId;
            this.currentState = currentState;
        }

        public String runId() { return runId; }
        public RunState currentState() { return currentState; }
    }

    /**
     * Thrown by {@link #deleteRun} when the run is NOT terminal. An active run
     * is still important and pins live pods, so it can't be hidden — the
     * operator must let it finish or abort it first. Surfaced as 409 CONFLICT
     * with code {@code RUN_NOT_DELETABLE}.
     */
    public static class RunNotDeletableException extends RuntimeException {
        private final String runId;
        private final RunState currentState;

        public RunNotDeletableException(String runId, RunState currentState) {
            super("run " + runId + " cannot be deleted while " + currentState
                    + " — only terminal runs (COMPLETED/FAILED/ABORTED) can be hidden");
            this.runId = runId;
            this.currentState = currentState;
        }

        public String runId() { return runId; }
        public RunState currentState() { return currentState; }
    }

    /**
     * Surface as 409 CONFLICT.
     * scaleUp gates capacity per-(application, region); a run launched
     * without an application binding has no app to gate against, so
     * scaleUp is unavailable. Legacy untagged runs (pre-V4) hit this.
     */
    public static class RunNotScalableNoApplicationException extends RuntimeException {
        private final String runId;

        public RunNotScalableNoApplicationException(String runId) {
            super("run " + runId + " has no application binding; scaleUp requires "
                    + "the run to be launched against a registered application");
            this.runId = runId;
        }

        public String runId() { return runId; }
    }

    /**
     * Surface as 503 SERVICE_UNAVAILABLE — client should retry once a
     * pod is back, or re-submit with {@code ?bestEffort=true} to accept
     * the partial claim. Carries per-region shortfall so the UI can
     * highlight which regions fell short.
     */
    public static class InsufficientCapacityException extends RuntimeException {
        private final Map<String, RegionShortfall> shortfall;

        public InsufficientCapacityException(Map<String, RegionShortfall> shortfall) {
            super(formatShortfall(shortfall));
            this.shortfall = shortfall;
        }

        public Map<String, RegionShortfall> shortfall() {
            return shortfall;
        }

        static String formatShortfall(Map<String, RegionShortfall> shortfall) {
            StringBuilder sb = new StringBuilder();
            shortfall.forEach((region, s) -> {
                if (sb.length() > 0) sb.append("; ");
                sb.append(region).append(": requested ").append(s.requested())
                  .append(", claimed ").append(s.claimed());
            });
            return sb.toString();
        }
    }

    /** Surface as 400 BAD_REQUEST — caller named a region the registry has never seen. */
    public static class UnknownRegionException extends RuntimeException {
        private final List<String> regions;

        public UnknownRegionException(List<String> regions) {
            super("unknown region(s): " + String.join(", ", regions));
            this.regions = List.copyOf(regions);
        }

        public List<String> regions() {
            return regions;
        }
    }

    /** Surface as 400 BAD_REQUEST — total allocation exceeds the per-run cap. */
    public static class FleetSizeExceededException extends RuntimeException {
        private final int requested;
        private final int max;

        public FleetSizeExceededException(int requested, int max) {
            super("total fleet size " + requested + " exceeds cap of " + max);
            this.requested = requested;
            this.max = max;
        }

        public int requested() { return requested; }
        public int max() { return max; }
    }

    /**
     * Surface as 409 CONFLICT — D-Capacity v2 per-(app, region) maxAvailable
     * budget would be exceeded by this run, OR no capacity row is configured
     * for the requested region. Carries the actionable diagnostic so the UI
     * can render a clear "you have X of Y in use in region Z; this run wants
     * W more" message.
     */
    /** The group's per-region ceiling would be exceeded (409 {@code APPLICATION_CAPACITY_EXCEEDED}). */
    public static class GroupCapacityExceededException extends RuntimeException {
        private final String group;
        private final String region;
        private final int max;
        private final int active;
        private final int requested;

        public GroupCapacityExceededException(String group, String region,
                                              int max, int active, int requested,
                                              String customMessage) {
            super(customMessage != null
                    ? customMessage
                    : "group '" + group + "' in region '" + region
                    + "' would exceed maxAvailable cap " + max
                    + " (currently " + active + " active + " + requested + " requested)");
            this.group = group;
            this.region = region;
            this.max = max;
            this.active = active;
            this.requested = requested;
        }

        public String group() { return group; }
        public String region() { return region; }
        public int max() { return max; }
        public int active() { return active; }
        public int requested() { return requested; }
    }
}
