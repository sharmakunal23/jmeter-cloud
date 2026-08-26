package com.perf.k8sorchestrator.domain;

import java.time.Instant;

/**
 * A frozen aggregate snapshot of one COMPLETED run.
 * Mirrors the {@code globalOrchestrator.runTrend} row (Flyway V24). Written
 * exactly once when global-orch observes a run reach COMPLETED; read back as
 * the cheap 7-day baseline for the daily perf-test report (Phase D).
 *
 * @param runId           run primary key (also the trend row PK).
 * @param applicationName run's application (nullable — a run may be untagged).
 * @param p50Ms           run-level p50 (throughput-weighted mean of per-window p50s).
 * @param p95Ms           run-level p95 (throughput-weighted mean of per-window p95s).
 * @param p99Ms           run-level p99 (throughput-weighted mean of per-window p99s).
 * @param errorRate       total errors / total samples over the run (0..1).
 * @param throughputRps   total samples / wall-clock span seconds.
 * @param completedAt     when the run reached terminal.
 */
public record RunTrend(
        String runId,
        String applicationName,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double errorRate,
        double throughputRps,
        Instant completedAt) {
}
