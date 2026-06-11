package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.kafka.MetricPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link MetricPublisher} for the orchestrator-package pipeline tests.
 * Collects {@link WorkerMetricBatch} envelopes (K-1 shape) so test assertions can
 * inspect what the state machine would have sent to Kafka, without any broker
 * dependency.
 *
 * <p>An identical recording fake exists in the {@code statemachine} test
 * package; duplicated here on purpose so the migration's zero-diff rule on
 * existing hot-path test files is preserved.
 */
final class RecordingMetricPublisher implements MetricPublisher {

    private final CopyOnWriteArrayList<WorkerMetricBatch> received = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> topics = new CopyOnWriteArrayList<>();
    private long publishedCount = 0;

    @Override
    public void publishAll(List<WorkerMetricBatch> envelopes, String topic) {
        received.addAll(envelopes);
        for (int i = 0; i < envelopes.size(); i++) {
            topics.add(topic);
        }
        publishedCount += envelopes.size();
    }

    List<String> topicsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(topics));
    }

    @Override
    public long getPublishedCount() {
        return publishedCount;
    }

    @Override
    public long getFailedCount() {
        return 0;
    }

    @Override
    public void close() {
        // no-op
    }

    List<WorkerMetricBatch> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }
}
