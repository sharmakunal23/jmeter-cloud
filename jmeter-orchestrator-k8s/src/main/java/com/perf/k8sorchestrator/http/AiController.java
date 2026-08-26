package com.perf.k8sorchestrator.http;

import com.perf.k8sorchestrator.domain.CompareInsights;
import com.perf.k8sorchestrator.domain.RunInsights;
import com.perf.k8sorchestrator.domain.Ulid;
import com.perf.k8sorchestrator.service.AiClient;
import com.perf.k8sorchestrator.service.AiClient.AiDisabledException;
import com.perf.k8sorchestrator.service.AiClient.AiResult;
import com.perf.k8sorchestrator.service.AiClient.AiUpstreamException;
import com.perf.k8sorchestrator.service.AiInsightsService;
import com.perf.k8sorchestrator.service.AiQuotaGuard;
import com.perf.k8sorchestrator.service.AiQuotaGuard.AiQuotaExceededException;
import com.perf.k8sorchestrator.service.RunService.RunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * AI-0 / AI-1 / AI-2 — the Anthropic-backed analysis surface.
 *
 * <p>All AI endpoints live on this <b>single</b> controller (sharing
 * {@code RunController}'s {@code /api/v1} base) so it can own its own
 * {@code @ExceptionHandler}s. Spring resolves controller-local handlers before
 * any advice, so keeping the AI exceptions here avoids being swallowed by
 * {@code RunController}'s catch-all {@code Exception} handler.
 *
 * <p>The API key lives only on the server: the UI calls these endpoints, never
 * Anthropic directly. {@code GET /ai/status} lets the UI hide the buttons when
 * no key is configured (local dev), so they never 503 on click.
 */
@RestController
@RequestMapping("/api/v1")
public class AiController {

    private static final Logger LOG = LoggerFactory.getLogger(AiController.class);

    private final AiClient ai;
    private final AiQuotaGuard quota;
    private final AiInsightsService insights;

    public AiController(AiClient ai, AiQuotaGuard quota, AiInsightsService insights) {
        this.ai = ai;
        this.quota = quota;
        this.insights = insights;
    }

    /**
     * AI-0 — feature probe. Consumes no quota and never calls Claude; the UI
     * polls it once to decide whether to render the AI buttons at all.
     */
    @GetMapping("/ai/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", ai.isEnabled());
        body.put("model", ai.model());
        return body;
    }

    /**
     * AI-0 — smoke test. Sends a fixed prompt and returns the reply + token
     * counts; for first-time-setup verification + latency baselining. Not wired
     * into the UI. Counts against the daily cap.
     */
    @PostMapping("/ai/ping")
    public Map<String, Object> ping() {
        if (!ai.isEnabled()) {
            throw new AiDisabledException("ANTHROPIC_API_KEY is not configured");
        }
        quota.acquire();
        AiResult r = ai.complete(
                "You are a health check. Reply with exactly the word: pong",
                "ping");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", r.text());
        body.put("tokensIn", r.tokensIn());
        body.put("tokensOut", r.tokensOut());
        return body;
    }

    /**
     * AI-1 — single-run insights. Cached for terminal runs; {@code ?fresh=true}
     * bypasses the cache and re-bills.
     */
    @PostMapping("/runs/{runId:" + Ulid.PATTERN + "}/insights")
    public RunInsights runInsights(
            @PathVariable String runId,
            @RequestParam(name = "fresh", defaultValue = "false") boolean fresh) {
        return insights.runInsights(runId, fresh);
    }

    /**
     * AI-2 — two-run comparison delta. Mirrors the batch endpoint's strict
     * exactly-2-distinct-ids contract. Both runs must exist (a missing run 404s
     * — we refuse to "compare" against nothing). Cached on the sorted pair for
     * terminal runs; {@code ?fresh=true} bypasses + re-bills.
     */
    @PostMapping("/runs/compare-insights")
    public CompareInsights compareInsights(
            @RequestParam(name = "ids") String idsParam,
            @RequestParam(name = "fresh", defaultValue = "false") boolean fresh) {
        if (idsParam == null || idsParam.isBlank()) {
            throw new IllegalArgumentException("ids query parameter is required");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String raw : idsParam.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) ids.add(id);
        }
        if (ids.size() != 2) {
            throw new IllegalArgumentException(
                    "ids must contain exactly 2 distinct run ids; got " + ids.size()
                            + " (the comparison view supports two runs)");
        }
        var it = ids.iterator();
        return insights.compareInsights(it.next(), it.next(), fresh);
    }

    // ── error handling (controller-local so RunController's catch-all can't swallow these) ──

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleNotFound(RunNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "RUN_NOT_FOUND",
                "message", e.getMessage()));
    }

    @ExceptionHandler(AiDisabledException.class)
    ResponseEntity<Map<String, Object>> handleDisabled(AiDisabledException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "AI_DISABLED",
                "message", "AI analysis is not configured on this server (no ANTHROPIC_API_KEY)."));
    }

    @ExceptionHandler(AiQuotaExceededException.class)
    ResponseEntity<Map<String, Object>> handleQuota(AiQuotaExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "code", "AI_QUOTA_EXCEEDED",
                "message", e.getMessage()));
    }

    @ExceptionHandler(AiUpstreamException.class)
    ResponseEntity<Map<String, Object>> handleUpstream(AiUpstreamException e) {
        LOG.warn("AI upstream call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "code", "AI_UPSTREAM_ERROR",
                "message", "The AI provider call failed; try again."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "INVALID_REQUEST",
                "message", e.getMessage()));
    }
}
