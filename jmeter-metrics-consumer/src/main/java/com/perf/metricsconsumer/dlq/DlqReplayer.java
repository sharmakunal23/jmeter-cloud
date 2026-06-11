package com.perf.metricsconsumer.dlq;

import com.perf.metricsconsumer.jdbc.WorkerMetricWriter;
import com.perf.orchestrator.WorkerMetricBatch;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Drains the {@code jmeter.metrics.perSecond.DLT} topic and re-attempts the
 * INSERT for each record. Activated by setting {@code metricsConsumer.dlqReplay=true}.
 *
 * <p>Operating model:
 * <ol>
 *   <li>Consumer polls the DLT with the same Avro deserializer as the main
 *       listener. Records that originally failed because of a transient
 *       Postgres error decode cleanly here.</li>
 *   <li>Each polled record is forwarded to {@link WorkerMetricWriter}; success
 *       advances the offset, failure logs the record bytes and continues
 *       (we don't loop on the same poison pill).</li>
 *   <li>Tool exits when {@code emptyPollLimit} consecutive polls return zero
 *       records — the DLT is drained.</li>
 * </ol>
 *
 * <p>Records that cannot be decoded (e.g., a poisoned non-Avro byte payload)
 * are surfaced via the listener container's {@code DefaultErrorHandler}; in
 * this CLI we let them propagate, log, and skip — manual recovery from the
 * topic via {@code kcat} is the documented escape hatch in the README.
 *
 * <p>Run example:
 * <pre>
 * java -jar jmeter-metrics-consumer.jar \
 *      --spring.main.web-application-type=none \
 *      --metricsConsumer.dlqReplay=true
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "metricsConsumer.dlqReplay", havingValue = "true")
public class DlqReplayer implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DlqReplayer.class);

    private final WorkerMetricWriter writer;

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String bootstrapServers;

    @Value("${metricsConsumer.schemaRegistryUrl:http://schema-registry:8081}")
    private String schemaRegistryUrl;

    @Value("${metricsConsumer.dlqTopic:jmeter.metrics.perSecond.DLT}")
    private String dlqTopic;

    @Value("${metricsConsumer.dlqReplayGroupId:jmeter-metrics-consumer-dlqReplayer}")
    private String groupId;

    @Value("${metricsConsumer.dlqEmptyPollLimit:3}")
    private int emptyPollLimit;

    @Value("${metricsConsumer.dlqPollTimeoutMs:2000}")
    private long pollTimeoutMs;

    public DlqReplayer(WorkerMetricWriter writer) {
        this.writer = writer;
    }

    @Override
    public void run(String... args) {
        LOG.info("DLQ replayer starting — topic={} groupId={}", dlqTopic, groupId);
        int attempted = 0;
        int written = 0;
        int skipped = 0;

        try (KafkaConsumer<String, WorkerMetricBatch> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.subscribe(Collections.singletonList(dlqTopic));

            int emptyPolls = 0;
            while (emptyPolls < emptyPollLimit) {
                ConsumerRecords<String, WorkerMetricBatch> records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;

                for (ConsumerRecord<String, WorkerMetricBatch> record : records) {
                    attempted++;
                    if (record.value() == null) {
                        skipped++;
                        LOG.warn("DLT record key={} part={} off={} has null value — skipping",
                                record.key(), record.partition(), record.offset());
                        continue;
                    }
                    try {
                        writer.writeBatch(List.of(record.value()));
                        written++;
                    } catch (RuntimeException e) {
                        skipped++;
                        LOG.warn("DLT record key={} part={} off={} could not be replayed: {}",
                                record.key(), record.partition(), record.offset(), e.toString());
                    }
                }
                consumer.commitSync();
            }
        }

        LOG.info("DLQ replayer finished — attempted={} written={} skipped={}",
                attempted, written, skipped);
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }
}
