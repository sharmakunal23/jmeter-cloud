package com.perf.orchestrator.kafka;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.config.OrchestratorConfig;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Publishes {@link WorkerMetricBatch} envelopes to Kafka. Backed by Spring Kafka's
 * {@link KafkaTemplate} so producer metrics surface at
 * {@code /actuator/metrics/kafka.producer.*} via the Micrometer Kafka binder.
 *
 * <h2>Envelope shape (K-1)</h2>
 * Each Kafka record carries a {@link WorkerMetricBatch} envelope — one per
 * {@code (workerId, windowSecond)} pair — instead of one record per
 * {@code (workerId, label, windowSecond)} triple. The {@code TumblingWindowAggregator}
 * groups buckets and emits envelopes; this publisher's job is just to serialise +
 * key + send.
 *
 * <h2>Lifecycle: per-process singleton</h2>
 * Constructed once at boot from {@link OrchestratorConfig} and shared across
 * every test run. The state machine calls {@link #flush()} at the end of each
 * run; {@link #close()} runs only at JVM shutdown. Sharing one producer across
 * runs keeps connections warm, accumulates broker-side metrics over the
 * orchestrator's lifetime, and avoids the ~100-200 ms producer-construction
 * cost on every {@code POST /test}.
 *
 * <h2>Delivery guarantees</h2>
 * The underlying {@code KafkaProducer} is configured for idempotent delivery:
 * <ul>
 *   <li>{@code enable.idempotence=true} — broker-side dedup of producer retries.</li>
 *   <li>{@code acks=all} — leader waits for all in-sync replicas before ack.</li>
 *   <li>{@code retries=MAX_VALUE} — retries until the delivery timeout expires.</li>
 * </ul>
 *
 * <h2>Asynchronous sending</h2>
 * {@link #publishBatch} and {@link #publishAll} return immediately without blocking.
 * Delivery failures are logged at WARNING level and counted in
 * {@link #getFailedCount()}.
 *
 * <h2>End-of-run flush</h2>
 * {@link #flush} delegates to {@link KafkaTemplate#flush}, which blocks until all
 * in-flight records have been delivered or the delivery timeout expires.
 *
 * <h2>Thread safety</h2>
 * {@link KafkaTemplate} is thread-safe. {@link #publishBatch} and {@link #publishAll}
 * may be called from the poll-loop thread. {@link #close} must be called exactly
 * once from the shutdown hook after all publish calls have returned.
 */
public final class KafkaMetricPublisher implements MetricPublisher {

    private static final Logger LOG = Logger.getLogger(KafkaMetricPublisher.class.getName());

    private final DefaultKafkaProducerFactory<String, WorkerMetricBatch> producerFactory;
    private final KafkaTemplate<String, WorkerMetricBatch> kafkaTemplate;
    private final MetricKeyStrategy                        keyStrategy;

    /** Monotonically increasing count of successfully broker-acknowledged envelopes. */
    private final LongAdder publishedCount = new LongAdder();

    /** Count of envelopes for which the broker callback reported an error. */
    private final LongAdder failedCount = new LongAdder();

    /** Guards against double-close — KafkaTemplate's underlying producer factory throws if disposed twice. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Package-private constructor — use the static factories below.
     * Visible to tests so they can wire a {@link KafkaTemplate} backed by a
     * {@code MockProducer}-returning {@link ProducerFactory} without going
     * through {@link #create(OrchestratorConfig)}. The {@code producerFactory}
     * argument may be null in tests where {@link #enableMicrometer(MeterRegistry)}
     * isn't exercised.
     */
    KafkaMetricPublisher(DefaultKafkaProducerFactory<String, WorkerMetricBatch> producerFactory,
                         KafkaTemplate<String, WorkerMetricBatch> kafkaTemplate,
                         MetricKeyStrategy keyStrategy) {
        this.producerFactory = producerFactory; // nullable in tests
        this.kafkaTemplate   = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        this.keyStrategy     = Objects.requireNonNull(keyStrategy,   "keyStrategy cannot be null");
    }

    /**
     * Builds a publisher around a caller-supplied {@link KafkaTemplate} —
     * intended for tests that wire a {@code MockProducer}. Production code
     * should use {@link #create(OrchestratorConfig)} instead. {@code producerFactory}
     * may be null since {@link #enableMicrometer(io.micrometer.core.instrument.MeterRegistry)}
     * isn't exercised by tests using a mock template.
     */
    public static KafkaMetricPublisher forTesting(KafkaTemplate<String, WorkerMetricBatch> kafkaTemplate) {
        return new KafkaMetricPublisher(null, kafkaTemplate, MetricKeyStrategy.standard());
    }

    /**
     * Creates a production publisher: builds a {@link DefaultKafkaProducerFactory}
     * from {@link OrchestratorConfig}, wraps it in a {@link KafkaTemplate}, and
     * binds the result to {@link MetricKeyStrategy#standard()}.
     *
     * <p>The returned publisher is the per-process singleton — pre-publish it into
     * the Spring context so controllers and the {@code TailerStateMachine}
     * receive the same instance.
     *
     * <p>The destination Kafka topic is NOT pinned at construction. With
     * per-application Kafka routing, the producer is shared
     * across runs but each run writes to its own
     * {@code jmeter.metrics.<applicationId>} topic — the per-run topic flows
     * in through {@link #publishBatchAsync(WorkerMetricBatch, String)} / {@link #publishAll}.
     *
     * <p>Producer-level metrics are not yet bound to a {@link MeterRegistry} —
     * call {@link #enableMicrometer(MeterRegistry)} after the Spring context is
     * up. Sends are deferred until the first {@code POST /test}, so wiring the
     * listener post-boot is in time for the first producer instantiation.
     *
     * @param config orchestrator configuration supplying brokers, schema registry URL, client.id
     * @return a ready-to-publish {@link KafkaMetricPublisher}
     */
    public static KafkaMetricPublisher create(OrchestratorConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        DefaultKafkaProducerFactory<String, WorkerMetricBatch> factory =
                new DefaultKafkaProducerFactory<>(buildProducerProps(config));
        KafkaTemplate<String, WorkerMetricBatch> template = new KafkaTemplate<>(factory);
        return new KafkaMetricPublisher(factory, template, MetricKeyStrategy.standard());
    }

    /**
     * Wires a {@link MicrometerProducerListener} onto the underlying
     * {@link KafkaTemplate} so kafka-clients producer metrics surface at
     * {@code /actuator/metrics/kafka.producer.*}.
     */
    public void enableMicrometer(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");
        if (producerFactory == null) {
            throw new IllegalStateException(
                    "enableMicrometer() requires a publisher built via create(config) — " +
                    "test publishers constructed directly from a custom ProducerFactory cannot bind metrics.");
        }
        producerFactory.addListener(new MicrometerProducerListener<>(meterRegistry));
    }

    // -----------------------------------------------------------------------
    // Publishing
    // -----------------------------------------------------------------------

    /**
     * Sends one {@link WorkerMetricBatch} envelope to {@code topic} asynchronously.
     *
     * <p>Returns immediately. Delivery outcome is reported via the
     * {@code CompletableFuture} returned by {@code KafkaTemplate.send} and reflected in
     * {@link #getPublishedCount()} / {@link #getFailedCount()}.
     *
     * <p>Partition key derived from envelope:
     * {@code "{region}|{workerId}|{windowSecond}"} via
     * {@link MetricKeyStrategy#standard()}. The same pod's envelopes spread
     * across all partitions over time (one key per windowSecond); within a
     * single second, split envelopes (when entries > MAX_ENTRIES_PER_ENVELOPE)
     * share one key and land adjacently. Per-pod ordering across seconds is
     * not preserved — see {@link MetricKeyStrategy} for why that's safe.
     *
     * @param envelope the envelope to publish; must not be null
     * @param topic    destination topic; must not be null/blank
     */
    public void publishBatch(WorkerMetricBatch envelope, String topic) {
        publishBatchAsync(envelope, topic);
    }

    /**
     * Asynchronously publishes one envelope to {@code topic} and returns the
     * underlying {@link KafkaTemplate#send} future. Internal counters update
     * on the future's completion (independent of any caller-attached
     * {@code whenComplete}).
     *
     * <p>Callers (e.g. {@code MetricsDispatcher}) chain their own
     * {@code whenComplete} to react to delivery outcome — typically
     * {@code buffer.delete(handle)} on success. Both completions fire
     * because they're attached to the same future independently.
     *
     * @param envelope the envelope to publish; must not be null
     * @param topic    destination topic; must not be null/blank
     * @return the send future — non-null even on synchronous failures
     */
    public CompletableFuture<SendResult<String, WorkerMetricBatch>> publishBatchAsync(
            WorkerMetricBatch envelope, String topic) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must be non-blank");
        }

        String key = keyStrategy.keyFor(envelope.getRegion().toString(),
                                        envelope.getWorkerId().toString(),
                                        envelope.getWindowSecond());
        ProducerRecord<String, WorkerMetricBatch> record =
                new ProducerRecord<>(topic, key, envelope);

        CompletableFuture<SendResult<String, WorkerMetricBatch>> future = kafkaTemplate.send(record);
        future.whenComplete((sendResult, exception) -> {
            if (exception != null) {
                failedCount.increment();
                LOG.warning(() -> String.format(
                        "Failed to deliver envelope: region=%s worker=%s second=%d entries=%d topic=%s — %s",
                        envelope.getRegion(), envelope.getWorkerId(),
                        envelope.getWindowSecond(), envelope.getEntries().size(),
                        topic, exception.getMessage()));
            } else {
                publishedCount.increment();
            }
        });
        return future;
    }

    /**
     * Publishes all envelopes in the list to {@code topic}.
     * Equivalent to calling {@link #publishBatch} in a loop.
     *
     * @param envelopes list of envelopes to publish; must not be null; empty list is a no-op
     * @param topic     destination topic; must not be null/blank
     */
    @Override
    public void publishAll(List<WorkerMetricBatch> envelopes, String topic) {
        Objects.requireNonNull(envelopes, "envelopes list cannot be null");
        for (WorkerMetricBatch envelope : envelopes) {
            publishBatch(envelope, topic);
        }
    }

    // -----------------------------------------------------------------------
    // Per-run flush
    // -----------------------------------------------------------------------

    /**
     * Blocks until every record sent before this call has been delivered to
     * the broker (or definitively failed). Called from the state machine at
     * end-of-run so the COMPLETED transition is observable only after the
     * final envelope has reached Kafka.
     *
     * <p>Does NOT close the underlying producer — {@link #close} is the only
     * call that does that, and it runs only on JVM shutdown.
     */
    @Override
    public void flush() {
        if (closed.get()) {
            return; // already shut down; no producer to flush
        }
        kafkaTemplate.flush();
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    /**
     * Returns the total number of envelopes successfully acknowledged by the broker
     * since this publisher was constructed. Updated asynchronously by the delivery
     * callback. Process-lifetime cumulative — multiple test runs share the counter.
     */
    @Override
    public long getPublishedCount() {
        return publishedCount.sum();
    }

    /**
     * Returns the total number of envelopes for which delivery failed after
     * all retries were exhausted. Process-lifetime cumulative.
     */
    @Override
    public long getFailedCount() {
        return failedCount.sum();
    }

    // -----------------------------------------------------------------------
    // Closeable — JVM-shutdown only
    // -----------------------------------------------------------------------

    /**
     * Flushes all in-flight envelopes and closes the underlying producer.
     * Idempotent — second call is a no-op. Called by the orchestrator's
     * shutdown hook, NOT by per-run state-machine teardown.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            LOG.fine("close() called on already-closed publisher — ignoring");
            return;
        }
        LOG.info(() -> String.format(
                "Closing KafkaMetricPublisher. published=%d failed=%d",
                publishedCount.sum(), failedCount.sum()));
        try {
            kafkaTemplate.flush();
        } catch (Exception e) {
            LOG.warning(() -> "flush() during close failed: " + e.getMessage());
        }
        try {
            kafkaTemplate.destroy();
        } catch (Exception e) {
            LOG.warning(() -> "kafkaTemplate.destroy() failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Producer configuration
    // -----------------------------------------------------------------------

    /**
     * Returns the producer config map used by the {@link DefaultKafkaProducerFactory}.
     * Same idempotence / acks / linger / batch / compression settings as the
     * legacy per-row publisher — only the value type changes.
     */
    private static Map<String, Object> buildProducerProps(OrchestratorConfig config) {
        Map<String, Object> props = new HashMap<>();

        // Connection
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBrokers());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
                config.getSchemaRegistryUrl());

        // Serialisers
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                KafkaAvroSerializer.class.getName());

        // Idempotent delivery — three settings that must be used together
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // Throughput tuning — batch within poll interval to amortise overhead
        props.put(ProducerConfig.LINGER_MS_CONFIG, 50);       // batch up to 50ms
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65_536);  // 64 KB batch ceiling
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // fast, good ratio

        // Per-message delivery timeout — fail fast enough to log and continue
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        // Client id for broker-side metrics and log correlation
        props.put(ProducerConfig.CLIENT_ID_CONFIG,
                "jmeter-orchestrator-" + config.getPodName());

        return props;
    }
}
