package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Handle to an envelope persisted in a {@link MetricsBuffer}. Returned by
 * {@link MetricsBuffer#enqueue} on success and by {@link MetricsBuffer#peekOldest}.
 *
 * <p>Carrying the deserialised {@link WorkerMetricBatch} alongside the file
 * handle keeps the dispatch hot path cheap: the dispatcher publishes
 * {@code envelope} directly without re-reading + ungzipping {@code file}.
 *
 * <p>For the in-memory buffer impl, {@code file} is {@code null}.
 *
 * @param id           sortable string identifier (millis-prefixed); also the
 *                     filename stem on disk. Lexicographic order = chronological
 *                     enqueue order.
 * @param file         on-disk location ({@code .envelope.gz}); {@code null} for
 *                     the in-memory impl
 * @param sizeBytes    on-disk gzipped size; for the in-memory impl, the
 *                     on-disk gzipped-JSON size
 * @param enqueuedAt   wall-clock instant the envelope was enqueued
 * @param envelope     the deserialised payload
 */
public record BufferedEnvelope(
        String id,
        Path file,
        long sizeBytes,
        Instant enqueuedAt,
        WorkerMetricBatch envelope
) {
    public BufferedEnvelope {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must be non-blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0, got: " + sizeBytes);
        }
        if (enqueuedAt == null) {
            throw new IllegalArgumentException("enqueuedAt must be non-null");
        }
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must be non-null");
        }
    }
}
