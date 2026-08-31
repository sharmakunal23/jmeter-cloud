package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.Backend;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.observability.ErrorContext;
import com.perf.orchestrator.storage.ResultSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Owns the test lifecycle
 * {@code IDLE → PREPARING → STARTING → RUNNING → DRAINING → COMPLETED}, or
 * {@link TestState#FAILED} / {@link TestState#ABORTED}.
 *
 * <p>Two single-thread executors: one runs the lifecycle sequentially, the
 * other the streaming pipeline, so the pipeline can keep draining after JMeter
 * exits while the run worker watches. {@link #stop()} is SIGTERM escalating to
 * SIGKILL after {@code JMETER_TERMINATION_GRACE_S}; {@link #abort()} is SIGKILL
 * at once. Both then write the sentinel, drain, and classify.
 *
 * <p>A run found non-terminal in {@link CurrentRun} at construction is marked
 * FAILED with reason {@code orchestrator_restart} — its JMeter child died with
 * the previous orchestrator and cannot be resumed.
 */
// NOT final: kept subclassable deliberately. The @Observed spans that forced
// this (CGLIB can't subclass a final class) left with SLIMDOWN (2026-07-21),
// but any future Spring proxying of this bean (@Transactional, @Async, a
// re-added aspect) would hit the same "Cannot subclass final class" startup
// failure — the modifier is not worth re-fighting.
public class TestRunManager implements TestRunGate {

    private static final Logger LOG = LoggerFactory.getLogger(TestRunManager.class);

    /** ±5 s skew tolerance per the openapi spec for {@code scheduledStartAt}. */
    private static final Duration SCHEDULE_SKEW_TOLERANCE = Duration.ofSeconds(5);

    /** Cap for how long the run worker waits for the pipeline to drain after sentinel. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);

    private final OrchestratorConfig defaults;
    private final ArtifactStager stager;
    private final CurrentRun currentRun;
    private final JmeterLauncher launcher;
    private final Function<OrchestratorConfig, StreamingPipeline> pipelineFactory;
    private final ResultSink resultSink;
    private final Clock clock;
    /** MID-TEST-SCALING Phase B — sends Shutdown / StopTestNow to JMeter's TCP shutdown port. */
    private final JmeterShutdownPortClient shutdownPortClient;
    /** Null when {@code BEANSHELL_PORT=0} — runtime property pushes disabled. */
    private final BeanShellPropsClient beanShellPropsClient;
    /**
     * UX-DYNAMICS events — whether the LAST start's dataFiles came from the
     * staged cache (true) or a fresh download (false); null when the run
     * carried no dataFiles. Read into the 202 body right after {@code start()}
     * (staging is synchronous on the request thread, so this is race-free).
     */
    private volatile Boolean lastDataFilesReused;
    /**
     * UX-DYNAMICS events — true once the current/last run's preserved
     * artifacts were removed by the post-run cleanup (clean landing + upload
     * OK). Surfaced on the status snapshot so the hub's sweeper can record
     * ARTIFACTS_CLEARED right after RESULTS_SAVED.
     */
    private volatile boolean lastArtifactsCleared;
    /**
     * Re-pointed at each per-run log file
     * ({@code logs/{runId}/jmeter.log}) before launch and cleared on
     * post-run cleanup. Optional: when constructed without one (tests),
     * the file-fallback never engages.
     */
    private final com.perf.orchestrator.logs.LogTail logTail;
    /**
     * Pre-run worker hygiene. Nullable; when absent
     * the run proceeds exactly as it did before this phase.
     */
    private final com.perf.orchestrator.hygiene.OrphanJmeterReaper orphanReaper;
    private final com.perf.orchestrator.hygiene.RunArtifactRetention retention;

    private final ExecutorService runWorker = Executors.newSingleThreadExecutor(
            namedDaemon("orch-run-worker"));
    private final ExecutorService pipelineWorker = Executors.newSingleThreadExecutor(
            namedDaemon("orch-pipeline"));

    /**
     * The active in-flight task — used by {@link #stop()} / {@link #abort()}
     * to communicate with the worker. Volatile because the HTTP threads
     * read this field without taking the run worker's lock.
     */
    private volatile Inflight inflight;

    /**
     * Set by {@link #shutdownGracefully} so subsequent calls are no-ops.
     * Volatile (not synchronized) so the HTTP threads can observe the flag
     * without contending with the shutdown thread.
     */
    private volatile boolean shuttingDown;

    /**
     * Source for pre-run artifact fetches. With {@code ARTIFACT_SOURCE=DOCUMENT_SERVICE}
     * this is a {@code DocumentServiceArtifactSource} that pulls blobs
     * by id; with {@code HTTP_UPLOAD} (default) it's an
     * {@link com.perf.orchestrator.storage.HttpArtifactSource} that
     * always returns empty (the orchestrator runs whatever was staged
     * out-of-band). The bug-fix landed 2026-05-10 — before it,
     * {@code start()} never consulted this and always ran the
     * locally-staged plan, which made the operator's "upload + launch"
     * UX run a stale plan from a previous test.
     */
    private final com.perf.orchestrator.storage.ArtifactSource artifactSource;

    /** UX-DYNAMICS T3 — stages run-scoped plugin jars under {@code ${PLUGINS_DIR}}. */
    private final PluginStager pluginStager;

    public TestRunManager(OrchestratorConfig defaults,
                          ArtifactStager stager,
                          CurrentRun currentRun,
                          JmeterLauncher launcher,
                          Function<OrchestratorConfig, StreamingPipeline> pipelineFactory,
                          ResultSink resultSink,
                          com.perf.orchestrator.storage.ArtifactSource artifactSource,
                          Clock clock) {
        this(defaults, stager, currentRun, launcher, pipelineFactory, resultSink,
                artifactSource, clock, null);
    }

    public TestRunManager(OrchestratorConfig defaults,
                          ArtifactStager stager,
                          CurrentRun currentRun,
                          JmeterLauncher launcher,
                          Function<OrchestratorConfig, StreamingPipeline> pipelineFactory,
                          ResultSink resultSink,
                          com.perf.orchestrator.storage.ArtifactSource artifactSource,
                          Clock clock,
                          com.perf.orchestrator.logs.LogTail logTail) {
        this(defaults, stager, currentRun, launcher, pipelineFactory, resultSink,
                artifactSource, clock, logTail, null, null);
    }

    /**
     * STATIC-FLEET Phase 6 overload — adds the two worker-hygiene
     * collaborators. Both nullable: they matter on a worker that is never
     * recycled, and passing null (as the shorter constructors do) simply
     * skips the pre-run checks.
     */
    public TestRunManager(OrchestratorConfig defaults,
                          ArtifactStager stager,
                          CurrentRun currentRun,
                          JmeterLauncher launcher,
                          Function<OrchestratorConfig, StreamingPipeline> pipelineFactory,
                          ResultSink resultSink,
                          com.perf.orchestrator.storage.ArtifactSource artifactSource,
                          Clock clock,
                          com.perf.orchestrator.logs.LogTail logTail,
                          com.perf.orchestrator.hygiene.OrphanJmeterReaper orphanReaper,
                          com.perf.orchestrator.hygiene.RunArtifactRetention retention) {
        this.orphanReaper    = orphanReaper;
        this.retention       = retention;
        this.defaults        = Objects.requireNonNull(defaults);
        this.stager          = Objects.requireNonNull(stager);
        this.currentRun      = Objects.requireNonNull(currentRun);
        this.launcher        = Objects.requireNonNull(launcher);
        this.pipelineFactory = Objects.requireNonNull(pipelineFactory);
        this.resultSink      = Objects.requireNonNull(resultSink);
        this.artifactSource  = Objects.requireNonNull(artifactSource);
        this.clock           = Objects.requireNonNull(clock);
        this.logTail         = logTail;
        // The shutdown-port client itself is
        // stateless; the port number is fixed at construction (matches
        // the launch-time -Jjmeterengine.nongui.port flag).
        this.shutdownPortClient = new JmeterShutdownPortClient(defaults.getJmeterShutdownPort());
        this.beanShellPropsClient = defaults.getBeanshellPort() > 0
                ? new BeanShellPropsClient(defaults.getBeanshellPort()) : null;
        this.pluginStager = new PluginStager(defaults);

        recoverFromCrashIfNeeded();
    }

    private void recoverFromCrashIfNeeded() {
        if (currentRun.isActive()) {
            LOG.warn("Found a non-terminal run on startup ({}). The previous JMeter " +
                     "child is gone — marking FAILED.", currentRun.state());
            currentRun.recordFailure("orchestrator_restart");
        }
    }

    /** Implements {@link TestRunGate} so step 6's controllers can call into us. */
    @Override
    public boolean isRunning() {
        return currentRun.isActive();
    }

    public CurrentRun.Snapshot snapshot() {
        return currentRun.snapshot();
    }

    public Optional<CurrentRun.Snapshot> snapshotIfPresent() {
        return currentRun.snapshotIfPresent();
    }

    // -----------------------------------------------------------------------
    // Public API — start / stop / abort
    // -----------------------------------------------------------------------

    /**
     * Validates and accepts a new run. Synchronous validation only — the
     * actual run executes on the run worker. Throws {@link StartRejection}
     * with a stable code so the controller can map directly to the right
     * HTTP status.
     */
    public synchronized CurrentRun.Snapshot start(StartTestRequest request) {
        Objects.requireNonNull(request, "request");
        if (shuttingDown) {
            throw new StartRejection("SHUTTING_DOWN", 503,
                    "Orchestrator is shutting down; new tests cannot be accepted.");
        }
        if (request.runId() == null || request.runId().isBlank()) {
            throw new StartRejection("BAD_REQUEST", 400, "runId is required.");
        }
        if (currentRun.isActive()) {
            throw new StartRejection("TEST_RUNNING", 409,
                    "A test is already in progress (state=" + currentRun.state() + ").");
        }

        // Bug-fix 2026-05-10 — stage the operator's intended plan from
        // the configured artifact source BEFORE checking the staged
        // plan exists. Without this, every run executed whatever was
        // last staged out-of-band on the pod, ignoring the blobId the
        // global-orchestrator passed in. With ARTIFACT_SOURCE=HTTP_UPLOAD
        // the source is a no-op (returns Optional.empty), preserving the
        // legacy direct-upload flow.
        Boolean stagedDataFilesReused = stageFromArtifactSource(request);

        try {
            if (stager.getPlanMetadata().isEmpty()) {
                throw new StartRejection("NO_TEST_PLAN", 412,
                        "Upload a test plan via POST /api/v1/testPlan first.");
            }
        } catch (IOException io) {
            throw new StartRejection("INTERNAL_ERROR", 500,
                    "Could not read test plan metadata: " + io.getMessage());
        }

        currentRun.beginRun(request.runId(),
                firstNonBlank(request.region(), defaults.getTestRegion()));
        // The provenance flags swap runs only HERE — a rejected start attempt
        // must not wipe the previous run's artifactsCleared before the hub's
        // sweeper observes it.
        lastDataFilesReused = stagedDataFilesReused;
        lastArtifactsCleared = false;
        Inflight i = new Inflight(request);
        inflight = i;
        i.runFuture = runWorker.submit(() -> runLifecycle(i));
        return currentRun.snapshot();
    }

    /**
     * Bug-fix 2026-05-10 — pulls testPlan + dataFiles blobs from the
     * configured artifact source when the request supplies blobIds.
     * No-op when blobIds are absent OR the source returns empty (the
     * default {@code HttpArtifactSource} always returns empty, so the
     * legacy upload-out-of-band flow keeps working).
     *
     * <p>Translates IO failures into stable {@link StartRejection} codes
     * so the controller maps them to predictable HTTP statuses without
     * leaking transport details.
     */
    private Boolean stageFromArtifactSource(StartTestRequest request) {
        String runId = request.runId();
        Boolean dataFilesReused = null;
        if (request.testPlanBlobId() != null && !request.testPlanBlobId().isBlank()) {
            try {
                java.util.Optional<java.io.InputStream> body = artifactSource.fetch(
                        com.perf.orchestrator.storage.ArtifactSource.KIND_TEST_PLAN,
                        new com.perf.orchestrator.storage.FetchSpec(runId,
                                java.util.Map.of("blobId", request.testPlanBlobId())));
                if (body.isPresent()) {
                    try (java.io.InputStream in = body.get()) {
                        stager.storeTestPlan(in, "plan-from-blob-" + request.testPlanBlobId() + ".jmx");
                    }
                }
            } catch (IOException io) {
                throw new StartRejection("ARTIFACT_FETCH_FAILED", 502,
                        "Could not fetch testPlan blob "
                        + request.testPlanBlobId() + ": " + io.getMessage());
            }
        }
        if (request.dataFilesBlobId() != null && !request.dataFilesBlobId().isBlank()) {
            String blobId = request.dataFilesBlobId();
            boolean reused = false;
            // UX-DYNAMICS T4 — reuse the staged copy when this exact blob is
            // already extracted and intact; refreshDataFiles bypasses. A
            // failed reuse check falls back to a fresh download — reuse may
            // never fail a run.
            if (!Boolean.TRUE.equals(request.refreshDataFiles())) {
                try {
                    java.util.Optional<DataFilesManifest> m = stager.getDataFilesManifest();
                    if (m.isPresent() && blobId.equals(m.get().blobId())
                            && stager.dataFilesIntact(m.get())) {
                        LOG.info("Reusing staged dataFiles blobId={} ({} files, {} bytes) — download skipped",
                                blobId, m.get().fileCount(), m.get().extractedBytes());
                        reused = true;
                    }
                } catch (RuntimeException | IOException e) {
                    LOG.warn("dataFiles reuse check failed — falling back to download: {}", e.toString());
                }
            }
            if (!reused) {
                try {
                    java.util.Optional<java.io.InputStream> body = artifactSource.fetch(
                            com.perf.orchestrator.storage.ArtifactSource.KIND_DATA_FILES,
                            new com.perf.orchestrator.storage.FetchSpec(runId,
                                    java.util.Map.of("blobId", blobId)));
                    if (body.isPresent()) {
                        try (java.io.InputStream in = body.get()) {
                            stager.storeDataFiles(in, blobId);
                        }
                    }
                } catch (IOException io) {
                    throw new StartRejection("ARTIFACT_FETCH_FAILED", 502,
                            "Could not fetch dataFiles blob "
                            + blobId + ": " + io.getMessage());
                }
            }
            dataFilesReused = reused;
        }
        // UX-DYNAMICS T3 — stage the run's library plugin jars (content-
        // addressed: cached blobs are never re-downloaded). A malformed
        // bundle throws ArtifactValidationException → 400, like dataFiles.
        if (!request.plugins().isEmpty()) {
            try {
                pluginStager.stage(artifactSource, runId, request.plugins());
            } catch (IOException io) {
                throw new StartRejection("ARTIFACT_FETCH_FAILED", 502,
                        "Could not fetch plugin jar(s): " + io.getMessage());
            }
        }
        return dataFilesReused;
    }

    /** Outcome of a runtime property push (UX-DYNAMICS T5). */
    public enum PropsPushOutcome { DISABLED, SENT, UNREACHABLE }

    /**
     * Pushes JMeter properties into the RUNNING child via the BeanShell
     * server. Only plan values read through {@code ${__P(name)}} observe the
     * update, at their next evaluation — thread counts do not change.
     */
    // Deliberately NOT synchronized: the client is stateless and the socket
    // carries 2s+2s timeouts — holding the manager monitor here would delay
    // stop()/abort()/start() by up to ~4 s on a wedged push.
    public PropsPushOutcome pushProperties(java.util.Map<String, String> properties) {
        if (beanShellPropsClient == null) return PropsPushOutcome.DISABLED;
        return beanShellPropsClient.sendProperties(properties)
                ? PropsPushOutcome.SENT : PropsPushOutcome.UNREACHABLE;
    }

    /** Tri-state for the 202 body: reused / downloaded / null = the run had no dataFiles. */
    public Boolean lastDataFilesReused() {
        return lastDataFilesReused;
    }

    /** True once the current/last run's preserved artifacts were cleaned post-run. */
    public boolean lastArtifactsCleared() {
        return lastArtifactsCleared;
    }

    /** Graceful stop — SIGTERM, drain, then COMPLETED/ABORTED. Idempotent. */
    public synchronized void stop() {
        Inflight i = inflight;
        if (i == null) return;
        i.stopRequested = true;
        if (i.process != null) i.process.sigterm();
    }

    /** Hard kill — SIGKILL, drain, ABORTED. Idempotent. */
    public synchronized void abort() {
        Inflight i = inflight;
        if (i == null) return;
        i.abortRequested = true;
        if (i.process != null) i.process.sigkill();
    }

    /**
     * Drains gracefully by sending "Shutdown" to JMeter's TCP shutdown port, so
     * in-flight samplers finish and the run lands {@link TestState#DRAINED}
     * rather than COMPLETED. Idempotent.
     *
     * <p>A failed TCP send (port not listening yet, JMeter already gone) falls
     * back to SIGTERM, which JMeter treats as the less graceful StopTestNow.
     * Exceeding {@code JMETER_DRAIN_TIMEOUT_S} escalates to SIGKILL and the run
     * lands ABORTED with reason {@code drainTimeoutExpired}.
     */
    public synchronized void drain() {
        Inflight i = inflight;
        if (i == null) return;
        if (i.drainRequested) return;  // already draining
        i.drainRequested = true;
        i.drainRequestedAtMs = System.currentTimeMillis();
        boolean sent = shutdownPortClient.sendShutdown();
        if (!sent && i.process != null) {
            // TCP path unavailable — fall back to SIGTERM. The drainRequested
            // flag is still set so the eventual exit will be classified as
            // DRAINED (assuming clean exit) rather than ABORTED.
            LOG.info("drain TCP send failed; falling back to SIGTERM for run {}",
                    i.request.runId());
            i.process.sigterm();
        }
    }

    /**
     * Fast shutdown — interrupts the run worker and pipeline worker
     * immediately. Used by tests; production code should call
     * {@link #shutdownGracefully(Duration)} so an in-flight test gets the
     * documented SIGTERM → grace → drain sequence before threads die.
     */
    public void shutdown() {
        runWorker.shutdownNow();
        pipelineWorker.shutdownNow();
    }

    /**
     * Drives any in-flight test through its normal stop → drain → terminal path
     * before the JVM exits, refusing new runs from the moment it is called.
     * Idempotent.
     *
     * <p>An executor that overruns its share of the budget is killed with
     * {@code shutdownNow()}, and the run loop records the run as
     * {@code ABORTED/interrupted}.
     *
     * @param grace total budget, split 90/10 between the run worker — which owns
     *              SIGTERM escalation, pipeline drain and auto-upload — and the
     *              pipeline worker. <b>A budget shorter than
     *              {@code JMETER_TERMINATION_GRACE_S} guarantees the JMeter
     *              child is SIGKILLed by the executor interrupt</b> rather than
     *              stopped by the orderly SIGTERM path.
     */
    public synchronized void shutdownGracefully(Duration grace) {
        if (shuttingDown) return;
        shuttingDown = true;
        Objects.requireNonNull(grace, "grace");

        // Tell the run worker's polling loop to begin SIGTERM/drain. If no
        // run is active, stop() is a no-op and the run worker already has
        // nothing to do.
        Inflight i = inflight;
        if (i != null) {
            LOG.info("Shutdown requested with {}s grace — driving in-flight run {} through stop → drain.",
                    grace.toSeconds(), i.request.runId());
            i.stopRequested = true;
            if (i.process != null) i.process.sigterm();
        } else {
            LOG.debug("Shutdown requested — no run in flight, executors will close immediately.");
        }

        // Refuse new submissions; let the current task complete normally.
        runWorker.shutdown();
        pipelineWorker.shutdown();

        long graceMs       = Math.max(grace.toMillis(), 1L);
        long runWorkerMs   = Math.max((graceMs * 9) / 10, 1L);
        long pipelineMs    = Math.max(graceMs - runWorkerMs, 1L);

        boolean runOrderly      = awaitOrLog(runWorker,      runWorkerMs, "run-worker");
        boolean pipelineOrderly = awaitOrLog(pipelineWorker, pipelineMs, "pipeline-worker");

        if (!runOrderly) {
            LOG.warn("Run worker did not drain within {} ms — forcing shutdownNow(). " +
                    "An in-flight test will be marked ABORTED/interrupted.", runWorkerMs);
            runWorker.shutdownNow();
        }
        if (!pipelineOrderly) {
            LOG.warn("Pipeline worker did not drain within {} ms — forcing shutdownNow(). " +
                    "Final 1-second metric window may not reach the metrics-consumer.", pipelineMs);
            pipelineWorker.shutdownNow();
        }
    }

    private static boolean awaitOrLog(java.util.concurrent.ExecutorService es, long millis, String name) {
        try {
            return es.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while awaiting {} termination — forcing immediate shutdown.", name);
            return false;
        }
    }

    /** Test/diagnostic only — used by start() to short-circuit if shutdown is in progress. */
    boolean isShuttingDown() {
        return shuttingDown;
    }

    // -----------------------------------------------------------------------
    // Lifecycle worker
    // -----------------------------------------------------------------------

    private void runLifecycle(Inflight i) {
        // perRun has to be visible in finally for the post-run cleanup
        // (WORKER-HYGIENE Phase A), so declare it here — but BUILD it inside
        // the try. buildPerRunConfig() runs OrchestratorConfig.from(env),
        // which throws OrchestratorConfigException on a bad config combo; if
        // that throw escapes runLifecycle it is swallowed by the run-worker
        // Future and the run hangs at PREPARING with no log and no recorded
        // failure. Keeping it inside the try routes any such failure through
        // the catch's recordFailure path so it is visible.
        OrchestratorConfig perRun = null;
        try {
            perRun = buildPerRunConfig(i.request);
            // Clean slate before JMeter is launched.
            // Deliberately here, on the run worker, and not in start(): a
            // process scan plus a SIGTERM→SIGKILL escalation can take seconds,
            // which must not block the Tomcat request thread. The run has
            // already been accepted; if the slate can't be cleaned it lands
            // FAILED with a reason, which is visible and correct.
            requireNoOrphanJmeter();
            sweepOldRunArtifacts(i.request.runId());
            // PREPARING — fresh per-run subdirs are created lazily by
            // buildLaunchSpec. WORKER-HYGIENE Phase A: the per-run dir layout
            // ({@code results/{runId}/}, {@code logs/{runId}/}) eliminates
            // the cross-run contamination that motivated the old
            // start-of-run cleanResultsAndLogs sweep — cleanup now runs in
            // the post-run finally block below.
            waitForScheduledStart(i.request);

            if (i.abortRequested) { currentRun.recordAborted("aborted_before_start"); return; }
            if (i.stopRequested)  { currentRun.recordAborted("stopped_before_start"); return; }

            // STARTING — spawn JMeter.
            currentRun.transitionTo(TestState.STARTING);
            JmeterLauncher.LaunchSpec spec = buildLaunchSpec(perRun, i.request);
            i.process = launcher.launch(spec);
            currentRun.recordJmeterPid(i.process.pid());

            // WORKER-OOM — make the child the kernel's preferred OOM victim so a
            // shared-cgroup OOM can never reap PID 1 (the orchestrator), and
            // snapshot the cgroup oom_kill counter so we can later tell a genuine
            // OOM apart from any other SIGKILL. Both are best-effort and Linux-only.
            CgroupOom.preferAsOomVictim(i.process.pid(), perRun.getJmeterOomScoreAdj());
            i.oomKillBaseline = CgroupOom.oomKillCount();

            // Start the streaming pipeline on its own thread; it blocks
            // until the sentinel arrives or the state machine times out.
            StreamingPipeline pipeline = pipelineFactory.apply(perRun);
            Future<?> pipelineDone = pipelineWorker.submit(pipeline::run);

            currentRun.transitionTo(TestState.RUNNING);

            // Wait for JMeter exit. We poll in short slices so a stop /
            // abort request reaches the process within a second.
            int exitCode = waitForJmeter(i, perRun);
            currentRun.recordExit(exitCode);

            // DRAINING — write sentinel so the pipeline transitions to DRAINING/DONE.
            writeSentinel(perRun);
            currentRun.transitionTo(TestState.DRAINING);
            try {
                pipelineDone.get(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception drainErr) {
                LOG.warn("Pipeline drain did not complete within {}: {}",
                        DRAIN_TIMEOUT, drainErr.toString());
                pipelineDone.cancel(true);
            }

            // Final classification. Order matters: abortRequested wins over
            // drainRequested (drain timeout sets abortRequested=true and
            // we want that to land as ABORTED, not DRAINED).
            boolean reachedCompleted = false;
            if (i.abortRequested) {
                // Drain timeout escalation
                // sets both drainRequested=true and abortRequested=true.
                // Distinguish "operator asked for abort" from "drain
                // timed out" via the reason string so the run-detail
                // page can show the right message.
                String reason = i.drainRequested
                        ? "drainTimeoutExpired"
                        : "aborted_by_request";
                currentRun.recordAborted(reason);
            } else if (i.drainRequested && exitCode == 0) {
                currentRun.recordDrained();
            } else if (i.stopRequested && exitCode != 0) {
                currentRun.recordAborted("stopped_by_request");
            } else if (exitCode != 0) {
                // WORKER-OOM — if the cgroup oom_kill counter advanced while this
                // run held the child, the non-zero exit was a kernel OOM SIGKILL,
                // not a clean JMeter error. Surface it unambiguously so it's not
                // confused with a recycler/operator kill (both land as exit 137).
                currentRun.recordFailure(classifyExit(i, exitCode));
            } else {
                currentRun.transitionTo(TestState.COMPLETED);
                reachedCompleted = true;
            }

            // Auto-upload kicks in only on a clean COMPLETED. Failures /
            // aborts skip the upload (no point pushing a partial run; the
            // JTL stays on disk for diagnosis). The sink check is defensive:
            // OrchestratorConfig.validateUploadCombo already rejects
            // autoUpload=true with a non-DOCUMENT_SERVICE sink at boot
            // (and on per-run override), so reaching here with
            // autoUpload=true guarantees DOCUMENT_SERVICE.
            if (reachedCompleted
                    && perRun.isAutoUploadResults()
                    && perRun.getResultSink() == Backend.DOCUMENT_SERVICE) {
                new ResultUploader(perRun, resultSink)
                        .upload(perRun, currentRun, perRun.getPodName(), i.request.application());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            currentRun.recordAborted("interrupted");
        } catch (Exception e) {
            ErrorContext.logError(LOG,
                    "runWorker runId=" + i.request.runId(),
                    "Run failed unexpectedly",
                    e);
            currentRun.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // Eager post-run cleanup. Sweeps
            // results/{runId}/ + logs/{runId}/ when the run terminated
            // cleanly. Preserved for:
            //   - FAILED / ABORTED (decision #7) — postmortem JTL + log.
            //   - COMPLETED with uploadState=FAILED — ResultUploader's
            //     contract is "permanent failure leaves the gzipped JTL
            //     on disk so an operator can fetch it via GET /results
            //     and replay the upload by hand". Sweeping would break it.
            try {
                TestState terminal = currentRun.state();
                String uploadState = currentRun.uploadState();
                boolean cleanLandingState =
                        terminal == TestState.COMPLETED || terminal == TestState.DRAINED;
                boolean uploadOk =
                        !"FAILED".equals(uploadState) && !"PENDING".equals(uploadState)
                                && !"UPLOADING".equals(uploadState);
                if (perRun != null && cleanLandingState && uploadOk) {
                    cleanRunDirs(perRun);
                    lastArtifactsCleared = true;
                } else {
                    LOG.info("Skipping post-run cleanup for run {} (state={}, uploadState={}) — " +
                            "preserving artifacts for diagnosis or upload replay.",
                            i.request.runId(), terminal, uploadState);
                }
            } catch (Exception cleanupErr) {
                LOG.warn("Post-run cleanup failed for run {}: {}",
                        i.request.runId(), cleanupErr.toString());
            }
            if (logTail != null) logTail.setLogFile(null);
            inflight = null;
        }
    }

    /**
     * Classify a non-zero JMeter exit into a failure reason. WORKER-OOM: if the
     * cgroup's {@code oom_kill} counter advanced since launch, the child was
     * SIGKILLed by the kernel OOM killer — report {@code jmeter_oom} (with the raw
     * code, e.g. {@code jmeter_oom_137}) so it's distinct from a recycler/operator
     * kill or a clean JMeter error code. Falls back to {@code jmeter_exit_N} when
     * the counter is unreadable (non-Linux) or did not move.
     */
    private static String classifyExit(Inflight i, int exitCode) {
        long baseline = i.oomKillBaseline;
        if (baseline != CgroupOom.UNAVAILABLE) {
            long now = CgroupOom.oomKillCount();
            if (now != CgroupOom.UNAVAILABLE && now > baseline) {
                LOG.warn("JMeter child OOM-killed by the cgroup (oom_kill {} -> {}, exit {}). "
                        + "Orchestrator survived; run marked FAILED with preserved artifacts.",
                        baseline, now, exitCode);
                return "jmeter_oom_" + exitCode;
            }
        }
        return "jmeter_exit_" + exitCode;
    }

    /**
     * Polls for JMeter exit. While alive, checks the stop / abort flags and
     * escalates: a stop request that has been outstanding longer than
     * {@code JMETER_TERMINATION_GRACE_S} is escalated to SIGKILL.
     */
    private int waitForJmeter(Inflight i, OrchestratorConfig perRun) throws InterruptedException {
        long graceMs = perRun.getJmeterTerminationGraceSeconds() * 1000L;
        long drainTimeoutMs = perRun.getJmeterDrainTimeoutSeconds() * 1000L;
        Long stopRequestedAtMs = null;
        while (true) {
            Optional<Integer> exit = i.process.awaitExit(Duration.ofMillis(500));
            if (exit.isPresent()) return exit.get();

            if (i.abortRequested) {
                i.process.sigkill();
                continue;
            }
            // Drain timeout escalation. The
            // drain endpoint already sent "Shutdown" via TCP (or fell back
            // to SIGTERM); if JMeter is still alive past the budget,
            // escalate to abort. The drainRequested flag stays set so the
            // classifier records the reason as drainTimeoutExpired.
            if (i.drainRequested
                    && System.currentTimeMillis() - i.drainRequestedAtMs > drainTimeoutMs
                    && !i.abortRequested) {
                LOG.warn("JMeter did not exit within {}s of drain — escalating to abort",
                        drainTimeoutMs / 1000);
                i.abortRequested = true;
                i.process.sigkill();
                continue;
            }
            if (i.stopRequested) {
                if (stopRequestedAtMs == null) {
                    stopRequestedAtMs = System.currentTimeMillis();
                    i.process.sigterm();
                } else if (System.currentTimeMillis() - stopRequestedAtMs > graceMs) {
                    LOG.warn("JMeter did not exit within {}s of SIGTERM — escalating to SIGKILL", graceMs / 1000);
                    i.process.sigkill();
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Per-run config + JMeter command building
    // -----------------------------------------------------------------------

    private OrchestratorConfig buildPerRunConfig(StartTestRequest request) {
        Map<String, String> env = new HashMap<>();
        env.put("POD_NAME",            defaults.getPodName());
        env.put("TEST_REGION",         firstNonBlank(request.region(), defaults.getTestRegion()));
        env.put("RUN_ID",              request.runId());
        // Per-run subdirs. The JTL, sentinel,
        // and offset state-file all land under results/{runId}/ so the
        // post-run cleanup is a single directory remove.
        env.put("JTL_PATH",            jtlPath(defaults, request.runId()).toString());
        env.put("SENTINEL_PATH",       sentinelPath(defaults, request.runId()).toString());
        env.put("STATE_FILE_PATH",     runResultsDir(defaults, request.runId())
                                                 .resolve(".jtlOffset").toString());
        env.put("WORKER_ID_SOURCE",    firstNonBlank(request.workerIdSource(),
                defaults.isUseThreadName() ? "THREAD_NAME" : "POD_NAME"));
        // Streaming knob: per-run aggregator grace period (seconds). Forwarded
        // so the orchestrator's GRACE_PERIOD_SECONDS env actually takes effect
        // per run — previously it wasn't threaded through here, so every run
        // silently used the built-in default of 2 regardless of the env — and
        // so POST /test can raise it to capture slow samples written late to
        // the JTL (see StartTestRequest#gracePeriodSeconds). Request override >
        // env default; OrchestratorConfig.from validates it's a positive int.
        env.put("GRACE_PERIOD_SECONDS",
                request.gracePeriodSeconds() != null
                        ? Integer.toString(request.gracePeriodSeconds())
                        : Integer.toString(defaults.getGracePeriodSeconds()));
        // Window width and metrics group: request > env > default. The group
        // becomes ?groupId= on every envelope of this run (TailerStateMachine →
        // dispatcher → buffer filename → ingest client).
        env.put("FLUSH_WINDOW_SECONDS",
                request.windowSeconds() != null
                        ? Integer.toString(request.windowSeconds())
                        : Integer.toString(defaults.getFlushWindowSeconds()));
        String metricsGroupId = request.metricsGroupId() != null && !request.metricsGroupId().isBlank()
                ? request.metricsGroupId() : defaults.getMetricsGroupId();
        if (metricsGroupId != null) {
            env.put("METRICS_GROUP_ID", metricsGroupId);
        }
        // Carry the orchestrator-era settings through unchanged so the per-run
        // OrchestratorConfig validates against the same rules as the parent.
        env.put("BASE_DIR",        defaults.getBaseDir());
        env.put("TEST_PLAN_DIR",   defaults.getTestPlanDir());
        env.put("DATA_FILES_DIR",  defaults.getDataFilesDir());
        env.put("RESULTS_DIR",     defaults.getResultsDir());
        env.put("LOGS_DIR",        defaults.getLogsDir());
        env.put("RUN_STATE_FILE",  defaults.getRunStateFile());
        env.put("JMETER_HOME",     defaults.getJmeterHome());
        env.put("JMETER_BIN",      defaults.getJmeterBin());
        env.put("JMX_PORT",        Integer.toString(defaults.getJmxPort()));
        env.put("ARTIFACT_SOURCE", defaults.getArtifactSource().name());
        env.put("RESULT_SINK",
                firstNonBlank(request.resultSink(), defaults.getResultSink().name()));
        env.put("AUTO_UPLOAD_RESULTS",
                request.autoUploadResults() != null
                        ? Boolean.toString(request.autoUploadResults())
                        : Boolean.toString(defaults.isAutoUploadResults()));
        // Forward the boot DOCUMENT_SERVICE_URL into the per-run config.
        // validateUploadCombo (OrchestratorConfig) rejects RESULT_SINK=
        // DOCUMENT_SERVICE + AUTO_UPLOAD_RESULTS=true with a blank URL — and
        // since per-run autoUpload can flip true (saveResults runs) while the
        // sink is DOCUMENT_SERVICE, omitting this key made from(env) throw and
        // strand the run at PREPARING.
        env.put("DOCUMENT_SERVICE_URL", defaults.getDocumentServiceUrl());
        // UX-DYNAMICS T3 — plugin staging knobs. The per-run config is
        // rebuilt from this hand-written map; anything not copied here is
        // silently lost for the run.
        env.put("PLUGINS_DIR",               defaults.getPluginsDir());
        env.put("BEANSHELL_PORT",            String.valueOf(defaults.getBeanshellPort()));
        env.put("MAX_PLUGIN_SIZE_MB",        Integer.toString(defaults.getMaxPluginSizeMb()));
        env.put("PLUGINS_CACHE_MAX_ENTRIES", Integer.toString(defaults.getPluginsCacheMaxEntries()));
        env.put("PLUGINS_CACHE_MAX_BYTES",   Long.toString(defaults.getPluginsCacheMaxBytes()));
        // Null/missing → 0 (original-fleet);
        // non-null → propagated onto every WorkerMetricBatch so the
        // consumer can compute "live members at second X" rollups.
        env.put("JOINED_AT_SECOND",
                request.joinedAtSecond() != null
                        ? Long.toString(request.joinedAtSecond())
                        : "0");
        return OrchestratorConfig.from(env);
    }

    /** True when {@code dir} is an existing directory holding at least one entry. */
    private static boolean hasDataFiles(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            LOG.warn("Could not list data-files dir {}: {}", dir, e.toString());
            return false;
        }
    }

    private JmeterLauncher.LaunchSpec buildLaunchSpec(OrchestratorConfig perRun, StartTestRequest request) throws IOException {
        Path planFile = stager.getPlanFile().orElseThrow(() ->
                new IOException("test plan file disappeared between validation and launch"));

        // Every run gets its own subdirectory
        // under results/ and logs/ so post-run cleanup is a single
        // directory-remove and FAILED / ABORTED runs keep their artifacts
        // without colliding with the next run.
        Path runResults = runResultsDir(perRun, perRun.getRunId());
        Path runLogs    = runLogsDir(perRun, perRun.getRunId());
        Files.createDirectories(runResults);
        Files.createDirectories(runLogs);

        Path jtl = runResults.resolve("results.jtl");
        Path log = runLogs.resolve("jmeter.log");

        // Pre-create the java.util.prefs root so JMeter doesn't log the
        // "Could not lock User prefs" warning on first sync. The path
        // matches javaPrefsArgs() below.
        Files.createDirectories(Path.of(perRun.getBaseDir(), "state", "javaPrefs"));

        // Make this run's log discoverable to GET /api/v1/logs?stream=jmeter
        // — LogTail's file-fallback otherwise looks at a stale path.
        if (logTail != null) logTail.setLogFile(log);

        // Data-file resolution fix: JMeter's CSV Data Set Config resolves a
        // relative filename against the directory of the .jmx (its FileServer
        // base dir), NOT the process working directory. The plan stages under
        // TEST_PLAN_DIR while data files extract under DATA_FILES_DIR, so a
        // plan referencing `users.csv` by bare name would never find it.
        // When data files are present, co-locate the plan with them — copy
        // plan.jmx into the data-files dir and run `-t` from there so JMeter's
        // base dir holds both the plan and the CSVs.
        Path planForRun = planFile;
        Path dataDir = Path.of(perRun.getDataFilesDir());
        if (hasDataFiles(dataDir)) {
            planForRun = dataDir.resolve(planFile.getFileName().toString());
            Files.copy(planFile, planForRun, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Co-located plan into data-files dir {} so JMeter resolves relative data files", dataDir);
        }

        List<String> command = new ArrayList<>();
        command.add(perRun.getJmeterBin());
        command.add("-n");                 // non-GUI
        command.add("-t"); command.add(planForRun.toString());
        command.add("-l"); command.add(jtl.toString());
        command.add("-j"); command.add(log.toString());
        // Open JMeter's TCP shutdown port so
        // POST /api/v1/test/drain can send a graceful "Shutdown" command
        // that lets in-flight samplers complete. Without this the only
        // stop mechanism is OS signals (StopTestNow-equivalent), which
        // truncates the current iteration's samplers.
        command.add("-Jjmeterengine.nongui.port=" + perRun.getJmeterShutdownPort());
        // UX-DYNAMICS T5 — the BeanShell server accepts runtime props.put(...)
        // pushes (POST /api/v1/test/properties). The ABSOLUTE startup-file path
        // is required: the child's cwd is BASE_DIR, so the stock relative
        // default (../extras/startup.bsh) would not resolve. Missing file →
        // WARN + skip both flags (a slimmed image must not break launches).
        if (perRun.getBeanshellPort() > 0) {
            java.nio.file.Path startup =
                    java.nio.file.Path.of(perRun.getJmeterHome(), "extras", "startup.bsh");
            if (java.nio.file.Files.exists(startup)) {
                command.add("-Jbeanshell.server.port=" + perRun.getBeanshellPort());
                command.add("-Jbeanshell.server.file=" + startup);
            } else {
                LOG.warn("BeanShell startup file {} missing — runtime property updates disabled for this run", startup);
            }
        }
        // UX-DYNAMICS T3 — run-scoped library plugin jars ride search_paths:
        // JMeter adds those jars to its classloader AND scans them for
        // components, so the one flag covers a plugin and its bundled
        // dependency jars (no user.classpath — that would double-add).
        // Placed before properties/jmeterArgs on purpose: a later operator-
        // supplied -Jsearch_paths wins and disables the library jars for
        // that run.
        List<String> pluginJars = pluginStager.resolveJars(request.plugins());
        if (!pluginJars.isEmpty()) {
            command.add("-Jsearch_paths=" + String.join(";", pluginJars));
        }
        // Track G (Step 31) — structured per-node properties forwarded
        // as -JKEY=VAL args. Validation in StartTestRequest's compact
        // constructor guarantees the keys/values are shell-safe by the
        // time we get here.
        request.properties().forEach((k, v) -> command.add("-J" + k + "=" + v));
        // User-supplied -G/-J args land verbatim.
        command.addAll(request.jmeterArgs());

        Map<String, String> env = new HashMap<>();
        // Forward PATH and HOME so JMeter's launcher script works under
        // an empty environment. A more aggressive whitelist can come
        // later; PATH/HOME alone is the standard minimum.
        if (System.getenv("PATH") != null) env.put("PATH", System.getenv("PATH"));
        if (System.getenv("HOME") != null) env.put("HOME", System.getenv("HOME"));

        // JMeter respects the JVM_ARGS env var for the child JVM. Per-run
        // overrides take precedence over the orchestrator default.
        String jvmArgs = request.jmeterJvmArgs().isEmpty()
                ? perRun.getJmeterJvmArgs()
                : String.join(" ", request.jmeterJvmArgs());
        env.put("JVM_ARGS", jvmArgs + " " + jmxAgentArgs(perRun) + " " + javaPrefsArgs(perRun));

        return new JmeterLauncher.LaunchSpec(command, env, Path.of(perRun.getBaseDir()), log);
    }

    private static String jmxAgentArgs(OrchestratorConfig cfg) {
        // Localhost-only — JMX is consumed by the in-process JmxMetricsCollector
        // (added in step 8) and never exposed off-host.
        return String.join(" ",
                "-Dcom.sun.management.jmxremote",
                "-Dcom.sun.management.jmxremote.port=" + cfg.getJmxPort(),
                "-Dcom.sun.management.jmxremote.rmi.port=" + cfg.getJmxPort(),
                "-Dcom.sun.management.jmxremote.local.only=true",
                "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false",
                "-Djava.rmi.server.hostname=127.0.0.1");
    }

    /**
     * Silences the JMeter-console noise:
     * <pre>
     * WARNING: Could not lock User prefs. Unix error code 2.
     * WARNING: Couldn't flush user prefs: java.util.prefs.BackingStoreException
     * </pre>
     * Java's default {@link java.util.prefs.Preferences} writes to
     * {@code $HOME/.java/.userPrefs/} (and the system root). In a slim
     * JMeter container the user's home dir often doesn't exist or
     * isn't writable, so the JVM logs these warnings on every flush.
     * Pointing both roots at a writable path under {@code BASE_DIR}
     * eliminates the warning AND keeps any preferences a future plugin
     * tries to write co-located with the rest of the orchestrator's
     * state (cleaned up by Phase A's eager-cleanup sweep on its own
     * schedule). Created on demand by the JVM — no need to mkdir here.
     */
    private static String javaPrefsArgs(OrchestratorConfig cfg) {
        String prefsRoot = cfg.getBaseDir() + "/state/javaPrefs";
        return String.join(" ",
                "-Djava.util.prefs.userRoot="   + prefsRoot,
                "-Djava.util.prefs.systemRoot=" + prefsRoot);
    }

    // -----------------------------------------------------------------------
    // Filesystem housekeeping
    // -----------------------------------------------------------------------

    /**
     * Refuses to launch JMeter while a previous
     * JMeter is still alive.
     *
     * <p>The orchestrator's own {@code recoverFromCrashIfNeeded} only fixes
     * what it <em>believes</em> about the last run; it cannot see a child
     * that outlived it. On a worker the control plane recycles, that gap is
     * closed by replacing the container. On an operator-declared worker
     * nothing closes it, and a leftover child holding {@code -Xmx2g} would
     * contend with every subsequent run on the same host.
     *
     * <p>Under the default {@code KILL} policy the reaper clears the orphan
     * and the run proceeds. Only an orphan that could not be killed (or
     * {@code REPORT} policy) aborts the run — failing loudly beats running
     * a test whose numbers are quietly wrong because two JMeters were
     * sharing the box.
     */
    private void requireNoOrphanJmeter() {
        if (orphanReaper == null) return;
        com.perf.orchestrator.hygiene.OrphanJmeterReaper.Scan scan = orphanReaper.sweep();
        String unresolved = orphanReaper.unresolvedOrphan();
        if (unresolved != null) {
            throw new IllegalStateException(
                    "refusing to start a test — " + unresolved
                    + "; a leftover JMeter would contend with this run for memory and CPU");
        }
        if (scan.found() > 0) {
            LOG.warn("Cleared {} orphaned JMeter process(es) {} before starting the run.",
                    scan.killed(), scan.pids());
        }
    }

    /**
     * The "clear existing results/logs" half. Applies
     * the retention bounds before the run writes anything, so a worker that
     * has accumulated preserved artifacts from earlier failures starts each
     * run with bounded disk rather than discovering the ceiling mid-test.
     * The incoming runId is protected — its directories are about to be
     * created.
     */
    private void sweepOldRunArtifacts(String runId) {
        if (retention == null || retention.isDisabled()) return;
        try {
            retention.sweep(runId == null ? java.util.Set.of() : java.util.Set.of(runId));
        } catch (Exception e) {
            // Housekeeping must never fail a run — the run may still have room.
            LOG.warn("Pre-run artifact retention sweep failed: {}", e.toString());
        }
    }

    /**
     * Eager post-run cleanup. Deletes the
     * per-run subdirectories under {@code results/} and {@code logs/}.
     * Called from the {@link #runLifecycle} {@code finally} block on
     * COMPLETED / DRAINED only — FAILED / ABORTED preserve artifacts for
     * postmortem (decision #7).
     */
    private void cleanRunDirs(OrchestratorConfig perRun) throws IOException {
        deleteTree(runResultsDir(perRun, perRun.getRunId()));
        deleteTree(runLogsDir(perRun, perRun.getRunId()));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException io) { LOG.warn("could not delete {}: {}", p, io.toString()); }
                    });
        }
    }

    private static void writeSentinel(OrchestratorConfig perRun) throws IOException {
        Path sentinel = sentinelPath(perRun, perRun.getRunId());
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "0",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Path runResultsDir(OrchestratorConfig cfg, String runId) {
        return Path.of(cfg.getResultsDir()).resolve(runId);
    }

    private static Path runLogsDir(OrchestratorConfig cfg, String runId) {
        return Path.of(cfg.getLogsDir()).resolve(runId);
    }

    private static Path jtlPath(OrchestratorConfig cfg, String runId) {
        return runResultsDir(cfg, runId).resolve("results.jtl");
    }

    private static Path sentinelPath(OrchestratorConfig cfg, String runId) {
        return runResultsDir(cfg, runId).resolve(".done");
    }

    // -----------------------------------------------------------------------
    // Scheduled start
    // -----------------------------------------------------------------------

    private void waitForScheduledStart(StartTestRequest request) throws InterruptedException {
        Optional<Instant> maybeScheduled = request.scheduledStartInstant();
        if (maybeScheduled.isEmpty()) return;
        Instant scheduled = maybeScheduled.get();

        long delayMs = scheduled.toEpochMilli() - clock.instant().toEpochMilli();
        if (delayMs > SCHEDULE_SKEW_TOLERANCE.toMillis()) {
            LOG.info("Waiting until scheduledStartAt={} ({} ms)", scheduled, delayMs);
            Thread.sleep(delayMs);
        } else if (delayMs < -SCHEDULE_SKEW_TOLERANCE.toMillis()) {
            LOG.warn("scheduledStartAt={} is more than {} s in the past — starting immediately",
                    scheduled, SCHEDULE_SKEW_TOLERANCE.toSeconds());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static ThreadFactory namedDaemon(String prefix) {
        AtomicLong i = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + i.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Mutable handle for an in-flight run. Accessed by the run worker and HTTP threads. */
    private static final class Inflight {
        final StartTestRequest request;
        volatile Future<?> runFuture;
        volatile JmeterProcess process;
        volatile boolean stopRequested;
        volatile boolean abortRequested;
        /** MID-TEST-SCALING Phase B — drain triggered via POST /api/v1/test/drain. */
        volatile boolean drainRequested;
        /** Wall-clock ms when drain was requested; used by waitForJmeter to enforce timeout. */
        volatile long drainRequestedAtMs;
        /**
         * WORKER-OOM — cgroup {@code oom_kill} counter snapshotted at launch.
         * A post-exit delta of >= 1 distinguishes a genuine cgroup OOM from any
         * other SIGKILL (drain / operator), so a 137 exit is classified
         * {@code jmeter_oom} rather than the ambiguous {@code jmeter_exit_137}.
         * {@link CgroupOom#UNAVAILABLE} when the counter can't be read.
         */
        volatile long oomKillBaseline = CgroupOom.UNAVAILABLE;

        Inflight(StartTestRequest request) { this.request = request; }
    }

    /**
     * Thrown by {@link #start(StartTestRequest)} to communicate the right
     * HTTP status + error code back to the controller without leaking
     * exception types up the chain.
     */
    public static final class StartRejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final int status;

        public StartRejection(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code()    { return code; }
        public int    status()  { return status; }
    }
}
