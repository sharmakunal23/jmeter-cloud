package com.perf.metricsconsumer.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the wall-clock timestamp of the last successfully processed
 * Kafka batch + the rolling row count. Three consumers:
 *
 * <ol>
 *   <li>Periodic INFO log (every 30 s) — operator can see at a glance
 *       from the container logs whether the consumer is making progress
 *       or wedged. Missing log lines = wedged.</li>
 *   <li>Micrometer gauge {@code metricsConsumer.lastBatchAgeSeconds} —
 *       Grafana / alertmanager hook for "consumer hasn't processed a
 *       batch in N minutes".</li>
 *   <li>{@link ConsumerHeartbeatHealthIndicator} — Spring Boot health
 *       contributor that flips DOWN when the age exceeds
 *       {@code metricsConsumer.heartbeat.staleAfterSeconds} (default
 *       300 s = 5 min). Compose's healthcheck flips the container
 *       UNHEALTHY, which can drive an automated restart in cloud
 *       deployments.</li>
 * </ol>
 *
 * <p>Why this matters: the 2026-05-15 incident showed up as "no metrics
 * in the UI" with no obvious logs — the consumer had been killed but the
 * dashboard just slowly stopped updating. A heartbeat would have flipped
 * the health endpoint to DOWN within minutes; restart automation would
 * have brought it back. Operator's eyes-on-glass detection time drops
 * from "next time someone looks at the UI" to "next health-check tick".
 *
 * <p>Boot-time semantics: {@code lastBatchAtMillis} is initialised to the
 * boot timestamp, NOT zero. A fresh boot doesn't trigger DOWN until
 * {@code staleAfterSeconds} have actually elapsed without a batch.
 */
@Component
public class ConsumerHeartbeat {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerHeartbeat.class);

    private final MeterRegistry meterRegistry;
    private final AtomicLong lastBatchAtMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong totalRowsProcessed = new AtomicLong(0);
    private final AtomicLong totalBatchesProcessed = new AtomicLong(0);

    public ConsumerHeartbeat(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("metricsConsumer.lastBatchAgeSeconds", lastBatchAtMillis,
                        ts -> (System.currentTimeMillis() - ts.get()) / 1000.0)
                .description("Wall-clock seconds since the last successfully-processed Kafka batch.")
                .register(meterRegistry);
        Gauge.builder("metricsConsumer.heartbeat.totalRowsProcessed", totalRowsProcessed,
                        AtomicLong::doubleValue)
                .description("Total rows processed since process start.")
                .register(meterRegistry);
        Gauge.builder("metricsConsumer.heartbeat.totalBatchesProcessed", totalBatchesProcessed,
                        AtomicLong::doubleValue)
                .description("Total Kafka batches processed since process start.")
                .register(meterRegistry);
    }

    /** Called by {@code WorkerMetricListener} after a successful writer commit. */
    public void markBatchProcessed(int rowsWritten) {
        lastBatchAtMillis.set(System.currentTimeMillis());
        totalRowsProcessed.addAndGet(Math.max(0, rowsWritten));
        totalBatchesProcessed.incrementAndGet();
    }

    /** Read by {@link ConsumerHeartbeatHealthIndicator}. */
    public Duration ageSinceLastBatch() {
        return Duration.ofMillis(System.currentTimeMillis() - lastBatchAtMillis.get());
    }

    public long totalRowsProcessed() {
        return totalRowsProcessed.get();
    }

    public long totalBatchesProcessed() {
        return totalBatchesProcessed.get();
    }
}
