package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.kafka.KafkaMetricPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async impl of {@link MetricsDispatcher} — single background thread + bounded
 * in-memory queue. Solves K-3's two non-negotiables:
 *
 * <ol>
 *   <li><b>Async constraint:</b> aggregator's poll thread must never block on
 *       disk I/O. {@link #offer(WorkerMetricBatch)} is a sub-microsecond CAS;
 *       the dispatch thread does the gzip + atomic-rename + publish.</li>
 *   <li><b>Reliability constraint:</b> envelopes survive Kafka outages and
 *       process crashes. The dispatch thread always persists to the buffer
 *       <em>before</em> publishing — failed publishes leave the file on disk
 *       for the retry sweeper to re-attempt.</li>
 * </ol>
 *
 * <h2>Loop semantics</h2>
 * <pre>
 *   loop:
 *     incoming = queue.poll(retryIntervalMs)        [blocks on empty queue]
 *     if (incoming != null) handleNew(incoming)     [persist + publish]
 *     retryOldestStale()                             [re-publish oldest if stale]
 * </pre>
 *
 * <p>Retry runs on every iteration so even under steady ingest a backlog
 * (Kafka momentarily down) drains naturally as the queue empties.
 *
 * <h2>Duplicate-publish safety</h2>
 * The retry path may double-publish an envelope already in flight from a
 * recent {@code handleNew}. Both paths are safe because:
 * <ul>
 *   <li>{@link KafkaMetricPublisher} uses idempotent producer config —
 *       broker-side dedup of producer retries.</li>
 *   <li>The consumer's INSERT is idempotent on
 *       {@code (runId, workerId, label, windowSecond)} via {@code ON CONFLICT
 *       DO NOTHING}.</li>
 *   <li>{@link MetricsBuffer#delete} is idempotent — double-deletes are no-ops.</li>
 * </ul>
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
    private final KafkaMetricPublisher publisher;
    private final HttpFallbackClient httpFallback;     // K-5 — nullable (null = no fallback)
    private final Clock clock;
    private final ArrayBlockingQueue<Pending> queue;
    private final Duration retryInterval;
    private final Duration retryAfter;
    private final Thread workerThread;
    private volatile boolean shutdown = false;

    /**
     * In-memory queue carrier — pairs the envelope with its destination
     * topic so the dispatch thread can route per-app instead of using a
     * boot-time default.
     */
    private record Pending(WorkerMetricBatch envelope, String topic) {}

    private final Counter cOffered;
    private final Counter cDropsForBackpressure;
    private final Counter cRetried;
    private final Counter cFallbackAccepted;
    private final Counter cFallbackTerminalReject;
    private final Counter cFallbackRetry;

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  KafkaMetricPublisher publisher,
                                  MeterRegistry meterRegistry) {
        this(buffer, publisher, null, meterRegistry, Clock.systemUTC(),
                DEFAULT_QUEUE_CAPACITY, DEFAULT_RETRY_INTERVAL, DEFAULT_RETRY_AFTER);
    }

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  KafkaMetricPublisher publisher,
                                  HttpFallbackClient httpFallback,
                                  MeterRegistry meterRegistry) {
        this(buffer, publisher, httpFallback, meterRegistry, Clock.systemUTC(),
                DEFAULT_QUEUE_CAPACITY, DEFAULT_RETRY_INTERVAL, DEFAULT_RETRY_AFTER);
    }

    public AsyncMetricsDispatcher(MetricsBuffer buffer,
                                  KafkaMetricPublisher publisher,
                                  HttpFallbackClient httpFallback,
                                  MeterRegistry meterRegistry,
                                  Clock clock,
                                  int queueCapacity,
                                  Duration retryInterval,
                                  Duration retryAfter) {
        this.buffer = Objects.requireNonNull(buffer, "buffer cannot be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher cannot be null");
        this.httpFallback = httpFallback; // nullable — null = no fallback (K-5 disabled)
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");
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

        this.cOffered = Counter.builder("metricsDispatch.offered")
                .description("Envelopes accepted onto the in-memory dispatch queue.")
                .register(meterRegistry);
        this.cDropsForBackpressure = Counter.builder("metricsDispatch.dropsForBackpressure")
                .description("Envelopes dropped because the in-memory dispatch queue was full — load-shedding signal.")
                .register(meterRegistry);
        this.cRetried = Counter.builder("metricsDispatch.retried")
                .description("Buffered envelopes re-published by the retry sweeper after a prior failure / Kafka outage.")
                .register(meterRegistry);
        this.cFallbackAccepted = Counter.builder("httpFallback.accepted")
                .description("Envelopes successfully accepted by the K-4 HTTP fallback after Kafka send failed.")
                .register(meterRegistry);
        this.cFallbackTerminalReject = Counter.builder("httpFallback.terminalRejects")
                .description("Envelopes the consumer rejected as malformed (HTTP 400/413). Data is lost; investigate.")
                .register(meterRegistry);
        this.cFallbackRetry = Counter.builder("httpFallback.retry")
                .description("HTTP fallback returned a retryable status (5xx, network error). Envelope stays on disk.")
                .register(meterRegistry);
        Gauge.builder("metricsDispatch.queue.depth", queue, q -> (double) q.size())
                .description("In-memory queue depth — should be near-zero under healthy operation.")
                .register(meterRegistry);

        this.workerThread = new Thread(this::runLoop, "metrics-dispatcher");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    @Override
    public boolean offer(WorkerMetricBatch envelope, String topic) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must be non-blank");
        }
        boolean accepted = queue.offer(new Pending(envelope, topic));
        if (accepted) {
            cOffered.increment();
        } else {
            cDropsForBackpressure.increment();
        }
        return accepted;
    }

    @Override
    public int offerAll(Collection<WorkerMetricBatch> envelopes, String topic) {
        Objects.requireNonNull(envelopes, "envelopes cannot be null");
        int accepted = 0;
        for (WorkerMetricBatch env : envelopes) {
            if (!offer(env, topic)) {
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

    private void runLoop() {
        LOG.info(() -> String.format(
                "AsyncMetricsDispatcher started — queueCapacity=%d retryInterval=%s retryAfter=%s",
                queue.remainingCapacity(), retryInterval, retryAfter));
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
        Optional<BufferedEnvelope> handle = buffer.enqueue(pending.envelope(), pending.topic());
        if (handle.isEmpty()) {
            return;
        }
        publishAsync(handle.get(), false);
    }

    private void retryOldestStale() {
        Optional<BufferedEnvelope> opt = buffer.peekOldest();
        if (opt.isEmpty()) return;
        BufferedEnvelope env = opt.get();
        Instant cutoff = clock.instant().minus(retryAfter);
        if (env.enqueuedAt().isAfter(cutoff)) return;
        if (env.topic() == null) {
            // Carryover from a pre-Phase-G build that didn't persist topic —
            // drop rather than guess. Logged once per stale entry it surfaces.
            LOG.warning(() -> String.format(
                    "Retry sweep: envelope %s has no topic sidecar (pre-Phase-G carryover) — dropping",
                    env.id()));
            buffer.delete(env);
            return;
        }
        cRetried.increment();
        publishAsync(env, true);
    }

    private void publishAsync(BufferedEnvelope handle, boolean isRetry) {
        if (handle.topic() == null) {
            // Defense-in-depth — handleNew always sets topic; only the retry
            // path can encounter a null topic and that path drops first.
            LOG.warning(() -> String.format(
                    "publishAsync: envelope %s missing topic — dropping", handle.id()));
            buffer.delete(handle);
            return;
        }
        try {
            publisher.publishBatchAsync(handle.envelope(), handle.topic())
                    .whenComplete((sr, kafkaEx) -> {
                        if (kafkaEx == null) {
                            buffer.delete(handle);
                            return;
                        }
                        // Kafka failed. Try HTTP fallback per envelope (K-5).
                        // If no fallback configured, leave on disk for K-3 retry.
                        if (httpFallback == null) {
                            if (LOG.isLoggable(Level.FINE)) {
                                LOG.fine(() -> String.format(
                                        "Kafka publish failed (isRetry=%s) for envelope %s; no HTTP fallback — leaving on disk: %s",
                                        isRetry, handle.id(), kafkaEx.getMessage()));
                            }
                            return;
                        }
                        attemptHttpFallback(handle, kafkaEx);
                    });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "AsyncMetricsDispatcher: synchronous publish failure", e);
        }
    }

    /**
     * K-5 — invoked when {@link KafkaMetricPublisher#publishBatchAsync} failed.
     * Maps the consumer's response codes onto buffer transitions:
     * <ul>
     *   <li>202 (ACCEPTED) → delete from buffer.</li>
     *   <li>400/413 (TERMINAL_REJECT) → delete from buffer + bump rejection
     *       counter. Keeping malformed envelopes on disk would just waste
     *       space; the counter surfaces the loss for operator review.</li>
     *   <li>5xx / network / timeout (RETRY) → leave on disk for K-3 sweeper.</li>
     * </ul>
     */
    private void attemptHttpFallback(BufferedEnvelope handle, Throwable kafkaEx) {
        try {
            httpFallback.send(handle.envelope())
                    .whenComplete((result, fallbackEx) -> {
                        if (fallbackEx != null || result == null) {
                            // Defensive — JdkHttpFallbackClient never throws via the future,
                            // but if a custom impl does, treat as RETRY.
                            cFallbackRetry.increment();
                            if (LOG.isLoggable(Level.FINE)) {
                                LOG.fine(() -> String.format(
                                        "HTTP fallback threw for envelope %s — leaving on disk",
                                        handle.id()));
                            }
                            return;
                        }
                        switch (result.outcome()) {
                            case ACCEPTED -> {
                                buffer.delete(handle);
                                cFallbackAccepted.increment();
                            }
                            case TERMINAL_REJECT -> {
                                buffer.delete(handle);
                                cFallbackTerminalReject.increment();
                                LOG.warning(() -> String.format(
                                        "HTTP fallback rejected envelope %s as malformed (status=%d, %s) — DELETED from buffer (data lost)",
                                        handle.id(), result.statusCode(), result.detail()));
                            }
                            case RETRY -> {
                                cFallbackRetry.increment();
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(() -> String.format(
                                            "HTTP fallback returned retry status (status=%d, %s) for envelope %s — staying on disk",
                                            result.statusCode(), result.detail(), handle.id()));
                                }
                            }
                        }
                    });
        } catch (Exception e) {
            cFallbackRetry.increment();
            LOG.log(Level.WARNING, "AsyncMetricsDispatcher: synchronous HTTP fallback failure", e);
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
