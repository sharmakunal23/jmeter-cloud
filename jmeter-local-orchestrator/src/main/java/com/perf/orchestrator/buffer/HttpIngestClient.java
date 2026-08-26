package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * The orchestrator's metrics sink — POSTs JSON {@link WorkerMetricBatch}
 * envelopes to the metrics-consumer's {@code /api/v1/ingest} endpoint.
 *
 * <p>This is the platform's only publish path.
 *
 * <p>The contract maps the ingest response codes to a {@link HttpIngestResult.Outcome}
 * the dispatcher uses to decide buffer-state transitions:
 *
 * <ul>
 *   <li>{@link HttpIngestResult.Outcome#ACCEPTED} — buffer.delete(envelope).</li>
 *   <li>{@link HttpIngestResult.Outcome#TERMINAL_REJECT} — buffer.delete(envelope)
 *       (the consumer says the payload is malformed; retrying won't help, and
 *       leaving it on disk wastes space). Counter {@code metricsIngest.terminalRejects}
 *       surfaces the loss for operator review.</li>
 *   <li>{@link HttpIngestResult.Outcome#RETRY} — leave on disk so the
 *       dispatcher's K-3 retry sweeper picks it up later.</li>
 * </ul>
 *
 * <p>Implementations must be non-blocking and return the future immediately —
 * the dispatcher chains delete-on-success without spending its single worker
 * thread on HTTP I/O.
 */
public interface HttpIngestClient extends Closeable {

    /**
     * POST the envelope to the configured ingest endpoint.
     *
     * @return future that completes with the outcome; never completes
     *         exceptionally — network errors map to {@code RETRY}
     */
    CompletableFuture<HttpIngestResult> send(WorkerMetricBatch envelope);

    /** No-op default — JDK HttpClient does not require explicit close. */
    @Override
    default void close() { }
}
