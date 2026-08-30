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
 * Single-thread coordinator between the aggregator (producer) and the ingest
 * client + disk buffer. {@link #offer} is a sub-microsecond CAS onto a bounded
 * queue — the aggregator's poll thread never blocks on disk or HTTP. The
 * dispatch thread persists each envelope to the buffer (with the run's
 * application group, so a replay after a restart still routes correctly),
 * POSTs it, and applies the response:
 *
 * <ul>
 *   <li>ACCEPTED → delete from the buffer.</li>
 *   <li>TERMINAL_REJECT ({@code 400/413/415/405}) → delete, WARN, count in
 *       {@link #failedCount()} — an unchanged replay would fail the same way.</li>
 *   <li>AUTH_REJECT ({@code 401/403}) → keep on disk, ERROR, and stop posting
 *       for {@code authRetry} (default 30 s) — the token is wrong or rotated;
 *       hammering the consumer only fills its access log.</li>
 *   <li>RETRY ({@code 429}, {@code 5xx}, I/O) → keep on disk; the sweeper
 *       republishes the oldest envelope once it is {@code retryAfter} old.</li>
 * </ul>
 */
public final class AsyncMetricsDispatcher implements MetricsDispatcher {

    private static final Logger LOG = Logger.getLogger(AsyncMetricsDispatcher.class.getName());

    /** Default bounded queue capacity ({@code METRICS_INGEST_QUEUE_CAPACITY}). */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    /** Default poll timeout — the retry cadence when nothing new arrives ({@code METRICS_INGEST_RETRY_INTERVAL_MS}). */
    public static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(500);

    /** A buffered envelope older than this is eligible for republish ({@code METRICS_INGEST_RETRY_AFTER_MS}). */
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);

    /** How long posting pauses after a {@code 401/403} ({@code METRICS_INGEST_AUTH_RETRY_MS}). */
    public static final Duration DEFAULT_AUTH_RETRY = Duration.ofSeconds(30);

    /** An envelope waiting for the dispatch thread, with the group it routes to. */
    private record Pending(WorkerMetricBatch envelope, String groupId) { }

    private final MetricsBuffer buffer;
    private final HttpIngestClient ingestClient;
    private final Clock clock;
    private final ArrayBlockingQueue<Pending> queue;
    private final Duration retryInterval;
    private final Duration retryAfter;
    private final Duration authRetry;
    private final Thread workerThread;
    private volatile boolean shutdown = false;
    /** Instant until which posting is paused after an auth rejection; {@code null} when open. */
    private volatile Instant authBlockedUntil;

    /**
     * A backpressure drop is silent data loss and the WARN is its only signal;
     * throttled so a saturated queue cannot flood the log.
     */
    private final WarningThrottle backpressureWarnings = new WarningThrottle();
    private final WarningThrottle authWarnings = new WarningThrottle();

    private final LongAdder published = new LongAdder();
    /** Envelopes terminally rejected (400/413/415/405) — exposed via {@link #failedCount()}. */
    private final LongAdder failed = new LongAdder();
    private final LongAdder authRejected = new LongAdder();

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  HttpIngestClient ingestClient) {
        this(buffer, ingestClient, Clock.systemUTC(),
                DEFAULT_QUEUE_CAPACITY, DEFAULT_RETRY_INTERVAL, DEFAULT_RETRY_AFTER, DEFAULT_AUTH_RETRY);
    }

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  HttpIngestClient ingestClient,
                                  Clock clock,
                                  int queueCapacity,
                                  Duration retryInterval,
                                  Duration retryAfter) {
        this(buffer, ingestClient, clock, queueCapacity, retryInterval, retryAfter, DEFAULT_AUTH_RETRY);
    }

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  HttpIngestClient ingestClient,
                                  Clock clock,
                                  int queueCapacity,
                                  Duration retryInterval,
                                  Duration retryAfter,
                                  Duration authRetry) {
        this.buffer = Objects.requireNonNull(buffer, "buffer cannot be null");
        this.ingestClient = Objects.requireNonNull(ingestClient, "ingestClient cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got: " + queueCapacity);
        }
        requirePositive(retryInterval, "retryInterval");
        requirePositive(retryAfter, "retryAfter");
        requirePositive(authRetry, "authRetry");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.retryInterval = retryInterval;
        this.retryAfter = retryAfter;
        this.authRetry = authRetry;

        this.workerThread = new Thread(this::runLoop, "metrics-dispatcher");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }

    @Override
    public boolean offer(WorkerMetricBatch envelope) {
        return offer(envelope, null);
    }

    @Override
    public boolean offer(WorkerMetricBatch envelope, String groupId) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        boolean accepted = queue.offer(new Pending(envelope, groupId));
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
        return offerAll(envelopes, null);
    }

    @Override
    public int offerAll(Collection<WorkerMetricBatch> envelopes, String groupId) {
        Objects.requireNonNull(envelopes, "envelopes cannot be null");
        int accepted = 0;
        for (WorkerMetricBatch env : envelopes) {
            if (!offer(env, groupId)) {
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

    /** Envelopes the consumer answered {@code 401/403} for — still on disk, waiting for a valid token. */
    public long authRejectedCount() {
        return authRejected.sum();
    }

    private boolean authBlocked() {
        Instant until = authBlockedUntil;
        return until != null && clock.instant().isBefore(until);
    }

    private void runLoop() {
        LOG.info(() -> String.format(
                "AsyncMetricsDispatcher started — queueCapacity=%d retryInterval=%s retryAfter=%s authRetry=%s",
                queue.remainingCapacity(), retryInterval, retryAfter, authRetry));
        while (!shutdown) {
            try {
                Pending incoming = queue.poll(retryInterval.toMillis(), TimeUnit.MILLISECONDS);
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

    private void handleNew(Pending pending) {
        Optional<BufferedEnvelope> handle = buffer.enqueue(pending.envelope(), pending.groupId());
        if (handle.isEmpty() || authBlocked()) {
            return;   // buffered; the sweeper posts it once the auth pause ends
        }
        publishAsync(handle.get());
    }

    private void retryOldestStale() {
        if (authBlocked()) return;
        Optional<BufferedEnvelope> opt = buffer.peekOldest();
        if (opt.isEmpty()) return;
        BufferedEnvelope env = opt.get();
        Instant cutoff = clock.instant().minus(retryAfter);
        if (env.enqueuedAt().isAfter(cutoff)) return;
        publishAsync(env);
    }

    private void publishAsync(BufferedEnvelope handle) {
        try {
            ingestClient.send(handle.envelope(), handle.groupId())
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
                            case AUTH_REJECT -> {
                                authRejected.increment();
                                authBlockedUntil = clock.instant().plus(authRetry);
                                authWarnings.record(
                                        () -> LOG.severe(() -> String.format(
                                                "Ingest refused envelope %s (status=%d, %s) — METRICS_INGEST_AUTH is missing or rotated; "
                                                + "envelopes stay buffered, posting pauses for %s",
                                                handle.id(), result.statusCode(), result.detail(), authRetry)),
                                        suppressed -> LOG.severe(() -> String.format(
                                                "Ingest auth still refused — %d further rejections suppressed in the last minute",
                                                suppressed)));
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
        // Whatever the worker thread never took still belongs on disk — the
        // buffer is the durability boundary, the queue is not.
        java.util.List<Pending> left = new java.util.ArrayList<>();
        queue.drainTo(left);
        for (Pending p : left) {
            try {
                buffer.enqueue(p.envelope(), p.groupId());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "close(): could not persist queued envelope", e);
            }
        }
        if (!left.isEmpty()) {
            LOG.info(() -> "close(): persisted " + left.size() + " queued envelope(s) to the disk buffer");
        }
    }
}
