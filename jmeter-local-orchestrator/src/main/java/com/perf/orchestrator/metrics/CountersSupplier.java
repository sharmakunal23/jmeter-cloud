package com.perf.orchestrator.metrics;

/**
 * Supplier seam for {@link OrchestratorCounters}.
 *
 * <p>Wired from a lambda that pulls counters out of {@code CurrentRun}'s
 * snapshot plus a disk-free probe, so the HTTP layer stays unaware of where
 * they come from and the supplier can be swapped without touching it.
 */
@FunctionalInterface
public interface CountersSupplier {
    OrchestratorCounters snapshot();
}
