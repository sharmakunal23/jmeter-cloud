package com.perf.orchestrator.metrics;

/**
 * Supplier seam for {@link OrchestratorCounters}.
 *
 * <p>Wired in {@code OrchestratorMain} from a small lambda that pulls
 * counters from {@code CurrentRun}'s snapshot plus a disk-free probe.
 * The interface keeps {@code ObservabilityController} and
 * {@code PrometheusExporter} unaware of how the counters are sourced —
 * future steps can swap the supplier (e.g. for one driven by the
 * streaming pipeline directly) without touching the HTTP layer.
 */
@FunctionalInterface
public interface CountersSupplier {
    OrchestratorCounters snapshot();
}
