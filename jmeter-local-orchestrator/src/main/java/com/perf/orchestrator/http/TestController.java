package com.perf.orchestrator.http;

import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.StartTestRequest;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.lifecycle.TestState;
import com.perf.orchestrator.lifecycle.UpdatePropertiesRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the singleton test run:
 * {@code POST/GET/DELETE /api/v1/test} and {@code POST /api/v1/test/abort}.
 *
 * <p>All status-code mapping for parsing failures and lifecycle rejections is delegated to
 * {@link GlobalErrorHandler}; this class only deals with happy-path JSON
 * shapes and the {@code 404 NO_TEST_EXISTS} / {@code 404 NO_ACTIVE_RUN}
 * envelopes that aren't exception-driven.
 *
 * <h2>Request shape</h2>
 * {@code POST /api/v1/test} accepts a JSON body matching {@link StartTestRequest}.
 * Spring's Jackson converter handles deserialization — malformed JSON
 * surfaces as {@link org.springframework.http.converter.HttpMessageNotReadableException}
 * and is mapped to 400 BAD_REQUEST by the advice. An invalid
 * {@code scheduledStartAt} surfaces as {@link IllegalArgumentException}
 * from {@link StartTestRequest#scheduledStartInstant()} and is mapped
 * the same way.
 */
@RestController
public final class TestController {

    private final TestRunManager runManager;

    public TestController(TestRunManager runManager) {
        this.runManager = runManager;
    }

    @PostMapping(path = "/api/v1/test", consumes = "application/json")
    public ResponseEntity<?> start(@RequestBody StartTestRequest request) {
        // Validate the parseable scheduledStartAt up-front so a bad value
        // comes back as 400 (via GlobalErrorHandler) rather than crashing
        // the run worker mid-PREPARING. Throws IllegalArgumentException
        // when present-but-invalid; Optional.empty when absent.
        request.scheduledStartInstant();

        CurrentRun.Snapshot snap = runManager.start(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId",     snap.runId());
        body.put("state",     snap.state().name());
        body.put("startedAt", snap.startedAt() == null ? null : snap.startedAt().toString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/api/v1/test")
    public ResponseEntity<?> status() {
        Optional<CurrentRun.Snapshot> snap = runManager.snapshotIfPresent();
        if (snap.isEmpty()) {
            return notFound("NO_TEST_EXISTS", "No test has been started.");
        }
        return ResponseEntity.ok(snapshotJson(snap.get()));
    }

    @DeleteMapping("/api/v1/test")
    public ResponseEntity<?> stop() {
        if (!runManager.isRunning()) {
            return notFound("NO_ACTIVE_RUN", "No test is currently running.");
        }
        runManager.stop();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/test/abort")
    public ResponseEntity<?> abort() {
        if (!runManager.isRunning()) {
            return notFound("NO_ACTIVE_RUN", "No test is currently running.");
        }
        runManager.abort();
        return ResponseEntity.accepted().build();
    }

    /**
     * Graceful drain via JMeter's TCP shutdown
     * port. In-flight samplers complete; no new ones start. The run lands
     * in {@link TestState#DRAINED} on clean exit. If the drain budget
     * ({@code JMETER_DRAIN_TIMEOUT_S}, default 60 s) elapses without exit,
     * the lifecycle escalates to abort and the run lands ABORTED with
     * reason {@code "drainTimeoutExpired"}.
     *
     * <p>Returns 202 immediately — the caller (global-orchestrator's
     * scaleDown path) polls {@code GET /api/v1/test} for state convergence.
     * Idempotent: repeated calls during the same drain window are no-ops.
     */
    @PostMapping("/api/v1/test/drain")
    public ResponseEntity<?> drain() {
        if (!runManager.isRunning()) {
            return notFound("NO_ACTIVE_RUN", "No test is currently running.");
        }
        runManager.drain();
        return ResponseEntity.accepted().build();
    }

    /**
     * UX-DYNAMICS T5 — pushes JMeter property values into the RUNNING child
     * via the BeanShell server. Only plan values read through
     * {@code ${__P(name)}} / {@code ${__property(name)}} observe the update,
     * at their next evaluation — thread counts and once-read config do not.
     */
    @PostMapping("/api/v1/test/properties")
    public ResponseEntity<?> updateProperties(@RequestBody UpdatePropertiesRequest request) {
        if (!runManager.isRunning()) {
            return notFound("NO_ACTIVE_RUN", "No test is currently running.");
        }
        return switch (runManager.pushProperties(request.properties())) {
            case DISABLED -> ResponseEntity.status(503).body(Map.of(
                    "error", "BEANSHELL_DISABLED",
                    "message", "The BeanShell server is disabled on this worker (BEANSHELL_PORT=0)."));
            case UNREACHABLE -> ResponseEntity.status(502).body(Map.of(
                    "error", "BEANSHELL_UNREACHABLE",
                    "message", "The BeanShell server did not accept the push — JMeter may still be starting."));
            case SENT -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("runId", runManager.snapshotIfPresent()
                        .map(CurrentRun.Snapshot::runId).orElse(null));
                body.put("applied", java.util.List.copyOf(request.properties().keySet()));
                yield ResponseEntity.ok(body);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Response shape — mirrors api/openapi.yaml#TestStateResponse
    // -----------------------------------------------------------------------

    static Map<String, Object> snapshotJson(CurrentRun.Snapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId",            s.runId());
        out.put("state",            s.state().name());
        out.put("startedAt",        s.startedAt() == null   ? null : s.startedAt().toString());
        out.put("completedAt",      s.completedAt() == null ? null : s.completedAt().toString());
        out.put("elapsedMs",        elapsedMs(s));
        out.put("rowsIngested",     s.rowsIngested());
        out.put("windowsPublished", s.windowsPublished());
        out.put("publishErrors",    s.publishErrors());
        out.put("lastPublishAckMs", s.lastPublishAckMs());
        out.put("jmeterPid",        s.jmeterPid());
        out.put("jmeterAlive",      s.state() == TestState.RUNNING || s.state() == TestState.STARTING);
        out.put("exitCode",         s.exitCode());
        out.put("uploadState",      s.uploadState());
        if (s.uploadTarget() != null) out.put("uploadTarget", s.uploadTarget());
        if (s.uploadFailureReason() != null) out.put("uploadFailureReason", s.uploadFailureReason());
        if (s.failureReason() != null) out.put("failureReason", s.failureReason());
        return out;
    }

    private static ResponseEntity<Map<String, String>> notFound(String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static long elapsedMs(CurrentRun.Snapshot s) {
        if (s.startedAt() == null) return 0L;
        Instant end = s.completedAt() != null ? s.completedAt() : Instant.now();
        return Math.max(0L, end.toEpochMilli() - s.startedAt().toEpochMilli());
    }
}
