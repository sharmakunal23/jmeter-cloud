package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test fake — records every offered envelope synchronously. No buffer, no
 * background thread, no HTTP. Use in wire-up tests that want to assert on
 * the published envelopes immediately after the state machine runs.
 */
public final class SynchronousMetricsDispatcher implements MetricsDispatcher {

    /**
     * CopyOnWriteArrayList because offer() is called from the state machine
     * thread while test assertions read from the test thread.
     */
    private final CopyOnWriteArrayList<WorkerMetricBatch> received = new CopyOnWriteArrayList<>();

    @Override
    public boolean offer(WorkerMetricBatch envelope) {
        received.add(envelope);
        return true;
    }

    @Override
    public int offerAll(Collection<WorkerMetricBatch> envelopes) {
        received.addAll(envelopes);
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
    public long publishedCount() {
        return received.size();
    }

    @Override
    public long failedCount() {
        return 0;
    }

    @Override
    public void close() {
        // nothing to release
    }

    /** Returns an unmodifiable snapshot of all received envelopes. */
    public List<WorkerMetricBatch> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }
}
