package com.perf.k8sorchestrator.sweep;

import com.perf.k8sorchestrator.domain.Application;
import com.perf.k8sorchestrator.domain.Application.HealthStatus;
import com.perf.k8sorchestrator.domain.ApplicationHealthHistory;
import com.perf.k8sorchestrator.domain.Ulid;
import com.perf.k8sorchestrator.repo.ApplicationHealthHistoryRepository;
import com.perf.k8sorchestrator.repo.ApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D-AppRegistry — periodic health-check poller. Runs every 30 s by
 * default (override via {@code globalOrchestrator.application.healthPoll.intervalMs}).
 * For each registered application with at least one health endpoint,
 * GETs each URL with a 5 s timeout and persists an aggregate status:
 *
 * <ul>
 *   <li>{@link HealthStatus#HEALTHY} — every endpoint returned 2xx</li>
 *   <li>{@link HealthStatus#DEGRADED} — at least one ok + at least one failed</li>
 *   <li>{@link HealthStatus#UNHEALTHY} — every endpoint failed</li>
 *   <li>{@link HealthStatus#UNKNOWN} — no endpoints configured</li>
 * </ul>
 *
 * <p>Per-endpoint detail (status code, latency, error) lands in
 * {@code application.lastHealthDetails} as a JSONB array so the UI can
 * surface a "what failed" tooltip without a second backend call.
 *
 * <p>Polling is sequential per app to keep memory bounded — even at
 * 8 endpoints × 30+ apps that's well under a second of work per cycle.
 * Spring's TaskScheduler runs us on a single dedicated thread; the
 * scheduling annotation guarantees no overlap (fixed-delay).
 */
@Component
public class ApplicationHealthPoller {

    private static final Logger log = LoggerFactory.getLogger(ApplicationHealthPoller.class);
    private static final Duration PER_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ApplicationRepository repo;
    private final ApplicationHealthHistoryRepository historyRepo;
    private final HttpClient http;

    public ApplicationHealthPoller(ApplicationRepository repo,
                                   ApplicationHealthHistoryRepository historyRepo) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(PER_REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Record a health-transition row when an app's
     * aggregate status differs from the snapshot's previous status (including
     * the first observation, null → status). Transitions only — not every poll
     * — so the log stays compact for the daily readiness email's downtime
     * windows. Best-effort: a failed insert never breaks the poll. (Multi-replica
     * can produce a duplicate row for the same change; the composer collapses
     * consecutive same-status rows.)
     */
    private void recordTransitionIfChanged(Application app, HealthStatus newStatus) {
        if (app.lastHealthStatus() == newStatus) return;
        try {
            historyRepo.insert(new ApplicationHealthHistory(
                    Ulid.generate(), app.applicationId(), newStatus.name(), Instant.now()));
        } catch (Exception e) {
            log.warn("ApplicationHealthPoller: health-history insert for {} ({}) failed: {}",
                    app.name(), app.applicationId(), e.toString());
        }
    }

    @Scheduled(fixedDelayString =
            "${k8sOrchestrator.application.healthPoll.intervalMs:30000}",
               initialDelayString =
            "${k8sOrchestrator.application.healthPoll.initialDelayMs:5000}")
    public void pollAll() {
        List<Application> apps;
        try {
            apps = repo.findAll();
        } catch (Exception e) {
            log.warn("ApplicationHealthPoller: findAll failed; skipping cycle", e);
            return;
        }
        Instant cycleStart = Instant.now();
        int polled = 0;
        for (Application app : apps) {
            if (app.healthEndpoints() == null || app.healthEndpoints().isEmpty()) {
                // UNKNOWN status — record so the UI can show "no endpoints".
                repo.updateHealth(app.applicationId(), HealthStatus.UNKNOWN, cycleStart, List.of());
                recordTransitionIfChanged(app, HealthStatus.UNKNOWN);
                continue;
            }
            try {
                List<Map<String, Object>> details = checkEndpoints(app.healthEndpoints());
                HealthStatus status = aggregateStatus(details);
                repo.updateHealth(app.applicationId(), status, Instant.now(), details);
                recordTransitionIfChanged(app, status);
                polled++;
            } catch (Exception e) {
                log.warn("ApplicationHealthPoller: poll for {} ({}) failed",
                        app.name(), app.applicationId(), e);
            }
        }
        if (polled > 0) {
            log.debug("ApplicationHealthPoller: polled {}/{} application(s) in {}ms",
                    polled, apps.size(),
                    Duration.between(cycleStart, Instant.now()).toMillis());
        }
    }

    private List<Map<String, Object>> checkEndpoints(List<String> urls) {
        List<Map<String, Object>> out = new java.util.ArrayList<>(urls.size());
        for (String url : urls) {
            out.add(probe(url));
        }
        return out;
    }

    private Map<String, Object> probe(String url) {
        Instant started = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(PER_REQUEST_TIMEOUT)
                    .GET()
                    .header("User-Agent", "jmeter-cloud/ApplicationHealthPoller")
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            long latency = Duration.between(started, Instant.now()).toMillis();
            int code = resp.statusCode();
            boolean ok = code >= 200 && code < 300;
            result.put("statusCode", code);
            result.put("latencyMs", latency);
            result.put("ok", ok);
            if (!ok) {
                result.put("error", "non-2xx status " + code);
            }
        } catch (Exception e) {
            long latency = Duration.between(started, Instant.now()).toMillis();
            result.put("statusCode", null);
            result.put("latencyMs", latency);
            result.put("ok", false);
            result.put("error", e.getClass().getSimpleName() + ": " + nullToBlank(e.getMessage()));
            // InterruptedException etiquette — re-set the interrupt flag.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return result;
    }

    static HealthStatus aggregateStatus(List<Map<String, Object>> details) {
        if (details == null || details.isEmpty()) return HealthStatus.UNKNOWN;
        int oks = 0;
        int fails = 0;
        for (Map<String, Object> d : details) {
            if (Boolean.TRUE.equals(d.get("ok"))) oks++;
            else fails++;
        }
        if (oks == details.size()) return HealthStatus.HEALTHY;
        if (oks == 0) return HealthStatus.UNHEALTHY;
        return HealthStatus.DEGRADED;
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }
}
