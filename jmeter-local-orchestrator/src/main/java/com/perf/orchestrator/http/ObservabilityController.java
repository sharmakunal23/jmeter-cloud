package com.perf.orchestrator.http;

import com.perf.orchestrator.logs.LogTail;
import com.perf.orchestrator.metrics.CountersSupplier;
import com.perf.orchestrator.metrics.JmeterJvmSnapshot;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.OrchestratorCounters;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for {@code /api/v1/metrics/jmeterJvm},
 * {@code /api/v1/metrics/orchestrator}, and {@code /api/v1/logs}.
 *
 * <p><b>Spring MVC since Step 4.4g.</b> These app-level JSON/text endpoints
 * are the orchestrator's whole metrics surface since SLIMDOWN (2026-07-21):
 * the Prometheus exposition that 4.4g moved the counters to left with the
 * Micrometer stack ({@code /actuator/prometheus} is 404 by design). The
 * counters here were never Micrometer — {@code CountersSupplier} reads
 * {@code CurrentRun} snapshots + {@code LongAdder}s directly.
 */
@RestController
public final class ObservabilityController {

    private static final int DEFAULT_LOG_TAIL = 200;
    private static final int MAX_LOG_TAIL = 10_000;

    private final JmxMetricsCollector jmx;
    private final CountersSupplier counters;
    private final LogTail logTail;

    public ObservabilityController(JmxMetricsCollector jmx,
                                   CountersSupplier counters,
                                   LogTail logTail) {
        this.jmx = jmx;
        this.counters = counters;
        this.logTail = logTail;
    }

    @GetMapping("/api/v1/metrics/jmeterJvm")
    public ResponseEntity<?> jmeterJvm() {
        Optional<JmeterJvmSnapshot> snap = jmx.snapshot();
        if (snap.isEmpty()) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", "JMETER_NOT_RUNNING");
            body.put("message", "JMeter JMX endpoint is unreachable.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        return ResponseEntity.ok(jmeterJvmJson(snap.get()));
    }

    @GetMapping("/api/v1/metrics/orchestrator")
    public ResponseEntity<Map<String, Object>> orchestratorMetrics() {
        OrchestratorCounters c = counters.snapshot();
        return ResponseEntity.ok(c.toJsonMap());
    }

    @GetMapping(path = "/api/v1/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> logs(
            @RequestParam(value = "tail",   required = false) Integer tailParam,
            @RequestParam(value = "stream", required = false) String streamParam) {
        int n = clamp(tailParam == null ? DEFAULT_LOG_TAIL : tailParam, 1, MAX_LOG_TAIL);
        // `stream` selects the source — different surfaces, intentionally
        // not merged so a UI tab labelled "Console" doesn't sprout JMeter
        // log4j lines on it (and vice versa).
        //   - "console" (default) → orchestrator's in-memory ring of the
        //                           JMeter child's stdout + stderr.
        //   - "jmeter"            → tail of jmeter.log on disk (JMeter's
        //                           own log4j output, distinct content).
        // Anything else throws → 400 BAD_REQUEST via GlobalErrorHandler.
        String stream = streamParam == null || streamParam.isBlank() ? "console" : streamParam;
        List<String> lines = switch (stream) {
            case "console" -> logTail.tailRingOnly(n);
            case "jmeter"  -> logTail.tailFileOnly(n);
            default -> throw new IllegalArgumentException(
                    "stream must be one of: console, jmeter (got '" + stream + "')");
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(String.join("\n", lines));
    }

    static Map<String, Object> jmeterJvmJson(JmeterJvmSnapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heapUsedBytes",    s.heapUsedBytes());
        out.put("heapMaxBytes",     s.heapMaxBytes());
        out.put("nonHeapUsedBytes", s.nonHeapUsedBytes());
        out.put("gcYoungCount",     s.gcYoungCount());
        out.put("gcYoungPauseMs",   s.gcYoungPauseMs());
        out.put("gcOldCount",       s.gcOldCount());
        out.put("gcOldPauseMs",     s.gcOldPauseMs());
        out.put("threadCount",      s.threadCount());
        out.put("cpuLoadPercent",   s.cpuLoadPercent());
        out.put("uptimeMs",         s.uptimeMs());
        out.put("loadedClasses",    s.loadedClasses());
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
