package com.perf.globalorchestrator.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

/**
 * Helper for the OBSERVABILITY Phase D {@code @Observed}-annotated methods.
 *
 * <p>An annotated method runs inside an Observation. To enrich the resulting
 * span with business IDs (so Jaeger renders them as searchable tags), call
 * {@link #tag(String, String)} early in the method body. Null-safe — if the
 * observation registry sampled the call out, or if the method was invoked
 * outside a request (e.g. a unit test), the call is a no-op.
 *
 * <h2>Cardinality contract</h2>
 * The values passed here are almost always high-cardinality identifiers
 * ({@code runId}, {@code workerId}, {@code podBaseUrl}). Micrometer
 * surfaces low-cardinality key-values as BOTH span tags AND Prometheus
 * metric labels — putting {@code runId="r-abc-123"} on a timer metric
 * would spawn a new time-series per run and balloon Prometheus storage
 * (we hit this in Phase G smoke testing — the
 * {@code globalOrchestrator_refreshAndGet_seconds_bucket} time-series
 * fanned out per runId and made {@code histogram_quantile()} return NaN).
 *
 * <p>This helper therefore uses {@code highCardinalityKeyValue}, which
 * Micrometer attaches to the span only — the metric stays a single
 * aggregable time-series. Low-cardinality tags that you DO want on the
 * metric (e.g. {@code action}, {@code bestEffort}) belong in
 * {@code @Observed(lowCardinalityKeyValues = {"action", "scaleUpRun"})}
 * — they're set at annotation-parse time and never escape into per-call
 * cardinality.
 */
public final class SpanAttributes {

    private SpanAttributes() {}

    /**
     * Tags the current observation's span. No-op when no Observation is
     * in scope or when {@code value} is null/blank. Span-only (high-
     * cardinality) so it never lands as a Prometheus metric label.
     */
    public static void tag(String key, String value) {
        if (value == null || value.isBlank()) return;
        Observation current = ObservationThreadLocalAccessor.getInstance().getValue();
        if (current == null) return;
        current.highCardinalityKeyValue(key, value);
    }
}
