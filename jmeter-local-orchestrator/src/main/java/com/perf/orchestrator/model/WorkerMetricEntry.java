package com.perf.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One per-label aggregate inside a {@link WorkerMetricBatch} envelope,
 * produced by {@code SecondBucket#toMetricEntry()}. Envelope-level identity
 * ({@code runId}, {@code workerId}, {@code region}, {@code windowSecond}, …)
 * lives on the batch — this record carries only the per-label numbers.
 *
 * <p>All double fields are guaranteed finite by the aggregator (division
 * guards + HDRHistogram longs) — JSON cannot carry {@code NaN}/{@code
 * Infinity} portably, and the ingest contract requires finite values
 * (pinned by {@code SecondBucketTest}).
 *
 * <h2>{@code sumElapsedMs} vs {@code avgRespTimeMs} (SCHEMA-OPT Phase 2)</h2>
 * {@code sumElapsedMs} is the exact total the bucket accumulated;
 * {@code avgRespTimeMs} is {@code sumElapsedMs / throughput}, i.e. strictly
 * derived and strictly lossier. The consumer stores the sum, not the mean —
 * a sum folds across workers, labels and time without weighting drift, and it
 * survives the round-trip exactly, which the mean does not.
 *
 * <p>Both are on the wire on purpose. {@code avgRespTimeMs} is kept so a
 * consumer meeting an <em>older</em> worker (one that predates this field) can
 * still reconstruct the sum as {@code round(avgRespTimeMs × throughput)} — the
 * exact value that worker's rows already carried. Dropping it would turn a
 * rolling upgrade into either silent zeroes or a terminal 400. Adding a field
 * is producer-first-safe per cross-component contract #1; removing one is not.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerMetricEntry(
        String label,
        long throughput,
        long errorCount,
        double errorRate,
        double avgRespTimeMs,
        long sumElapsedMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms,
        double minMs,
        double maxMs,
        long rawMaxMs,
        long bytesReceived,
        long bytesSent,
        Map<String, Long> statusCodes,
        long activeThreads) {
}
