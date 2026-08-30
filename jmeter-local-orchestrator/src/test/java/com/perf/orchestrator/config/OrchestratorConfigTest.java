package com.perf.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("OrchestratorConfig")
class OrchestratorConfigTest {

    // -----------------------------------------------------------------------
    // Shared fixture
    // -----------------------------------------------------------------------

    /**
     * Returns a mutable map containing all required variables so individual
     * tests can surgically remove or replace one entry without affecting others.
     */
    static Map<String, String> fullValidEnv() {
        return new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "20250413-east",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done"
        ));
    }

    // -----------------------------------------------------------------------
    // Startup validation behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when a required environment variable is absent")
    class WhenRequiredVariableIsAbsent {

        @ParameterizedTest(name = "refuses to start when {0} is missing")
        @ValueSource(strings = {
                "POD_NAME", "TEST_REGION", "RUN_ID", "JTL_PATH", "SENTINEL_PATH"
        })
        void refuses_to_start(String missingKey) {
            Map<String, String> env = fullValidEnv();
            env.remove(missingKey);

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining(missingKey);
        }

        @Test
        @DisplayName("treats an explicit null value as absent — protects against misconfigured env injection")
        void treats_explicit_null_value_as_absent() {
            // Kubernetes secret injection bugs can occasionally produce null map values.
            // These must be caught here, not NPE inside the orchestrator.
            Map<String, String> env = new HashMap<>(fullValidEnv());
            env.put("POD_NAME", null);

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("POD_NAME");
        }

        @ParameterizedTest(name = "treats a blank value for {0} the same as absent")
        @ValueSource(strings = {
                "POD_NAME", "TEST_REGION", "RUN_ID", "JTL_PATH", "SENTINEL_PATH"
        })
        void treats_blank_value_as_absent(String keyWithBlankValue) {
            // A variable set to whitespace (e.g. by a misconfigured ConfigMap) must be
            // caught here, not discovered as a NullPointerException deep in the orchestrator.
            Map<String, String> env = fullValidEnv();
            env.put(keyWithBlankValue, "   ");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining(keyWithBlankValue);
        }

        @Test
        @DisplayName("reports all missing variables in one error rather than stopping at the first")
        void reports_all_missing_variables_at_once() {
            // Operators should be able to fix all missing vars in a single
            // ConfigMap update — not discover them one restart at a time.
            Map<String, String> env = new HashMap<>(); // nothing present

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContainingAll(
                            "POD_NAME", "TEST_REGION", "RUN_ID", "JTL_PATH", "SENTINEL_PATH"
                    );
        }
    }

    // -----------------------------------------------------------------------
    // Default value behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when optional variables are absent")
    class WhenOptionalVariablesAreAbsent {

        @Test
        @DisplayName("applies safe production defaults for all optional settings")
        void applies_safe_defaults() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            // Asserting all defaults together: if a default changes, this test
            // surfaces it immediately rather than requiring per-field test failures.
            assertSoftly(softly -> {
                softly.assertThat(config.getGracePeriodSeconds())
                        .as("grace period — 2s allows late-arriving rows without over-holding windows")
                        .isEqualTo(2);

                softly.assertThat(config.getPollIntervalMs())
                        .as("poll interval — 100ms gives ~10 reads/sec, keeping lag well under 1s")
                        .isEqualTo(100);

                softly.assertThat(config.getFileWaitPollIntervalMs())
                        .as("file wait interval — 500ms when JTL has not appeared yet")
                        .isEqualTo(500);

                softly.assertThat(config.getMaxReadBytes())
                        .as("max read bytes — 128 KB per poll balances throughput and GC pressure")
                        .isEqualTo(131_072);

                softly.assertThat(config.getStateFlushIntervalMs())
                        .as("state flush interval — 10s caps worst-case reprocessing on crash recovery")
                        .isEqualTo(10_000);

                softly.assertThat(config.getDrainEmptyPollsThreshold())
                        .as("drain threshold — 3 consecutive empty reads before declaring file exhausted")
                        .isEqualTo(3);

                softly.assertThat(config.getStateFilePath())
                        .as("default state file lives alongside the JTL in /results")
                        .isEqualTo("/results/.jtlOffset");
            });
        }

        @Test
        @DisplayName("honours explicitly configured values over defaults")
        void honours_explicit_values_over_defaults() {
            Map<String, String> env = fullValidEnv();
            env.put("GRACE_PERIOD_SECONDS", "5");
            env.put("POLL_INTERVAL_MS", "200");
            env.put("STATE_FILE_PATH", "/custom/path/.state");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getGracePeriodSeconds()).isEqualTo(5);
                softly.assertThat(config.getPollIntervalMs()).isEqualTo(200);
                softly.assertThat(config.getStateFilePath()).isEqualTo("/custom/path/.state");
            });
        }

        @Test
        @DisplayName("accepts HTTP_PORT=0 — the ephemeral-port escape hatch used by hermetic tests so parallel @SpringBootTest forks do not fight over a fixed port")
        void accepts_ephemeral_http_port() {
            Map<String, String> env = fullValidEnv();
            env.put("HTTP_PORT", "0");

            assertThat(OrchestratorConfig.from(env).getHttpPort()).isZero();
        }

        @Test
        @DisplayName("defaults WORKER_ID_SOURCE to POD_NAME — standalone and master-master deployments unaffected")
        void defaults_worker_id_source_to_pod_name() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertThat(config.isUseThreadName()).isFalse();
        }

        @Test
        @DisplayName("accepts WORKER_ID_SOURCE=THREAD_NAME for master-slave deployments")
        void accepts_thread_name_worker_id_source() {
            Map<String, String> env = fullValidEnv();
            env.put("WORKER_ID_SOURCE", "THREAD_NAME");

            assertThat(OrchestratorConfig.from(env).isUseThreadName()).isTrue();
        }

        @Test
        @DisplayName("accepts WORKER_ID_SOURCE=POD_NAME explicitly — same as omitting the variable")
        void accepts_explicit_pod_name_worker_id_source() {
            Map<String, String> env = fullValidEnv();
            env.put("WORKER_ID_SOURCE", "POD_NAME");

            assertThat(OrchestratorConfig.from(env).isUseThreadName()).isFalse();
        }

        @Test
        @DisplayName("rejects an unknown WORKER_ID_SOURCE — prevents silent misconfiguration")
        void rejects_unknown_worker_id_source() {
            Map<String, String> env = fullValidEnv();
            env.put("WORKER_ID_SOURCE", "HOSTNAME");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("WORKER_ID_SOURCE");
        }

        @Test
        @DisplayName("defaults timezoneId to UTC when TIMEZONE_ID is absent")
        void defaults_timezone_to_utc() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertThat(config.getTimezoneId()).isEqualTo("UTC");
        }

        @Test
        @DisplayName("accepts a valid IANA timezone ID for TIMEZONE_ID")
        void accepts_valid_iana_timezone() {
            Map<String, String> env = fullValidEnv();
            env.put("TIMEZONE_ID", "America/New_York");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertThat(config.getTimezoneId()).isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("rejects an invalid TIMEZONE_ID — prevents silent epoch-second miscalculation")
        void rejects_invalid_timezone_id() {
            Map<String, String> env = fullValidEnv();
            env.put("TIMEZONE_ID", "Not/A/RealZone");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("TIMEZONE_ID");
        }
    }

    // -----------------------------------------------------------------------
    // Numeric option validation behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when a numeric optional variable has an invalid value")
    class WhenNumericVariableIsInvalid {

        @ParameterizedTest(name = "rejects zero for {0} — zero would disable the feature entirely")
        @ValueSource(strings = {
                "GRACE_PERIOD_SECONDS",
                "POLL_INTERVAL_MS",
                "FILE_WAIT_POLL_INTERVAL_MS",
                "MAX_READ_BYTES",
                "STATE_FLUSH_INTERVAL_MS",
                "DRAIN_EMPTY_POLLS_THRESHOLD"
        })
        void rejects_zero(String key) {
            Map<String, String> env = fullValidEnv();
            env.put(key, "0");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining(key);
        }

        @ParameterizedTest(name = "rejects negative value for {0}")
        @ValueSource(strings = {
                "GRACE_PERIOD_SECONDS",
                "POLL_INTERVAL_MS",
                "FILE_WAIT_POLL_INTERVAL_MS"
        })
        void rejects_negative_value(String key) {
            Map<String, String> env = fullValidEnv();
            env.put(key, "-1");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining(key);
        }

        @ParameterizedTest(name = "rejects non-numeric string for {0}")
        @ValueSource(strings = {"GRACE_PERIOD_SECONDS", "POLL_INTERVAL_MS", "MAX_READ_BYTES"})
        void rejects_non_numeric_string(String key) {
            // Catches typos in ConfigMap values like GRACE_PERIOD_SECONDS=two
            Map<String, String> env = fullValidEnv();
            env.put(key, "two");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining(key);
        }
    }

    // -----------------------------------------------------------------------
    // Identity behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when fully constructed")
    class WhenFullyConstructed {

        @Test
        @DisplayName("exposes required values exactly as supplied without modification")
        void exposes_required_values_unchanged() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getPodName()).isEqualTo("jmeter-worker-0");
                softly.assertThat(config.getTestRegion()).isEqualTo("us-east-1");
                softly.assertThat(config.getRunId()).isEqualTo("20250413-east");
                softly.assertThat(config.getJtlPath()).isEqualTo("/results/results.jtl");
                softly.assertThat(config.getSentinelPath()).isEqualTo("/results/.done");
                softly.assertThat(config.getMetricsIngestUrl())
                        .isEqualTo("http://metrics-consumer:8083/api/v1/ingest");
            });
        }

        @Test
        @DisplayName("produces a non-empty toString suitable for startup logging")
        void toString_is_loggable() {
            // Startup log should show enough context for ops to confirm which
            // pod and run the orchestrator belongs to without being verbose.
            String output = OrchestratorConfig.from(fullValidEnv()).toString();

            assertThat(output)
                    .contains("jmeter-worker-0")
                    .contains("us-east-1")
                    .contains("20250413-east")
                    .isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // Orchestrator HTTP / lifecycle / storage defaults
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("orchestrator defaults")
    class OrchestratorDefaults {

        @Test
        @DisplayName("apply safe HTTP server defaults — small bounded thread pool, 5-min request timeout for large uploads")
        void applies_http_defaults() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getHttpPort()).as("default REST port").isEqualTo(8080);
                softly.assertThat(config.getHttpBindAddress()).as("default bind").isEqualTo("0.0.0.0");
                softly.assertThat(config.getHttpMinThreads()).as("min threads — keeps idle RSS small").isEqualTo(2);
                softly.assertThat(config.getHttpMaxThreads()).as("max threads — bounded so HTTP cannot starve the streaming pipeline").isEqualTo(8);
                softly.assertThat(config.getHttpRequestTimeoutSeconds())
                        .as("request timeout — large data-file uploads need headroom")
                        .isEqualTo(300);
            });
        }

        @Test
        @DisplayName("derive filesystem layout from BASE_DIR=/opt/jmeter")
        void derives_paths_from_base_dir() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getBaseDir()).isEqualTo("/opt/jmeter");
                softly.assertThat(config.getTestPlanDir()).isEqualTo("/opt/jmeter/testPlan");
                softly.assertThat(config.getDataFilesDir()).isEqualTo("/opt/jmeter/dataFiles");
                softly.assertThat(config.getResultsDir()).isEqualTo("/opt/jmeter/results");
                softly.assertThat(config.getLogsDir()).isEqualTo("/opt/jmeter/logs");
                softly.assertThat(config.getRunStateFile()).isEqualTo("/opt/jmeter/state/currentRun.json");
            });
        }

        @Test
        @DisplayName("apply documented upload-limit caps — zip-bomb defenses turned on by default")
        void applies_upload_limit_defaults() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getMaxPlanSizeMb()).isEqualTo(32);
                softly.assertThat(config.getMaxDataZipSizeMb()).isEqualTo(512);
                softly.assertThat(config.getMaxExtractedSizeMb()).isEqualTo(1024);
                softly.assertThat(config.getMaxEntrySizeMb()).isEqualTo(256);
                softly.assertThat(config.getMaxFileCount()).isEqualTo(500);
            });
        }

        @Test
        @DisplayName("apply JMeter-launch defaults — JMETER_BIN derived from JMETER_HOME")
        void applies_jmeter_defaults() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getJmeterHome()).isEqualTo("/opt/jmeter");
                softly.assertThat(config.getJmeterBin()).isEqualTo("/opt/jmeter/bin/jmeter");
                // RELIABILITY Round 6 — JMeter child heap raised 1g → 2g + fail-fast on OOM.
                // WORKER-OOM — native-region caps so ExitOnOutOfMemoryError fires before
                // the cgroup SIGKILLs (heap-only -Xmx can't see native growth).
                softly.assertThat(config.getJmeterJvmArgs())
                        .isEqualTo("-Xms2g -Xmx2g -XX:+ExitOnOutOfMemoryError "
                                + "-XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=512m "
                                + "-XX:ReservedCodeCacheSize=240m");
                // WORKER-OOM — child is the preferred OOM victim so PID 1 (orchestrator) survives.
                softly.assertThat(config.getJmeterOomScoreAdj()).isEqualTo(1000);
                softly.assertThat(config.getJmxPort()).isEqualTo(9999);
            });
        }

        @Test
        @DisplayName("default to HTTP_UPLOAD source/sink with auto-upload disabled — safe until the doc service is live")
        void defaults_backends_to_http_upload() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getArtifactSource()).isEqualTo(Backend.HTTP_UPLOAD);
                softly.assertThat(config.getResultSink()).isEqualTo(Backend.HTTP_UPLOAD);
                softly.assertThat(config.isAutoUploadResults()).isFalse();
            });
        }

        @Test
        @DisplayName("default document-service settings to empty URL, 60s timeout, 3 retries")
        void applies_document_service_defaults() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getDocumentServiceUrl()).isEmpty();
                softly.assertThat(config.getDocumentServiceAuthHeader()).isEmpty();
                softly.assertThat(config.getDocumentServiceTimeoutSeconds()).isEqualTo(60);
                softly.assertThat(config.getDocumentServiceRetryCount()).isEqualTo(3);
                softly.assertThat(config.getS3Region()).isEmpty();
            });
        }

        @Test
        @DisplayName("apply observability buffer defaults — 1000 log lines, 30s ingest health check, 5s probe timeout, disk gate disabled")
        void applies_observability_defaults() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getLogBufferLines()).isEqualTo(1000);
                softly.assertThat(config.getIngestHealthCheckIntervalMs()).isEqualTo(30_000);
                softly.assertThat(config.getIngestHealthCheckTimeoutMs()).isEqualTo(5_000);
                softly.assertThat(config.getMinFreeDiskMb())
                        .as("disk gate is disabled by default — operators opt in by setting a positive MB value")
                        .isZero();
            });
        }

        @Test
        @DisplayName("MIN_FREE_DISK_MB accepts a positive value when the operator wants the gate active")
        void min_free_disk_accepts_positive_override() {
            Map<String, String> env = fullValidEnv();
            env.put("MIN_FREE_DISK_MB", "1024");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertThat(config.getMinFreeDiskMb()).isEqualTo(1024);
        }

        @Test
        @DisplayName("MIN_FREE_DISK_MB rejects negative values — a typo cannot accidentally disable the threshold")
        void min_free_disk_rejects_negative() {
            Map<String, String> env = fullValidEnv();
            env.put("MIN_FREE_DISK_MB", "-1");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("MIN_FREE_DISK_MB");
        }

        @Test
        @DisplayName("INGEST_HEALTH_CHECK_TIMEOUT_MS rejects zero — a zero-timeout probe would always fail, masking real consumer faults")
        void ingest_health_check_timeout_rejects_zero() {
            Map<String, String> env = fullValidEnv();
            env.put("INGEST_HEALTH_CHECK_TIMEOUT_MS", "0");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("INGEST_HEALTH_CHECK_TIMEOUT_MS");
        }

        @Test
        @DisplayName("disable auth by default — empty AUTH_TOKEN means anonymous access")
        void disables_auth_by_default() {
            OrchestratorConfig config = OrchestratorConfig.from(fullValidEnv());

            assertSoftly(softly -> {
                softly.assertThat(config.getAuthToken()).isEmpty();
                softly.assertThat(config.isAuthEnabled()).isFalse();
            });
        }
    }

    @Nested
    @DisplayName("orchestrator-era explicit overrides")
    class OrchestratorExplicitOverrides {

        @Test
        @DisplayName("propagate a custom BASE_DIR through every derived path that was not set explicitly")
        void custom_base_dir_propagates_to_derived_paths() {
            // Operators commonly override BASE_DIR alone (e.g. /var/jmeter on EC2);
            // every derived path must follow without having to set each one.
            Map<String, String> env = fullValidEnv();
            env.put("BASE_DIR", "/var/jmeter");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getBaseDir()).isEqualTo("/var/jmeter");
                softly.assertThat(config.getTestPlanDir()).isEqualTo("/var/jmeter/testPlan");
                softly.assertThat(config.getDataFilesDir()).isEqualTo("/var/jmeter/dataFiles");
                softly.assertThat(config.getResultsDir()).isEqualTo("/var/jmeter/results");
                softly.assertThat(config.getLogsDir()).isEqualTo("/var/jmeter/logs");
                softly.assertThat(config.getRunStateFile()).isEqualTo("/var/jmeter/state/currentRun.json");
            });
        }

        @Test
        @DisplayName("honour an individual path override without touching siblings — operator can move just one directory")
        void individual_path_override_is_respected() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULTS_DIR", "/mnt/nvme/results");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getResultsDir()).isEqualTo("/mnt/nvme/results");
                softly.assertThat(config.getLogsDir()).isEqualTo("/opt/jmeter/logs");
                softly.assertThat(config.getDataFilesDir()).isEqualTo("/opt/jmeter/dataFiles");
            });
        }

        @Test
        @DisplayName("derive JMETER_BIN from a custom JMETER_HOME unless overridden explicitly")
        void jmeter_bin_derives_from_jmeter_home() {
            Map<String, String> env = fullValidEnv();
            env.put("JMETER_HOME", "/usr/local/jmeter");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertThat(config.getJmeterBin()).isEqualTo("/usr/local/jmeter/bin/jmeter");
        }

        @Test
        @DisplayName("honour an explicit JMETER_BIN even when JMETER_HOME is set elsewhere")
        void explicit_jmeter_bin_wins_over_derivation() {
            Map<String, String> env = fullValidEnv();
            env.put("JMETER_HOME", "/usr/local/jmeter");
            env.put("JMETER_BIN", "/opt/wrapper/jmeter-launch");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertThat(config.getJmeterBin()).isEqualTo("/opt/wrapper/jmeter-launch");
        }

        @Test
        @DisplayName("honour HTTP overrides — port, bind address, thread pool, request timeout")
        void honours_http_overrides() {
            Map<String, String> env = fullValidEnv();
            env.put("HTTP_PORT", "9090");
            env.put("HTTP_BIND_ADDRESS", "127.0.0.1");
            env.put("HTTP_MIN_THREADS", "4");
            env.put("HTTP_MAX_THREADS", "16");
            env.put("HTTP_REQUEST_TIMEOUT_S", "600");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getHttpPort()).isEqualTo(9090);
                softly.assertThat(config.getHttpBindAddress()).isEqualTo("127.0.0.1");
                softly.assertThat(config.getHttpMinThreads()).isEqualTo(4);
                softly.assertThat(config.getHttpMaxThreads()).isEqualTo(16);
                softly.assertThat(config.getHttpRequestTimeoutSeconds()).isEqualTo(600);
            });
        }

        @Test
        @DisplayName("honour upload-limit overrides — operators can tighten or relax the zip-bomb caps")
        void honours_upload_limit_overrides() {
            Map<String, String> env = fullValidEnv();
            env.put("MAX_PLAN_SIZE_MB", "8");
            env.put("MAX_DATA_ZIP_SIZE_MB", "256");
            env.put("MAX_EXTRACTED_SIZE_MB", "768");
            env.put("MAX_ENTRY_SIZE_MB", "128");
            env.put("MAX_FILE_COUNT", "100");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getMaxPlanSizeMb()).isEqualTo(8);
                softly.assertThat(config.getMaxDataZipSizeMb()).isEqualTo(256);
                softly.assertThat(config.getMaxExtractedSizeMb()).isEqualTo(768);
                softly.assertThat(config.getMaxEntrySizeMb()).isEqualTo(128);
                softly.assertThat(config.getMaxFileCount()).isEqualTo(100);
            });
        }

        @Test
        @DisplayName("treat any non-empty AUTH_TOKEN as enabling bearer-token auth")
        void non_empty_auth_token_enables_auth() {
            Map<String, String> env = fullValidEnv();
            env.put("AUTH_TOKEN", "s3cr3t");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getAuthToken()).isEqualTo("s3cr3t");
                softly.assertThat(config.isAuthEnabled()).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("ARTIFACT_SOURCE backend selection")
    class ArtifactSourceSelection {

        @ParameterizedTest(name = "accepts ARTIFACT_SOURCE={0}")
        @ValueSource(strings = {"HTTP_UPLOAD", "S3", "DOCUMENT_SERVICE"})
        void accepts_each_supported_value(String value) {
            Map<String, String> env = fullValidEnv();
            env.put("ARTIFACT_SOURCE", value);

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertThat(config.getArtifactSource()).isEqualTo(Backend.valueOf(value));
        }

        @Test
        @DisplayName("rejects an unknown ARTIFACT_SOURCE — silent fallback would mask a config typo")
        void rejects_unknown_source() {
            Map<String, String> env = fullValidEnv();
            env.put("ARTIFACT_SOURCE", "GCS");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("ARTIFACT_SOURCE")
                    .hasMessageContaining("GCS");
        }
    }

    @Nested
    @DisplayName("RESULT_SINK backend selection")
    class ResultSinkSelection {

        @ParameterizedTest(name = "accepts RESULT_SINK={0}")
        @ValueSource(strings = {"HTTP_UPLOAD", "DOCUMENT_SERVICE"})
        void accepts_each_supported_value(String value) {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", value);

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertThat(config.getResultSink()).isEqualTo(Backend.valueOf(value));
        }

        @Test
        @DisplayName("rejects RESULT_SINK=S3 with a clear error — auto-upload targets the document service only")
        void rejects_s3_as_sink() {
            // S3-as-sink is intentionally unsupported per the design (see
            // docs/orchestratorPlan.md "Storage Backends"). The orchestrator
            // never needs cloud-specific result-storage code paths because
            // the document service hides whatever underlying store it uses.
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "S3");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("RESULT_SINK=S3")
                    .hasMessageContaining("not supported");
        }

        @Test
        @DisplayName("rejects an unknown RESULT_SINK")
        void rejects_unknown_sink() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "GCS");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("RESULT_SINK");
        }
    }

    @Nested
    @DisplayName("AUTO_UPLOAD_RESULTS parsing")
    class AutoUploadResultsParsing {

        @ParameterizedTest(name = "accepts \"{0}\" as boolean true")
        @ValueSource(strings = {"true", "TRUE", "True", "1", "yes", "YES"})
        void accepts_truthy_values(String raw) {
            // AUTO_UPLOAD_RESULTS=true requires RESULT_SINK=DOCUMENT_SERVICE
            // (validateUploadCombo); fill that in so this test stays scoped
            // to boolean parsing, not upload-combo validation.
            Map<String, String> env = fullValidEnv();
            env.put("AUTO_UPLOAD_RESULTS", raw);
            env.put("RESULT_SINK", "DOCUMENT_SERVICE");
            env.put("DOCUMENT_SERVICE_URL", "https://docs.internal/api");

            assertThat(OrchestratorConfig.from(env).isAutoUploadResults()).isTrue();
        }

        @ParameterizedTest(name = "accepts \"{0}\" as boolean false")
        @ValueSource(strings = {"false", "FALSE", "0", "no", "NO"})
        void accepts_falsy_values(String raw) {
            Map<String, String> env = fullValidEnv();
            env.put("AUTO_UPLOAD_RESULTS", raw);

            assertThat(OrchestratorConfig.from(env).isAutoUploadResults()).isFalse();
        }

        @Test
        @DisplayName("rejects a non-boolean string — silent fallback would hide a config typo")
        void rejects_non_boolean() {
            Map<String, String> env = fullValidEnv();
            env.put("AUTO_UPLOAD_RESULTS", "maybe");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("AUTO_UPLOAD_RESULTS");
        }
    }

    @Nested
    @DisplayName("Document Service URL requirement")
    class DocumentServiceUrlRequirement {

        @Test
        @DisplayName("required when RESULT_SINK=DOCUMENT_SERVICE and AUTO_UPLOAD_RESULTS=true — only the upload path needs the URL")
        void required_when_sink_is_doc_service_and_auto_upload_on() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "DOCUMENT_SERVICE");
            env.put("AUTO_UPLOAD_RESULTS", "true");
            // DOCUMENT_SERVICE_URL omitted

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("DOCUMENT_SERVICE_URL");
        }

        @Test
        @DisplayName("not required when AUTO_UPLOAD_RESULTS=false — orchestrator does no post-test work")
        void not_required_when_auto_upload_off() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "DOCUMENT_SERVICE");
            env.put("AUTO_UPLOAD_RESULTS", "false");
            // DOCUMENT_SERVICE_URL omitted — fine, nothing will call it

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getResultSink()).isEqualTo(Backend.DOCUMENT_SERVICE);
                softly.assertThat(config.isAutoUploadResults()).isFalse();
                softly.assertThat(config.getDocumentServiceUrl()).isEmpty();
            });
        }

        @Test
        @DisplayName("rejects RESULT_SINK=HTTP_UPLOAD with AUTO_UPLOAD_RESULTS=true — fail loud, do not silently SKIP")
        void rejects_http_upload_with_auto_upload_on() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "HTTP_UPLOAD");
            env.put("AUTO_UPLOAD_RESULTS", "true");
            // DOCUMENT_SERVICE_URL omitted — irrelevant, the sink itself is rejected

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("AUTO_UPLOAD_RESULTS=true")
                    .hasMessageContaining("RESULT_SINK=DOCUMENT_SERVICE")
                    .hasMessageContaining("HTTP_UPLOAD");
        }

        @Test
        @DisplayName("RESULT_SINK=HTTP_UPLOAD + AUTO_UPLOAD_RESULTS=false validates cleanly — the documented default")
        void http_upload_with_auto_upload_off_is_default() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "HTTP_UPLOAD");
            env.put("AUTO_UPLOAD_RESULTS", "false");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getResultSink()).isEqualTo(Backend.HTTP_UPLOAD);
                softly.assertThat(config.isAutoUploadResults()).isFalse();
            });
        }

        @Test
        @DisplayName("validates cleanly when all three pieces are present")
        void valid_combination_passes() {
            Map<String, String> env = fullValidEnv();
            env.put("RESULT_SINK", "DOCUMENT_SERVICE");
            env.put("AUTO_UPLOAD_RESULTS", "true");
            env.put("DOCUMENT_SERVICE_URL", "https://docs.internal/api");
            env.put("DOCUMENT_SERVICE_AUTH_HEADER", "Authorization: Bearer xyz");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.isAutoUploadResults()).isTrue();
                softly.assertThat(config.getDocumentServiceUrl()).isEqualTo("https://docs.internal/api");
                softly.assertThat(config.getDocumentServiceAuthHeader()).isEqualTo("Authorization: Bearer xyz");
            });
        }
    }

    @Nested
    @DisplayName("orchestrator-era numeric validation")
    class OrchestratorNumericValidation {

        @ParameterizedTest(name = "rejects zero for {0} — would disable the feature entirely")
        @ValueSource(strings = {
                // HTTP_PORT intentionally absent — 0 is the ephemeral-port
                // escape hatch (covered by `accepts_ephemeral_http_port`).
                "HTTP_MIN_THREADS", "HTTP_MAX_THREADS", "HTTP_REQUEST_TIMEOUT_S",
                "MAX_PLAN_SIZE_MB", "MAX_DATA_ZIP_SIZE_MB", "MAX_EXTRACTED_SIZE_MB",
                "MAX_ENTRY_SIZE_MB", "MAX_FILE_COUNT",
                "JMX_PORT", "DOCUMENT_SERVICE_TIMEOUT_S",
                "LOG_BUFFER_LINES", "INGEST_HEALTH_CHECK_INTERVAL_MS"
        })
        void rejects_zero_for_positive_int_keys(String key) {
            Map<String, String> env = fullValidEnv();
            env.put(key, "0");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining(key);
        }

        @Test
        @DisplayName("accepts DOCUMENT_SERVICE_RETRY_COUNT=0 — zero disables retries, which is a valid choice")
        void retry_count_accepts_zero() {
            // RETRY_COUNT differs from the other ints: zero is a meaningful
            // value (retries disabled), not a misconfiguration.
            Map<String, String> env = fullValidEnv();
            env.put("DOCUMENT_SERVICE_RETRY_COUNT", "0");

            assertThat(OrchestratorConfig.from(env).getDocumentServiceRetryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("rejects a negative DOCUMENT_SERVICE_RETRY_COUNT")
        void retry_count_rejects_negative() {
            Map<String, String> env = fullValidEnv();
            env.put("DOCUMENT_SERVICE_RETRY_COUNT", "-1");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("DOCUMENT_SERVICE_RETRY_COUNT");
        }

        @Test
        @DisplayName("rejects HTTP_MIN_THREADS greater than HTTP_MAX_THREADS — Jetty would throw at boot")
        void min_threads_must_not_exceed_max() {
            Map<String, String> env = fullValidEnv();
            env.put("HTTP_MIN_THREADS", "16");
            env.put("HTTP_MAX_THREADS", "8");

            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("HTTP_MIN_THREADS")
                    .hasMessageContaining("HTTP_MAX_THREADS");
        }

        @Test
        @DisplayName("accepts equal HTTP_MIN_THREADS and HTTP_MAX_THREADS — a fixed-size pool")
        void min_equals_max_is_valid() {
            Map<String, String> env = fullValidEnv();
            env.put("HTTP_MIN_THREADS", "4");
            env.put("HTTP_MAX_THREADS", "4");

            OrchestratorConfig config = OrchestratorConfig.from(env);

            assertSoftly(softly -> {
                softly.assertThat(config.getHttpMinThreads()).isEqualTo(4);
                softly.assertThat(config.getHttpMaxThreads()).isEqualTo(4);
            });
        }
    }

    @Nested
    @DisplayName("metrics routing (PRIVATE-CLOUD-ALIGNMENT Track 5)")
    class MetricsRouting {

        @Test
        @DisplayName("defaults: 15-second windows, no group, no auth, the dispatcher's three knobs")
        void defaults() {
            OrchestratorConfig cfg = OrchestratorConfig.from(fullValidEnv());
            assertThat(cfg.getFlushWindowSeconds()).isEqualTo(15);
            assertThat(cfg.getMetricsGroupId()).isNull();
            assertThat(cfg.getMetricsIngestAuth()).isNull();
            assertThat(cfg.getMetricsIngestQueueCapacity()).isEqualTo(256);
            assertThat(cfg.getMetricsIngestRetryIntervalMs()).isEqualTo(500);
            assertThat(cfg.getMetricsIngestRetryAfterMs()).isEqualTo(5_000);
            assertThat(cfg.getMetricsIngestAuthRetryMs()).isEqualTo(30_000);
        }

        @Test
        @DisplayName("METRICS_GROUP_ID must be a consumer group id; METRICS_INGEST_AUTH is the whole header value")
        void group_and_auth() {
            Map<String, String> env = new HashMap<>(fullValidEnv());
            env.put("METRICS_GROUP_ID", "cps");
            env.put("METRICS_INGEST_AUTH", " Bearer abc ");
            env.put("FLUSH_WINDOW_SECONDS", "1");
            OrchestratorConfig cfg = OrchestratorConfig.from(env);
            assertThat(cfg.getMetricsGroupId()).isEqualTo("cps");
            assertThat(cfg.getMetricsIngestAuth()).isEqualTo("Bearer abc");
            assertThat(cfg.getFlushWindowSeconds()).isEqualTo(1);

            env.put("METRICS_GROUP_ID", "CPS");
            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("METRICS_GROUP_ID");
            env.put("METRICS_GROUP_ID", "cps");
            env.put("FLUSH_WINDOW_SECONDS", "0");
            assertThatThrownBy(() -> OrchestratorConfig.from(env))
                    .isInstanceOf(OrchestratorConfigException.class)
                    .hasMessageContaining("FLUSH_WINDOW_SECONDS");
        }
    }

}
