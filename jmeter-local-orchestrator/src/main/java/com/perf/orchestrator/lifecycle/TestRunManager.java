package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.Backend;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.observability.ErrorContext;
import com.perf.orchestrator.observability.SpanAttributes;
import com.perf.orchestrator.storage.ResultSink;
import io.micrometer.observation.annotation.Observed;
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
 * Owns the test lifecycle:
 * {@code IDLE → PREPARING → STARTING → RUNNING → DRAINING → COMPLETED}
 * (or {@link TestState#FAILED} / {@link TestState#ABORTED}).
 *
 * <h2>Threads</h2>
 * One single-thread executor for the run worker (sequential lifecycle), one
 * single-thread executor for the inner streaming pipeline (so it can drain
 * after JMeter exits while the run worker observes), plus the daemon
 * stdout/stderr drainers spawned inside {@link JmeterProcessManager}.
 *
 * <h2>Stop / abort semantics</h2>
 * <ul>
 *   <li>{@link #stop()}: SIGTERM → wait {@code JMETER_TERMINATION_GRACE_S} →
 *       SIGKILL fallback → write sentinel → drain → COMPLETED if exit-code 0,
 *       otherwise ABORTED.</li>
 *   <li>{@link #abort()}: SIGKILL immediately → write sentinel → drain →
 *       ABORTED.</li>
 * </ul>
 *
 * <h2>Restart recovery</h2>
 * If {@link CurrentRun} loaded a non-terminal state from disk, the run is
 * marked FAILED on construction with reason {@code "orchestrator_restart"} —
 * the JMeter child died with the previous orchestrator and cannot be resumed.
 */
// NOT final: the @Observed methods below make Spring AOP wrap this bean in a
// CGLIB proxy (Spring Boot's proxyTargetClass=true default). CGLIB can't
// subclass a final class, so a final modifier here fails context startup with
// "Cannot subclass final class TestRunManager" — which is exactly what bit the
// worker once the OBSERVABILITY track added @Observed. Keep it subclassable.
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
    /**
     * WORKER-HYGIENE Phase A — re-pointed at each per-run log file
     * ({@code logs/{runId}/jmeter.log}) before launch and cleared on
     * post-run cleanup. Optional: when constructed without one (tests),
     * the file-fallback never engages.
     */
    private final com.perf.orchestrator.logs.LogTail logTail;

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
        this.defaults        = Objects.requireNonNull(defaults);
        this.stager          = Objects.requireNonNull(stager);
        this.currentRun      = Objects.requireNonNull(currentRun);
        this.launcher        = Objects.requireNonNull(launcher);
        this.pipelineFactory = Objects.requireNonNull(pipelineFactory);
        this.resultSink      = Objects.requireNonNull(resultSink);
        this.artifactSource  = Objects.requireNonNull(artifactSource);
        this.clock           = Objects.requireNonNull(clock);
        this.logTail         = logTail;
        // MID-TEST-SCALING Phase B — the shutdown-port client itself is
        // stateless; the port number is fixed at construction (matches
        // the launch-time -Jjmeterengine.nongui.port flag).
        this.shutdownPortClient = new JmeterShutdownPortClient(defaults.getJmeterShutdownPort());

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
    @Observed(name = "localOrchestrator.startTest",
              contextualName = "startTest",
              lowCardinalityKeyValues = {"action", "startTest"})
    public synchronized CurrentRun.Snapshot start(StartTestRequest request) {
        Objects.requireNonNull(request, "request");
        SpanAttributes.tag("runId", request.runId());
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
        stageFromArtifactSource(request);

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
    private void stageFromArtifactSource(StartTestRequest request) {
        String runId = request.runId();
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
            try {
                java.util.Optional<java.io.InputStream> body = artifactSource.fetch(
                        com.perf.orchestrator.storage.ArtifactSource.KIND_DATA_FILES,
                        new com.perf.orchestrator.storage.FetchSpec(runId,
                                java.util.Map.of("blobId", request.dataFilesBlobId())));
                if (body.isPresent()) {
                    try (java.io.InputStream in = body.get()) {
                        stager.storeDataFiles(in);
                    }
                }
            } catch (IOException io) {
                throw new StartRejection("ARTIFACT_FETCH_FAILED", 502,
                        "Could not fetch dataFiles blob "
                        + request.dataFilesBlobId() + ": " + io.getMessage());
            }
        }
    }

    /** Graceful stop — SIGTERM, drain, then COMPLETED/ABORTED. Idempotent. */
    public synchronized void stop() {
        Inflight i = inflight;
        if (i == null) return;
        i.stopRequested = true;
        if (i.process != null) i.process.sigterm();
    }

    /** Hard kill — SIGKILL, drain, ABORTED. Idempotent. */
    @Observed(name = "localOrchestrator.abortTest",
              contextualName = "abortTest",
              lowCardinalityKeyValues = {"action", "abortTest"})
    public synchronized void abort() {
        Inflight i = inflight;
        if (i == null) return;
        i.abortRequested = true;
        if (i.process != null) i.process.sigkill();
    }

    /**
     * MID-TEST-SCALING Phase B — graceful drain. Sends "Shutdown" to
     * JMeter's TCP shutdown port so in-flight samplers complete and
     * JMeter exits cleanly. The lifecycle classifier sees the
     * {@code drainRequested} flag on exit and lands the run in
     * {@link TestState#DRAINED} rather than {@link TestState#COMPLETED}.
     *
     * <p>If the TCP send fails (port not yet listening, JMeter already
     * exited), falls back to SIGTERM — JMeter's signal handler treats
     * SIGTERM as a less-graceful stop (equivalent to StopTestNow). Either
     * way, the lifecycle worker observes the exit and classifies as
     * DRAINED if {@code drainRequested} was set first.
     *
     * <p>If the drain budget ({@code JMETER_DRAIN_TIMEOUT_S}, default 60 s)
     * elapses without exit, the {@link #waitForJmeter} polling loop
     * escalates to abort (SIGKILL) — the run lands as ABORTED with reason
     * {@code "drainTimeoutExpired"}.
     *
     * <p>Idempotent.
     */
    @Observed(name = "localOrchestrator.drainTest",
              contextualName = "drainTest",
              lowCardinalityKeyValues = {"action", "drainTest"})
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
     * Graceful shutdown — drives any in-flight test through its normal
     * stop → drain → terminal-state path before letting the JVM exit.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Flip {@code shuttingDown} so further {@code POST /test} calls
     *       fail fast (the controller can short-circuit).</li>
     *   <li>If a test is running, fire the existing {@link #stop()} path
     *       — sets {@code stopRequested} on the inflight task and sends
     *       SIGTERM to the JMeter child via the next 500 ms poll. The run
     *       worker already handles the rest: it waits for JMeter to exit
     *       (escalating to SIGKILL after {@code JMETER_TERMINATION_GRACE_S}),
     *       writes the sentinel, drains the pipeline, transitions to a
     *       terminal state, and returns.</li>
     *   <li>Call {@code shutdown()} on both executors so no new tasks are
     *       accepted, then {@code awaitTermination(grace)} on each.</li>
     *   <li>If either executor does not finish within the budget, log
     *       loudly and fall back to {@code shutdownNow()} — the run loop
     *       sees an {@link InterruptedException} and records the run as
     *       {@code ABORTED/interrupted}.</li>
     * </ol>
     *
     * <p>Idempotent — second and subsequent calls are no-ops.
     *
     * @param grace total budget for the entire drain; split between the
     *              two executors. The run worker (which owns SIGTERM
     *              escalation + pipeline drain + auto-upload) gets the
     *              first 90% of the budget; the pipeline worker gets the
     *              remainder. A budget shorter than
     *              {@code JMETER_TERMINATION_GRACE_S} guarantees the
     *              JMeter child will be SIGKILL'd by the executor
     *              interrupt rather than by the orderly SIGTERM path.
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
                    "Final 1-second metric window may not reach Kafka.", pipelineMs);
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
                // MID-TEST-SCALING Phase B — drain timeout escalation
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
            // WORKER-HYGIENE Phase A — eager post-run cleanup. Sweeps
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
            // MID-TEST-SCALING Phase B — drain timeout escalation. The
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
        // WORKER-HYGIENE Phase A — per-run subdirs. The JTL, sentinel,
        // and offset state-file all land under results/{runId}/ so the
        // post-run cleanup is a single directory remove.
        env.put("JTL_PATH",            jtlPath(defaults, request.runId()).toString());
        env.put("SENTINEL_PATH",       sentinelPath(defaults, request.runId()).toString());
        env.put("STATE_FILE_PATH",     runResultsDir(defaults, request.runId())
                                                 .resolve(".jtlOffset").toString());
        env.put("KAFKA_BROKERS",       firstNonBlank(request.kafkaBrokers(),       defaults.getKafkaBrokers()));
        env.put("SCHEMA_REGISTRY_URL", firstNonBlank(request.schemaRegistryUrl(),  defaults.getSchemaRegistryUrl()));
        env.put("KAFKA_TOPIC",         firstNonBlank(request.kafkaTopic(),         defaults.getKafkaTopic()));
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
        // MID-TEST-SCALING Phase C — null/missing → 0 (original-fleet);
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

        // WORKER-HYGIENE Phase A — every run gets its own subdirectory
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
        // MID-TEST-SCALING Phase B — open JMeter's TCP shutdown port so
        // POST /api/v1/test/drain can send a graceful "Shutdown" command
        // that lets in-flight samplers complete. Without this the only
        // stop mechanism is OS signals (StopTestNow-equivalent), which
        // truncates the current iteration's samplers.
        command.add("-Jjmeterengine.nongui.port=" + perRun.getJmeterShutdownPort());
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
     * WORKER-HYGIENE Phase A — eager post-run cleanup. Deletes the
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
