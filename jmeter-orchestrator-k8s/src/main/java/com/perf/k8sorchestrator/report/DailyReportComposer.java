package com.perf.k8sorchestrator.report;

import com.perf.k8sorchestrator.domain.Application;
import com.perf.k8sorchestrator.domain.RunTrend;
import com.perf.k8sorchestrator.repo.ApplicationRepository;
import com.perf.k8sorchestrator.repo.RunRepository;
import com.perf.k8sorchestrator.repo.RunTrendRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AUTOMATION Phase D (operator goal #1) — the daily unified performance-testing
 * report: one summary across all applications of the previous 24h of runs
 * (launched / completed / failed) plus p50 / p95 / error-rate for the last 24h
 * compared against each app's preceding 6-day baseline, and the top regressions
 * to investigate.
 *
 * <p>Sources, both cheap (no scan of the per-second metrics table at send time):
 * <ul>
 *   <li><b>Run counts</b> — {@link RunRepository#countByStateForApplicationSince}.</li>
 *   <li><b>Latency / error baselines</b> — the {@code runTrend} snapshots
 *       (one frozen aggregate row per COMPLETED run, written by Phase F).</li>
 * </ul>
 *
 * <p>Mirrors {@link InfraReadinessComposer}: {@link #compose()} returns a
 * structured {@link Report} (also served by the preview endpoint);
 * {@link #renderHtml(Report)} turns it into the email body.
 */
@Component
public class DailyReportComposer {

    private static final Duration RECENT = Duration.ofHours(24);
    private static final Duration BASELINE = Duration.ofDays(7);
    private static final int MAX_REGRESSIONS = 3;
    /** Only flag a p95 regression once it's meaningfully worse than baseline. */
    private static final double REGRESSION_THRESHOLD_PCT = 10.0;

    private final ApplicationRepository applications;
    private final RunRepository runs;
    private final RunTrendRepository runTrends;

    public DailyReportComposer(ApplicationRepository applications,
                               RunRepository runs,
                               RunTrendRepository runTrends) {
        this.applications = applications;
        this.runs = runs;
        this.runTrends = runTrends;
    }

    public Report compose() {
        Instant now = Instant.now();
        Instant recentStart = now.minus(RECENT);
        Instant baselineStart = now.minus(BASELINE);

        List<AppDaily> apps = new ArrayList<>();
        List<Regression> regressions = new ArrayList<>();
        long totalRuns = 0;

        List<Application> registered;
        try {
            registered = applications.findAll();
        } catch (Exception e) {
            registered = List.of();
        }

        for (Application app : registered) {
            String name = app.name();

            Map<String, Long> byState = runs.countByStateForApplicationSince(name, recentStart);
            long launched = byState.values().stream().mapToLong(Long::longValue).sum();
            long completed = byState.getOrDefault("COMPLETED", 0L);
            long failed = byState.getOrDefault("FAILED", 0L) + byState.getOrDefault("ABORTED", 0L);
            totalRuns += launched;

            // One read of the 7-day trend window, partitioned into the last-24h
            // "recent" set and the preceding "baseline" set.
            List<RunTrend> window = runTrends.findByApplicationSince(name, baselineStart);
            List<RunTrend> recentRows = new ArrayList<>();
            List<RunTrend> baselineRows = new ArrayList<>();
            for (RunTrend t : window) {
                if (t.completedAt() != null && t.completedAt().isBefore(recentStart)) {
                    baselineRows.add(t);
                } else {
                    recentRows.add(t);
                }
            }
            Stats recent = Stats.average(recentRows);
            Stats baseline = Stats.average(baselineRows);
            apps.add(new AppDaily(name, launched, completed, failed, recent, baseline));

            // Regression — both sides present and p95 meaningfully worse.
            if (recent != null && baseline != null && baseline.p95Ms() > 0) {
                double deltaPct = (recent.p95Ms() - baseline.p95Ms()) / baseline.p95Ms() * 100.0;
                if (deltaPct >= REGRESSION_THRESHOLD_PCT) {
                    regressions.add(new Regression(name, baseline.p95Ms(), recent.p95Ms(), deltaPct));
                }
            }
        }

        regressions.sort(Comparator.comparingDouble(Regression::deltaPct).reversed());
        List<Regression> top = regressions.size() > MAX_REGRESSIONS
                ? regressions.subList(0, MAX_REGRESSIONS) : regressions;
        return new Report(now, totalRuns, apps, new ArrayList<>(top));
    }

    public String subject(Report r) {
        String day = r.generatedAt().truncatedTo(ChronoUnit.SECONDS).toString().substring(0, 10);
        if (r.totalRuns() == 0) {
            return "[jmeter-cloud] Daily perf report " + day + " — no runs in the last 24h";
        }
        String base = "[jmeter-cloud] Daily perf report " + day + " — "
                + r.totalRuns() + " run(s) across " + r.apps().size() + " app(s)";
        return r.topRegressions().isEmpty()
                ? base
                : base + " · ⚠️ " + r.topRegressions().size() + " regression(s)";
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

        if (r.totalRuns() == 0) {
            body.append("<p style=\"color:#6b7280;margin:0 0 8px\">No runs were launched across any "
                    + "application in the last 24 hours.</p>");
        }

        if (!r.topRegressions().isEmpty()) {
            body.append("<h2 style=\"font-size:14px;margin:18px 0 6px;color:#b00020\">"
                    + "⚠️ Top regressions (p95 vs baseline)</h2>");
            body.append("<table style=\"").append(EmailLayout.TABLE).append("\">")
                .append("<tr><th style=\"").append(EmailLayout.TH).append("\">Application</th>")
                .append("<th style=\"").append(EmailLayout.TH).append("\">Baseline p95</th>")
                .append("<th style=\"").append(EmailLayout.TH).append("\">Last 24h p95</th>")
                .append("<th style=\"").append(EmailLayout.TH).append("\">Δ</th></tr>");
            for (Regression rg : r.topRegressions()) {
                body.append("<tr><td style=\"").append(EmailLayout.TD).append("\">").append(EmailLayout.escape(rg.application()))
                    .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(ms(rg.baselineP95Ms()))
                    .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(ms(rg.recentP95Ms()))
                    .append("</td><td style=\"").append(EmailLayout.TD).append(";color:#b00020;font-weight:600\">+")
                    .append(pct1(rg.deltaPct())).append("</td></tr>");
            }
            body.append("</table>");
        }

        body.append(EmailLayout.h2("Per application (last 24h)"));
        body.append("<table style=\"").append(EmailLayout.TABLE).append("\">")
            .append("<tr><th style=\"").append(EmailLayout.TH).append("\">Application</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Launched</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Completed</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Failed</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">p50 (24h / base)</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">p95 (24h / base)</th>")
            .append("<th style=\"").append(EmailLayout.TH).append("\">Error % (24h / base)</th></tr>");
        if (r.apps().isEmpty()) {
            body.append("<tr><td colspan=\"7\" style=\"").append(EmailLayout.TD)
                .append(";color:#6b7280\"><em>no registered applications</em></td></tr>");
        }
        for (AppDaily a : r.apps()) {
            body.append("<tr><td style=\"").append(EmailLayout.TD).append("\">").append(EmailLayout.escape(a.name()))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(a.launched())
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(a.completed())
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">").append(failedCell(a.failed()))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">")
                .append(metricPair(a.recent() == null ? null : a.recent().p50Ms(),
                                   a.baseline() == null ? null : a.baseline().p50Ms(), true))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">")
                .append(metricPair(a.recent() == null ? null : a.recent().p95Ms(),
                                   a.baseline() == null ? null : a.baseline().p95Ms(), true))
                .append("</td><td style=\"").append(EmailLayout.TD).append("\">")
                .append(metricPair(a.recent() == null ? null : a.recent().errorRate() * 100.0,
                                   a.baseline() == null ? null : a.baseline().errorRate() * 100.0, false))
                .append("</td></tr>");
        }
        body.append("</table>");

        String subtitle = "Generated " + r.generatedAt() + " · last 24h vs 6-day baseline";
        return EmailLayout.shell("Daily performance report", subtitle, customIntro, body.toString());
    }

    // ── Rendering helpers ───────────────────────────────────────────────

    private static String failedCell(long failed) {
        return failed > 0
                ? "<span style=\"color:#b00020;font-weight:600\">" + failed + "</span>"
                : String.valueOf(failed);
    }

    /** "12 ms / 11 ms" style current-vs-baseline cell; "—" when a side has no data. */
    private static String metricPair(Double current, Double baseline, boolean isMs) {
        String c = current == null ? "—" : (isMs ? ms(current) : pct1(current));
        String b = baseline == null ? "—" : (isMs ? ms(baseline) : pct1(baseline));
        return c + " <span style=\"color:#9ca3af\">/ " + b + "</span>";
    }

    private static String ms(double v) {
        return String.format(java.util.Locale.ROOT, "%.0f ms", v);
    }

    private static String pct1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", v);
    }

    // ── DTOs ───────────────────────────────────────────────────────────

    /** Averaged latency/error stats over a set of run snapshots; null when empty. */
    public record Stats(double p50Ms, double p95Ms, double errorRate, int runs) {
        static Stats average(List<RunTrend> trends) {
            if (trends.isEmpty()) return null;
            double p50 = 0, p95 = 0, err = 0;
            for (RunTrend t : trends) {
                p50 += t.p50Ms();
                p95 += t.p95Ms();
                err += t.errorRate();
            }
            int n = trends.size();
            return new Stats(p50 / n, p95 / n, err / n, n);
        }
    }

    public record AppDaily(String name, long launched, long completed, long failed,
                           Stats recent, Stats baseline) {}

    public record Regression(String application, double baselineP95Ms,
                             double recentP95Ms, double deltaPct) {}

    public record Report(Instant generatedAt, long totalRuns,
                         List<AppDaily> apps, List<Regression> topRegressions) {
        public Report {
            apps = apps == null ? List.of() : List.copyOf(apps);
            topRegressions = topRegressions == null ? List.of() : List.copyOf(topRegressions);
        }
    }
}
