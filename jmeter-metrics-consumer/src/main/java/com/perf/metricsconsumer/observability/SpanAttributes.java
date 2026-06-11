package com.perf.metricsconsumer.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

/**
 * OBSERVABILITY Phase D — span-attribute helper.
 *
 * <p>Tag the current observation's span from inside an {@code @Observed}
 * method. No-op when no observation is in scope or when {@code value} is
 * null/blank. See the equivalent class in jmeter-global-orchestrator for
 * the full design rationale.
 */
public final class SpanAttributes {

    private SpanAttributes() {}

    public static void tag(String key, String value) {
        if (value == null || value.isBlank()) return;
        Observation current = ObservationThreadLocalAccessor.getInstance().getValue();
        if (current == null) return;
        // High-cardinality: span-only, never escapes to Prometheus
        // metric labels. See the equivalent class in
        // jmeter-global-orchestrator for the full rationale.
        current.highCardinalityKeyValue(key, value);
    }
}
