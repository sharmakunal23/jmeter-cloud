package com.perf.orchestrator.http;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two platform endpoints that stay bespoke rather than deferring to
 * Actuator: {@code /api/v1/ready} and {@code /api/v1/config}, served by Tomcat
 * on {@code HTTP_PORT} like every other route.
 *
 * <p>{@code /api/v1/ready} combines metrics-consumer reachability with disk
 * pressure in a documented precedence that Spring's health aggregator cannot
 * express. {@code /api/v1/config} returns the redacted environment snapshot the
 * orchestrator booted with — {@code /actuator/env} is a different shape and does
 * not redact this platform's keys ({@code AUTH_TOKEN},
 * {@code DOCUMENT_SERVICE_AUTH_HEADER}, {@code DOCUMENT_SERVICE_URL} userinfo).
 *
 * <p>Health and info live at {@code /actuator/health} and {@code /actuator/info};
 * counters at {@code /api/v1/metrics/orchestrator}. There is no
 * {@code /actuator/prometheus} — 404 by design.
 */
@RestController
public final class PlatformController {

    private static final String REDACTED = "***";

    private final OrchestratorConfig config;
    private final ReadinessProbe readiness;

    public PlatformController(OrchestratorConfig config, ReadinessProbe readiness) {
        this.config = config;
        this.readiness = readiness;
    }

    @GetMapping("/api/v1/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        ReadinessProbe.Snapshot snap = readiness.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snap.isUp() ? "UP" : "DOWN");
        body.put("ingestReachable", snap.ingestReachable());
        body.put("diskFreeBytes", snap.diskFreeBytes());
        body.put("testState", snap.testState());
        if (snap.reason() != null) {
            body.put("reason", snap.reason());
        }
        HttpStatus status = snap.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/api/v1/config")
    public Map<String, Object> config() {
        return redactedConfig();
    }

    /**
     * Builds the effective resolved-config map shown by {@code GET /api/v1/config}.
     *
     * <p>Insertion order is intentional: HTTP / paths / limits / JMeter /
     * backends / ingest — same grouping as {@code OrchestratorConfig}'s field
     * declaration so operators reading the JSON see related settings together.
     *
     * <p>Package-private so the existing pure-Java tests can assert keys and
     * redaction without going through Spring MVC.
     */
    Map<String, Object> redactedConfig() {
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("HTTP_PORT",              config.getHttpPort());
        out.put("HTTP_BIND_ADDRESS",      config.getHttpBindAddress());
        out.put("HTTP_MIN_THREADS",       config.getHttpMinThreads());
        out.put("HTTP_MAX_THREADS",       config.getHttpMaxThreads());
        out.put("HTTP_REQUEST_TIMEOUT_S", config.getHttpRequestTimeoutSeconds());
        out.put("AUTH_TOKEN",             redactSecret(config.getAuthToken()));

        out.put("BASE_DIR",       config.getBaseDir());
        out.put("TEST_PLAN_DIR",  config.getTestPlanDir());
        out.put("DATA_FILES_DIR", config.getDataFilesDir());
        out.put("RESULTS_DIR",    config.getResultsDir());
        out.put("LOGS_DIR",       config.getLogsDir());
        out.put("RUN_STATE_FILE", config.getRunStateFile());

        out.put("MAX_PLAN_SIZE_MB",      config.getMaxPlanSizeMb());
        out.put("MAX_DATA_ZIP_SIZE_MB",  config.getMaxDataZipSizeMb());
        out.put("MAX_EXTRACTED_SIZE_MB", config.getMaxExtractedSizeMb());
        out.put("MAX_ENTRY_SIZE_MB",     config.getMaxEntrySizeMb());
        out.put("MAX_FILE_COUNT",        config.getMaxFileCount());

        out.put("JMETER_HOME",     config.getJmeterHome());
        out.put("JMETER_BIN",      config.getJmeterBin());
        out.put("JMETER_JVM_ARGS",      config.getJmeterJvmArgs());
        out.put("JMETER_OOM_SCORE_ADJ", config.getJmeterOomScoreAdj());
        out.put("JMX_PORT",             config.getJmxPort());

        out.put("ARTIFACT_SOURCE",     config.getArtifactSource().name());
        out.put("RESULT_SINK",         config.getResultSink().name());
        out.put("AUTO_UPLOAD_RESULTS", config.isAutoUploadResults());

        out.put("DOCUMENT_SERVICE_URL",            redactUrlUserInfo(config.getDocumentServiceUrl()));
        out.put("DOCUMENT_SERVICE_AUTH_HEADER",    redactSecret(config.getDocumentServiceAuthHeader()));
        out.put("DOCUMENT_SERVICE_TIMEOUT_S",      config.getDocumentServiceTimeoutSeconds());
        out.put("DOCUMENT_SERVICE_RETRY_COUNT",    config.getDocumentServiceRetryCount());
        out.put("S3_REGION",                       config.getS3Region());

        out.put("LOG_BUFFER_LINES",                config.getLogBufferLines());
        out.put("INGEST_HEALTH_CHECK_INTERVAL_MS", config.getIngestHealthCheckIntervalMs());
        out.put("INGEST_HEALTH_CHECK_TIMEOUT_MS",  config.getIngestHealthCheckTimeoutMs());
        out.put("MIN_FREE_DISK_MB",                config.getMinFreeDiskMb());
        out.put("ORCHESTRATOR_SHUTDOWN_GRACE_S",   config.getOrchestratorShutdownGraceSeconds());

        out.put("METRICS_INGEST_URL",  redactUrlUserInfo(config.getMetricsIngestUrl()));
        out.put("TEST_REGION",         config.getTestRegion());
        out.put("POD_NAME",            config.getPodName());
        out.put("WORKER_ID_SOURCE",    config.isUseThreadName() ? "THREAD_NAME" : "POD_NAME");

        return out;
    }

    private static String redactSecret(String value) {
        return value == null || value.isBlank() ? "" : REDACTED;
    }

    /**
     * Strips the userinfo segment from a URL so credentials embedded as
     * {@code https://user:pass@host/path} do not leak through {@code /config}.
     * Returns the input unchanged when no userinfo is present, when the value
     * is blank, or when parsing fails (a malformed URL is still safer to show
     * verbatim than to silently rewrite into something the operator did not
     * configure).
     *
     * <p>Output shape: {@code https://***:***@host/path} when the userinfo
     * had a colon; {@code https://***@host/path} otherwise.
     */
    static String redactUrlUserInfo(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        try {
            URI uri = new URI(value);
            String userInfo = uri.getUserInfo();
            if (userInfo == null) {
                return value;
            }
            String redactedUserInfo = userInfo.contains(":") ? "***:***" : "***";
            URI rebuilt = new URI(
                    uri.getScheme(),
                    redactedUserInfo,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment());
            return rebuilt.toString();
        } catch (URISyntaxException e) {
            // Malformed URL — better to surface the operator's input verbatim
            // (it's already broken) than to silently mangle it.
            return value;
        }
    }
}
