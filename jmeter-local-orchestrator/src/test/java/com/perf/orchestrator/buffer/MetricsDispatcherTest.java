package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import com.perf.orchestrator.kafka.KafkaMetricPublisher;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetricsDispatcher")
class MetricsDispatcherTest {

    private static final String TOPIC = "jmeter.metrics.perSecond";

    private SimpleMeterRegistry meterRegistry;
    private InMemoryMetricsBuffer buffer;
    private MockProducer<String, WorkerMetricBatch> mockProducer;
    private KafkaMetricPublisher publisher;
    private AsyncMetricsDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        buffer = new InMemoryMetricsBuffer();
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    private void wireDispatcher(boolean autoCompleteSends) {
        wireDispatcher(autoCompleteSends, AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY);
    }

    private void wireDispatcher(boolean autoCompleteSends, int queueCapacity) {
        wireDispatcher(autoCompleteSends, queueCapacity, /* httpFallback */ null);
    }

    private void wireDispatcher(boolean autoCompleteSends,
                                 int queueCapacity,
                                 HttpFallbackClient httpFallback) {
        mockProducer = newMockProducer(autoCompleteSends);
        publisher = KafkaMetricPublisher.forTesting(templateFor(mockProducer));
        dispatcher = new AsyncMetricsDispatcher(
                buffer, publisher, httpFallback, meterRegistry,
                java.time.Clock.systemUTC(),
                queueCapacity,
                Duration.ofMillis(50),     // tight retryInterval for fast tests
                Duration.ofMillis(200));   // short retryAfter for fast tests
    }

    // -----------------------------------------------------------------------
    // Happy path — offer → buffer → publish → delete
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("offer accepts envelope and returns true")
        void offer_accepts_and_returns_true() {
            wireDispatcher(true);

            boolean accepted = dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            assertThat(accepted).isTrue();
            assertThat(meterRegistry.counter("metricsDispatch.offered").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("envelope flows through buffer and is published to Kafka")
        void envelope_flows_through_buffer_to_kafka() {
            wireDispatcher(true);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            // Mock producer auto-completes; buffer should drain to zero once publish settles.
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        assertThat(mockProducer.history()).hasSize(1);
                        assertThat(buffer.depthEnvelopes()).isZero();
                    });
        }

        @Test
        @DisplayName("offerAll accepts all envelopes when queue has room")
        void offerAll_accepts_all_when_room() {
            wireDispatcher(true);

            int accepted = dispatcher.offerAll(List.of(
                    envelope(1L, "w-1", "GET /a"),
                    envelope(2L, "w-1", "GET /b"),
                    envelope(3L, "w-1", "GET /c")), "test.topic");

            assertThat(accepted).isEqualTo(3);
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSize(3));
        }
    }

    // -----------------------------------------------------------------------
    // Backpressure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("backpressure")
    class Backpressure {

        @Test
        @DisplayName("offer returns false + bumps counter when in-memory queue is full")
        void offer_returns_false_when_queue_full() {
            // Use autoComplete=false so the dispatch thread can't drain the queue.
            wireDispatcher(false, /* queueCapacity */ 2);

            // Two envelopes fit; the dispatch thread might pick one up between
            // offers, so do a tight loop and accept that some succeed before
            // the queue fills. The third onwards should reliably fail.
            for (int i = 0; i < 2; i++) {
                dispatcher.offer(envelope(i, "w-1", "GET /a"), "test.topic");
            }
            // Spam more offers; with autoCompleteSends=false the dispatch thread
            // is stuck waiting for callbacks and the queue stays full eventually.
            int rejections = 0;
            for (int i = 0; i < 20; i++) {
                if (!dispatcher.offer(envelope(100 + i, "w-1", "GET /a"), "test.topic")) {
                    rejections++;
                }
            }

            assertThat(rejections).as("at least some offers must be rejected when queue is saturated")
                    .isGreaterThan(0);
            assertThat(meterRegistry.counter("metricsDispatch.dropsForBackpressure").count())
                    .isGreaterThan(0);
        }
    }

    // -----------------------------------------------------------------------
    // Retry on publish failure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("retry on publish failure")
    class RetryOnFailure {

        @Test
        @DisplayName("envelope stays in buffer when publish fails; retry sweeper republishes after retryAfter")
        void retries_failed_envelope_after_stale_window() {
            // autoComplete=false so we manually fail the first send, then succeed the retry.
            wireDispatcher(false);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            // Wait for the first send to be queued
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSize(1));

            // Fail the first send — envelope stays on disk in the buffer.
            mockProducer.errorNext(new RuntimeException("broker unavailable"));
            assertThat(buffer.depthEnvelopes()).isEqualTo(1L);

            // Wait for the retry sweeper to fire (retryAfter=200ms; retryInterval=50ms).
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSizeGreaterThanOrEqualTo(2));

            // Complete the retry's send → buffer drains.
            mockProducer.completeNext();
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes()).isZero());

            assertThat(meterRegistry.counter("metricsDispatch.retried").count()).isGreaterThan(0);
        }
    }

    // -----------------------------------------------------------------------
    // K-5 — HTTP fallback when Kafka send fails
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("HTTP fallback (K-5)")
    class HttpFallback {

        @Test
        @DisplayName("Kafka fails → HTTP succeeds → buffer drains, fallback counter bumps")
        void kafka_fails_http_succeeds_buffer_drains() {
            RecordingHttpFallback fallback = RecordingHttpFallback.alwaysAccepted();
            wireDispatcher(/* autoComplete */ false, MetricsDispatcher.class.getDeclaredFields().length > 0
                    ? AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY : 256, fallback);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSize(1));

            // Fail the Kafka send → dispatcher should call HTTP fallback.
            mockProducer.errorNext(new RuntimeException("broker unavailable"));

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        assertThat(fallback.callCount.get())
                                .as("fallback should fire after Kafka failure")
                                .isGreaterThanOrEqualTo(1);
                        assertThat(buffer.depthEnvelopes())
                                .as("buffer should drain after HTTP succeeds")
                                .isZero();
                    });
            assertThat(meterRegistry.counter("httpFallback.accepted").count()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Kafka fails → HTTP returns 503 RETRY → envelope stays in buffer for sweeper")
        void kafka_fails_http_retry_envelope_stays() {
            RecordingHttpFallback fallback = RecordingHttpFallback.alwaysReturning(
                    HttpFallbackResult.retry(503, "consumer down"));
            wireDispatcher(/* autoComplete */ false, AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY, fallback);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSize(1));

            mockProducer.errorNext(new RuntimeException("broker unavailable"));

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(fallback.callCount.get()).isGreaterThanOrEqualTo(1));

            // After RETRY, envelope must remain on disk for the K-3 sweeper.
            assertThat(buffer.depthEnvelopes()).isEqualTo(1L);
            assertThat(meterRegistry.counter("httpFallback.retry").count()).isGreaterThanOrEqualTo(1);
            assertThat(meterRegistry.counter("httpFallback.accepted").count()).isZero();
        }

        @Test
        @DisplayName("Kafka fails → HTTP returns 400 TERMINAL_REJECT → envelope deleted (data loss counter bumps)")
        void kafka_fails_http_terminal_reject_envelope_deleted() {
            RecordingHttpFallback fallback = RecordingHttpFallback.alwaysReturning(
                    HttpFallbackResult.terminalReject(400, "malformed"));
            wireDispatcher(/* autoComplete */ false, AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY, fallback);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSize(1));

            mockProducer.errorNext(new RuntimeException("broker unavailable"));

            // Buffer drains AND the rejection counter increments — operator
            // can see the loss without it being silent.
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes()).isZero());
            assertThat(meterRegistry.counter("httpFallback.terminalRejects").count())
                    .isGreaterThanOrEqualTo(1);
            assertThat(meterRegistry.counter("httpFallback.accepted").count()).isZero();
        }

        @Test
        @DisplayName("Kafka succeeds → HTTP fallback is never called")
        void kafka_succeeds_no_fallback() {
            RecordingHttpFallback fallback = RecordingHttpFallback.alwaysAccepted();
            wireDispatcher(/* autoComplete */ true, AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY, fallback);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes()).isZero());

            // Give a tiny moment to be sure no spurious fallback call lands
            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            assertThat(fallback.callCount.get())
                    .as("fallback must not fire when Kafka send succeeds")
                    .isZero();
        }
    }

    /** Test fake recording fallback invocations and returning a configurable outcome. */
    private static final class RecordingHttpFallback implements HttpFallbackClient {
        final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        private final HttpFallbackResult outcome;

        private RecordingHttpFallback(HttpFallbackResult outcome) { this.outcome = outcome; }
        static RecordingHttpFallback alwaysAccepted() {
            return new RecordingHttpFallback(HttpFallbackResult.accepted());
        }
        static RecordingHttpFallback alwaysReturning(HttpFallbackResult outcome) {
            return new RecordingHttpFallback(outcome);
        }
        @Override
        public java.util.concurrent.CompletableFuture<HttpFallbackResult> send(WorkerMetricBatch envelope) {
            callCount.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(outcome);
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("close stops the dispatch thread within timeout")
        void close_stops_thread() throws InterruptedException {
            wireDispatcher(true);

            // Sanity — dispatcher is up and processing
            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "test.topic");
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(mockProducer.history()).hasSize(1));

            long start = System.nanoTime();
            dispatcher.close();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(elapsedMs).as("close() must return within the join timeout")
                    .isLessThan(6_000L);
        }
    }

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    private static MockProducer<String, WorkerMetricBatch> newMockProducer(boolean autoComplete) {
        MockSchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
        KafkaAvroSerializer avroSerializer = new KafkaAvroSerializer(schemaRegistry);
        avroSerializer.configure(
                Map.of(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://test"),
                false);
        Serializer<WorkerMetricBatch> valueSerializer = new Serializer<>() {
            @Override public byte[] serialize(String topic, WorkerMetricBatch data) {
                return avroSerializer.serialize(topic, data);
            }
        };
        return new MockProducer<>(autoComplete, new StringSerializer(), valueSerializer);
    }

    private static KafkaTemplate<String, WorkerMetricBatch> templateFor(
            MockProducer<String, WorkerMetricBatch> mockProducer) {
        Producer<String, WorkerMetricBatch> closeSafeProducer = org.mockito.Mockito.spy(mockProducer);
        org.mockito.Mockito.doNothing().when(closeSafeProducer).close();
        org.mockito.Mockito.doNothing().when(closeSafeProducer).close(org.mockito.ArgumentMatchers.any());
        ProducerFactory<String, WorkerMetricBatch> factory = new ProducerFactory<>() {
            @Override public Producer<String, WorkerMetricBatch> createProducer() {
                return closeSafeProducer;
            }
        };
        return new KafkaTemplate<>(factory);
    }

    private static WorkerMetricBatch envelope(long sec, String workerId, String label) {
        Map<String, Long> statusCodes = new HashMap<>();
        statusCodes.put("200", 1L);
        WorkerMetricEntry entry = WorkerMetricEntry.newBuilder()
                .setLabel(label)
                .setThroughput(1L).setErrorCount(0L).setErrorRate(0.0)
                .setAvgRespTimeMs(10.0)
                .setP50Ms(10.0).setP90Ms(10.0).setP95Ms(10.0).setP99Ms(10.0)
                .setMinMs(10.0).setMaxMs(10.0).setRawMaxMs(10L)
                .setBytesReceived(100L).setBytesSent(50L)
                .setStatusCodes(statusCodes)
                .setActiveThreads(1L)
                .build();
        return WorkerMetricBatch.newBuilder()
                .setWindowSecond(sec)
                .setWindowTimestamp("2026/05/11 12:00:00")
                .setRegion("us-east-1")
                .setWorkerId(workerId)
                .setRunId("test-run")
                .setEntries(List.of(entry))
                .build();
    }
}
