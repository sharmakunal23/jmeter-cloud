package com.perf.metricsconsumer.kafka;

import com.perf.orchestrator.WorkerMetricBatch;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires the Avro-aware envelope consumer container factory + DLQ error handler.
 * The {@code @KafkaListener} on {@link WorkerMetricListener} binds to this
 * factory by name ({@code workerMetricContainerFactory}).
 *
 * <p>Container is in <strong>batch mode</strong> — one listener invocation
 * per Kafka poll, with up to {@code maxPollRecords} {@link WorkerMetricBatch}
 * envelopes per call. The writer explodes envelopes into per-row INSERTs and
 * batches the SQL into chunks at {@code maxRowsPerInsert}.
 *
 * <p><b>K-2 grain shift:</b> {@code maxPollRecords} is at envelope grain now,
 * not per-row. Default 50 envelopes × ~200 entries = ~10k rows per poll —
 * sized for the writer's chunking ceiling. Pre-K-2 default of 500 was
 * per-row; using it at envelope grain would yield 500 × ~200 = 100k rows
 * per poll, which is too much for one bind buffer.
 *
 * <p>Failures during deserialisation or message handling route to the
 * {@code jmeter.metrics.perSecond.DLT} topic with a fixed 1-s backoff
 * after 3 retries.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String bootstrapServers;

    @Value("${metricsConsumer.schemaRegistryUrl:http://schema-registry:8081}")
    private String schemaRegistryUrl;

    @Value("${metricsConsumer.groupId:jmeter-metrics-consumer}")
    private String groupId;

    /**
     * Upper bound on envelopes per batch. K-2 grain: at ~200 entries per
     * envelope, 20 envelopes ≈ 4k rows per poll. Improvement #6 (2026-05-16)
     * dropped this from 50 → 20 — smaller batches mean faster offset
     * commits, which means faster recovery from a slow Postgres or a
     * deserialise hiccup. The throughput cost is negligible (the writer
     * still does one INSERT per poll); the resilience win is real.
     */
    @Value("${metricsConsumer.maxPollRecords:20}")
    private int maxPollRecords;

    /**
     * Per-partition fetch ceiling. Improvement #6 (2026-05-16) — explicit
     * 1 MB cap so a single oversized envelope (or a misbehaving producer
     * pushing 10 MB records) can't blow the consumer's heap on the next
     * poll. Apache Kafka default is 1 MB; pinning it here makes the value
     * visible + alters-at-config-time instead of "whatever the next
     * dependency bump ships".
     */
    @Value("${metricsConsumer.maxPartitionFetchBytes:1048576}")
    private int maxPartitionFetchBytes;

    /**
     * Broker-side wait for fetch.min.bytes to be met. At low ingest rates
     * this is the floor for batch-end-to-end latency; default 1 s keeps the
     * dashboard near-realtime even when only a handful of pods are running.
     */
    @Value("${metricsConsumer.fetchMaxWaitMs:1000}")
    private int fetchMaxWaitMs;

    /**
     * KAFKA-PER-APP Phase D — how often the consumer re-reads cluster
     * metadata, which is also how often the {@code topicPattern}
     * subscription rediscovers new app topics. 30 s strikes the balance:
     * fast enough that an app registered just now is consumed within
     * ~30 s of the broker creating its topic; slow enough that we don't
     * hammer the broker's metadata path on the steady state. Default
     * {@code metadata.max.age.ms} is 5 min — too slow for the per-app
     * topic flow.
     */
    @Value("${metricsConsumer.metadataMaxAgeMs:30000}")
    private int metadataMaxAgeMs;

    @Bean
    public ConsumerFactory<String, WorkerMetricBatch> workerMetricConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        // SpecificRecord — the avro-maven-plugin generated WorkerMetricBatch class.
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        // Start at the earliest offset on first run so a freshly-spun-up
        // consumer doesn't miss envelopes produced during its boot.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Spring Kafka manages offset commits via the listener container's
        // BATCH ack mode — flip auto-commit off so duplicate acks can't slip in.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);
        // Phase D: pattern-subscription topic rediscovery cadence.
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, metadataMaxAgeMs);
        // Improvement #6: explicit per-partition fetch cap.
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, maxPartitionFetchBytes);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WorkerMetricBatch>
            workerMetricContainerFactory(
                    ConsumerFactory<String, WorkerMetricBatch> consumerFactory,
                    KafkaTemplate<String, byte[]> dltKafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, WorkerMetricBatch> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // Batch listener: one onWorkerMetricBatch() call per poll, up to
        // maxPollRecords envelopes. Pairs with the writer's chunked INSERT.
        factory.setBatchListener(true);

        // DLQ: failed batches publish to {originalTopic}.DLT. 3 retries with
        // 1 s back-off before giving up and routing to DLT.
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(dltKafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(dlt, new FixedBackOff(1_000L, 3L));
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    /**
     * KAFKA-PER-APP Phase D — bytes-only consumer factory for the DLT
     * skeleton listener. Avro deserialise is intentionally NOT used here:
     * DLT records may not be Avro-decodable (the schema-registry
     * deserializer's failure is what landed them in the DLT in the first
     * place), so we just consume the raw bytes + metadata for counting +
     * logging. No DLT recovery wired — failures consuming a DLT itself
     * just retry forever (DefaultErrorHandler's default), which is fine
     * for a skeleton.
     */
    @Bean
    public ConsumerFactory<String, byte[]> dltSkeletonConsumerFactory(
            @Value("${metricsConsumer.dltGroupId:jmeter-metrics-consumer-dlt}") String dltGroupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, dltGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Smaller batch budget — DLT volume is expected to be tiny relative
        // to the main stream; no need for the K-2 envelope-grain ceiling.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        // Same metadata-refresh cadence as the main listener so a new
        // app's DLT is discovered on the same 30 s clock.
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, metadataMaxAgeMs);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]>
            dltSkeletonContainerFactory(
                    ConsumerFactory<String, byte[]> dltSkeletonConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dltSkeletonConsumerFactory);
        factory.setBatchListener(true);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, byte[]> dltKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer",
                "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer",
                "org.apache.kafka.common.serialization.ByteArraySerializer");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
