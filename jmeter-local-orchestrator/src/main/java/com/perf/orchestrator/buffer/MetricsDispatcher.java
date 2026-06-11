package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collection;

/**
 * Coordinator between the aggregator (producer) and the buffer + publisher.
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
     * Offer an envelope for dispatch to {@code topic}. Returns {@code false}
     * if the dispatcher cannot accept it (queue full on the async impl).
     * Callers should not retry on the same thread on {@code false} —
     * increment a counter and move on.
     *
     * <p>Topic is per-call because per-application Kafka routing means
     * consecutive runs write to different topics while sharing the same
     * dispatcher singleton.
     *
     * @param envelope payload; must not be null
     * @param topic    destination Kafka topic; must not be null/blank
     */
    boolean offer(WorkerMetricBatch envelope, String topic);

    /**
     * Convenience for offering many envelopes to the same {@code topic}.
     * Returns the count accepted; stops on the first refusal so callers know
     * how many landed.
     */
    int offerAll(Collection<WorkerMetricBatch> envelopes, String topic);

    /** Returns the current depth of the in-memory queue, in envelopes. */
    int queueDepth();

    /**
     * Block until the in-memory queue is empty (or the timeout elapses).
     * Returns true if drained within the timeout.
     */
    boolean awaitQueueDrain(Duration timeout) throws InterruptedException;

    /** Stop the dispatcher and release resources. Idempotent. */
    @Override
    void close();
}
