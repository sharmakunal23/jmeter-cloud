package com.perf.orchestrator.config;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable configuration for the orchestrator process.
 *
 * <p>Constructed once at startup. All required variables are validated eagerly
 * so the orchestrator fails with a clear error rather than a NullPointerException
 * twenty minutes into a test run. All missing variables are reported in a
 * single exception — not one-at-a-time — so the operator can fix everything
 * in one restart cycle.
 *
 * <p>Optional variables fall back to safe production defaults when absent.
 */
public final class OrchestratorConfig {

    // -----------------------------------------------------------------------
    // Required — orchestrator cannot function without these
    // -----------------------------------------------------------------------

    private final String podName;
    private final String testRegion;
    private final String runId;
    private final String jtlPath;
    private final String sentinelPath;
    private final String kafkaBrokers;
    private final String schemaRegistryUrl;
    private final String kafkaTopic;

    // -----------------------------------------------------------------------
    // Optional — sensible defaults applied when absent
    // -----------------------------------------------------------------------

    /** Seconds to wait for late-arriving rows before closing a window. */
    private final int gracePeriodSeconds;

    /** How often (ms) to poll for new bytes in the JTL file while RUNNING. */
    private final int pollIntervalMs;

    /** How often (ms) to check whether the JTL file has appeared while WAITING. */
    private final int fileWaitPollIntervalMs;

    /** Path where byte-offset state is persisted for crash recovery. */
    private final String stateFilePath;

    /** Maximum bytes read from the JTL file on each poll (controls memory pressure). */
    private final int maxReadBytes;

    /** How often (ms) the byte-offset state is flushed to disk. */
    private final int stateFlushIntervalMs;

    /**
     * Consecutive empty polls required before DRAINING considers the file
     * fully consumed. Protects against treating a momentary JMeter flush pause
     * as end-of-file.
     */
    private final int drainEmptyPollsThreshold;

    /**
     * Timezone used to interpret JTL timestamps when converting to epoch seconds.
     *
     * <p>JMeter writes timestamps using the wall-clock time of the host it runs on.
     * The orchestrator runs as a container in the same pod — both use the same clock —
     * so the default of {@code UTC} is correct for pods with no explicit TZ setting.
     * Set {@code TIMEZONE_ID} if your JMeter image explicitly sets a non-UTC timezone
     * (e.g. {@code America/New_York}) to prevent epoch-second calculations from being
     * off by the UTC offset.
     *
     * <p>Valid values: any IANA timezone ID accepted by {@link java.time.ZoneId#of(String)}.
     */
    private final String timezoneId;

    /**
     * When {@code true}, the aggregator derives {@code workerId} from the JTL
     * {@code threadName} column instead of using the fixed {@code POD_NAME}.
     *
     * <p>Set {@code WORKER_ID_SOURCE=THREAD_NAME} on the master pod orchestrator in a
     * master-slave deployment. The master JTL contains rows from all slaves, each
     * stamped with the slave's DNS hostname in the {@code threadName} column
     * (e.g. {@code jmeter-slave-2.jmeter-workers.perf.svc.cluster.local-Thread Group 1-1}).
     * The aggregator extracts the pod name prefix before the first {@code .} to produce
     * a per-slave {@code workerId} (e.g. {@code jmeter-slave-2}).
     *
     * <p>For standalone per-pod deployments (each worker writes its own JTL), leave
     * this at the default {@code POD_NAME} — the pod name is already unambiguous.
     *
     * <p>Valid values: {@code POD_NAME} (default) or {@code THREAD_NAME}.
     */
    private final boolean useThreadName;

    // -----------------------------------------------------------------------
    // Orchestrator HTTP / lifecycle / storage settings
    // -----------------------------------------------------------------------

    // HTTP server
    private final int    httpPort;
    private final String httpBindAddress;
    private final int    httpMinThreads;
    private final int    httpMaxThreads;
    private final int    httpRequestTimeoutSeconds;

    // Auth — empty string disables bearer-token auth
    private final String authToken;

    // Filesystem layout (defaults derive from baseDir)
    private final String baseDir;
    private final String testPlanDir;
    private final String dataFilesDir;
    private final String resultsDir;
    private final String logsDir;
    private final String runStateFile;

    // Metrics-buffer write-ahead queue (K-3).
    // The buffer persists envelopes to disk under metricsBufferPath BEFORE
    // they're published, so envelopes survive Kafka outages + crashes.
    // Caps are JMeter-considerate: the buffer never starves the JTL writer.
    private final String metricsBufferPath;
    private final long metricsBufferMaxBytes;
    private final long metricsBufferMaxFileBytes;
    private final long metricsBufferMinFreeDiskBytes;
    private final int metricsBufferMaxAgeHours;

    // K-5 — HTTP fallback to metrics-consumer's /api/v1/ingest when Kafka send fails.
    private final boolean metricsHttpFallbackEnabled;
    private final String metricsHttpFallbackUrl;
    private final int metricsHttpFallbackConnectTimeoutMs;
    private final int metricsHttpFallbackRequestTimeoutMs;

    // Upload limits — guard against zip-bombs and accidental DoS
    private final int maxPlanSizeMb;
    private final int maxDataZipSizeMb;
    private final int maxExtractedSizeMb;
    private final int maxEntrySizeMb;
    private final int maxFileCount;

    // JMeter child process
    private final String jmeterHome;
    private final String jmeterBin;
    private final String jmeterJvmArgs;
    private final int    jmeterOomScoreAdj;
    private final int    jmxPort;
    private final int    jmeterShutdownPort;
    private final int    jmeterDrainTimeoutSeconds;
    private final int    jmeterTerminationGraceSeconds;
    private final int    orchestratorShutdownGraceSeconds;

    /**
     * MID-TEST-SCALING Phase C — seconds since {@code run.startedAt} at which
     * this worker joined. {@code 0} for original-fleet; {@code > 0} for
     * mid-test scale-up joiners. Forwarded onto every published
     * {@code WorkerMetricBatch}. Source: {@code JOINED_AT_SECOND} env var
     * (set by {@code TestRunManager.buildPerRunConfig} from the
     * {@link com.perf.orchestrator.lifecycle.StartTestRequest} body).
     */
    private final long   joinedAtSecond;

    // Storage backend selection
    private final Backend artifactSource;
    private final Backend resultSink;
    private final boolean autoUploadResults;

    // Document Service backend
    private final String documentServiceUrl;
    private final String documentServiceAuthHeader;
    private final int    documentServiceTimeoutSeconds;
    private final int    documentServiceRetryCount;

    // S3 backend
    private final String s3Region;

    // Observability
    private final int logBufferLines;
    private final int kafkaHealthCheckIntervalMs;
    private final int kafkaHealthCheckTimeoutMs;
    private final int minFreeDiskMb;

    // -----------------------------------------------------------------------
    // Declared once so missing-variable validation is the single source of truth
    // -----------------------------------------------------------------------

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "POD_NAME",
            "TEST_REGION",
            "RUN_ID",
            "JTL_PATH",
            "SENTINEL_PATH",
            "KAFKA_BROKERS",
            "SCHEMA_REGISTRY_URL",
            "KAFKA_TOPIC"
    );

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private OrchestratorConfig(Map<String, String> env) {
        Objects.requireNonNull(env, "Environment map cannot be null");
        validateRequiredPresent(env);

        this.podName           = env.get("POD_NAME");
        this.testRegion        = env.get("TEST_REGION");
        this.runId             = env.get("RUN_ID");
        this.jtlPath           = env.get("JTL_PATH");
        this.sentinelPath      = env.get("SENTINEL_PATH");
        this.kafkaBrokers      = env.get("KAFKA_BROKERS");
        this.schemaRegistryUrl = env.get("SCHEMA_REGISTRY_URL");
        this.kafkaTopic        = env.get("KAFKA_TOPIC");

        this.gracePeriodSeconds      = parsePositiveInt(env, "GRACE_PERIOD_SECONDS",      2);
        this.pollIntervalMs          = parsePositiveInt(env, "POLL_INTERVAL_MS",          100);
        this.fileWaitPollIntervalMs  = parsePositiveInt(env, "FILE_WAIT_POLL_INTERVAL_MS", 500);
        this.stateFilePath           = env.getOrDefault("STATE_FILE_PATH", "/results/.jtlOffset");
        this.maxReadBytes            = parsePositiveInt(env, "MAX_READ_BYTES",            131_072);
        this.stateFlushIntervalMs    = parsePositiveInt(env, "STATE_FLUSH_INTERVAL_MS",   10_000);
        this.drainEmptyPollsThreshold = parsePositiveInt(env, "DRAIN_EMPTY_POLLS_THRESHOLD", 3);
        this.timezoneId               = env.getOrDefault("TIMEZONE_ID", "UTC");
        validateTimezoneId(this.timezoneId);
        this.useThreadName            = parseWorkerIdSource(env.getOrDefault("WORKER_ID_SOURCE", "POD_NAME"));

        // ---------------- Orchestrator HTTP / lifecycle / storage settings ----

        // 0 = ephemeral (Tomcat binds an OS-assigned free port). Useful in
        // tests so parallel @SpringBootTest forks don't collide on a fixed
        // port; production defaults to 8080.
        this.httpPort                  = parseNonNegativeInt(env, "HTTP_PORT",          8080);
        this.httpBindAddress           = env.getOrDefault("HTTP_BIND_ADDRESS",          "0.0.0.0");
        this.httpMinThreads            = parsePositiveInt(env, "HTTP_MIN_THREADS",       2);
        this.httpMaxThreads            = parsePositiveInt(env, "HTTP_MAX_THREADS",       8);
        this.httpRequestTimeoutSeconds = parsePositiveInt(env, "HTTP_REQUEST_TIMEOUT_S", 300);
        validateThreadPoolBounds(this.httpMinThreads, this.httpMaxThreads);

        // Empty-string default (not null) so callers can compare without NPE checks.
        this.authToken = env.getOrDefault("AUTH_TOKEN", "");

        this.baseDir       = env.getOrDefault("BASE_DIR",       "/opt/jmeter");
        this.testPlanDir   = env.getOrDefault("TEST_PLAN_DIR",  this.baseDir + "/testPlan");
        this.dataFilesDir  = env.getOrDefault("DATA_FILES_DIR", this.baseDir + "/dataFiles");
        this.resultsDir    = env.getOrDefault("RESULTS_DIR",    this.baseDir + "/results");
        this.logsDir       = env.getOrDefault("LOGS_DIR",       this.baseDir + "/logs");
        this.runStateFile  = env.getOrDefault("RUN_STATE_FILE", this.baseDir + "/state/currentRun.json");

        // Metrics-buffer knobs — defaults match the K-3 spec. JMeter is the
        // priority disk tenant; the buffer never grows past metricsBufferMaxBytes
        // and refuses writes if free disk drops below metricsBufferMinFreeDiskBytes.
        this.metricsBufferPath              = env.getOrDefault("METRICS_BUFFER_PATH",
                this.baseDir + "/metricsBuffer");
        this.metricsBufferMaxBytes          = parsePositiveLong(env, "METRICS_BUFFER_MAX_BYTES",
                20L * 1024L * 1024L);            // 20 MB
        this.metricsBufferMaxFileBytes      = parsePositiveLong(env, "METRICS_BUFFER_MAX_FILE_BYTES",
                200L * 1024L);                   // 200 KB
        this.metricsBufferMinFreeDiskBytes  = parsePositiveLong(env, "METRICS_BUFFER_MIN_FREE_DISK_BYTES",
                1024L * 1024L * 1024L);          // 1 GB — JMeter wins
        this.metricsBufferMaxAgeHours       = parsePositiveInt(env, "METRICS_BUFFER_MAX_AGE_HOURS", 6);

        // K-5 — HTTP fallback knobs.
        this.metricsHttpFallbackEnabled            = parseBoolean(env, "METRICS_HTTP_FALLBACK_ENABLED", true);
        this.metricsHttpFallbackUrl                = env.getOrDefault("METRICS_HTTP_FALLBACK_URL",
                "http://metrics-consumer:8083/api/v1/ingest");
        this.metricsHttpFallbackConnectTimeoutMs   = parsePositiveInt(env, "METRICS_HTTP_FALLBACK_CONNECT_TIMEOUT_MS", 2_000);
        this.metricsHttpFallbackRequestTimeoutMs   = parsePositiveInt(env, "METRICS_HTTP_FALLBACK_REQUEST_TIMEOUT_MS", 5_000);

        this.maxPlanSizeMb      = parsePositiveInt(env, "MAX_PLAN_SIZE_MB",      32);
        this.maxDataZipSizeMb   = parsePositiveInt(env, "MAX_DATA_ZIP_SIZE_MB",  512);
        this.maxExtractedSizeMb = parsePositiveInt(env, "MAX_EXTRACTED_SIZE_MB", 1024);
        this.maxEntrySizeMb     = parsePositiveInt(env, "MAX_ENTRY_SIZE_MB",     256);
        this.maxFileCount       = parsePositiveInt(env, "MAX_FILE_COUNT",        500);

        this.jmeterHome    = env.getOrDefault("JMETER_HOME",     "/opt/jmeter");
        this.jmeterBin     = env.getOrDefault("JMETER_BIN",       this.jmeterHome + "/bin/jmeter");
        // RELIABILITY Round 6 — JMeter child heap raised 1g → 2g (and fail-fast on
        // OOM). 1g was undersized for the platform's real workload: a 12 h run at
        // 200-250 rps across ~100 unique endpoints accumulates enough per-host
        // connection/cookie/DNS state (plus any plan-level listeners/assertions) to
        // exhaust 1g around the 1-hour mark — observed as whole-worker failures that
        // never reproduce in a 5-minute smoke. ExitOnOutOfMemoryError makes a JMeter
        // heap exhaustion crash cleanly (→ non-zero exit, classified jmeter_exit_N)
        // instead of GC-thrashing for minutes and corrupting late samples. Override
        // per-deployment via JMETER_JVM_ARGS, or per-run via the POST /test body's
        // jmeterJvmArgs. The worker container limit (PODPROVISIONER_WORKER_MEMORY_MB,
        // default 6144) is sized to fit this 2g child + the 1g orchestrator + native
        // + page cache.
        //
        // WORKER-OOM (2026-06-01) — bound the JVM's *native* regions, not just the
        // heap. The shared-cgroup OOM that killed worker-2's child was a SIGKILL from
        // the kernel, which ExitOnOutOfMemoryError can NOT catch: that flag only fires
        // when the JVM itself throws OutOfMemoryError on a heap allocation, whereas the
        // cgroup ceiling is crossed by native growth (-Xmx bounds heap only — direct
        // byte buffers, Metaspace, JIT code cache, thread stacks are all outside it).
        // The save-response-data hypothesis was refuted (image + plan both keep
        // responseData=false), so the spike was native/GC, invisible to -Xmx2g. Capping
        // MaxDirectMemorySize / MaxMetaspaceSize / ReservedCodeCacheSize closes the gap
        // between -Xmx and the cgroup limit so the JVM hits its OWN ceiling first and
        // exits cleanly (ExitOnOutOfMemoryError → classified jmeter_exit_N) instead of
        // being SIGKILLed mid-test. Sized generously for JMeter + baked-in plugins
        // (Metaspace ~120-180m observed, direct buffers small even under TLS): the caps
        // are a fail-fast backstop, not a tight budget. 2g heap + ~1g capped native
        // still sits well under the child's share of the 6 GiB worker.
        this.jmeterJvmArgs = env.getOrDefault("JMETER_JVM_ARGS",
                "-Xms2g -Xmx2g -XX:+ExitOnOutOfMemoryError "
                + "-XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=512m "
                + "-XX:ReservedCodeCacheSize=240m");
        // WORKER-OOM Part A — make the JMeter child the kernel's preferred OOM victim
        // so the orchestrator (PID 1) is NEVER the one reaped. Without this the cgroup
        // OOM killer picks by oom_badness; if the orchestrator's heap + the multi-GB
        // JTL page-cache it tails made IT the fattest process, PID 1 dies, the whole
        // pod vanishes, in-flight Kafka batches are lost, and the global-orchestrator
        // can't tell it apart from an IMAGE_MISMATCH drain. Raising the child's
        // /proc/<pid>/oom_score_adj (unprivileged when raising) guarantees the child
        // dies first → the orchestrator always survives to roll the run FAILED and
        // preserve artifacts. Range [-1000, 1000]; 1000 = "kill me first". Set 0 (or
        // negative) to opt out of the nudge.
        this.jmeterOomScoreAdj = parseIntInRange(env, "JMETER_OOM_SCORE_ADJ",
                1000, -1000, 1000);
        this.jmxPort       = parsePositiveInt(env, "JMX_PORT",   9999);
        // MID-TEST-SCALING Phase B — JMeter's TCP shutdown port. JMeter in
        // non-GUI mode listens on this port (when launched with
        // -Jjmeterengine.nongui.port=N) for graceful "Shutdown" / forceful
        // "StopTestNow" commands. Kept distinct from JMX_PORT so observability
        // and control planes don't collide. Default 4445 matches JMeter's own.
        this.jmeterShutdownPort = parsePositiveInt(env,
                "JMETER_SHUTDOWN_PORT", 4445);
        // Default drain budget: 60s. After this, the lifecycle escalates the
        // drain to abort (SIGKILL) and the run ends ABORTED.
        this.jmeterDrainTimeoutSeconds = parsePositiveInt(env,
                "JMETER_DRAIN_TIMEOUT_S", 60);
        this.jmeterTerminationGraceSeconds = parsePositiveInt(env,
                "JMETER_TERMINATION_GRACE_S", 120);
        // MID-TEST-SCALING Phase C — non-negative; 0 means original-fleet.
        this.joinedAtSecond = parseNonNegativeLong(env, "JOINED_AT_SECOND", 0L);
        // Total budget the JVM shutdown hook gives the orchestrator to
        // drain in-flight work (SIGTERM JMeter, drain pipeline, flush
        // Kafka) before forcing executors with shutdownNow(). Decoupled
        // from JMETER_TERMINATION_GRACE_S so K8s
        // terminationGracePeriodSeconds can stay small for the common
        // idle-pod case while operators of long-test deployments raise
        // both env vars and terminationGracePeriodSeconds together.
        this.orchestratorShutdownGraceSeconds = parsePositiveInt(env,
                "ORCHESTRATOR_SHUTDOWN_GRACE_S", 30);

        this.artifactSource    = Backend.parseArtifactSource(env.getOrDefault("ARTIFACT_SOURCE", "HTTP_UPLOAD"));
        this.resultSink        = Backend.parseResultSink(env.getOrDefault("RESULT_SINK",         "HTTP_UPLOAD"));
        this.autoUploadResults = parseBoolean(env, "AUTO_UPLOAD_RESULTS", false);

        this.documentServiceUrl            = env.getOrDefault("DOCUMENT_SERVICE_URL", "");
        this.documentServiceAuthHeader     = env.getOrDefault("DOCUMENT_SERVICE_AUTH_HEADER", "");
        this.documentServiceTimeoutSeconds = parsePositiveInt(env, "DOCUMENT_SERVICE_TIMEOUT_S", 60);
        this.documentServiceRetryCount     = parseNonNegativeInt(env, "DOCUMENT_SERVICE_RETRY_COUNT", 3);

        this.s3Region = env.getOrDefault("S3_REGION", "");

        this.logBufferLines             = parsePositiveInt(env, "LOG_BUFFER_LINES",             1000);
        this.kafkaHealthCheckIntervalMs = parsePositiveInt(env, "KAFKA_HEALTH_CHECK_INTERVAL_MS", 30_000);
        this.kafkaHealthCheckTimeoutMs  = parsePositiveInt(env, "KAFKA_HEALTH_CHECK_TIMEOUT_MS",  5_000);
        // 0 disables the threshold so /ready never returns 503 due to disk —
        // the documented default. Operators who want the gate set a positive
        // MB value; external disk monitoring (Prometheus alert, CloudWatch)
        // is the recommended path until the orchestrator's own threshold is
        // tuned for the deployment. Negative values are rejected so a typo
        // cannot accidentally produce a non-zero gate.
        this.minFreeDiskMb              = parseNonNegativeInt(env, "MIN_FREE_DISK_MB",            0);

        validateUploadCombo(this.resultSink, this.autoUploadResults, this.documentServiceUrl);
    }

    /**
     * Builds configuration from the real process environment.
     *
     * <p>This is the production entry point used by {@code OrchestratorMain}.
     *
     * <p><b>Intentionally not unit-tested.</b> This method is a one-line
     * delegation to {@link #from(Map)} with no logic of its own. Any logic
     * added here must instead be placed in {@link #from(Map)} where it is
     * testable. If you find yourself wanting to add logic here, that is
     * a signal to refactor.
     */
    public static OrchestratorConfig fromEnvironment() {
        return new OrchestratorConfig(System.getenv());
    }

    /**
     * Builds configuration from an explicit map.
     * Intended for tests and for callers that manage their own env source.
     */
    public static OrchestratorConfig from(Map<String, String> env) {
        return new OrchestratorConfig(env);
    }

    // -----------------------------------------------------------------------
    // Validation helpers
    // -----------------------------------------------------------------------

    private static void validateRequiredPresent(Map<String, String> env) {
        Set<String> missing = REQUIRED_KEYS.stream()
                .filter(key -> Objects.toString(env.get(key), "").isBlank())
                .collect(Collectors.toSet());

        if (!missing.isEmpty()) {
            String keys = missing.stream().sorted().collect(Collectors.joining(", "));
            throw new OrchestratorConfigException(
                    "Orchestrator cannot start. Missing or blank required environment variables: " + keys);
        }
    }

    private static int parsePositiveInt(Map<String, String> env, String key, int defaultValue) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new OrchestratorConfigException(
                        "'" + key + "' must be a positive integer (> 0), got: " + raw.trim());
            }
            return value;
        } catch (NumberFormatException e) {
            throw new OrchestratorConfigException(
                    "'" + key + "' must be a valid integer, got: '" + raw.trim() + "'");
        }
    }

    private static long parsePositiveLong(Map<String, String> env, String key, long defaultValue) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new OrchestratorConfigException(
                        "'" + key + "' must be a positive long (> 0), got: " + raw.trim());
            }
            return value;
        } catch (NumberFormatException e) {
            throw new OrchestratorConfigException(
                    "'" + key + "' must be a valid long, got: '" + raw.trim() + "'");
        }
    }

    private static void validateTimezoneId(String id) {
        try {
            java.time.ZoneId.of(id);
        } catch (java.time.DateTimeException e) {
            throw new OrchestratorConfigException(
                    "'TIMEZONE_ID' value '" + id + "' is not a valid IANA timezone ID. " +
                    "Examples: UTC, America/New_York, Europe/London");
        }
    }

    private static boolean parseWorkerIdSource(String value) {
        return switch (value) {
            case "POD_NAME"    -> false;
            case "THREAD_NAME" -> true;
            default -> throw new OrchestratorConfigException(
                    "'WORKER_ID_SOURCE' must be 'POD_NAME' or 'THREAD_NAME', got: '" + value + "'");
        };
    }

    private static int parseIntInRange(Map<String, String> env, String key,
                                       int defaultValue, int min, int max) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new OrchestratorConfigException(
                        "'" + key + "' must be in [" + min + ", " + max + "], got: " + raw.trim());
            }
            return value;
        } catch (NumberFormatException e) {
            throw new OrchestratorConfigException(
                    "'" + key + "' must be a valid integer, got: '" + raw.trim() + "'");
        }
    }

    private static int parseNonNegativeInt(Map<String, String> env, String key, int defaultValue) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new OrchestratorConfigException(
                        "'" + key + "' must be a non-negative integer (>= 0), got: " + raw.trim());
            }
            return value;
        } catch (NumberFormatException e) {
            throw new OrchestratorConfigException(
                    "'" + key + "' must be a valid integer, got: '" + raw.trim() + "'");
        }
    }

    private static long parseNonNegativeLong(Map<String, String> env, String key, long defaultValue) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new OrchestratorConfigException(
                        "'" + key + "' must be a non-negative long (>= 0), got: " + raw.trim());
            }
            return value;
        } catch (NumberFormatException e) {
            throw new OrchestratorConfigException(
                    "'" + key + "' must be a valid long, got: '" + raw.trim() + "'");
        }
    }

    private static boolean parseBoolean(Map<String, String> env, String key, boolean defaultValue) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String trimmed = raw.trim().toLowerCase();
        return switch (trimmed) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> throw new OrchestratorConfigException(
                    "'" + key + "' must be a boolean (true/false), got: '" + raw.trim() + "'");
        };
    }

    private static void validateThreadPoolBounds(int min, int max) {
        if (min > max) {
            throw new OrchestratorConfigException(
                    "'HTTP_MIN_THREADS' (" + min + ") must be <= 'HTTP_MAX_THREADS' (" + max + ")");
        }
    }

    private static void validateUploadCombo(Backend resultSink, boolean autoUpload, String docServiceUrl) {
        if (!autoUpload) {
            // No upload requested — sink choice is irrelevant.
            return;
        }
        // AUTO_UPLOAD_RESULTS=true means the operator wants the JTL pushed
        // somewhere. Only DOCUMENT_SERVICE is a real upload target; HTTP_UPLOAD
        // is a no-op that keeps the JTL local. Letting that combination boot
        // produces a silently-SKIPPED upload at end-of-test — fail loud instead.
        if (resultSink != Backend.DOCUMENT_SERVICE) {
            throw new OrchestratorConfigException(
                    "AUTO_UPLOAD_RESULTS=true requires RESULT_SINK=DOCUMENT_SERVICE; got " +
                    resultSink + ". Either set RESULT_SINK=DOCUMENT_SERVICE (and provide " +
                    "DOCUMENT_SERVICE_URL) or set AUTO_UPLOAD_RESULTS=false to keep the JTL " +
                    "local and fetch it via GET /api/v1/results/file.");
        }
        if (docServiceUrl.isBlank()) {
            throw new OrchestratorConfigException(
                    "'DOCUMENT_SERVICE_URL' must be set when RESULT_SINK=DOCUMENT_SERVICE and " +
                    "AUTO_UPLOAD_RESULTS=true. Either disable AUTO_UPLOAD_RESULTS, switch RESULT_SINK " +
                    "to HTTP_UPLOAD, or provide the document service base URL.");
        }
    }

    // -----------------------------------------------------------------------
    // Accessors — no setters, config is immutable after construction
    // -----------------------------------------------------------------------

    public String getPodName()              { return podName; }
    public String getTestRegion()           { return testRegion; }
    public String getRunId()                { return runId; }
    public String getJtlPath()              { return jtlPath; }
    public String getSentinelPath()         { return sentinelPath; }
    public String getKafkaBrokers()         { return kafkaBrokers; }
    public String getSchemaRegistryUrl()    { return schemaRegistryUrl; }
    public String getKafkaTopic()           { return kafkaTopic; }
    public int    getGracePeriodSeconds()   { return gracePeriodSeconds; }
    public int    getPollIntervalMs()       { return pollIntervalMs; }
    public int    getFileWaitPollIntervalMs(){ return fileWaitPollIntervalMs; }
    public String getStateFilePath()        { return stateFilePath; }
    public int    getMaxReadBytes()         { return maxReadBytes; }
    public int    getStateFlushIntervalMs() { return stateFlushIntervalMs; }
    public int    getDrainEmptyPollsThreshold() { return drainEmptyPollsThreshold; }
    public String getTimezoneId()           { return timezoneId; }
    public boolean isUseThreadName()        { return useThreadName; }

    // Orchestrator-era accessors (added in step 2)

    public int    getHttpPort()                      { return httpPort; }
    public String getHttpBindAddress()               { return httpBindAddress; }
    public int    getHttpMinThreads()                { return httpMinThreads; }
    public int    getHttpMaxThreads()                { return httpMaxThreads; }
    public int    getHttpRequestTimeoutSeconds()     { return httpRequestTimeoutSeconds; }
    public String getAuthToken()                     { return authToken; }
    public boolean isAuthEnabled()                   { return !authToken.isBlank(); }

    public String getBaseDir()                       { return baseDir; }
    public String getTestPlanDir()                   { return testPlanDir; }
    public String getDataFilesDir()                  { return dataFilesDir; }
    public String getResultsDir()                    { return resultsDir; }
    public String getLogsDir()                       { return logsDir; }
    public String getRunStateFile()                  { return runStateFile; }
    public String getMetricsBufferPath()             { return metricsBufferPath; }
    public long getMetricsBufferMaxBytes()           { return metricsBufferMaxBytes; }
    public long getMetricsBufferMaxFileBytes()       { return metricsBufferMaxFileBytes; }
    public long getMetricsBufferMinFreeDiskBytes()   { return metricsBufferMinFreeDiskBytes; }
    public int  getMetricsBufferMaxAgeHours()        { return metricsBufferMaxAgeHours; }
    public boolean isMetricsHttpFallbackEnabled()    { return metricsHttpFallbackEnabled; }
    public String  getMetricsHttpFallbackUrl()       { return metricsHttpFallbackUrl; }
    public int     getMetricsHttpFallbackConnectTimeoutMs() { return metricsHttpFallbackConnectTimeoutMs; }
    public int     getMetricsHttpFallbackRequestTimeoutMs() { return metricsHttpFallbackRequestTimeoutMs; }

    public int getMaxPlanSizeMb()                    { return maxPlanSizeMb; }
    public int getMaxDataZipSizeMb()                 { return maxDataZipSizeMb; }
    public int getMaxExtractedSizeMb()               { return maxExtractedSizeMb; }
    public int getMaxEntrySizeMb()                   { return maxEntrySizeMb; }
    public int getMaxFileCount()                     { return maxFileCount; }

    public String getJmeterHome()                    { return jmeterHome; }
    public String getJmeterBin()                     { return jmeterBin; }
    public String getJmeterJvmArgs()                 { return jmeterJvmArgs; }
    public int    getJmeterOomScoreAdj()             { return jmeterOomScoreAdj; }
    public int    getJmxPort()                       { return jmxPort; }
    public int    getJmeterShutdownPort()            { return jmeterShutdownPort; }
    public int    getJmeterDrainTimeoutSeconds()     { return jmeterDrainTimeoutSeconds; }
    public int    getJmeterTerminationGraceSeconds()    { return jmeterTerminationGraceSeconds; }
    public long   getJoinedAtSecond()                { return joinedAtSecond; }
    public int    getOrchestratorShutdownGraceSeconds() { return orchestratorShutdownGraceSeconds; }

    public Backend getArtifactSource()               { return artifactSource; }
    public Backend getResultSink()                   { return resultSink; }
    public boolean isAutoUploadResults()             { return autoUploadResults; }

    public String getDocumentServiceUrl()            { return documentServiceUrl; }
    public String getDocumentServiceAuthHeader()     { return documentServiceAuthHeader; }
    public int    getDocumentServiceTimeoutSeconds() { return documentServiceTimeoutSeconds; }
    public int    getDocumentServiceRetryCount()     { return documentServiceRetryCount; }

    public String getS3Region()                      { return s3Region; }

    public int getLogBufferLines()                   { return logBufferLines; }
    public int getKafkaHealthCheckIntervalMs()       { return kafkaHealthCheckIntervalMs; }
    public int getKafkaHealthCheckTimeoutMs()        { return kafkaHealthCheckTimeoutMs; }
    public int getMinFreeDiskMb()                    { return minFreeDiskMb; }

    @Override
    public String toString() {
        // Intentionally omits nothing sensitive — Kafka brokers and registry URLs
        // are not secrets. Do not add credentials here if they are ever added.
        return "OrchestratorConfig{" +
                "podName='" + podName + '\'' +
                ", testRegion='" + testRegion + '\'' +
                ", runId='" + runId + '\'' +
                ", jtlPath='" + jtlPath + '\'' +
                ", kafkaTopic='" + kafkaTopic + '\'' +
                ", gracePeriodSeconds=" + gracePeriodSeconds +
                ", pollIntervalMs=" + pollIntervalMs +
                '}';
    }
}
