package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collection;

/**
 * Coordinator between the aggregator (producer) and the buffer + ingest client.
 *
 * <p>Production wire uses {@link AsyncMetricsDispatcher} — single background
 * thread + bounded in-memory queue, persists to disk before publishing.
 * Tests can substitute a synchronous fake.
 *
 * <p>{@link #offer} is the producer-side hot path — sub-microsecond on
 * {@code AsyncMetricsDispatcher}, synchronous on test fakes.
 */
public interface MetricsDispatcher extends Closeable {

    /**
     * Offer an envelope for dispatch to the metrics-consumer. Returns
     * {@code false} if the dispatcher cannot accept it (queue full on the
     * async impl). Callers should not retry on the same thread on
     * {@code false} — increment a counter and move on.
     *
     * @param envelope payload; must not be null
     */
    boolean offer(WorkerMetricBatch envelope);

    /**
     * Convenience for offering many envelopes. Returns the count accepted;
     * stops on the first refusal so callers know how many landed.
     */
    int offerAll(Collection<WorkerMetricBatch> envelopes);

    /** Returns the current depth of the in-memory queue, in envelopes. */
    int queueDepth();

    /**
     * Block until the in-memory queue is empty (or the timeout elapses).
     * Returns true if drained within the timeout.
     */
    boolean awaitQueueDrain(Duration timeout) throws InterruptedException;

    /**
     * Envelopes accepted (202) by the metrics-consumer since process start.
     * Process-lifetime cumulative — multiple test runs share the counter.
     */
    long publishedCount();

    /**
     * Envelopes terminally rejected (400/413) by the metrics-consumer since
     * process start — permanent data loss, surfaced for operator review.
     * Transient failures (5xx / network) are NOT counted here; those stay on
     * disk for the retry sweeper.
     */
    long failedCount();

    /** Stop the dispatcher and release resources. Idempotent. */
    @Override
    void close();
}
