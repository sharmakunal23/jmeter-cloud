package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.Application.HealthStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What "healthy" means for an application, in one place: a GET per endpoint,
 * 2xx passes, and the per-endpoint result maps the UI renders. Shared by the
 * background {@code ApplicationHealthPoller} and a workflow's health-check task
 * so a gate and a badge can never disagree.
 *
 * <p>Redirects are not followed — a 302 to a login page is not a healthy
 * service, and treating it as one is the failure this rule exists to prevent.
 */
@Component
public class HealthProbe {

    /** The background poller's timeout; a workflow node sets its own. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;

    public HealthProbe() {
        // No client-level connectTimeout on purpose: it would cap the caller's
        // own timeout, so a health-check node allowed 20 s would still fail at
        // 5 s against a SUT slow to accept the connection. The per-request
        // timeout below bounds the whole exchange, connect included.
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** One result map per URL, in order — keys: url, statusCode, latencyMs, ok, error. */
    public List<Map<String, Object>> probeAll(List<String> urls, Duration timeout) {
        List<Map<String, Object>> out = new ArrayList<>(urls.size());
        for (String url : urls) out.add(probe(url, timeout));
        return out;
    }

    public Map<String, Object> probe(String url, Duration timeout) {
        Instant started = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .header("User-Agent", "jmeter-cloud/HealthProbe")
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            boolean ok = code >= 200 && code < 300;
            result.put("statusCode", code);
            result.put("latencyMs", elapsedMs(started));
            result.put("ok", ok);
            if (!ok) result.put("error", "non-2xx status " + code);
        } catch (Exception e) {
            result.put("statusCode", null);
            result.put("latencyMs", elapsedMs(started));
            result.put("ok", false);
            result.put("error", e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "" : e.getMessage()));
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return result;
    }

    private static long elapsedMs(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }

    /** Endpoints that returned 2xx. */
    public static int okCount(List<Map<String, Object>> details) {
        int oks = 0;
        for (Map<String, Object> d : details) {
            if (Boolean.TRUE.equals(d.get("ok"))) oks++;
        }
        return oks;
    }

    /** The application's aggregate badge: all ok, none ok, or in between. */
    public static HealthStatus aggregate(List<Map<String, Object>> details) {
        if (details == null || details.isEmpty()) return HealthStatus.UNKNOWN;
        int oks = okCount(details);
        if (oks == details.size()) return HealthStatus.HEALTHY;
        if (oks == 0) return HealthStatus.UNHEALTHY;
        return HealthStatus.DEGRADED;
    }
}
