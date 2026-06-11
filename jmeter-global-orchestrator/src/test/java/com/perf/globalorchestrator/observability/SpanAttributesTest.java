package com.perf.globalorchestrator.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * OBSERVABILITY Phase D — exercises the SpanAttributes null-safety
 * + tagging contract.
 *
 * <p>Out of scope here: end-to-end {@code @Observed} → span recording
 * through the AOP proxy chain. That requires a Spring-managed proxy
 * and is covered by the Jaeger smoke test in the Phase D runbook.
 */
class SpanAttributesTest {

    @Test
    void tagIsNoOpWhenNoObservationInScope() {
        assertThatCode(() -> SpanAttributes.tag("runId", "r-1")).doesNotThrowAnyException();
    }

    @Test
    void tagIsNoOpForNullOrBlankValues() {
        // Open a real Observation so the registry path is exercised — the
        // SpanAttributes helper has to skip the null / blank guard BEFORE
        // accessing the registry, otherwise it would NPE on the value.
        TestObservationRegistry registry = TestObservationRegistry.create();
        Observation observation = Observation.start("test", registry);
        try (Observation.Scope scope = observation.openScope()) {
            assertThatCode(() -> SpanAttributes.tag("runId", null)).doesNotThrowAnyException();
            assertThatCode(() -> SpanAttributes.tag("runId", "")).doesNotThrowAnyException();
            assertThatCode(() -> SpanAttributes.tag("runId", "   ")).doesNotThrowAnyException();
        } finally {
            observation.stop();
        }
    }

    @Test
    void tagAddsHighCardinalityKeyValueWhenObservationActive() {
        // SpanAttributes intentionally uses highCardinalityKeyValue so
        // per-runId / per-workerId tags land on the span only, NOT as
        // Prometheus metric labels (cardinality explosion fix shipped
        // 2026-05-25 during Phase G smoke).
        TestObservationRegistry registry = TestObservationRegistry.create();
        Observation observation = Observation.start("scaleUpRun", registry);
        try (Observation.Scope scope = observation.openScope()) {
            SpanAttributes.tag("runId", "r-1");
            SpanAttributes.tag("bestEffort", "true");
        } finally {
            observation.stop();
        }
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("scaleUpRun")
                .that()
                .hasHighCardinalityKeyValue("runId", "r-1")
                .hasHighCardinalityKeyValue("bestEffort", "true")
                // Negative assertion: these are NOT on the low-cardinality
                // set, so they won't leak into Prometheus metric labels.
                .doesNotHaveLowCardinalityKeyValueWithKey("runId")
                .doesNotHaveLowCardinalityKeyValueWithKey("bestEffort");
    }
}
