package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link MetricsBuffer} for tests that need the dispatch/drain
 * coordination behavior without disk I/O. Same chronological ordering, same
 * idempotent {@link #delete}, no gzip / atomic-rename / boot-scrubber concerns.
 *
 * <p>{@link BufferedEnvelope#file()} is always {@code null}; {@code sizeBytes}
 * is a rough estimate from {@code envelope.entries().size()}.
 */
public final class InMemoryMetricsBuffer implements MetricsBuffer {

    private final ConcurrentSkipListMap<String, BufferedEnvelope> index = new ConcurrentSkipListMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong idCounter = new AtomicLong();
    private final Clock clock;

    public InMemoryMetricsBuffer() {
        this(Clock.systemUTC());
    }

    public InMemoryMetricsBuffer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<BufferedEnvelope> enqueue(WorkerMetricBatch envelope, String groupId) {
        long size = estimateSize(envelope);
        Instant now = clock.instant();
        String id = String.format("%013d-%06d", now.toEpochMilli(), idCounter.incrementAndGet());
        BufferedEnvelope handle = new BufferedEnvelope(id, null, size, now, envelope, groupId);
        index.put(id, handle);
        totalBytes.addAndGet(size);
        return Optional.of(handle);
    }

    @Override
    public Optional<BufferedEnvelope> peekOldest() {
        Map.Entry<String, BufferedEnvelope> first = index.firstEntry();
        return first == null ? Optional.empty() : Optional.of(first.getValue());
    }

    @Override
    public void delete(BufferedEnvelope envelope) {
        BufferedEnvelope removed = index.remove(envelope.id());
        if (removed != null) {
            totalBytes.addAndGet(-removed.sizeBytes());
        }
    }

    @Override
    public long depthBytes() {
        return totalBytes.get();
    }

    @Override
    public long depthEnvelopes() {
        return index.size();
    }

    @Override
    public void close() {
        index.clear();
        totalBytes.set(0);
    }

    /** Returns a snapshot of all currently buffered envelopes in chronological order. */
    public List<BufferedEnvelope> snapshot() {
        return new ArrayList<>(index.values());
    }

    /** Roughly proportional to envelope size — useful for tests asserting cap behavior. */
    private static long estimateSize(WorkerMetricBatch env) {
        return 100L + 110L * env.entries().size();
    }
}
