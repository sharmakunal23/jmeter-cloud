package com.perf.orchestrator.kafka;

import com.perf.orchestrator.WorkerMetricBatch;

import java.io.Closeable;
import java.util.List;

/**
 * Contract for publishing per-second metric envelopes out of the orchestrator.
 *
 * <p>Implemented by {@link KafkaMetricPublisher} in production. The interface
 * exists solely for testability — {@link com.perf.orchestrator.statemachine.TailerStateMachine}
 * depends on this type rather than the concrete Kafka class, so integration
 * tests can substitute a collecting implementation without a running Kafka broker.
 *
 * <h2>Envelope shape (K-1)</h2>
 * Each {@link WorkerMetricBatch} carries a pod-window's worth of per-label entries.
 * The interface deals exclusively in envelopes; the per-row {@code WorkerMetricDto}
 * intermediate type was removed during K-1 (2026-05-11) as part
 * of the hard cutover to envelope-per-window publishing.
 *
 * <h2>Per-run vs. per-process lifecycle</h2>
 * The production publisher is a per-process singleton — one
 * {@code KafkaProducer} (warm TCP connections + batch buffers) is shared across
 * every test run. The state machine calls {@link #flush()} at the end of each
 * run to guarantee all envelopes from that run reach the broker before the run
 * is marked COMPLETED, but does <em>not</em> call {@link #close()}. The publisher
 * is closed only on JVM shutdown, by the orchestrator's shutdown hook.
 */
public interface MetricPublisher extends Closeable {

    /**
     * Publishes all envelopes in the list to {@code topic}. Implementations must
     * be non-blocking; delivery failures are tracked internally and surfaced via
     * {@link #getFailedCount()}.
     *
     * <p>Topic is per-call because per-application Kafka routing requires the
     * same producer to write into different topics across consecutive runs. The producer
     * stays warm; only the destination changes.
     *
     * @param envelopes list of envelopes to publish; must not be null; empty list is a no-op
     * @param topic     destination Kafka topic; must not be null/blank
     */
    void publishAll(List<WorkerMetricBatch> envelopes, String topic);

    /**
     * Blocks until every record sent before this call has been delivered to the
     * broker (or definitively failed). Called at the end of each test run by
     * {@code TailerStateMachine} to guarantee no envelopes are lost across the
     * COMPLETED transition. Implementations that don't buffer (test fakes) may
     * leave this as the default no-op.
     */
    default void flush() {}

    /** Returns the total number of envelopes successfully acknowledged. */
    long getPublishedCount();

    /** Returns the total number of envelopes that failed after all retries. */
    long getFailedCount();
}
