package com.perf.globalorchestrator.http;

import com.perf.globalorchestrator.report.DailyReportComposer;
import com.perf.globalorchestrator.report.InfraReadinessComposer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AUTOMATION Phase E — on-demand preview of the report emails, so a report is
 * never email-only: an operator (or the UI) can fetch the same content the
 * scheduled fire would send. Covered by {@code CriticalPaths} (/api/v1/**),
 * so no observability config change.
 *
 * <p>The optional {@code customSubject} / {@code customIntro} query params let the
 * create dialog preview the operator's <em>unsaved</em> tailoring exactly as it
 * will send.
 */
@RestController
@RequestMapping("/api/v1/automation/reports")
public class AutomationReportController {

    private final InfraReadinessComposer infraReadiness;
    private final DailyReportComposer dailyReport;

    public AutomationReportController(InfraReadinessComposer infraReadiness,
                                      DailyReportComposer dailyReport) {
        this.infraReadiness = infraReadiness;
        this.dailyReport = dailyReport;
    }

    /** The infra-readiness report as it would be emailed (structured + rendered HTML + subject). */
    @GetMapping("/infraReadiness")
    public ResponseEntity<Map<String, Object>> infraReadiness(
            @RequestParam(value = "customSubject", required = false) String customSubject,
            @RequestParam(value = "customIntro", required = false) String customIntro) {
        InfraReadinessComposer.Report report = infraReadiness.compose();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", infraReadiness.subject(report, customSubject));
        body.put("report", report);
        body.put("html", infraReadiness.renderHtml(report, customIntro));
        return ResponseEntity.ok(body);
    }

    /** The daily perf-test report as it would be emailed (AUTOMATION Phase D). */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(
            @RequestParam(value = "customSubject", required = false) String customSubject,
            @RequestParam(value = "customIntro", required = false) String customIntro) {
        DailyReportComposer.Report report = dailyReport.compose();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", dailyReport.subject(report, customSubject));
        body.put("report", report);
        body.put("html", dailyReport.renderHtml(report, customIntro));
        return ResponseEntity.ok(body);
    }
}
