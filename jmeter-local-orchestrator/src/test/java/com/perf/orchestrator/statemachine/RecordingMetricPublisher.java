package com.perf.orchestrator.statemachine;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.kafka.MetricPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link MetricPublisher} for use in integration tests.
 *
 * <p>Collects all published {@link WorkerMetricBatch} envelopes in a thread-safe
 * list so test assertions can inspect what the state machine would have sent
 * to Kafka, without any broker dependency.
 *
 * <p>Package-private — only needed by {@link TailerStateMachineTest}.
 */
final class RecordingMetricPublisher implements MetricPublisher {

    /**
     * CopyOnWriteArrayList is used because publishAll() is called from the
     * state machine thread while test assertions read from the test thread.
     */
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
        // no-op — nothing to flush
    }

    /** Returns an unmodifiable snapshot of all received envelopes. */
    List<WorkerMetricBatch> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }

    /** Returns the topic recorded for the envelope at {@code index}. */
    String topicAt(int index) {
        return topics.get(index);
    }
}
