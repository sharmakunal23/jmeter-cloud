package com.perf.globalorchestrator.report;

import com.perf.globalorchestrator.client.DocumentServiceClient;
import com.perf.globalorchestrator.domain.Application;
import com.perf.globalorchestrator.domain.ApplicationHealthHistory;
import com.perf.globalorchestrator.repo.ApplicationHealthHistoryRepository;
import com.perf.globalorchestrator.repo.ApplicationRepository;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AUTOMATION Phase E (operator goal #2) — builds the daily infra-readiness
 * report: a green-light "all clear" or a list of what was/is unhealthy and for
 * how long.
 *
 * <p>Sources:
 * <ul>
 *   <li><b>Backends</b> — the global-orchestrator's own {@link HealthEndpoint}
 *       aggregate (covers db / redis / kafka indicators in-process) plus a
 *       document-service probe.</li>
 *   <li><b>Applications</b> — each registered app's current health from the
 *       registry + the last-24h downtime computed from the
 *       {@code applicationHealthHistory} transition log.</li>
 * </ul>
 *
 * <p>{@link #compose()} returns a structured {@link Report} (also served by the
 * preview endpoint); {@link #renderHtml(Report)} turns it into the email body.
 */
@Component
public class InfraReadinessComposer {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final HealthEndpoint healthEndpoint;
    private final DocumentServiceClient documentService;
    private final ApplicationRepository applications;
    private final ApplicationHealthHistoryRepository history;

    public InfraReadinessComposer(HealthEndpoint healthEndpoint,
                                  DocumentServiceClient documentService,
                                  ApplicationRepository applications,
                                  ApplicationHealthHistoryRepository history) {
        this.healthEndpoint = healthEndpoint;
        this.documentService = documentService;
        this.applications = applications;
        this.history = history;
    }

    public Report compose() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(WINDOW);

        List<BackendStatus> backends = new ArrayList<>();
        // The global-orchestrator itself — we're running, so UP.
        backends.add(new BackendStatus("global-orchestrator", "UP", "this service"));
        // In-process health indicators (db / redis / kafka / …).
        try {
            HealthComponent root = healthEndpoint.health();
            if (root instanceof CompositeHealth ch) {
                for (Map.Entry<String, HealthComponent> e : ch.getComponents().entrySet()) {
                    backends.add(new BackendStatus(e.getKey(), e.getValue().getStatus().getCode(), null));
                }
            }
        } catch (Exception e) {
            backends.add(new BackendStatus("healthEndpoint", "UNKNOWN", "probe failed: " + e));
        }
        // document-service is a separate process — probe it.
        backends.add(new BackendStatus("document-service",
                documentService.isHealthy() ? "UP" : "DOWN", null));

        List<AppReadiness> apps = new ArrayList<>();
        List<Application> registered;
        try {
            registered = applications.findAll();
        } catch (Exception e) {
            registered = List.of();
        }
        for (Application app : registered) {
            String current = app.lastHealthStatus() == null ? "UNKNOWN" : app.lastHealthStatus().name();
            long downMinutes = downtimeMinutes(app.applicationId(), current, windowStart, now);
            apps.add(new AppReadiness(app.name(), current, downMinutes, !isDown(current)));
        }

        boolean backendsAllUp = backends.stream().allMatch(b -> "UP".equals(b.status()));
        boolean appsAllClear = apps.stream().allMatch(a -> a.healthyNow() && a.downtimeMinutes24h() == 0);
        boolean allClear = backendsAllUp && appsAllClear;
        return new Report(now, allClear, backends, apps);
    }

    /** Down-time (UNHEALTHY/DEGRADED) minutes for an app over the 24h window,
     *  walking the transition log. Collapses consecutive same-status rows so a
     *  multi-replica duplicate transition doesn't distort the total. */
    private long downtimeMinutes(String applicationId, String fallbackCurrent,
                                 Instant windowStart, Instant now) {
        String state = history.findLatestBefore(applicationId, windowStart)
                .map(ApplicationHealthHistory::status)
                .orElse(fallbackCurrent);
        long downMs = 0;
        Instant segStart = windowStart;
        for (ApplicationHealthHistory t : history.findSince(applicationId, windowStart)) {
            if (t.status().equals(state)) continue; // collapse duplicate transition
            if (isDown(state)) downMs += Duration.between(segStart, t.changedAt()).toMillis();
            segStart = t.changedAt();
            state = t.status();
        }
        if (isDown(state)) downMs += Duration.between(segStart, now).toMillis();
        return Duration.ofMillis(Math.max(0, downMs)).toMinutes();
    }

    private static boolean isDown(String status) {
        return "UNHEALTHY".equals(status) || "DEGRADED".equals(status);
    }

    public String subject(Report r) {
        String day = r.generatedAt().truncatedTo(ChronoUnit.SECONDS).toString().substring(0, 10);
        if (r.allClear()) {
            return "[jmeter-cloud] Infra readiness " + day + " — ✅ all clear";
        }
        long issues = r.backends().stream().filter(b -> !"UP".equals(b.status())).count()
                + r.apps().stream().filter(a -> !a.healthyNow() || a.downtimeMinutes24h() > 0).count();
        return "[jmeter-cloud] Infra readiness " + day + " — ⚠️ " + issues + " issue(s)";
    }

    /** Subject with an operator override — the custom subject when set, else the default. */
    public String subject(Report r, String customSubject) {
        return customSubject != null && !customSubject.isBlank() ? customSubject : subject(r);
    }

    public String renderHtml(Report r) {
        return renderHtml(r, null);
    }

    /** Light, inline-styled email body with an optional operator intro note. */
    public String renderHtml(Report r, String customIntro) {
        StringBuilder body = new StringBuilder();
        if (r.allClear()) {
            body.append("<p style=\"color:#137333;font-weight:600;margin:0 0 8px\">"
                    + "✅ All clear — every backend and registered application was healthy "
                    + "over the last 24 hours.</p>");
        } else {
            body.append("<p style=\"color:#b00020;font-weight:600;margin:0 0 8px\">"
                    + "⚠️ Issues detected — see below.</p>");
        }

        body.append(EmailLayout.h2("Backends"));
        body.append("<table style=\"").append(EmailLayout.TABLE).append("\">")
            .append("<tr><th style=\"").append(EmailLayout.TH).append("\">Component</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Status</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Detail</th></tr>");
        for (BackendStatus b : r.backends()) {
            body.append("<tr><td style=\"").append(EmailLayout.TD).append("\">").append(EmailLayout.escape(b.name()))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(EmailLayout.statusChip(b.status()))
                .append("</td><td style=\"").append(EmailLayout.TD).append(";color:#6b7280\">")
                .append(EmailLayout.escape(b.detail() == null ? "" : b.detail())).append("</td></tr>");
        }
        body.append("</table>");

        body.append(EmailLayout.h2("Applications (24h)"));
        body.append("<table style=\"").append(EmailLayout.TABLE).append("\">")
            .append("<tr><th style=\"").append(EmailLayout.TH).append("\">Application</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Current</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Downtime (min)</th></tr>");
        if (r.apps().isEmpty()) {
            body.append("<tr><td colspan=\"3\" style=\"").append(EmailLayout.TD)
                .append(";color:#6b7280\"><em>no registered applications</em></td></tr>");
        }
        for (AppReadiness a : r.apps()) {
            body.append("<tr><td style=\"").append(EmailLayout.TD).append("\">").append(EmailLayout.escape(a.name()))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(EmailLayout.statusChip(a.currentStatus()))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(a.downtimeMinutes24h())
                .append("</td></tr>");
        }
        body.append("</table>");

        String subtitle = "Generated " + r.generatedAt() + " · window: last 24h";
        return EmailLayout.shell("Infra readiness", subtitle, customIntro, body.toString());
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    public record BackendStatus(String name, String status, String detail) {}

    public record AppReadiness(String name, String currentStatus, long downtimeMinutes24h, boolean healthyNow) {}

    public record Report(Instant generatedAt, boolean allClear,
                         List<BackendStatus> backends, List<AppReadiness> apps) {
        public Report {
            backends = backends == null ? List.of() : List.copyOf(backends);
            apps = apps == null ? List.of() : List.copyOf(apps);
        }
    }
}
