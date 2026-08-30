package com.perf.orchestrator.registry;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Step 15 — registers this orchestrator pod with the global-orchestrator
 * on boot and pings {@code POST /api/v1/heartbeat} every 30 s.
 *
 * <p>Bean is wired only when {@code GLOBAL_ORCHESTRATOR_URL} is set
 * ({@code @ConditionalOnProperty}). Local-dev launches without a global
 * boot just fine — the orchestrator fully functions as a stand-alone
 * worker even without a control plane.
 *
 * <p>Both register and heartbeat are best-effort: a failure logs at
 * WARN and is retried by the next scheduled tick. We don't fail the
 * orchestrator's boot or its HTTP request handling on a global-side
 * outage.
 */
@Component
@ConditionalOnProperty(name = "GLOBAL_ORCHESTRATOR_URL")
public class PodRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(PodRegistrar.class);

    private final HttpClient http;
    private final String globalUrl;
    private final String podId;
    private final String region;
    private final String podBaseUrl;
    private final String groupId;

    public PodRegistrar(
            @Value("${GLOBAL_ORCHESTRATOR_URL:}")    String globalUrl,
            @Value("${HOSTNAME:#{null}}")            String hostname,
            @Value("${REGION:us-east-1}")            String region,
            @Value("${HTTP_PORT:8080}")              String httpPort,
            // POD_ID + POD_BASE_URL let the operator override the
            // hostname-derived defaults — useful in K8s where the
            // service name differs from the pod name.
            @Value("${POD_ID:#{null}}")              String podIdOverride,
            @Value("${POD_BASE_URL:#{null}}")        String podBaseUrlOverride,
            // The application group whose pool this worker joins — stamped by
            // the regional's PodProvisioner (GROUP-CAPACITY, 2026-08-30; was
            // APPLICATION_ID). Optional: operator-declared static workers get
            // their group from the declare call and register without it.
            @Value("${GROUP_ID:#{null}}")            String groupId) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.globalUrl = stripTrailingSlash(globalUrl);
        this.podId = podIdOverride != null && !podIdOverride.isBlank()
                ? podIdOverride
                : (hostname != null && !hostname.isBlank() ? hostname : "orchestrator-unknown");
        this.region = region;
        this.podBaseUrl = podBaseUrlOverride != null && !podBaseUrlOverride.isBlank()
                ? podBaseUrlOverride
                : "http://" + this.podId + ":" + httpPort;
        this.groupId = groupId != null && !groupId.isBlank() ? groupId : null;
    }

    @PostConstruct
    void registerOnBoot() {
        // Don't block bean init on the global being reachable. Fire-and-
        // forget; the heartbeat scheduler retries on the standard cadence.
        Thread t = new Thread(this::register, "podRegistrar-bootRegister");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${podRegistrar.heartbeatIntervalMs:30000}",
               initialDelayString = "${podRegistrar.heartbeatInitialDelayMs:30000}")
    public void heartbeat() {
        String body = "{\"podId\":\"" + podId + "\"}";
        try {
            HttpResponse<String> resp = post("/api/v1/heartbeat", body);
            if (resp.statusCode() == 404) {
                // Global lost our row (DB reset / migration); re-register inline.
                LOG.info("Heartbeat returned 404 — re-registering with global at {}", globalUrl);
                register();
                return;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.warn("Heartbeat to {} failed: HTTP {} body={}", globalUrl, resp.statusCode(), resp.body());
                return;
            }
        } catch (Exception e) {
            LOG.warn("Heartbeat to {} failed: {}", globalUrl, e.toString());
        }
    }

    private void register() {
        StringBuilder body = new StringBuilder(192);
        body.append("{\"podId\":\"").append(podId).append("\",")
            .append("\"region\":\"").append(region).append("\",")
            .append("\"baseUrl\":\"").append(podBaseUrl).append("\"");
        if (groupId != null) {
            body.append(",\"groupId\":\"").append(groupId).append("\"");
        }
        body.append("}");
        try {
            HttpResponse<String> resp = post("/api/v1/registerPod", body.toString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.warn("registerPod to {} failed: HTTP {} body={}",
                        globalUrl, resp.statusCode(), resp.body());
                return;
            }
            LOG.info("Registered with global-orchestrator at {} as podId={} baseUrl={} groupId={}",
                    globalUrl, podId, podBaseUrl, groupId);
        } catch (Exception e) {
            LOG.warn("registerPod to {} failed: {}", globalUrl, e.toString());
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(globalUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
