package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
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
import com.perf.globalorchestrator.observability.SpanAttributes;
import com.perf.globalorchestrator.repo.PodRepository;
import com.perf.globalorchestrator.repo.ApplicationCapacityRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import com.perf.globalorchestrator.repo.RunEventRepository;
import com.perf.globalorchestrator.config.CacheConfig;
import com.perf.globalorchestrator.repo.MetricsRollupRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import com.perf.globalorchestrator.repo.RunTrendRepository;
import com.perf.globalorchestrator.domain.RunTrend;
import org.springframework.cache.annotation.Cacheable;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
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
import java.util.List;
import java.util.Map;
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
     * AUDIT-TRAIL — self-reference so the {@code @Transactional} claim+insert
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
    private final ApplicationCapacityRepository applicationCapacity;
    private final LocalOrchestratorClient localClient;
    // AUTOMATION Phase F — runTrend snapshot on the run-terminal transition.
    private final MetricsRollupRepository metricsRollup;
    private final RunTrendRepository runTrends;
    /** WORKER-HYGIENE Phase E — spin-to-fill on shortfall. Optional so tests can omit it. */
    private final com.perf.globalorchestrator.provision.PodSpinService spinService;
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

    private final Counter runsStarted;
    private final Counter runsFailed;
    private final Counter runsAborted;
    private final Counter runsDeleted;
    private final Counter membersFailedLost;
    private final Counter fanoutsAccepted;
    private final Counter fanoutsRejected;
    private final Counter claimShortfalls;
    private final Counter scaleUpsStarted;
    private final Counter scaleUpsRejected;
    private final Counter scaleDownsStarted;
    private final Counter scaleDownsRejected;

    public RunService(
            RunRepository runs,
            RunEventRepository auditEvents,
            RunAuditWriter audit,
            PodRepository pods,
            ApplicationRepository applications,
            ApplicationCapacityRepository applicationCapacity,
            LocalOrchestratorClient localClient,
            MetricsRollupRepository metricsRollup,
            RunTrendRepository runTrends,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.perf.globalorchestrator.provision.PodSpinService spinService,
            MeterRegistry meterRegistry,
            @Value("${globalOrchestrator.region:us-east-1}") String region,
            @Value("${globalOrchestrator.fanoutThreads:8}") int fanoutThreads,
            @Value("${globalOrchestrator.maxFleetSizePerRun:100}") int maxFleetSizePerRun,
            @Value("${globalOrchestrator.spinShortfall.healthTimeoutMs:60000}") long spinHealthTimeoutMs) {
        this.runs = runs;
        this.auditEvents = auditEvents;
        this.audit = audit;
        this.pods = pods;
        this.applications = applications;
        this.applicationCapacity = applicationCapacity;
        this.localClient = localClient;
        this.metricsRollup = metricsRollup;
        this.runTrends = runTrends;
        this.spinService = spinService;
        this.region = region;
        this.maxFleetSizePerRun = maxFleetSizePerRun;
        this.spinHealthTimeoutMs = spinHealthTimeoutMs;
        this.fanoutPool = Executors.newFixedThreadPool(Math.max(1, fanoutThreads),
                r -> {
                    Thread t = new Thread(r, "globalOrch-fanout");
                    t.setDaemon(true);
                    return t;
                });
        this.runsStarted     = Counter.builder("globalOrchestrator.runs.started").register(meterRegistry);
        this.runsFailed      = Counter.builder("globalOrchestrator.runs.failed").register(meterRegistry);
        this.runsAborted     = Counter.builder("globalOrchestrator.runs.aborted")
                .description("Runs force-terminated via POST /runs/{id}/abort.")
                .register(meterRegistry);
        this.runsDeleted     = Counter.builder("globalOrchestrator.runs.deleted")
                .description("Terminal runs soft-deleted (hidden) via DELETE /runs/{id}.")
                .register(meterRegistry);
        this.membersFailedLost = Counter.builder("globalOrchestrator.members.failedLost")
                .description("Active fleet members force-failed because their worker's pod was marked LOST.")
                .register(meterRegistry);
        this.fanoutsAccepted = Counter.builder("globalOrchestrator.fanouts.accepted").register(meterRegistry);
        this.fanoutsRejected = Counter.builder("globalOrchestrator.fanouts.rejected").register(meterRegistry);
        this.claimShortfalls = Counter.builder("globalOrchestrator.runs.claimShortfalls")
                .description("Run-launch attempts that found fewer IDLE pods than requested.")
                .register(meterRegistry);
        this.scaleUpsStarted = Counter.builder("globalOrchestrator.runs.scaleUpsStarted")
                .description("Mid-test scale-up requests that progressed past validation + capacity gate.")
                .register(meterRegistry);
        this.scaleUpsRejected = Counter.builder("globalOrchestrator.runs.scaleUpsRejected")
                .description("Mid-test scale-up requests rejected before any pod was claimed (state, capacity, validation).")
                .register(meterRegistry);
        this.scaleDownsStarted = Counter.builder("globalOrchestrator.runs.scaleDownsStarted")
                .description("Mid-test scale-down requests that progressed past validation (one or more drains fired).")
                .register(meterRegistry);
        this.scaleDownsRejected = Counter.builder("globalOrchestrator.runs.scaleDownsRejected")
                .description("Mid-test scale-down requests rejected before any drain fired (state, validation).")
                .register(meterRegistry);
        // SECURITY S-0 — in-flight (non-terminal) run count across all apps.
        // Sampled at scrape cadence via a cheap indexed COUNT; a sustained climb
        // is the run-launch-flood / stuck-fleet abuse signal the alert rules
        // watch. DB hiccup → NaN (Micrometer drops the sample) rather than a
        // throwing scrape. No tenant dimension yet (single-team platform);
        // re-key per tenant when S-4 lands.
        Gauge.builder("security.concurrentRuns", runs, r -> {
                    try {
                        return (double) r.countActive();
                    } catch (RuntimeException e) {
                        return Double.NaN;
                    }
                })
                .description("Current in-flight (non-terminal) runs across all applications (S-0 abuse signal).")
                .register(meterRegistry);
    }

    @Observed(name = "globalOrchestrator.startRun",
              contextualName = "startRun",
              lowCardinalityKeyValues = {"action", "startRun"})
    public Run startRun(StartRunRequest request, boolean bestEffort, Actor actor) {
        SpanAttributes.tag("applicationName", request == null ? null : request.application());
        SpanAttributes.tag("bestEffort", String.valueOf(bestEffort));
        SpanAttributes.tag("actor", actor.name());
        validate(request);
        StartTransactionResult started;
        try {
            started = self.openRunAndClaimPods(request, bestEffort, actor);
        } catch (InsufficientCapacityException shortfallEx) {
            // WORKER-HYGIENE Phase E — spin-to-fill on shortfall. The
            // transactional claim attempt rolled back (no run row inserted
            // yet, no pod claims committed), so we can spin pods to fill
            // and retry without worrying about cleanup. The cap-check
            // inside openRunAndClaimPods has ALREADY passed (the in-flight
            // check); we still re-check at provisioning time
            // (pod count vs ceiling) since LOST pods can push provisioning
            // past the ceiling while leaving the in-flight check happy.
            if (!request.isSpinShortfall() || spinService == null) {
                throw shortfallEx; // strict mode (or no provisioner wired) — propagate
            }
            spinToFillShortfall(request.application(), shortfallEx.shortfall());
            started = self.openRunAndClaimPods(request, bestEffort, actor); // retry after spin
        }
        // Fan-out happens OUTSIDE the transaction — the HTTP calls are
        // long-haul and we don't want to hold pod-row locks for the
        // entire fan-out window.
        Map<String, FanoutOutcome> outcomes = fanOut(
                started.runId(), started.members(),
                request.testPlanBlobId(), request.dataFilesBlobId(),
                request.application(), request.isSaveResults());

        long accepted = outcomes.values().stream().filter(o -> o.state() == MemberState.ACCEPTED).count();
        if (accepted == started.members().size()) {
            runs.updateRunState(started.runId(), RunState.RUNNING, started.stateReason());
        } else if (accepted == 0) {
            runs.updateRunState(started.runId(), RunState.FAILED, "all fan-outs rejected");
            // Save Results — a run that failed at launch never produced a clean
            // upload, so clear the flag (same reasoning as commitAbort): the UI
            // won't offer a Download-that-404s and refreshAndGet's terminal
            // fast-path won't chase the dead workers. Only a clean COMPLETED keeps it.
            runs.clearSaveResults(started.runId());
            runsFailed.increment();
            // AUDIT-TRAIL — the run went terminal at launch (every fan-out
            // rejected). Bookend its timeline with a RUN_FAILED event.
            recordRunTerminal(started.runId(), RunState.FAILED, "all fan-outs rejected");
        } else {
            String reason = "partial fan-out: " + accepted + "/" + started.members().size() + " accepted";
            if (started.stateReason() != null) {
                reason = started.stateReason() + "; " + reason;
            }
            runs.updateRunState(started.runId(), RunState.RUNNING, reason);
        }
        runsStarted.increment();
        return runs.findByRunId(started.runId()).orElseThrow();
    }

    /**
     * WORKER-HYGIENE Phase E — spins enough pods to fill each region's
     * shortfall, then polls the new pods' actuator/health until they're
     * reachable. The caller (startRun) then retries the claim.
     *
     * <p>Cap-check: per region, if {@code provisioned + gap > maxAvailable},
     * surface {@link ApplicationCapacityExceededException} so the UI can
     * tell the operator "raise the ceiling first." Bypasses the spin —
     * partial filling would leave the operator confused and the cap rule
     * is sponsor-mandated.
     */
    private void spinToFillShortfall(String applicationName,
                                     Map<String, RegionShortfall> shortfall) {
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
            int provisioned = pods.countByApplicationAndRegion(
                    boundApp.applicationId(), regionName);
            int max = applicationCapacity.find(boundApp.applicationId(), regionName)
                    .map(c -> c.maxAvailable())
                    .orElse(0);
            if (provisioned + gap > max) {
                throw new ApplicationCapacityExceededException(
                        boundApp.name(), regionName, max, provisioned, gap,
                        "spinShortfall would exceed maxAvailable — operator must raise the ceiling first");
            }
        }

        // (2) Spin per region. Collect the new pods' baseUrls so we can
        // gate the retry on their readiness.
        List<String> newBaseUrls = new ArrayList<>();
        for (Map.Entry<String, RegionShortfall> e : shortfall.entrySet()) {
            String regionName = e.getKey();
            int gap = e.getValue().requested() - e.getValue().claimed();
            for (int i = 0; i < gap; i++) {
                com.perf.globalorchestrator.provision.PodSpinService.SpinResult r =
                        spinService.spin(boundApp.applicationId(), boundApp.name(), regionName);
                newBaseUrls.add(r.baseUrl());
                LOG.info("spinShortfall: spun {} for app={} region={}",
                        r.podName(), boundApp.name(), regionName);
            }
        }

        // (3) Wait for every new pod to report /actuator/health 2xx so the
        // subsequent fanout doesn't race container startup. Bounded by
        // spinHealthTimeoutMs (default 60 s) — that's enough for a fresh
        // jmeter-local-orchestrator container to bind 8080 even on a busy
        // host. Past the timeout, return anyway; the retry's
        // claimIdleByRegionAndApp will still pick up the rows (which are
        // already IDLE in the registry), and the fanout will reject any
        // unreachable pod into REJECTED — same as today's behavior for
        // any unlucky pod that crashed between heartbeat and fanout.
        waitForHealth(newBaseUrls);
    }

    private void waitForHealth(List<String> baseUrls) {
        if (baseUrls.isEmpty()) return;
        long deadline = System.currentTimeMillis() + spinHealthTimeoutMs;
        java.util.Set<String> pending = new java.util.LinkedHashSet<>(baseUrls);
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            pending.removeIf(localClient::isHealthy);
            if (pending.isEmpty()) break;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.warn("spinShortfall health-wait interrupted; proceeding with retry");
                return;
            }
        }
        if (!pending.isEmpty()) {
            LOG.warn("spinShortfall: {} pod(s) still unhealthy after {} ms — proceeding anyway",
                    pending.size(), spinHealthTimeoutMs);
        }
    }

    /**
     * MID-TEST-SCALING Phase A — adds workers to a RUNNING run.
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
    @Observed(name = "globalOrchestrator.scaleUpRun",
              contextualName = "scaleUpRun",
              lowCardinalityKeyValues = {"action", "scaleUpRun"})
    public ScaleUpRunResponse scaleUpRun(String runId, ScaleUpRunRequest request,
                                         boolean bestEffort, boolean spinShortfall, Actor actor) {
        SpanAttributes.tag("runId", runId);
        SpanAttributes.tag("bestEffort", String.valueOf(bestEffort));
        SpanAttributes.tag("spinShortfall", String.valueOf(spinShortfall));
        SpanAttributes.tag("actor", actor.name());
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        // AUDIT-TRAIL Phase C — the run exists from here on, so a rejected
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
            scaleUpsStarted.increment();

            // Fan-out — same machinery as initial start, sourced from the
            // run row's persisted blob IDs.
            Map<String, FanoutOutcome> outcomes = fanOut(
                    runId, opened.members(),
                    run.testPlanBlobId(), run.dataFilesBlobId(),
                    run.application(), run.saveResults());

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
            scaleUpsRejected.increment();
            recordScaleUpRejected(runId, request, bestEffort, actor, "RUN_NOT_SCALABLE");
            throw e;
        } catch (RunNotScalableNoApplicationException e) {
            scaleUpsRejected.increment();
            recordScaleUpRejected(runId, request, bestEffort, actor, "RUN_NOT_SCALABLE_NO_APPLICATION");
            throw e;
        } catch (FleetSizeExceededException e) {
            scaleUpsRejected.increment();
            recordScaleUpRejected(runId, request, bestEffort, actor, "FLEET_SIZE_EXCEEDED");
            throw e;
        } catch (ApplicationCapacityExceededException e) {
            scaleUpsRejected.increment();
            recordScaleUpRejected(runId, request, bestEffort, actor, "APPLICATION_CAPACITY_EXCEEDED");
            throw e;
        } catch (InsufficientCapacityException e) {
            scaleUpsRejected.increment();
            recordScaleUpRejected(runId, request, bestEffort, actor, "INSUFFICIENT_CAPACITY");
            throw e;
        } catch (IllegalArgumentException e) {
            scaleUpsRejected.increment();
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
        List<FleetAllocationEntry> allocation = resolveAllocation(request);
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
            // An allocation-based run claims exclusively from its target
            // application's own pod pool, so it MUST resolve to a registered
            // application. A run naming no app (or an unregistered one) has
            // no pool to claim from now that the legacy null-app pool is
            // gone — reject it up front with the same 409 surface as a
            // capacity miss (the app effectively has zero configured
            // capacity). The legacy UNKNOWN_REGION pre-check that used to
            // live here only fired for the null-app path; for a registered
            // app the per-region capacity-grid check below is the
            // authoritative region validator (a never-configured region
            // yields a clearer 409 "no capacity configured ... in region X").
            if (boundApp == null) {
                String name = (request.application() == null || request.application().isBlank())
                        ? "(none)" : request.application();
                throw new ApplicationCapacityExceededException(
                        name, allocation.get(0).region(), 0, 0, allocation.get(0).count(),
                        "run targets unregistered application '" + name + "'; allocation-based "
                        + "runs require a registered application with configured capacity");
            }
            for (FleetAllocationEntry e : allocation) {
                var capacityRow = applicationCapacity.find(boundApp.applicationId(), e.region());
                if (capacityRow.isEmpty()) {
                    throw new ApplicationCapacityExceededException(
                            boundApp.name(), e.region(), 0, 0, e.count(),
                            "no capacity configured for this app in region '" + e.region() + "'");
                }
                int max = capacityRow.get().maxAvailable();
                int active = applicationCapacity.countActivePodsForAppRegion(boundApp.name(), e.region());
                if (active + e.count() > max) {
                    throw new ApplicationCapacityExceededException(
                            boundApp.name(), e.region(), max, active, e.count(), null);
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
                // Phase 6b: every allocation-based run claims from its target
                // application's own pod pool. boundApp is guaranteed non-null
                // here — the validation above rejects an allocation run that
                // names no registered application (the legacy null-app pool
                // and its claimIdleByRegion fallback are gone).
                List<Pod> regionClaim = pods.claimIdleByRegionAndApp(
                        e.region(), boundApp.applicationId(), e.count());
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
            claimShortfalls.increment();
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

        String runId = Ulid.generate();
        Instant now = Instant.now();
        Run run = new Run(
                runId, region,
                request.testPlanBlobId(),
                request.dataFilesBlobId(),
                // UI-D3 — empty/blank `application` is normalised to NULL so
                // the LIKE filter doesn't accidentally match an empty string.
                (request.application() == null || request.application().isBlank())
                        ? null : request.application(),
                // AUDIT-TRAIL — `initiatedBy` is the actor (X-Actor / cached
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
                List.of());
        runs.insertRun(run);

        List<RunFleetMember> initialMembers = new ArrayList<>(claimed.size());
        for (Pod p : claimed) {
            Map<String, String> props = propertiesByPodId.getOrDefault(p.podId(), Map.of());
            RunFleetMember m = new RunFleetMember(
                    runId, p.podId(), p.region(), MemberState.PENDING,
                    null, null, p.baseUrl(), now, null, null, props);
            runs.insertFleetMember(m);
            initialMembers.add(m);
            // WORKER-HYGIENE Phase B — bump the per-pod run counter inside
            // the run-claim transaction so the value is consistent with
            // runFleetMember insert rate. Phase D's reconciler reads this
            // to decide "this pod has done N runs; recycle it on idle."
            pods.incrementRunsServed(p.podId());
        }
        runs.updateRunState(runId, RunState.STARTING, stateReason);

        // AUDIT-TRAIL Phase C — one RUN_START event, written inside this
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

        return new StartTransactionResult(runId, initialMembers, stateReason);
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
     * MID-TEST-SCALING Phase B — drains workers from a RUNNING run.
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
    @Observed(name = "globalOrchestrator.scaleDownRun",
              contextualName = "scaleDownRun",
              lowCardinalityKeyValues = {"action", "scaleDownRun"})
    public ScaleDownRunResponse scaleDownRun(String runId, ScaleDownRunRequest request, Actor actor) {
        SpanAttributes.tag("runId", runId);
        SpanAttributes.tag("actor", actor.name());
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        // AUDIT-TRAIL Phase C — the run exists, so each rejection below records
        // a rejected event before throwing (RunNotFound above does not — there
        // is nothing to FK the event to). scaleDownRun is not transactional, so
        // the SCALE_DOWN / DRAIN_WORKER outcome event is written post-hoc at the
        // return sites with the actual drained/skipped ledger.
        if (run.state() != RunState.RUNNING) {
            scaleDownsRejected.increment();
            recordScaleDownRejected(runId, request, actor, "RUN_NOT_SCALABLE");
            throw new RunNotInScalableStateException(runId, run.state());
        }
        if (!request.isExclusive()) {
            scaleDownsRejected.increment();
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
                    scaleDownsRejected.increment();
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
                    scaleDownsRejected.increment();
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
        scaleDownsStarted.increment();

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
            final String baseUrl = m.podBaseUrl();
            futures.put(m.workerId(), fanoutPool.submit(() -> localClient.drainTest(runId, baseUrl)));
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
     * Force-terminates a run — the operator's "stop now" / zombie-run cleanup.
     * Unlike {@link #scaleDownRun} (graceful, run keeps running), abort rolls
     * the WHOLE run to ABORTED and releases every fleet-member binding, freeing
     * the run's pods for re-claim and drain.
     *
     * <p>Flow (mirrors scaleDown's transaction discipline):
     * <ol>
     *   <li>404 {@link RunNotFoundException} if the run is unknown; 409
     *       {@link RunNotAbortableException} if it is already terminal — nothing
     *       to abort, its pods are already released (idempotency guard so a
     *       double-click doesn't emit a second ABORT event).</li>
     *   <li>Best-effort fire {@code POST /api/v1/test/abort} to each non-terminal
     *       member OUTSIDE the transaction (long-haul HTTP). A zombie run's
     *       workers are already gone → those RPCs fail and land in the skipped
     *       ledger; the run is force-aborted regardless.</li>
     *   <li>In one transaction ({@link #commitAbort}): mark every non-terminal
     *       member ABORTED, roll the run to ABORTED, and append one ABORT audit
     *       event — atomic so a rolled-back mutation emits zero events.</li>
     * </ol>
     *
     * <p>Marking the members terminal is load-bearing, not cosmetic: the
     * pod-claim {@code NOT EXISTS} guard, {@code countActivePodsForAppRegion},
     * and {@link #rollUp} all key on member state. A run left ABORTED with
     * members still RUNNING would leave its pods unclaimable AND could
     * self-downgrade back to RUNNING on the next status poll of a saveResults
     * run (rollUp sees a live member). Anchoring the binding release on member
     * state is also what lets the drained pods read as READY immediately.
     */
    @Observed(name = "globalOrchestrator.abortRun",
              contextualName = "abortRun",
              lowCardinalityKeyValues = {"action", "abortRun"})
    public Run abortRun(String runId, Actor actor, String reason) {
        SpanAttributes.tag("runId", runId);
        SpanAttributes.tag("actor", actor.name());
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
        List<String> aborted = new ArrayList<>();
        List<RunEventPayloads.Skipped> skipped = new ArrayList<>();
        Map<String, Future<LocalOrchestratorClient.AbortTestResult>> futures = new LinkedHashMap<>();
        for (RunFleetMember m : targets) {
            if (m.podBaseUrl() == null) {
                skipped.add(new RunEventPayloads.Skipped(m.workerId(), "no podBaseUrl recorded"));
                continue;
            }
            final String baseUrl = m.podBaseUrl();
            futures.put(m.workerId(), fanoutPool.submit(() -> localClient.abortTest(runId, baseUrl)));
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
        runsAborted.increment();
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
        SpanAttributes.tag("runId", runId);
        SpanAttributes.tag("actor", actor.name());
        Run run = runs.findByRunId(runId).orElseThrow(() -> new RunNotFoundException(runId));
        if (!run.state().isTerminal()) {
            throw new RunNotDeletableException(runId, run.state());
        }
        // Commit the audit event + hide atomically through the self proxy so
        // @Transactional applies (proxy-mode self-invocation limitation) — a
        // failure rolls back BOTH (the "rolled-back mutation emits zero events"
        // invariant).
        self.commitDelete(runId, actor, reason);
        runsDeleted.increment();
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
    public void reapLostWorkerMembers(String reason) {
        List<String> affected;
        try {
            affected = runs.failActiveMembersOnLostPods(reason);
        } catch (RuntimeException e) {
            LOG.warn("reapLostWorkerMembers: failing members on LOST pods failed: {}", e.toString());
            return;
        }
        if (affected.isEmpty()) return;
        membersFailedLost.increment(affected.size());
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
        // Look up the application by name to get its applicationId for
        // the per-app claim path. The run row carries the app *name*, but
        // the pod table is keyed on applicationId.
        com.perf.globalorchestrator.domain.Application boundApp = applications
                .findByName(run.application())
                .orElseThrow(() -> new IllegalStateException(
                        "run " + run.runId() + " references unknown application '" + run.application() + "'"));

        // Per-(app, region) capacity gate — same shape as
        // openRunAndClaimPods. Applied BEFORE claim so a rejected scale-up
        // never holds capacity. The active count INCLUDES the run's own
        // existing members (they're counted by countActivePodsForAppRegion
        // because they're in active member states), so the gate naturally
        // honors the cumulative-fleet cap across the run's lifetime.
        for (FleetAllocationEntry e : allocation) {
            var capacityRow = applicationCapacity.find(boundApp.applicationId(), e.region());
            if (capacityRow.isEmpty()) {
                throw new ApplicationCapacityExceededException(
                        boundApp.name(), e.region(), 0, 0, e.count(),
                        "no capacity configured for this app in region '" + e.region() + "'");
            }
            int max = capacityRow.get().maxAvailable();
            int active = applicationCapacity.countActivePodsForAppRegion(boundApp.name(), e.region());
            if (active + e.count() > max) {
                throw new ApplicationCapacityExceededException(
                        boundApp.name(), e.region(), max, active, e.count(), null);
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
            List<Pod> regionClaim = pods.claimIdleByRegionAndApp(
                    e.region(), boundApp.applicationId(), e.count());
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
            claimShortfalls.increment();
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
            // WORKER-HYGIENE Phase B — same per-pod counter bump as the
            // initial-claim path. A scale-up member is a fresh run-claim
            // for that pod, even though the parent run is mid-flight.
            pods.incrementRunsServed(p.podId());
        }

        // AUDIT-TRAIL Phase C — one SCALE_UP event, atomic with the new
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
    @Observed(name = "globalOrchestrator.refreshAndGet",
              contextualName = "refreshAndGet",
              lowCardinalityKeyValues = {"action", "refreshAndGet"})
    public Run refreshAndGet(String runId) {
        SpanAttributes.tag("runId", runId);
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
        for (RunFleetMember m : run.fleetMembers()) {
            if (m.podBaseUrl() == null) continue;
            // State mapping only matters while the run is live; a terminal
            // member's state never changes, but its uploadState still might.
            boolean pollState = !terminal && !m.state().isTerminal();
            if (!pollState && !run.saveResults()) continue;
            localClient.getTestStatus(m.podBaseUrl()).ifPresent(snap -> {
                if (pollState) {
                    MemberState mapped = mapLocalState(snap.get("state"));
                    if (mapped != null && mapped != m.state()
                            // MID-TEST-SCALING Phase B — DRAINING is sticky.
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
            runs.updateRunState(runId, rolled, null);
            // AUDIT-TRAIL — the platform just detected the run reached a
            // terminal state. Emit exactly one terminal event (the early
            // return at the top of this method on subsequent polls — the run
            // is now terminal — guarantees no duplicate).
            if (rolled.isTerminal()) {
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
                // AUTOMATION Phase F — snapshot the run's aggregate into
                // runTrend, but only for a clean COMPLETED (a failed/aborted
                // run's partial metrics aren't a meaningful baseline). This is
                // the single terminal-transition tick (the early-return at the
                // top guarantees we never re-enter for an already-terminal run),
                // so the snapshot happens exactly once.
                if (rolled == RunState.COMPLETED) {
                    recordRunTrend(refreshed);
                }
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
     * CACHE C-2 (2026-05-26) — the run's metadata (row + fleet members).
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
     * AUDIT-TRAIL — one page of the reverse-chronological audit timeline for a
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
                                          String stateReason) {}

    /** MID-TEST-SCALING Phase A — return shape of {@link #openMembersInExistingRun}. */
    private record ScaleUpTransactionResult(List<RunFleetMember> members, String stateReason) {}

    private record FanoutOutcome(MemberState state, int statusCode, String reason) {}

    private Map<String, FanoutOutcome> fanOut(String runId, List<RunFleetMember> members,
                                              String testPlanBlobId, String dataFilesBlobId,
                                              String application, boolean saveResults) {
        // KAFKA-PER-APP Phase C — registered-app runs route metrics to
        // their per-app topic; the topic name is computed once for the
        // whole fan-out (every member of a run shares the same topic).
        // Legacy untagged runs leave the field unset → the local-orch
        // falls back to its own KAFKA_TOPIC env-var default
        // ({@code jmeter.metrics.perSecond}), preserving the pre-Phase-A
        // wire shape until Phase E retires the legacy topic.
        String perRunKafkaTopic = (application != null && !application.isBlank())
                ? "jmeter.metrics." + application
                : null;
        Map<String, Future<FanoutOutcome>> futures = new LinkedHashMap<>();
        for (RunFleetMember m : members) {
            runs.updateMemberState(runId, m.workerId(), MemberState.REQUESTED, null, null);
            Map<String, Object> body = new HashMap<>();
            body.put("runId", runId);
            // Each worker must stamp ITS OWN region onto every WorkerMetric it
            // publishes — the metrics "region" column drives the UI's split-by-
            // region view + per-region Grafana. Previously this sent the
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
            if (perRunKafkaTopic != null) {
                body.put("kafkaTopic", perRunKafkaTopic);
            }
            // MID-TEST-SCALING Phase C — only include joinedAtSecond on
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
                StartTestResult r = localClient.startTest(runId, m.podBaseUrl(), body);
                if (r.accepted() || r.ok()) {
                    return new FanoutOutcome(MemberState.ACCEPTED, r.statusCode(), null);
                }
                return new FanoutOutcome(MemberState.FAILED, r.statusCode(),
                        r.body() == null ? "no body" : truncate(r.body(), 240));
            }));
        }
        Map<String, FanoutOutcome> outcomes = new LinkedHashMap<>();
        futures.forEach((workerId, f) -> {
            try {
                FanoutOutcome o = f.get();
                outcomes.put(workerId, o);
                runs.updateMemberState(runId, workerId, o.state(), o.reason(), o.statusCode());
                if (o.state() == MemberState.ACCEPTED) fanoutsAccepted.increment();
                else fanoutsRejected.increment();
            } catch (Exception e) {
                ErrorContext.logWarn(LOG,
                        "startFanout runId=" + runId + " workerId=" + workerId,
                        "fan-out future threw",
                        e);
                outcomes.put(workerId, new FanoutOutcome(MemberState.FAILED, 0, e.toString()));
                runs.updateMemberState(runId, workerId, MemberState.FAILED, e.toString(), 0);
                fanoutsRejected.increment();
            }
        });
        return outcomes;
    }

    private static MemberState mapLocalState(Object stateObj) {
        if (stateObj == null) return null;
        String s = stateObj.toString();
        return switch (s) {
            case "IDLE", "PREPARING", "STARTING" -> MemberState.ACCEPTED;
            case "RUNNING", "DRAINING"           -> MemberState.RUNNING;
            case "COMPLETED" -> MemberState.COMPLETED;
            case "FAILED"    -> MemberState.FAILED;
            case "ABORTED"   -> MemberState.ABORTED;
            // MID-TEST-SCALING Phase B — local DRAINED → global DRAINED.
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
            // MID-TEST-SCALING Phase B — DRAINED counts as a successful
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
        // MID-TEST-SCALING Phase B — DRAINING members are live; the run
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
     * AUDIT-TRAIL — a platform-detected run-terminal event (RUN_COMPLETED /
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
     * AUTOMATION Phase F — snapshot one COMPLETED run's aggregate into
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
            MetricsRollupRepository.RunAggregate agg = metricsRollup.runAggregate(run.runId());
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
            recordEvent(runId, RunEventType.RESULTS_SAVED, Actor.system("orchestrator"),
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
     * MID-TEST-SCALING Phase A — surface as 409 CONFLICT.
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
     * MID-TEST-SCALING Phase A — surface as 409 CONFLICT.
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
    public static class ApplicationCapacityExceededException extends RuntimeException {
        private final String application;
        private final String region;
        private final int max;
        private final int active;
        private final int requested;

        public ApplicationCapacityExceededException(String application, String region,
                                                    int max, int active, int requested,
                                                    String customMessage) {
            super(customMessage != null
                    ? customMessage
                    : "application '" + application + "' in region '" + region
                    + "' would exceed maxAvailable cap " + max
                    + " (currently " + active + " active + " + requested + " requested)");
            this.application = application;
            this.region = region;
            this.max = max;
            this.active = active;
            this.requested = requested;
        }

        public String application() { return application; }
        public String region() { return region; }
        public int max() { return max; }
        public int active() { return active; }
        public int requested() { return requested; }
    }
}
