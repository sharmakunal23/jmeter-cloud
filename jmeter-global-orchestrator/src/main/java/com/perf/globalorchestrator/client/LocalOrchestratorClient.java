package com.perf.globalorchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.observability.ErrorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thin HTTP client for the per-pod local-orchestrator's REST API. Uses
 * the JDK's {@link HttpClient} (no extra deps; same library the
 * orchestrator's own ResultUploader uses). All calls go through a single
 * shared client — the JDK pools connections per-target-host internally.
 */
@Component
public class LocalOrchestratorClient {

    private static final Logger LOG = LoggerFactory.getLogger(LocalOrchestratorClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;

    public LocalOrchestratorClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Result of a fan-out POST /api/v1/test call. */
    public record StartTestResult(int statusCode, String body, boolean accepted) {
        public boolean ok() { return statusCode >= 200 && statusCode < 300; }
    }

    public StartTestResult startTest(String runId, String podBaseUrl, Map<String, Object> body) {
        URI target = URI.create(stripTrailingSlash(podBaseUrl) + "/api/v1/test");
        try {
            String payload = mapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(target)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            addRunIdHeader(builder, runId);
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new StartTestResult(resp.statusCode(), resp.body(), resp.statusCode() == 202);
        } catch (Exception e) {
            ErrorContext.logWarn(LOG,
                    "startTest runId=" + runId + " podBaseUrl=" + podBaseUrl,
                    "startTest fan-out to " + target + " failed",
                    e);
            return new StartTestResult(0, e.toString(), false);
        }
    }

    /**
     * Fires {@code POST /api/v1/test/drain} on
     * the pod's local-orchestrator. Empty body, returns 202 on accepted /
     * 404 NO_ACTIVE_RUN. The local-orch handles the drain asynchronously
     * (TCP shutdown port → SIGTERM fallback → JMeter exits → DRAINED);
     * this client does NOT wait for convergence — caller polls
     * {@link #getTestStatus} for the eventual terminal state.
     */
    public DrainTestResult drainTest(String runId, String podBaseUrl) {
        URI target = URI.create(stripTrailingSlash(podBaseUrl) + "/api/v1/test/drain");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody());
            addRunIdHeader(builder, runId);
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new DrainTestResult(resp.statusCode(), resp.body(), resp.statusCode() == 202);
        } catch (Exception e) {
            ErrorContext.logWarn(LOG,
                    "drainTest runId=" + runId + " podBaseUrl=" + podBaseUrl,
                    "drainTest call to " + target + " failed",
                    e);
            return new DrainTestResult(0, e.toString(), false);
        }
    }

    /** Result of a {@code POST /api/v1/test/drain} fan-out call. */
    public record DrainTestResult(int statusCode, String body, boolean accepted) {
        public boolean ok() { return statusCode >= 200 && statusCode < 300; }
    }

    /**
     * Fires {@code POST /api/v1/test/abort} on the pod's local-orchestrator —
     * the hard kill (SIGKILL → ABORTED), as opposed to {@link #drainTest}'s
     * graceful shutdown. Used by the run-abort endpoint to force a live worker
     * to stop immediately. Best-effort: returns 202 on accepted, 404 when the
     * pod has no active run, and a synthetic 0 when the pod is unreachable (the
     * common zombie-run case — the container is already gone). The caller
     * force-marks the run/member ABORTED regardless of this result; the RPC is
     * only a courtesy so a healthy worker stops its JMeter child promptly.
     */
    public AbortTestResult abortTest(String runId, String podBaseUrl) {
        URI target = URI.create(stripTrailingSlash(podBaseUrl) + "/api/v1/test/abort");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody());
            addRunIdHeader(builder, runId);
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new AbortTestResult(resp.statusCode(), resp.body(), resp.statusCode() == 202);
        } catch (Exception e) {
            ErrorContext.logWarn(LOG,
                    "abortTest runId=" + runId + " podBaseUrl=" + podBaseUrl,
                    "abortTest call to " + target + " failed",
                    e);
            return new AbortTestResult(0, e.toString(), false);
        }
    }

    /** Result of a {@code POST /api/v1/test/abort} fan-out call. */
    public record AbortTestResult(int statusCode, String body, boolean accepted) {
        public boolean ok() { return statusCode >= 200 && statusCode < 300; }
    }

    /**
     * Fetches the local-orchestrator's log tail (text/plain). Used by
     * Step 19's per-pod log panel — the global proxies the request so
     * the browser only ever talks to one origin.
     *
     * @param tail   number of trailing lines requested. Local orchestrator
     *               clamps to [1, 10000].
     * @param stream stream selector forwarded as the {@code stream} query
     *               param. Validated by the local orchestrator
     *               ({@code console} | {@code jmeter}); pass {@code null}
     *               to fall through to the local-orch default
     *               ({@code console}).
     * @return raw log text + the local orchestrator's status code.
     *         A 404 means no log buffer (e.g., no run yet); the UI
     *         renders that as "no logs yet" rather than an error.
     *         A 400 is forwarded so a bad {@code stream} value reaches
     *         the operator with the local-orch's diagnostic message.
     */
    public LogsResult getLogs(String podBaseUrl, int tail, String stream) {
        StringBuilder url = new StringBuilder(stripTrailingSlash(podBaseUrl))
                .append("/api/v1/logs?tail=").append(tail);
        if (stream != null && !stream.isBlank()) {
            url.append("&stream=").append(URLEncoder.encode(stream, StandardCharsets.UTF_8));
        }
        URI target = URI.create(url.toString());
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new LogsResult(resp.statusCode(), resp.body());
        } catch (Exception e) {
            LOG.debug("getLogs {} failed: {}", target, e.toString());
            return new LogsResult(0, "");
        }
    }

    public record LogsResult(int statusCode, String body) {
        public boolean ok() { return statusCode >= 200 && statusCode < 300; }
    }

    /** Fetches the local-orchestrator's current test snapshot (or empty on 404 / unreachable). */
    public Optional<Map<String, Object>> getTestStatus(String podBaseUrl) {
        URI target = URI.create(stripTrailingSlash(podBaseUrl) + "/api/v1/test");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return Optional.empty();
            if (resp.statusCode() >= 400) {
                LOG.debug("getTestStatus {} returned {} — treating as no-snapshot", target, resp.statusCode());
                return Optional.empty();
            }
            JsonNode node = mapper.readTree(resp.body());
            Map<String, Object> snap = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> snap.put(e.getKey(),
                    e.getValue().isValueNode() ? e.getValue().asText(null) : e.getValue().toString()));
            return Optional.of(snap);
        } catch (Exception e) {
            LOG.debug("getTestStatus {} failed: {} — treating as no-snapshot", target, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Readiness ping. Returns true when the
     * pod's actuator health endpoint reports 2xx. Used after spinning
     * a fresh pod via {@link com.perf.globalorchestrator.provision.PodSpinService}
     * to wait until the container has bound 8080 before fanning out
     * {@code POST /api/v1/test} to it — otherwise the fanout races
     * the startup and the member lands in REJECTED.
     */
    public boolean isHealthy(String podBaseUrl) {
        URI target = URI.create(stripTrailingSlash(podBaseUrl) + "/actuator/health");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Propagates the runId across the service
     * boundary as the {@code X-Run-Id} header. Local-orch's MdcEnrichmentFilter
     * (Phase C) picks the value up into MDC so log lines on the receiving
     * side carry the runId automatically. No-op when {@code runId} is
     * null / blank — the fanout still works for paths that don't have a
     * run context (e.g. health probes).
     *
     * <p>The W3C {@code traceparent} header is also propagated, but by
     * Spring Boot's OTel autoconfig — not by this method.
     */
    static void addRunIdHeader(HttpRequest.Builder builder, String runId) {
        if (runId == null || runId.isBlank()) return;
        builder.header("X-Run-Id", runId);
    }
}
