package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.kafka.MetricPublisher;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Test fake — calls {@link MetricPublisher#publishAll(List)} synchronously
 * on every {@link #offer}. No buffer, no background thread, no async pipeline.
 * Use in wire-up tests that want to assert on a recording publisher's snapshot
 * immediately after the state machine runs.
 */
public final class SynchronousMetricsDispatcher implements MetricsDispatcher {

    private final MetricPublisher publisher;

    public SynchronousMetricsDispatcher(MetricPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean offer(WorkerMetricBatch envelope, String topic) {
        publisher.publishAll(List.of(envelope), topic);
        return true;
    }

    @Override
    public int offerAll(Collection<WorkerMetricBatch> envelopes, String topic) {
        publisher.publishAll(List.copyOf(envelopes), topic);
        return envelopes.size();
    }

    @Override
    public int queueDepth() {
        return 0;
    }

    @Override
    public boolean awaitQueueDrain(Duration timeout) {
        return true;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
