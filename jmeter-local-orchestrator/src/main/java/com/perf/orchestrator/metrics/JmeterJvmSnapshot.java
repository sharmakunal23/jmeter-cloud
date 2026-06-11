package com.perf.orchestrator.metrics;

/**
 * Response shape for {@code GET /api/v1/metrics/jmeterJvm} — matches the
 * {@code JmeterJvmMetrics} schema in {@code api/openapi.yaml}.
 *
 * <p>{@code cpuLoadPercent} is a process-level percentage in 0–100;
 * GC counters are cumulative since JMeter started.
 */
public record JmeterJvmSnapshot(
        long heapUsedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long gcYoungCount,
        long gcYoungPauseMs,
        long gcOldCount,
        long gcOldPauseMs,
        int  threadCount,
        double cpuLoadPercent,
        long uptimeMs,
        int  loadedClasses) {
}
