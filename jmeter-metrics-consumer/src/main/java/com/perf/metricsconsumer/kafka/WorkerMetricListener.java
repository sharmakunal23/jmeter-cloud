package com.perf.metricsconsumer.kafka;

import com.perf.metricsconsumer.health.ConsumerHeartbeat;
import com.perf.metricsconsumer.jdbc.WorkerMetricWriter;
import com.perf.metricsconsumer.util.RateLimitedLogger;
import com.perf.orchestrator.WorkerMetricBatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Batch listener for the per-app metrics topics matching the regex
 * {@code jmeter.metrics.<applicationName>}.
 *
 * <p>KAFKA-PER-APP Phase D (2026-05-15) — switched from a single-topic
 * subscription ({@code topics = "jmeter.metrics.perSecond"}) to a pattern
 * subscription ({@code topicPattern = "jmeter\\.metrics\\.[^.]+$"}). Kafka
 * rediscovers topics matching the pattern every {@code metadata.max.age.ms}
 * (set to 30 s in this consumer — see {@link KafkaConsumerConfig}) so a
 * newly-registered application's topic is consumed within ~30 s without a
 * consumer restart. The DLT topics ({@code jmeter.metrics.<app>.DLT}) are
 * NOT matched by this pattern (the {@code [^.]+} clamp excludes the extra
 * {@code .DLT} dot-segment); they're consumed by a separate listener — see
 * {@link DltSkeletonListener}.
 *
 * <p>Each poll delivers up to {@code metricsConsumer.maxPollRecords} decoded
 * {@link WorkerMetricBatch} envelopes; the listener hands the whole batch to
 * {@link WorkerMetricWriter}, which explodes envelopes into per-row INSERTs
 * with ON CONFLICT DO NOTHING. A throw routes the batch through the DLQ
 * pipeline configured in {@link KafkaConsumerConfig}.
 *
 * <h2>K-2 envelope shape</h2>
 * The listener consumes envelopes (one per pod-window) instead of per-row
 * records. Each envelope carries N entries (typically 1-500), so a batch of
 * E envelopes yields up to {@code E × MAX_ENTRIES_PER_ENVELOPE} rows post-
 * explode. The writer chunks the resulting INSERT into manageable statements
 * (default 5000 rows per statement) so a single oversized poll can't blow
 * Postgres parse time.
 *
 * <h2>Counter semantics</h2>
 * The {@code metricsConsumer.records.*} counters track <em>row</em>-grain
 * outcomes (post-explode), not envelope-grain — so dashboards keep showing
 * the same per-row throughput numbers as the pre-K-2 era.
 *
 * <h2>Per-topic lag visibility</h2>
 * Spring Kafka registers {@code kafka.consumer.records-lag-max} per
 * {@code (topic, partition)} via the Micrometer binder, so the kafka-exporter
 * + Grafana per-app dashboard (Phase F) can template a chart on
 * {@code topic=~"jmeter\\.metrics\\..+"}. No code change here — the metric
 * naming is automatic.
 */
@Component
public class WorkerMetricListener {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricListener.class);

    /**
     * Rate-limited WARN sink for batch-failure cascades. A persistently
     * unreachable Postgres or a poison message can spam this path
     * thousands of times per second; rate-limit to one line per second
     * per error key so the log buffer can't pin heap.
     */
    private static final RateLimitedLogger RL_LOG =
            new RateLimitedLogger(LOG, /* minIntervalMs */ 1000L);

    private final WorkerMetricWriter writer;
    private final ConsumerHeartbeat heartbeat;
    private final Counter consumed;
    private final Counter inserted;
    private final Counter duplicates;
    private final Counter failures;
    private final Counter envelopesConsumed;
    private final Timer batchTimer;

    public WorkerMetricListener(WorkerMetricWriter writer,
                                ConsumerHeartbeat heartbeat,
                                MeterRegistry meterRegistry) {
        this.writer = writer;
        this.heartbeat = heartbeat;
        this.consumed = Counter.builder("metricsConsumer.records.consumed")
                .description("WorkerMetric rows consumed from Kafka (post-explode).")
                .register(meterRegistry);
        this.inserted = Counter.builder("metricsConsumer.records.inserted")
                .description("WorkerMetric rows inserted into Postgres.")
                .register(meterRegistry);
        this.duplicates = Counter.builder("metricsConsumer.records.duplicates")
                .description("WorkerMetric rows collapsed by ON CONFLICT DO NOTHING.")
                .register(meterRegistry);
        this.failures = Counter.builder("metricsConsumer.records.failures")
                .description("Envelope batches that failed to insert (will be retried / DLT'd).")
                .register(meterRegistry);
        this.envelopesConsumed = Counter.builder("metricsConsumer.envelopes.consumed")
                .description("WorkerMetricBatch envelopes received from Kafka (K-2).")
                .register(meterRegistry);
        this.batchTimer = Timer.builder("metricsConsumer.batch.duration")
                .description("Wall-time per batch (Kafka receive → Postgres committed).")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @KafkaListener(
            topicPattern = "${metricsConsumer.topicPattern:jmeter\\.metrics\\.[^.]+$}",
            groupId = "${metricsConsumer.groupId:jmeter-metrics-consumer}",
            concurrency = "${metricsConsumer.concurrency:4}",
            containerFactory = "workerMetricContainerFactory")
    public void onWorkerMetricBatch(List<WorkerMetricBatch> envelopes) {
        if (envelopes.isEmpty()) {
            return;
        }
        long startNs = System.nanoTime();
        int totalRows = 0;
        for (WorkerMetricBatch env : envelopes) {
            totalRows += env.getEntries().size();
        }
        try {
            int written = writer.writeBatch(envelopes);
            envelopesConsumed.increment(envelopes.size());
            consumed.increment(totalRows);
            inserted.increment(written);
            duplicates.increment(totalRows - written);
            // Improvement #4 (2026-05-16) — mark batch processed so the
            // heartbeat scheduler + health indicator can surface
            // "consumer alive". Only on success: a poison batch should
            // NOT count as progress (it'll get retried + DLT'd via the
            // error handler).
            heartbeat.markBatchProcessed(written);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Inserted {}/{} rows from {} envelopes ({} duplicates dropped)",
                        written, totalRows, envelopes.size(), totalRows - written);
            }
        } catch (RuntimeException e) {
            failures.increment();
            RL_LOG.warn("BATCH_INSERT_FAIL",
                    "Batch of {} envelopes ({} rows) failed; retrying via DLT pipeline. cause={}",
                    envelopes.size(), totalRows, e.toString());
            throw e;
        } finally {
            batchTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }
}
