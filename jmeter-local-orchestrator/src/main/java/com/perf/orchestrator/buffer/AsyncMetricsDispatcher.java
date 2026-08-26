package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.observability.WarningThrottle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single background thread plus a bounded queue, so the aggregator's poll thread
 * never blocks on disk or network: {@link #offer(WorkerMetricBatch)} is a
 * sub-microsecond CAS and the dispatch thread does the gzip, the atomic rename
 * and the POST.
 *
 * <p><b>It always persists to the buffer before publishing</b>, which is what
 * makes envelopes survive a consumer outage or a process crash — a failed
 * publish simply leaves the file on disk for the retry sweeper.
 *
 * <p>Response handling maps directly onto the consumer's contract: {@code 202}
 * deletes from the buffer; {@code 400}/{@code 413} also delete but WARN and bump
 * {@link #failedCount()}, since keeping a malformed envelope would waste space
 * forever; anything else — 5xx, network, timeout — leaves it on disk to retry.
 *
 * <p>The loop retries the oldest stale envelope on every iteration, not only
 * when idle, so a backlog drains even under steady ingest.
 */
public final class AsyncMetricsDispatcher implements MetricsDispatcher {

    private static final Logger LOG = Logger.getLogger(AsyncMetricsDispatcher.class.getName());

    /** In-memory queue capacity in envelopes. ~25 KB each → 256 ≈ 6.5 MB worst-case backlog. */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    /** Default poll timeout — controls retry cadence under idle (no-traffic) conditions. */
    public static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(500);

    /** A buffered envelope older than this is eligible for republish on the retry path. */
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);

    private final MetricsBuffer buffer;
    private final HttpIngestClient ingestClient;
    private final Clock clock;
    private final ArrayBlockingQueue<WorkerMetricBatch> queue;
    private final Duration retryInterval;
    private final Duration retryAfter;
    private final Thread workerThread;
    private volatile boolean shutdown = false;

    /**
     * SLIMDOWN D-4: a backpressure drop is silent data loss and the WARN is
     * its only signal. Throttled (burst 5 / 60 s window + suppressed-count
     * summary) because a wedged dispatch thread would otherwise emit one
     * line per second for the whole outage. Single caller — the
     * aggregator's poll thread — matching WarningThrottle's contract.
     */
    private final WarningThrottle backpressureWarnings = new WarningThrottle();

    /** Envelopes accepted (202) by the consumer — exposed via {@link #publishedCount()}. */
    private final LongAdder published = new LongAdder();

    /** Envelopes terminally rejected (400/413) — exposed via {@link #failedCount()}. */
    private final LongAdder failed = new LongAdder();

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  HttpIngestClient ingestClient) {
        this(buffer, ingestClient, Clock.systemUTC(),
                DEFAULT_QUEUE_CAPACITY, DEFAULT_RETRY_INTERVAL, DEFAULT_RETRY_AFTER);
    }

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  HttpIngestClient ingestClient,
                                  Clock clock,
                                  int queueCapacity,
                                  Duration retryInterval,
                                  Duration retryAfter) {
        this.buffer = Objects.requireNonNull(buffer, "buffer cannot be null");
        this.ingestClient = Objects.requireNonNull(ingestClient, "ingestClient cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got: " + queueCapacity);
        }
        if (retryInterval == null || retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("retryInterval must be a positive duration");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("retryAfter must be a positive duration");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.retryInterval = retryInterval;
        this.retryAfter = retryAfter;

        this.workerThread = new Thread(this::runLoop, "metrics-dispatcher");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    @Override
    public boolean offer(WorkerMetricBatch envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        boolean accepted = queue.offer(envelope);
        if (!accepted) {
            backpressureWarnings.record(
                    () -> LOG.warning(() -> String.format(
                            "Dispatch queue full (%d) — envelope for runId=%s windowSecond=%d DROPPED (backpressure; data lost)",
                            queue.size(), envelope.runId(), envelope.windowSecond())),
                    suppressed -> LOG.warning(() -> String.format(
                            "Dispatch queue backpressure: %d further envelope drops suppressed in the last minute",
                            suppressed)));
        }
        return accepted;
    }

    @Override
    public int offerAll(Collection<WorkerMetricBatch> envelopes) {
        Objects.requireNonNull(envelopes, "envelopes cannot be null");
        int accepted = 0;
        for (WorkerMetricBatch env : envelopes) {
            if (!offer(env)) {
                break;
            }
            accepted++;
        }
        return accepted;
    }

    @Override
    public int queueDepth() {
        return queue.size();
    }

    @Override
    public boolean awaitQueueDrain(Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!queue.isEmpty()) {
            if (System.nanoTime() > deadlineNanos) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    @Override
    public long publishedCount() {
        return published.sum();
    }

    @Override
    public long failedCount() {
        return failed.sum();
    }

    private void runLoop() {
        LOG.info(() -> String.format(
                "AsyncMetricsDispatcher started — queueCapacity=%d retryInterval=%s retryAfter=%s",
                queue.remainingCapacity(), retryInterval, retryAfter));
        while (!shutdown) {
            try {
                WorkerMetricBatch incoming = queue.poll(retryInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (incoming != null) {
                    handleNew(incoming);
                }
                retryOldestStale();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.fine("AsyncMetricsDispatcher interrupted — shutting down");
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "AsyncMetricsDispatcher: unexpected error in loop", e);
            }
        }
        LOG.info("AsyncMetricsDispatcher stopped");
    }

    private void handleNew(WorkerMetricBatch envelope) {
        Optional<BufferedEnvelope> handle = buffer.enqueue(envelope);
        if (handle.isEmpty()) {
            return;
        }
        publishAsync(handle.get());
    }

    private void retryOldestStale() {
        Optional<BufferedEnvelope> opt = buffer.peekOldest();
        if (opt.isEmpty()) return;
        BufferedEnvelope env = opt.get();
        Instant cutoff = clock.instant().minus(retryAfter);
        if (env.enqueuedAt().isAfter(cutoff)) return;
        publishAsync(env);
    }

    private void publishAsync(BufferedEnvelope handle) {
        try {
            ingestClient.send(handle.envelope())
                    .whenComplete((result, ex) -> {
                        if (ex != null || result == null) {
                            // Defensive — JdkHttpIngestClient never throws via the future,
                            // but if a custom impl does, treat as RETRY.
                            if (LOG.isLoggable(Level.FINE)) {
                                LOG.fine(() -> String.format(
                                        "Ingest send threw for envelope %s — leaving on disk",
                                        handle.id()));
                            }
                            return;
                        }
                        switch (result.outcome()) {
                            case ACCEPTED -> {
                                buffer.delete(handle);
                                published.increment();
                            }
                            case TERMINAL_REJECT -> {
                                buffer.delete(handle);
                                failed.increment();
                                LOG.warning(() -> String.format(
                                        "Ingest rejected envelope %s as malformed (status=%d, %s) — DELETED from buffer (data lost)",
                                        handle.id(), result.statusCode(), result.detail()));
                            }
                            case RETRY -> {
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(() -> String.format(
                                            "Ingest returned retry status (status=%d, %s) for envelope %s — staying on disk",
                                            result.statusCode(), result.detail(), handle.id()));
                                }
                            }
                        }
                    });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "AsyncMetricsDispatcher: synchronous ingest failure", e);
        }
    }

    @Override
    public void close() {
        shutdown = true;
        workerThread.interrupt();
        try {
            workerThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
