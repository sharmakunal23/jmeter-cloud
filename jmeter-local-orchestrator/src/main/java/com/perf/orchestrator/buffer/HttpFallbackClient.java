package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP fallback for envelope publishing — used by {@link AsyncMetricsDispatcher}
 * when a Kafka send fails. Posts the Avro-binary `WorkerMetricBatch` to the
 * metrics-consumer's K-4 `/api/v1/ingest` endpoint.
 *
 * <p>The contract maps the K-4 response codes to a {@link HttpFallbackResult.Outcome}
 * the dispatcher uses to decide buffer-state transitions:
 *
 * <ul>
 *   <li>{@link HttpFallbackResult.Outcome#ACCEPTED} — buffer.delete(envelope).</li>
 *   <li>{@link HttpFallbackResult.Outcome#TERMINAL_REJECT} — buffer.delete(envelope)
 *       (the consumer says the payload is malformed; retrying won't help, and
 *       leaving it on disk wastes space). Counter {@code httpFallback.terminalRejects}
 *       surfaces the loss for operator review.</li>
 *   <li>{@link HttpFallbackResult.Outcome#RETRY} — leave on disk so the
 *       dispatcher's K-3 retry sweeper picks it up later.</li>
 * </ul>
 *
 * <p>Implementations must be non-blocking and return the future immediately —
 * the dispatcher chains delete-on-success without spending its single worker
 * thread on HTTP I/O.
 */
public interface HttpFallbackClient extends Closeable {

    /**
     * POST the envelope to the configured fallback endpoint.
     *
     * @return future that completes with the outcome; never completes
     *         exceptionally — network errors map to {@code RETRY}
     */
    CompletableFuture<HttpFallbackResult> send(WorkerMetricBatch envelope);

    /** No-op default — JDK HttpClient does not require explicit close. */
    @Override
    default void close() { }
}
