package com.perf.globalorchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.provision.PodSpec;
import com.perf.globalorchestrator.provision.ProvisionResult;
import com.perf.globalorchestrator.provision.ProvisionedPod;
import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionUnavailableException;
import com.perf.globalorchestrator.region.RegionalCallException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for a {@code jmeter-regional-orchestrator}'s provisioning API
 * (its {@code api/openapi.yaml} is the contract). Every method takes the
 * region's base URL; nothing is cached here.
 *
 * <p>A region that cannot be reached throws {@link RegionUnavailableException};
 * an error body throws {@link RegionalCallException}, except a
 * {@code 404 POD_NOT_FOUND} on start/restart, which is the interface's
 * {@link IllegalStateException}.
 */
@Component
public class RegionalClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration readTimeout;

    public RegionalClient(ObjectMapper mapper,
                          @Value("${globalOrchestrator.regionalClient.connectTimeoutMs:2000}") long connectTimeoutMs,
                          @Value("${globalOrchestrator.regionalClient.readTimeoutMs:10000}") long readTimeoutMs) {
        this.mapper = mapper;
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Pod lifecycle facts from {@code GET /api/v1/pods/{podName}}: {@code ready} is the readiness probe, {@code dead} the kubelet's verdict. */
    public record PodState(boolean exists, boolean running, boolean ready, boolean dead, String reason) {}

    /** One row of {@code GET /api/v1/workers} — a worker Pod as the kubelet sees it. */
    public record WorkerLiveness(String podName, String groupId, String phase, boolean ready, boolean dead,
                                 String reason, Integer exitCode, int restarts, String message) {}

    public List<WorkerLiveness> listWorkers(String regionalUrl) {
        JsonNode n = json(send(regionalUrl, "GET", "/api/v1/workers", null));
        List<WorkerLiveness> out = new ArrayList<>();
        for (JsonNode w : n) {
            JsonNode exit = w.get("exitCode");
            out.add(new WorkerLiveness(text(w, "podName"), text(w, "groupId"), text(w, "phase"),
                    w.path("ready").asBoolean(false), w.path("dead").asBoolean(false), text(w, "reason"),
                    exit == null || exit.isNull() ? null : exit.asInt(), w.path("restarts").asInt(0), text(w, "message")));
        }
        return out;
    }

    /**
     * {@code POST /api/v1/workers/status} — every named worker's
     * {@code GET /api/v1/test} in one call. A worker that did not answer maps
     * to an empty Optional; a name the regional did not report is absent.
     */
    public Map<String, Optional<Map<String, Object>>> statusBatch(String regionalUrl, List<String> podNames) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("podNames", podNames));
        } catch (IOException e) {
            throw new IllegalArgumentException("unserialisable pod names", e);
        }
        JsonNode n = json(send(regionalUrl, "POST", "/api/v1/workers/status", body));
        Map<String, Optional<Map<String, Object>>> out = new LinkedHashMap<>();
        for (JsonNode w : n) {
            String name = text(w, "podName");
            JsonNode snap = w.get("body");
            if (w.path("status").asInt(0) == 200 && snap != null && snap.isObject()) {
                Map<String, Object> m = new LinkedHashMap<>();
                snap.fields().forEachRemaining(e -> m.put(e.getKey(),
                        e.getValue().isValueNode() ? e.getValue().asText(null) : e.getValue().toString()));
                out.put(name, Optional.of(m));
            } else {
                out.put(name, Optional.empty());
            }
        }
        return out;
    }

    public RegionCapabilities capabilities(String regionalUrl) {
        JsonNode n = json(send(regionalUrl, "GET", "/api/v1/capabilities", null));
        JsonNode workersFree = n.path("capacity").path("workersFree");
        return new RegionCapabilities(
                text(n, "region"), text(n, "namespace"), text(n, "headlessService"),
                text(n, "image"), n.path("localOrchestratorPort").asInt(8080), text(n, "version"),
                workersFree.isNumber() ? workersFree.asInt() : null);
    }

    public ProvisionResult createPod(String regionalUrl, PodSpec spec) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of(
                    "podName", spec.podName(),
                    "groupId", spec.groupId(),
                    "region", spec.region()));
        } catch (IOException e) {
            throw new IllegalArgumentException("unserialisable PodSpec", e);
        }
        JsonNode n = json(send(regionalUrl, "POST", "/api/v1/pods", body));
        return new ProvisionResult(text(n, "baseUrl"), text(n, "imageDigest"), instant(n, "createdAt"));
    }

    public PodState getPod(String regionalUrl, String podName) {
        JsonNode n = json(send(regionalUrl, "GET", "/api/v1/pods/" + podName, null));
        return new PodState(n.path("exists").asBoolean(false), n.path("running").asBoolean(false),
                n.path("ready").asBoolean(false), n.path("dead").asBoolean(false), text(n, "reason"));
    }

    public void deletePod(String regionalUrl, String podName) {
        send(regionalUrl, "DELETE", "/api/v1/pods/" + podName, null);
    }

    public void stopPod(String regionalUrl, String podName) {
        send(regionalUrl, "POST", "/api/v1/pods/" + podName + "/stop", null);
    }

    public void startPod(String regionalUrl, String podName) {
        send(regionalUrl, "POST", "/api/v1/pods/" + podName + "/start", null);
    }

    public void restartPod(String regionalUrl, String podName) {
        send(regionalUrl, "POST", "/api/v1/pods/" + podName + "/restart", null);
    }

    public List<ProvisionedPod> listPods(String regionalUrl, String groupId, String region) {
        String query = "?groupId=" + URLEncoder.encode(groupId, StandardCharsets.UTF_8)
                + (region == null ? "" : "&region=" + URLEncoder.encode(region, StandardCharsets.UTF_8));
        JsonNode n = json(send(regionalUrl, "GET", "/api/v1/pods" + query, null));
        List<ProvisionedPod> out = new ArrayList<>();
        for (JsonNode p : n) {
            out.add(new ProvisionedPod(text(p, "podName"), text(p, "groupId"), text(p, "region"),
                    text(p, "status"), instant(p, "startedAt"), text(p, "imageDigest")));
        }
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────

    private HttpResponse<String> send(String regionalUrl, String method, String path, String jsonBody) {
        URI target = URI.create(stripTrailingSlash(regionalUrl) + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(readTimeout);
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RegionUnavailableException(null, "regional orchestrator at " + regionalUrl
                    + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegionUnavailableException(null, "interrupted calling " + regionalUrl, e);
        }
        if (resp.statusCode() >= 400) {
            String code = "HTTP_" + resp.statusCode();
            String message = resp.body();
            try {
                JsonNode err = mapper.readTree(resp.body());
                if (err.hasNonNull("code")) code = err.get("code").asText();
                if (err.hasNonNull("message")) message = err.get("message").asText();
            } catch (IOException ignored) {
                // Non-JSON error body — keep the raw text.
            }
            if (resp.statusCode() == 404 && "POD_NOT_FOUND".equals(code)) {
                throw new IllegalStateException(message);
            }
            throw new RegionalCallException(resp.statusCode(), code,
                    method + " " + path + " at " + regionalUrl + " → " + resp.statusCode() + " " + code + ": " + message);
        }
        return resp;
    }

    private JsonNode json(HttpResponse<String> resp) {
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new RegionalCallException(resp.statusCode(), "MALFORMED_RESPONSE",
                    "regional orchestrator answered non-JSON: " + e.getMessage());
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? Instant.now() : Instant.parse(s);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
