package com.perf.metricsconsumer.kafka;

import com.perf.metricsconsumer.util.RateLimitedLogger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KAFKA-PER-APP Phase D — observability skeleton for the per-app dead-letter
 * topics ({@code jmeter.metrics.<applicationName>.DLT}). Subscribes via the
 * pattern {@code jmeter.metrics.<anything>.DLT} so any new app's DLT is
 * picked up within {@code metadata.max.age.ms} (30 s) of broker creation.
 *
 * <p>What it does:
 * <ul>
 *   <li>Counts every received DLT envelope into a per-topic micrometer
 *       counter {@code metricsConsumer.dlt.received{topic="<topic>"}} so
 *       the operator can alert on "any DLT activity at all" or per-app
 *       "this app's DLT is filling up".</li>
 *   <li>Logs each batch at WARN with topic + partition + offset + size so
 *       a tail of the consumer's log shows what's failing in near-realtime.</li>
 * </ul>
 *
 * <p>What it does NOT do (deliberately deferred):
 * <ul>
 *   <li><b>Sidetable persistence.</b> A {@code metrics."dlqAudit"} table
 *       would let the operator browse historical failures via SQL or a
 *       UI tab. The Avro deserialise of the DLT envelope is the missing
 *       piece (DLT bodies are raw bytes — the original deserialise
 *       failure is what put them there).</li>
 *   <li><b>Per-app replay UX.</b> Today's {@code DlqReplayer} is keyed on
 *       a single topic; per-app replay needs a controller endpoint
 *       {@code POST /api/v1/applications/{id}/dlqReplay}. Same forward
 *       work entry.</li>
 * </ul>
 *
 * <p>Bytes-only deserializer here (DLT records may not be Avro-decodable
 * — the schema-registry deserializer's failure is what landed them in
 * the DLT). We just need the metadata, not the body shape.
 */
@Component
public class DltSkeletonListener {

    private static final Logger LOG = LoggerFactory.getLogger(DltSkeletonListener.class);

    /**
     * Rate-limited so a DLT storm (e.g. a single poisonous deploy that
     * dead-letters every batch) doesn't pin the consumer's heap via the
     * Logback async queue. One line per second per topic suffices for
     * operator awareness; the per-topic counter carries the true rate.
     */
    private static final RateLimitedLogger RL_LOG =
            new RateLimitedLogger(LOG, /* minIntervalMs */ 1000L);

    private final MeterRegistry meterRegistry;

    public DltSkeletonListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topicPattern = "${metricsConsumer.dltTopicPattern:jmeter\\.metrics\\..+\\.DLT$}",
            groupId = "${metricsConsumer.dltGroupId:jmeter-metrics-consumer-dlt}",
            concurrency = "1",
            containerFactory = "dltSkeletonContainerFactory")
    public void onDeadLetter(List<ConsumerRecord<String, byte[]>> records) {
        if (records.isEmpty()) return;
        // Per-topic counter increments unconditionally — kafka-exporter's
        // per-topic lag pairs with this for the Phase F per-app dashboard.
        // The COUNTER carries the true rate; the log line is rate-limited.
        for (ConsumerRecord<String, byte[]> rec : records) {
            Counter.builder("metricsConsumer.dlt.received")
                    .description("Per-app dead-letter envelopes received from Kafka.")
                    .tags(List.of(Tag.of("topic", rec.topic())))
                    .register(meterRegistry)
                    .increment();
        }
        // One batch-summary log line per topic per second — preserves the
        // diagnostic without per-record amplification (a 100-record batch
        // would otherwise emit 101 WARNs).
        records.stream().map(ConsumerRecord::topic).distinct().forEach(topic ->
                RL_LOG.warn("DLT_RECEIVED:" + topic,
                        "DLT envelopes received topic={} batchSize={}",
                        topic, records.size()));
    }
}
