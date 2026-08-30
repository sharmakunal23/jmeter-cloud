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
 *   <li>{@link HttpIngestResult.Outcome#AUTH_REJECT} — leave on disk and
 *       pause posting; the token needs fixing, not the data.</li>
 *   <li>{@link HttpIngestResult.Outcome#RETRY} — leave on disk so the
 *       dispatcher's retry sweeper picks it up later.</li>
 * </ul>
 *
 * <p>Implementations must be non-blocking and return the future immediately —
 * the dispatcher chains delete-on-success without spending its single worker
 * thread on HTTP I/O.
 */
public interface HttpIngestClient extends Closeable {

    /** POST to the configured URL with no group parameter. */
    default CompletableFuture<HttpIngestResult> send(WorkerMetricBatch envelope) {
        return send(envelope, null);
    }

    /**
     * POST the envelope to the ingest endpoint for {@code groupId} — the run's
     * application group, sent as {@code ?groupId=}; {@code null} posts to the
     * configured URL unchanged.
     *
     * @return future that completes with the outcome; never completes
     *         exceptionally — network errors map to {@code RETRY}
     */
    CompletableFuture<HttpIngestResult> send(WorkerMetricBatch envelope, String groupId);

    /** No-op default — JDK HttpClient does not require explicit close. */
    @Override
    default void close() { }
}
