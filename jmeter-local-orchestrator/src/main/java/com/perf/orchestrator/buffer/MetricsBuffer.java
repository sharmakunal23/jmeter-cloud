package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;

import java.io.Closeable;
import java.util.Optional;

/**
 * Write-ahead queue for {@link WorkerMetricBatch} envelopes.
 *
 * <p>The producer's poll thread calls {@link #enqueue} (sub-millisecond);
 * a separate dispatch thread (see {@code MetricsDispatcher}) reads the
 * oldest persisted envelope via {@link #peekOldest}, publishes it to Kafka,
 * and removes it via {@link #delete} on success.
 *
 * <p><b>Reliability contract:</b> once {@link #enqueue} returns a non-empty
 * {@link Optional}, the envelope survives a process crash. The disk-backed
 * impl achieves this via atomic {@code Files.move(ATOMIC_MOVE)} from a
 * temporary file to the final {@code .envelope.gz} name — no half-written
 * file is ever visible to {@link #peekOldest}.
 *
 * <p><b>JMeter-considerate sizing</b> — the disk-backed impl enforces
 * caps designed for a pod where JMeter is the priority disk tenant:
 * <ul>
 *   <li>Total buffer bytes capped (default 20 MB)</li>
 *   <li>Each envelope file capped (default 200 KB)</li>
 *   <li>Free disk reservation overrides the cap when the volume is tight</li>
 *   <li>TTL on individual envelopes (default 6 hours)</li>
 *   <li>Drop-oldest-first when the cap is hit (after TTL sweep)</li>
 * </ul>
 *
 * <p>{@link #enqueue} returns {@link Optional#empty()} when the envelope is
 * refused — counters tracked inside the impl tell the operator why (oversize,
 * low-disk, etc.). The producer treats this as a load-shedding signal.
 *
 * <p><b>Threading:</b> single-writer / single-reader is the production usage
 * pattern (poll thread → dispatch thread). The disk-backed impl additionally
 * supports concurrent reads of the size gauges from a Micrometer poll thread.
 */
public interface MetricsBuffer extends Closeable {

    /**
     * Persist {@code envelope} together with its destination {@code topic}. May
     * internally TTL-sweep stale envelopes, evict the oldest, or refuse the new
     * envelope outright (oversize / low free disk).
     *
     * <p>Topic is persisted alongside the envelope (sidecar metadata in the
     * disk-backed impl) so cross-restart replay routes to the same per-app
     * topic the envelope was originally bound for.
     *
     * @param envelope payload; must not be null
     * @param topic    destination Kafka topic; must not be null/blank
     * @return a {@link BufferedEnvelope} handle the caller passes to
     *         {@link #delete} on successful publish; {@link Optional#empty()}
     *         if refused
     */
    Optional<BufferedEnvelope> enqueue(WorkerMetricBatch envelope, String topic);

    /** Returns the oldest envelope currently in the buffer, or empty if none. */
    Optional<BufferedEnvelope> peekOldest();

    /**
     * Removes the envelope from the buffer. No-op if already deleted (idempotent
     * — the dispatcher may signal delete twice on a Kafka retry race).
     */
    void delete(BufferedEnvelope envelope);

    /**
     * Total bytes currently held in the buffer. For the disk-backed impl,
     * this is the sum of {@code .envelope.gz} file sizes on disk (gzipped).
     */
    long depthBytes();

    /** Number of envelopes currently in the buffer. */
    long depthEnvelopes();
}
