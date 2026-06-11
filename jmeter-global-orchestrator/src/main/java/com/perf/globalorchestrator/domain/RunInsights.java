package com.perf.globalorchestrator.domain;

import java.time.Instant;
import java.util.List;

/**
 * AI-1 — Claude's reading of a single run, served by
 * {@code POST /api/v1/runs/{runId}/insights}.
 *
 * <p>{@code summary} is a free-text overview; {@code findings} are the
 * severity-tagged bullets the UI renders as a small table. {@code tokensIn} /
 * {@code tokensOut} surface cost for observability. {@code cachedAt} is the
 * moment the response was produced; {@code fromCache} tells the UI whether this
 * load hit the durable {@code aiResponse} table (no new Claude bill) or was
 * freshly generated.
 *
 * <p>The AI is <b>advisory, never authoritative</b> — the system prompt and the
 * UI copy both say so.
 */
public record RunInsights(
        String runId,
        String model,
        String promptVersion,
        String summary,
        List<Finding> findings,
        int tokensIn,
        int tokensOut,
        Instant cachedAt,
        boolean fromCache) {

    /** One severity-tagged observation. {@code severity} ∈ {info, warn, crit}. */
    public record Finding(String severity, String title, String detail) { }
}
