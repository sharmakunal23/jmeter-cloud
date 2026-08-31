package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.HttpResultSink;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Lifecycle tests for {@link TestRunManager} with a fake JMeter process —
 * no subprocess spawned, no network required. Drives the manager's
 * outer state machine through every documented transition.
 */
@DisplayName("TestRunManager — outer-state lifecycle")
class TestRunManagerTest {

    @TempDir Path baseDir;

    private OrchestratorConfig config;
    private ArtifactStager stager;
    private CurrentRun currentRun;
    private FakeLauncher launcher;
    private FakePipelineFactory pipelineFactory;
    private TestRunManager manager;

    @BeforeEach
    void prepare() throws IOException {
        config = configIn(baseDir);
        stager = new ArtifactStager(config);
        // Land a plan up-front so 412 doesn't trip us.
        stager.storeTestPlan(new ByteArrayInputStream(jmxBody()), "plan.jmx");

        currentRun = CurrentRun.load(Path.of(config.getRunStateFile()), Clock.systemUTC());
        launcher = new FakeLauncher();
        pipelineFactory = new FakePipelineFactory();
        manager = new TestRunManager(
                config, stager, currentRun,
                launcher,
                pipelineFactory,
                new HttpResultSink(), // no-op sink — auto-upload doesn't fire on the default config
                new com.perf.orchestrator.storage.HttpArtifactSource(), // legacy upload path — fetch is a no-op
                Clock.systemUTC());
    }

    @AfterEach
    void cleanup() {
        // Make sure no run is left in-flight, otherwise the run worker keeps
        // writing the state file while @TempDir tries to delete the parent
        // directory — surfaces as "Failed to delete temp directory" in CI.
        if (currentRun.isActive()) {
            manager.abort();
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .until(() -> currentRun.isTerminal() || currentRun.state() == TestState.IDLE);
        }
        manager.shutdown();
    }

    // -----------------------------------------------------------------------
    // UX-DYNAMICS T3 — run-scoped plugin jars on the launch command
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("plugin jars → -Jsearch_paths")
    class PluginJars {

        private static final String ULID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

        private StartTestRequest reqWithPlugins(String runId) {
            return new StartTestRequest(runId, "us-east-1", null,
                    null, null, List.of("-Gduration=30"), List.of(),
                    java.util.Map.of("USER_OFFSET", "0"),
                    null, null, null, null, null, null, null, null,
                    List.of(new PluginSpec(ULID, "casutg.jar")), null);
        }

        @Test
        @DisplayName("a cached plugin jar rides -Jsearch_paths, before -J properties and jmeterArgs")
        void searchPathsComposedAndOrdered() throws Exception {
            java.nio.file.Path jar = baseDir.resolve("plugins").resolve(ULID + ".jar");
            java.nio.file.Files.createDirectories(jar.getParent());
            java.nio.file.Files.write(jar, new byte[]{0x50, 0x4b, 3, 4});

            manager.start(reqWithPlugins("r-plugins"));
            Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> launcher.lastSpec.get() != null);
            List<String> cmd = launcher.lastSpec.get().command();
            String searchPaths = cmd.stream()
                    .filter(a -> a.startsWith("-Jsearch_paths=")).findFirst().orElseThrow();
            assertThat(searchPaths).contains(jar.toAbsolutePath().toString());
            assertThat(cmd.indexOf(searchPaths))
                    .isLessThan(cmd.indexOf("-JUSER_OFFSET=0"))
                    .isLessThan(cmd.indexOf("-Gduration=30"));
        }

        @Test
        @DisplayName("no plugins → no -Jsearch_paths flag")
        void absentWithoutPlugins() {
            manager.start(req("r-noplugins"));
            Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> launcher.lastSpec.get() != null);
            assertThat(launcher.lastSpec.get().command())
                    .noneMatch(a -> a.startsWith("-Jsearch_paths="));
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("start() validation")
    class StartValidation {

        @Test
        @DisplayName("rejects with 412 NO_TEST_PLAN when no plan has been uploaded")
        void rejects_412_when_no_plan() throws Exception {
            stager.clearTestPlan();

            assertThatThrownBy(() -> manager.start(req("r1")))
                    .isInstanceOfSatisfying(TestRunManager.StartRejection.class, r -> {
                        assertSoftly(softly -> {
                            softly.assertThat(r.status()).isEqualTo(412);
                            softly.assertThat(r.code()).isEqualTo("NO_TEST_PLAN");
                        });
                    });
        }

        @Test
        @DisplayName("rejects with 409 TEST_RUNNING when a run is already in flight")
        void rejects_409_when_already_running() {
            // First run never exits — leaves the manager in RUNNING.
            launcher.exitCode.set(null); // never exits in this test
            manager.start(req("first"));
            awaitState(TestState.RUNNING);

            assertThatThrownBy(() -> manager.start(req("second")))
                    .isInstanceOfSatisfying(TestRunManager.StartRejection.class, r -> {
                        assertSoftly(softly -> {
                            softly.assertThat(r.status()).isEqualTo(409);
                            softly.assertThat(r.code()).isEqualTo("TEST_RUNNING");
                        });
                    });
        }

        @Test
        @DisplayName("rejects with 400 BAD_REQUEST when runId is missing")
        void rejects_400_when_run_id_missing() {
            assertThatThrownBy(() -> manager.start(req(null)))
                    .isInstanceOfSatisfying(TestRunManager.StartRejection.class, r -> {
                        assertThat(r.status()).isEqualTo(400);
                        assertThat(r.code()).isEqualTo("BAD_REQUEST");
                    });
        }
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("PREPARING → STARTING → RUNNING → DRAINING → COMPLETED with the documented snapshot fields")
        void runs_full_happy_lifecycle() {
            launcher.exitCode.set(0);

            CurrentRun.Snapshot accepted = manager.start(req("happy"));
            assertSoftly(softly -> {
                softly.assertThat(accepted.runId()).isEqualTo("happy");
                softly.assertThat(accepted.state())
                        .as("controller sees PREPARING immediately after start()")
                        .isEqualTo(TestState.PREPARING);
            });

            // Let the worker reach COMPLETED. The fake pipeline returns
            // immediately, so this should land within the awaitility window.
            awaitState(TestState.COMPLETED);

            CurrentRun.Snapshot snap = currentRun.snapshot();
            assertSoftly(softly -> {
                softly.assertThat(snap.state()).isEqualTo(TestState.COMPLETED);
                softly.assertThat(snap.exitCode()).isEqualTo(0);
                softly.assertThat(snap.jmeterPid()).isNotNull();
                softly.assertThat(snap.startedAt()).isNotNull();
                softly.assertThat(snap.completedAt()).isNotNull();
                softly.assertThat(launcher.launched.get())
                        .as("the launcher must have been called exactly once for this happy run")
                        .isEqualTo(1);
                softly.assertThat(pipelineFactory.built.get()).isEqualTo(1);
            });

            // A fresh run can start once the previous one has reached terminal.
            manager.start(req("second"));
            awaitState(TestState.COMPLETED);
        }

        @Test
        @DisplayName("non-zero JMeter exit lands the run in FAILED with a reason — not COMPLETED")
        void non_zero_exit_lands_failed() {
            launcher.exitCode.set(2);

            manager.start(req("crashy"));
            awaitState(TestState.FAILED);

            CurrentRun.Snapshot snap = currentRun.snapshot();
            assertSoftly(softly -> {
                softly.assertThat(snap.exitCode()).isEqualTo(2);
                softly.assertThat(snap.failureReason()).contains("jmeter_exit_2");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Stop / abort
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("stop and abort")
    class StopAndAbort {

        @Test
        @DisplayName("stop() sends SIGTERM, waits, then completes with exit-0 path → COMPLETED")
        void stop_signals_sigterm() {
            launcher.exitCode.set(null); // hangs until told to exit
            manager.start(req("graceful"));
            awaitState(TestState.RUNNING);

            // sigterm in the fake completes the process with exit 0.
            launcher.exitOnSigterm.set(0);
            manager.stop();

            awaitState(TestState.COMPLETED);
            assertSoftly(softly -> {
                softly.assertThat(launcher.lastSigtermCount.get()).isGreaterThanOrEqualTo(1);
                softly.assertThat(currentRun.snapshot().exitCode()).isEqualTo(0);
            });
        }

        @Test
        @DisplayName("abort() sends SIGKILL and lands in ABORTED — even if the process would have returned 0")
        void abort_lands_aborted() {
            launcher.exitCode.set(null);
            launcher.exitOnSigkill.set(137); // typical SIGKILL exit-code

            manager.start(req("hardkill"));
            awaitState(TestState.RUNNING);

            manager.abort();
            awaitState(TestState.ABORTED);

            assertSoftly(softly -> {
                softly.assertThat(launcher.lastSigkillCount.get()).isGreaterThanOrEqualTo(1);
                softly.assertThat(currentRun.snapshot().failureReason())
                        .as("the abort path records aborted_by_request, not a jmeter_exit reason")
                        .isEqualTo("aborted_by_request");
            });
        }

        @Test
        @DisplayName("drain() falls back to SIGTERM when no JMeter listens; clean exit lands DRAINED, not COMPLETED")
        void drain_lands_drained() {
            launcher.exitCode.set(null);     // hangs until told to exit
            launcher.exitOnSigterm.set(0);   // SIGTERM fallback yields exit-0

            manager.start(req("drain-happy"));
            awaitState(TestState.RUNNING);

            // No real JMeter listening on the shutdown port → TCP send fails
            // → drain() falls back to SIGTERM → fake exits with 0.
            // drainRequested=true && exitCode=0 → DRAINED (not COMPLETED).
            manager.drain();
            awaitState(TestState.DRAINED);

            assertSoftly(softly -> {
                softly.assertThat(launcher.lastSigtermCount.get()).isGreaterThanOrEqualTo(1);
                softly.assertThat(currentRun.snapshot().exitCode()).isEqualTo(0);
                softly.assertThat(currentRun.snapshot().state())
                        .as("DRAINED is the correct terminal state after a successful drain")
                        .isEqualTo(TestState.DRAINED);
            });
        }
    }

    // -----------------------------------------------------------------------
    // Restart recovery
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // WORKER-HYGIENE Phase A — eager post-run cleanup
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("eager post-run cleanup (WORKER-HYGIENE Phase A)")
    class EagerCleanup {

        @Test
        @DisplayName("COMPLETED → results/{runId}/ and logs/{runId}/ are deleted in the finally block")
        void completed_runs_get_cleaned_up() {
            launcher.exitCode.set(0);

            manager.start(req("hygiene-ok"));
            awaitState(TestState.COMPLETED);

            Path runResults = Path.of(config.getResultsDir()).resolve("hygiene-ok");
            Path runLogs    = Path.of(config.getLogsDir()).resolve("hygiene-ok");

            assertSoftly(softly -> {
                softly.assertThat(runResults)
                        .as("results subdir is swept after a clean COMPLETED")
                        .doesNotExist();
                softly.assertThat(runLogs)
                        .as("logs subdir is swept after a clean COMPLETED")
                        .doesNotExist();
            });
        }

        @Test
        @DisplayName("FAILED → results/{runId}/ and logs/{runId}/ are preserved for postmortem")
        void failed_runs_preserve_artifacts() {
            launcher.exitCode.set(2);

            manager.start(req("hygiene-fail"));
            awaitState(TestState.FAILED);

            Path runResults = Path.of(config.getResultsDir()).resolve("hygiene-fail");
            Path runLogs    = Path.of(config.getLogsDir()).resolve("hygiene-fail");

            assertSoftly(softly -> {
                softly.assertThat(runResults)
                        .as("FAILED runs keep their results dir for postmortem")
                        .exists();
                softly.assertThat(runLogs)
                        .as("FAILED runs keep their logs dir for postmortem")
                        .exists();
            });
        }

        @Test
        @DisplayName("ABORTED → results/{runId}/ and logs/{runId}/ are preserved for postmortem")
        void aborted_runs_preserve_artifacts() {
            launcher.exitCode.set(null);
            launcher.exitOnSigkill.set(137);

            manager.start(req("hygiene-abort"));
            awaitState(TestState.RUNNING);

            manager.abort();
            awaitState(TestState.ABORTED);

            Path runResults = Path.of(config.getResultsDir()).resolve("hygiene-abort");
            Path runLogs    = Path.of(config.getLogsDir()).resolve("hygiene-abort");

            assertSoftly(softly -> {
                softly.assertThat(runResults)
                        .as("ABORTED runs keep their results dir for postmortem")
                        .exists();
                softly.assertThat(runLogs)
                        .as("ABORTED runs keep their logs dir for postmortem")
                        .exists();
            });
        }

        @Test
        @DisplayName("DRAINED → results/{runId}/ and logs/{runId}/ are cleaned (graceful exit)")
        void drained_runs_get_cleaned_up() {
            launcher.exitCode.set(null);
            launcher.exitOnSigterm.set(0);

            manager.start(req("hygiene-drain"));
            awaitState(TestState.RUNNING);

            manager.drain();
            awaitState(TestState.DRAINED);

            Path runResults = Path.of(config.getResultsDir()).resolve("hygiene-drain");
            Path runLogs    = Path.of(config.getLogsDir()).resolve("hygiene-drain");

            assertSoftly(softly -> {
                softly.assertThat(runResults)
                        .as("DRAINED is a clean terminal — sweep applies")
                        .doesNotExist();
                softly.assertThat(runLogs)
                        .as("DRAINED is a clean terminal — sweep applies")
                        .doesNotExist();
            });
        }
    }

    @Nested
    @DisplayName("restart recovery")
    class RestartRecovery {

        @Test
        @DisplayName("a fresh manager with a non-terminal snapshot on disk marks the run FAILED with reason=orchestrator_restart")
        void non_terminal_snapshot_is_marked_failed_on_construction() {
            // First, leave a RUNNING snapshot on disk by transitioning a
            // CurrentRun directly (no real run worker involved).
            CurrentRun stale = CurrentRun.load(Path.of(config.getRunStateFile()), Clock.systemUTC());
            stale.beginRun("ghost", "us-east-1");
            stale.transitionTo(TestState.RUNNING);

            // Now construct a fresh manager — should observe the stale state
            // and recover by marking it FAILED.
            CurrentRun reloaded = CurrentRun.load(Path.of(config.getRunStateFile()), Clock.systemUTC());
            TestRunManager fresh = new TestRunManager(
                    config, stager, reloaded, launcher, pipelineFactory,
                    new HttpResultSink(),
                    new com.perf.orchestrator.storage.HttpArtifactSource(),
                    Clock.systemUTC());

            assertSoftly(softly -> {
                softly.assertThat(reloaded.state()).isEqualTo(TestState.FAILED);
                softly.assertThat(reloaded.snapshot().failureReason()).isEqualTo("orchestrator_restart");
            });

            fresh.shutdown();
        }
    }

    @Nested
    @DisplayName("shutdownGracefully")
    class GracefulShutdown {

        @Test
        @DisplayName("idle pod — completes within milliseconds, executors are torn down cleanly")
        void idle_shutdown_returns_quickly() {
            long start = System.currentTimeMillis();
            manager.shutdownGracefully(Duration.ofSeconds(5));
            long elapsed = System.currentTimeMillis() - start;

            assertSoftly(softly -> {
                softly.assertThat(elapsed)
                        .as("with no in-flight run, the hook must return well under the grace budget")
                        .isLessThan(2_000L);
                softly.assertThat(manager.isShuttingDown()).isTrue();
            });
        }

        @Test
        @DisplayName("in-flight run — drives through SIGTERM → COMPLETED before the grace expires, never escalates to shutdownNow()")
        void inflight_run_drains_within_grace() {
            // FakeProcess exits cleanly on SIGTERM — same flow as JMeter
            // taking the documented graceful exit during a real DELETE /test.
            launcher.exitCode.set(null);          // never exits on its own
            launcher.exitOnSigterm.set(0);        // SIGTERM → exit 0
            manager.start(req("graceful"));
            awaitState(TestState.RUNNING);

            manager.shutdownGracefully(Duration.ofSeconds(10));

            assertSoftly(softly -> {
                softly.assertThat(currentRun.isTerminal())
                        .as("graceful shutdown must drive the run to a terminal state")
                        .isTrue();
                softly.assertThat(launcher.lastSigtermCount.get())
                        .as("SIGTERM was fired by the shutdown path, not by an explicit DELETE /test")
                        .isGreaterThanOrEqualTo(1);
                softly.assertThat(launcher.lastSigkillCount.get())
                        .as("clean exit means no SIGKILL escalation needed")
                        .isZero();
            });
        }

        @Test
        @DisplayName("idempotent — second call is a no-op, does not double-shutdown the executors")
        void idempotent_when_called_twice() {
            manager.shutdownGracefully(Duration.ofSeconds(2));
            // Second call must not throw, must not block.
            manager.shutdownGracefully(Duration.ofSeconds(2));

            assertThat(manager.isShuttingDown()).isTrue();
        }

        @Test
        @DisplayName("rejects new POST /test with 503 SHUTTING_DOWN once the hook has fired — prevents accepting work that cannot finish")
        void rejects_new_runs_during_shutdown() {
            manager.shutdownGracefully(Duration.ofSeconds(2));

            assertThatThrownBy(() -> manager.start(req("late")))
                    .isInstanceOfSatisfying(TestRunManager.StartRejection.class, r -> {
                        assertSoftly(softly -> {
                            softly.assertThat(r.status()).isEqualTo(503);
                            softly.assertThat(r.code()).isEqualTo("SHUTTING_DOWN");
                        });
                    });
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void awaitState(TestState target) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> currentRun.state() == target);
    }

    // -----------------------------------------------------------------------
    // gracePeriodSeconds wire-through (request override > env default)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("gracePeriodSeconds wire-through")
    class GracePeriodWireThrough {

        @Test
        @DisplayName("request override sets the per-run aggregator grace period")
        void request_override_sets_per_run_grace() {
            launcher.exitCode.set(null); // stay RUNNING so the captured config sticks
            manager.start(reqWithGrace("grace-override", 15));
            awaitState(TestState.RUNNING);
            assertThat(pipelineFactory.lastConfig).isNotNull();
            assertThat(pipelineFactory.lastConfig.getGracePeriodSeconds()).isEqualTo(15);
        }

        @Test
        @DisplayName("absent override forwards the orchestrator's GRACE_PERIOD_SECONDS env (previously ignored → always defaulted to 2)")
        void absent_override_forwards_env_default() throws Exception {
            // A boot config whose grace is NON-default proves the wire-through:
            // before the fix, buildPerRunConfig never forwarded it, so the
            // per-run config silently used the built-in 2 regardless of env.
            Path graceBase = baseDir.resolve("graceDefault");
            Map<String, String> env = new HashMap<>(Map.of(
                    "POD_NAME", "w", "TEST_REGION", "us-east-1", "RUN_ID", "boot",
                    "JTL_PATH", "/results/results.jtl", "SENTINEL_PATH", "/results/.done"));
            env.put("BASE_DIR",       graceBase.toString());
            env.put("TEST_PLAN_DIR",  graceBase.resolve("testPlan").toString());
            env.put("DATA_FILES_DIR", graceBase.resolve("dataFiles").toString());
            env.put("RESULTS_DIR",    graceBase.resolve("results").toString());
            env.put("LOGS_DIR",       graceBase.resolve("logs").toString());
            env.put("RUN_STATE_FILE", graceBase.resolve("state/currentRun.json").toString());
            env.put("GRACE_PERIOD_SECONDS", "9");
            OrchestratorConfig graceCfg = OrchestratorConfig.from(env);

            ArtifactStager graceStager = new ArtifactStager(graceCfg);
            graceStager.storeTestPlan(new ByteArrayInputStream(jmxBody()), "plan.jmx");
            CurrentRun graceRun = CurrentRun.load(Path.of(graceCfg.getRunStateFile()), Clock.systemUTC());
            FakeLauncher graceLauncher = new FakeLauncher();
            graceLauncher.exitCode.set(null);
            FakePipelineFactory gracePf = new FakePipelineFactory();
            TestRunManager graceMgr = new TestRunManager(
                    graceCfg, graceStager, graceRun, graceLauncher, gracePf,
                    new HttpResultSink(),
                    new com.perf.orchestrator.storage.HttpArtifactSource(),
                    Clock.systemUTC());
            try {
                graceMgr.start(req("grace-default")); // no override → fall back to env (9)
                Awaitility.await().atMost(Duration.ofSeconds(3))
                        .until(() -> gracePf.lastConfig != null);
                assertThat(gracePf.lastConfig.getGracePeriodSeconds()).isEqualTo(9);
            } finally {
                graceMgr.abort();
                Awaitility.await().atMost(Duration.ofSeconds(3))
                        .until(() -> graceRun.isTerminal() || graceRun.state() == TestState.IDLE);
                graceMgr.shutdown();
            }
        }
    }

    private static StartTestRequest req(String runId) {
        // Bug-fix 2026-05-10 added `testPlanBlobId` + `dataFilesBlobId`
        // between `scheduledStartAt` and `jmeterArgs`. Both null in
        // these legacy tests — the orchestrator falls back to whatever
        // the test setup staged via stager.storeTestPlan directly.
        return new StartTestRequest(runId, "us-east-1", null,
                null, null,           // testPlanBlobId, dataFilesBlobId
                List.of(), List.of(),
                java.util.Map.of(),
                null, null, null,
                null,                 // joinedAtSecond — null = original-fleet (Phase C)
                null,                 // application — untagged in these legacy tests
                null,                 // gracePeriodSeconds — null = use the orchestrator default
                null, null,           // metricsGroupId, windowSeconds — orchestrator defaults
                List.of(),            // plugins — none in these legacy tests
                null);                // refreshDataFiles — default (reuse allowed)
    }

    private static StartTestRequest reqWithGrace(String runId, Integer gracePeriodSeconds) {
        return new StartTestRequest(runId, "us-east-1", null,
                null, null, List.of(), List.of(), java.util.Map.of(),
                null, null, null, null, null,
                gracePeriodSeconds, null, null, List.of(), null);
    }

    // -----------------------------------------------------------------------
    // UX-DYNAMICS T5 — BeanShell server posture on the launch command
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("BeanShell flags — secure by default")
    class BeanShellFlags {

        @Test
        @DisplayName("default config (BEANSHELL_PORT unset = 0): no -Jbeanshell flags")
        void offByDefault() {
            manager.start(req("r-bshOff"));
            Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> launcher.lastSpec.get() != null);
            assertThat(launcher.lastSpec.get().command())
                    .noneMatch(a -> a.startsWith("-Jbeanshell."));
        }

        @Test
        @DisplayName("BEANSHELL_PORT=4446 + extras/startup.bsh present: both flags, absolute file path")
        void optInEmitsBothFlags() throws Exception {
            Path home = baseDir.resolve("jmHome");
            Path startup = home.resolve("extras").resolve("startup.bsh");
            Files.createDirectories(startup.getParent());
            Files.writeString(startup, "// stock startup script\n");

            Map<String, String> env = configEnv(baseDir);
            env.put("BEANSHELL_PORT", "4446");
            env.put("JMETER_HOME", home.toString());
            OrchestratorConfig cfg = OrchestratorConfig.from(env);
            ArtifactStager st = new ArtifactStager(cfg);
            st.storeTestPlan(new ByteArrayInputStream(jmxBody()), "plan.jmx");
            TestRunManager m = new TestRunManager(
                    cfg, st, currentRun, launcher, pipelineFactory,
                    new HttpResultSink(),
                    new com.perf.orchestrator.storage.HttpArtifactSource(),
                    Clock.systemUTC());
            try {
                m.start(req("r-bshOn"));
                Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> launcher.lastSpec.get() != null);
                assertThat(launcher.lastSpec.get().command())
                        .contains("-Jbeanshell.server.port=4446")
                        .contains("-Jbeanshell.server.file=" + startup.toAbsolutePath());
                // Let the fake run finish before shutdown so @TempDir can delete.
                Awaitility.await().atMost(Duration.ofSeconds(3))
                        .until(() -> currentRun.isTerminal() || currentRun.state() == TestState.IDLE);
            } finally {
                m.shutdown();
            }
        }
    }

    private static OrchestratorConfig configIn(Path base) {
        return OrchestratorConfig.from(configEnv(base));
    }

    private static Map<String, String> configEnv(Path base) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "boot",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done"
        ));
        env.put("BASE_DIR",       base.toString());
        env.put("TEST_PLAN_DIR",  base.resolve("testPlan").toString());
        env.put("DATA_FILES_DIR", base.resolve("dataFiles").toString());
        env.put("RESULTS_DIR",    base.resolve("results").toString());
        env.put("LOGS_DIR",       base.resolve("logs").toString());
        env.put("RUN_STATE_FILE", base.resolve("state/currentRun.json").toString());
        // Tighten the SIGTERM grace so the stop test doesn't sit on the
        // default 120s. The behaviour we're verifying is the signal flow,
        // not the grace duration.
        env.put("JMETER_TERMINATION_GRACE_S", "2");
        return env;
    }

    private static byte[] jmxBody() {
        return "<jmeterTestPlan/>".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * In-memory {@link JmeterLauncher}. The returned process stays alive
     * until {@code exitCode} is non-null OR a sigterm/sigkill has been
     * matched against {@code exitOnSigterm}/{@code exitOnSigkill}.
     */
    static final class FakeLauncher implements JmeterLauncher {
        final AtomicInteger launched         = new AtomicInteger();
        final AtomicReference<Integer> exitCode      = new AtomicReference<>(0);
        final AtomicReference<Integer> exitOnSigterm = new AtomicReference<>(null);
        final AtomicReference<Integer> exitOnSigkill = new AtomicReference<>(null);
        final AtomicInteger lastSigtermCount = new AtomicInteger();
        final AtomicInteger lastSigkillCount = new AtomicInteger();

        final AtomicReference<LaunchSpec> lastSpec = new AtomicReference<>();

        @Override
        public JmeterProcess launch(LaunchSpec spec) {
            launched.incrementAndGet();
            lastSpec.set(spec);
            return new FakeProcess(this);
        }
    }

    static final class FakeProcess implements JmeterProcess {
        private final FakeLauncher launcher;
        private final long pid = Math.abs(new java.util.Random().nextLong()) % 100_000L + 1L;
        private final AtomicReference<Integer> liveExit = new AtomicReference<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicLong terminationDeadlineMs = new AtomicLong(Long.MAX_VALUE);

        FakeProcess(FakeLauncher l) {
            this.launcher = l;
            // Resolve "exit immediately" up-front; otherwise the process
            // hangs until a signal arrives.
            Integer initial = l.exitCode.get();
            if (initial != null) {
                liveExit.set(initial);
                alive.set(false);
            }
        }

        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive.get(); }

        @Override
        public synchronized void sigterm() {
            if (!alive.get()) return;
            launcher.lastSigtermCount.incrementAndGet();
            Integer cfg = launcher.exitOnSigterm.get();
            if (cfg != null) {
                liveExit.set(cfg);
                alive.set(false);
            }
        }

        @Override
        public synchronized void sigkill() {
            if (!alive.get()) return;
            launcher.lastSigkillCount.incrementAndGet();
            Integer cfg = launcher.exitOnSigkill.get();
            if (cfg != null) {
                liveExit.set(cfg);
                alive.set(false);
            } else {
                liveExit.set(137);
                alive.set(false);
            }
        }

        @Override
        public Optional<Integer> awaitExit(Duration timeout) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (alive.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            return Optional.ofNullable(liveExit.get());
        }
    }

    /**
     * Returns a no-op {@link StreamingPipeline} factory — calling
     * {@code pipeline.run()} returns immediately. The full pipeline
     * lifecycle is covered by {@code StreamingPipelineTest}; here we just
     * need the manager to advance through DRAINING → COMPLETED.
     */
    static final class FakePipelineFactory implements java.util.function.Function<OrchestratorConfig, StreamingPipeline> {
        final AtomicInteger built = new AtomicInteger();
        /** The most recent per-run config the manager handed the factory. */
        volatile OrchestratorConfig lastConfig;

        @Override
        public StreamingPipeline apply(OrchestratorConfig cfg) {
            built.incrementAndGet();
            lastConfig = cfg;
            return new StreamingPipeline(cfg,
                    new com.perf.orchestrator.buffer.SynchronousMetricsDispatcher()) {
                @Override public void run() { /* simulate a fast drain */ }
            };
        }
    }
}
