package com.perf.globalorchestrator.domain;

import java.time.Instant;
import java.util.List;

/**
 * AI-2 — Claude's reading of the delta between two runs,
 * served by {@code POST /api/v1/runs/compareInsights?ids=A,B}.
 *
 * <p>{@code runIds} preserves the operator's submitted order (A then B);
 * {@code summary} reads B relative to A; {@code findings} are per-metric
 * verdicts the UI colour-codes. The durable cache is keyed on the sorted pair so
 * order does not split entries; {@code fromCache} reports whether this load was
 * billed.
 */
public record CompareInsights(
        List<String> runIds,
        String model,
        String promptVersion,
        String summary,
        List<CompareFinding> findings,
        int tokensIn,
        int tokensOut,
        Instant cachedAt,
        boolean fromCache) {

    /**
     * One per-metric comparison. {@code verdict} ∈ {regression, improvement,
     * "no significant change"}; {@code delta} is a human-readable change string
     * (e.g. {@code "+12.3%"}, {@code "-0.4 pp"}); {@code evidence} names the two
     * figures it was computed from, so the operator can check it.
     */
    public record CompareFinding(String metric, String verdict, String delta,
                                 String detail, String evidence) { }
}
