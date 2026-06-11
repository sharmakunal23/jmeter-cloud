package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import com.perf.orchestrator.kafka.KafkaMetricPublisher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * K-7 reliability sweep — wires the real {@code AsyncMetricsDispatcher} +
 * {@code DiskBackedMetricsBuffer} + {@code JdkHttpFallbackClient} together
 * with a {@link MockProducer} (controllable Kafka) and a JDK {@link HttpServer}
 * (controllable HTTP fallback target) and verifies the load-bearing
 * reliability invariants.
 *
 * <p>The drills here exercise the same code paths as a full-scale production
 * load test, just deterministically and at component scale (small envelope
 * counts, controlled timing). Full-scale measurement (20 pods × 200 TPS ×
 * 200 endpoints, 5-minute runs, real Kafka, real Postgres) is the operator's
 * job.
 */
@DisplayName("K-7 reliability sweep — automated drills")
class ReliabilityDrillsIT {

    private static final String TOPIC = "jmeter.metrics.perSecond";

    @TempDir Path bufferDir;

    private SimpleMeterRegistry meterRegistry;
    private DiskBackedMetricsBuffer buffer;
    private MockProducer<String, WorkerMetricBatch> mockProducer;
    private KafkaMetricPublisher publisher;
    private HttpServer httpServer;
    private int httpPort;
    private final AtomicInteger httpStatusToReturn = new AtomicInteger(202);
    private final AtomicInteger httpRequestCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> receivedWindowSeconds = new ConcurrentLinkedQueue<>();
    private AsyncMetricsDispatcher dispatcher;

    @BeforeEach
    void setUp() throws IOException {
        meterRegistry = new SimpleMeterRegistry();
        buffer = new DiskBackedMetricsBuffer(
                bufferDir,
                DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig.defaults(),
                meterRegistry,
                Clock.systemUTC());

        mockProducer = newMockProducer(/* autoComplete */ true);
        publisher = KafkaMetricPublisher.forTesting(templateFor(mockProducer));

        // Real HTTP server on an ephemeral port — controllable response status.
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpPort = httpServer.getAddress().getPort();
        httpServer.createContext("/api/v1/ingest", new ControllableHandler());
        httpServer.setExecutor(null);
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) dispatcher.close();
        if (buffer != null) buffer.close();
        if (httpServer != null) httpServer.stop(0);
    }

    private void wireDispatcher() {
        wireDispatcher(/* httpFallbackEnabled */ true);
    }

    private void wireDispatcher(boolean httpFallbackEnabled) {
        HttpFallbackClient fallback = httpFallbackEnabled
                ? new JdkHttpFallbackClient(
                        "http://127.0.0.1:" + httpPort + "/api/v1/ingest",
                        Duration.ofMillis(500), Duration.ofMillis(500))
                : null;
        dispatcher = new AsyncMetricsDispatcher(
                buffer, publisher, fallback, meterRegistry,
                Clock.systemUTC(),
                AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY,
                Duration.ofMillis(50),    // tight retryInterval for fast tests
                Duration.ofMillis(200));  // short retryAfter so retry sweeper kicks in
    }

    // -----------------------------------------------------------------------
    // Drill 1 — Happy path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Drill 1 — happy path (no outage)")
    class HappyPath {

        @Test
        @DisplayName("100 envelopes flow Kafka → ack; buffer drains to zero; no fallback fires")
        void happy_path() {
            wireDispatcher();
            int N = 100;

            for (int i = 0; i < N; i++) {
                dispatcher.offer(envelope(1_700_000_000L + i), "test.topic");
            }

            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertThat(mockProducer.history().size())
                                .as("all envelopes sent to Kafka")
                                .isGreaterThanOrEqualTo(N);
                        assertThat(buffer.depthEnvelopes())
                                .as("buffer drained")
                                .isZero();
                    });

            // Kafka took every envelope; HTTP fallback never fired.
            assertSoftly(softly -> {
                softly.assertThat(httpRequestCount.get())
                        .as("HTTP fallback must not fire when Kafka succeeds")
                        .isZero();
                softly.assertThat(meterRegistry.counter("httpFallback.accepted").count()).isZero();
                softly.assertThat(meterRegistry.counter("httpFallback.retry").count()).isZero();
                softly.assertThat(meterRegistry.counter("metricsBuffer.dropsForCap").count()).isZero();
            });
        }
    }

    // -----------------------------------------------------------------------
    // Drill 2 — Kafka outage; HTTP takes over
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Drill 2 — Kafka outage during run")
    class KafkaOutage {

        @Test
        @DisplayName("Kafka fails for first batch; HTTP succeeds; recovery → all envelopes accounted for")
        void kafka_fails_then_recovers_via_http() {
            // autoComplete=false so we control Kafka outcomes
            mockProducer = newMockProducer(false);
            publisher = KafkaMetricPublisher.forTesting(templateFor(mockProducer));
            wireDispatcher();

            int N = 20;
            for (int i = 0; i < N; i++) {
                dispatcher.offer(envelope(1_700_000_000L + i), "test.topic");
            }

            // Wait for sends to queue in the MockProducer.
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(mockProducer.history().size())
                            .isGreaterThanOrEqualTo(1));

            // Fail every queued send → dispatcher should fall back to HTTP per envelope.
            // MockProducer.errorNext() fails ONE pending send; loop until empty.
            while (mockProducer.errorNext(new RuntimeException("simulated kafka outage"))) {
                // keep failing
            }

            // Buffer must drain via HTTP fallback within the test timeout.
            Awaitility.await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertThat(buffer.depthEnvelopes())
                                .as("envelopes drained via HTTP fallback")
                                .isZero();
                        assertThat(httpRequestCount.get())
                                .as("HTTP fallback received envelopes")
                                .isGreaterThanOrEqualTo(1);
                    });

            assertThat(meterRegistry.counter("httpFallback.accepted").count())
                    .as("HTTP path acknowledged the failover envelopes")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Drill 3 — Both ingesters down; envelopes accumulate; recovery drains
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Drill 3 — both ingesters down, then recovery")
    class BothDown {

        @Test
        @DisplayName("Kafka fails + HTTP returns 503 → buffer accumulates; HTTP recovers → buffer drains")
        void both_down_then_http_recovers() {
            mockProducer = newMockProducer(false);
            publisher = KafkaMetricPublisher.forTesting(templateFor(mockProducer));
            httpStatusToReturn.set(503);
            wireDispatcher();

            int N = 10;
            for (int i = 0; i < N; i++) {
                dispatcher.offer(envelope(1_700_000_000L + i), "test.topic");
            }

            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(mockProducer.history().size())
                            .isGreaterThanOrEqualTo(1));

            // Fail Kafka → fallback fires → 503 → RETRY → envelope stays on disk
            while (mockProducer.errorNext(new RuntimeException("kafka down"))) { }

            // Wait for 503 fallback to fire; buffer should still have envelopes.
            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertThat(meterRegistry.counter("httpFallback.retry").count())
                                .isGreaterThanOrEqualTo(1);
                    });
            assertThat(buffer.depthEnvelopes())
                    .as("envelopes accumulate when both ingesters fail")
                    .isGreaterThan(0L);

            // Now HTTP recovers → buffer drains via the K-3 retry sweeper which
            // re-publishes oldest stale envelope, hitting the (still-failing)
            // Kafka first, then falling back to HTTP which now returns 202.
            httpStatusToReturn.set(202);
            // Auto-complete future Kafka sends as failures so retry-sweeper
            // doesn't accidentally drain via Kafka path.
            // (MockProducer's accumulator path: errorNext only acts on pending.)

            Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        // Drain Kafka pending sends as they re-arrive (retry path issues them)
                        while (mockProducer.errorNext(new RuntimeException("kafka still down"))) { }
                        assertThat(buffer.depthEnvelopes())
                                .as("buffer drains once HTTP recovers")
                                .isZero();
                    });

            assertThat(meterRegistry.counter("httpFallback.accepted").count())
                    .as("HTTP eventually accepted the buffered envelopes")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Drill 4 — Crash recovery via boot scrubber
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Drill 4 — crash recovery (boot scrubber)")
    class CrashRecovery {

        @Test
        @DisplayName("Envelopes persisted before 'crash' are picked up by the new buffer instance and drained")
        void boot_scrubber_recovers_persisted_envelopes() {
            mockProducer = newMockProducer(false);
            publisher = KafkaMetricPublisher.forTesting(templateFor(mockProducer));
            wireDispatcher();

            int N = 5;
            for (int i = 0; i < N; i++) {
                dispatcher.offer(envelope(1_700_000_000L + i), "test.topic");
            }

            // Wait for envelopes to land on disk via buffer.enqueue
            Awaitility.await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes())
                            .isGreaterThanOrEqualTo(1L));

            // Simulate orchestrator crash: dispatcher dies before publishes complete.
            dispatcher.close();
            buffer.close();

            // Reboot — instantiate a fresh dispatcher + buffer against the same disk.
            // The boot scrubber should re-index every persisted envelope.
            SimpleMeterRegistry rebootRegistry = new SimpleMeterRegistry();
            DiskBackedMetricsBuffer rebootBuffer = new DiskBackedMetricsBuffer(
                    bufferDir,
                    DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig.defaults(),
                    rebootRegistry,
                    Clock.systemUTC());

            // Boot scrubber recovered N envelopes from disk.
            assertThat(rebootBuffer.depthEnvelopes())
                    .as("boot scrubber picks up envelopes the prior process persisted")
                    .isGreaterThanOrEqualTo(1L);
            assertThat(rebootRegistry.counter("metricsBuffer.bootRecovered").count())
                    .isGreaterThanOrEqualTo(1.0);

            // Wire a new dispatcher + autoComplete-true publisher → buffer drains via Kafka.
            MockProducer<String, WorkerMetricBatch> rebootProducer = newMockProducer(true);
            KafkaMetricPublisher rebootPublisher =
                    KafkaMetricPublisher.forTesting(templateFor(rebootProducer));
            HttpFallbackClient fallback = new JdkHttpFallbackClient(
                    "http://127.0.0.1:" + httpPort + "/api/v1/ingest",
                    Duration.ofMillis(500), Duration.ofMillis(500));
            AsyncMetricsDispatcher rebootDispatcher = new AsyncMetricsDispatcher(
                    rebootBuffer, rebootPublisher, fallback, rebootRegistry,
                    Clock.systemUTC(),
                    AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY,
                    Duration.ofMillis(50),
                    Duration.ofMillis(200));

            try {
                Awaitility.await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(rebootBuffer.depthEnvelopes())
                                .as("recovered envelopes drain via Kafka after reboot")
                                .isZero());
            } finally {
                rebootDispatcher.close();
                rebootBuffer.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Drill 5 — Disk pressure (free-disk guard)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Drill 5 — disk pressure (JMeter-considerate)")
    class DiskPressure {

        @Test
        @DisplayName("When free-disk reservation is set above current free disk, every enqueue is refused — JMeter wins")
        void free_disk_guard_refuses_writes() {
            // Set the threshold above any plausible free disk so the guard always trips.
            DiskBackedMetricsBuffer pressuredBuffer = new DiskBackedMetricsBuffer(
                    bufferDir.resolveSibling(bufferDir.getFileName() + "-pressured"),
                    new DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig(
                            1024L * 1024L, 200L * 1024L,
                            Long.MAX_VALUE,        // impossible free-disk threshold
                            Duration.ofHours(6)),
                    meterRegistry,
                    Clock.systemUTC());

            try {
                // Direct buffer call — bypassing the dispatcher to keep this drill
                // focused on the free-disk guard's behavior.
                for (int i = 0; i < 10; i++) {
                    pressuredBuffer.enqueue(envelope(1_700_000_000L + i), "test.topic");
                }

                assertSoftly(softly -> {
                    softly.assertThat(pressuredBuffer.depthEnvelopes())
                            .as("no envelopes persisted while free-disk threshold violated")
                            .isZero();
                    softly.assertThat(meterRegistry.counter("metricsBuffer.dropsForLowDisk").count())
                            .as("dropsForLowDisk counter records every refusal — operator-visible")
                            .isEqualTo(10.0);
                });
            } finally {
                pressuredBuffer.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Drill 6 — Per-run topic routing (Phase G fix)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Drill 6 — per-run Kafka topic routing")
    class PerRunTopicRouting {

        @Test
        @DisplayName("Two consecutive 'runs' with different topics from one publisher → envelopes land on the right topic; no cross-contamination")
        void two_runs_two_topics_same_publisher() {
            // Single warm publisher (the production singleton shape) — proves
            // the Phase G fix: topic is per-call, not pinned at construction.
            wireDispatcher(/* httpFallbackEnabled */ false);

            String topicA = "jmeter.metrics.appA";
            String topicB = "jmeter.metrics.appB";
            int perRun = 25;

            // Run A — 25 envelopes against topicA.
            for (int i = 0; i < perRun; i++) {
                dispatcher.offer(envelope(1_700_000_000L + i), topicA);
            }
            // Run B — 25 envelopes against topicB, fired immediately
            // (same dispatcher, same publisher, same orchestrator boot).
            for (int i = 0; i < perRun; i++) {
                dispatcher.offer(envelope(1_700_001_000L + i), topicB);
            }

            Awaitility.await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertThat(mockProducer.history().size()).isEqualTo(2 * perRun);
                        assertThat(buffer.depthEnvelopes()).isZero();
                    });

            long sentToA = mockProducer.history().stream()
                    .filter(r -> topicA.equals(r.topic())).count();
            long sentToB = mockProducer.history().stream()
                    .filter(r -> topicB.equals(r.topic())).count();
            long sentElsewhere = mockProducer.history().stream()
                    .filter(r -> !topicA.equals(r.topic()) && !topicB.equals(r.topic()))
                    .count();

            assertSoftly(softly -> {
                softly.assertThat(sentToA)
                        .as("run-A envelopes land on topicA — proves topic flows per-call")
                        .isEqualTo(perRun);
                softly.assertThat(sentToB)
                        .as("run-B envelopes land on topicB — proves topic flows per-call")
                        .isEqualTo(perRun);
                softly.assertThat(sentElsewhere)
                        .as("no envelope leaks to a third topic — the Phase G regression " +
                            "would have sent everything to a boot-time default")
                        .isZero();
            });
        }

        @Test
        @DisplayName("Crash-recovery: envelope persisted with topicA replays to topicA after buffer reload")
        void crash_recovery_preserves_topic() throws IOException {
            String topicA = "jmeter.metrics.appCrashRecovery";

            // Persist directly to the buffer (no dispatcher) — simulates an
            // envelope that was on disk when the orchestrator crashed.
            buffer.enqueue(envelope(1_700_000_000L), topicA);
            assertThat(buffer.depthEnvelopes()).isEqualTo(1L);

            // Simulate orchestrator restart by re-loading the buffer from disk.
            buffer.close();
            DiskBackedMetricsBuffer reloaded = new DiskBackedMetricsBuffer(
                    bufferDir,
                    DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig.defaults(),
                    meterRegistry,
                    Clock.systemUTC());
            try {
                BufferedEnvelope recovered = reloaded.peekOldest().orElseThrow();
                assertThat(recovered.topic())
                        .as("topic sidecar survives restart — replay routes to the right per-app topic")
                        .isEqualTo(topicA);
                reloaded.delete(recovered);
            } finally {
                reloaded.close();
                buffer = null; // tearDown's close() guard
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    private final class ControllableHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            httpRequestCount.incrementAndGet();
            // Drain body
            byte[] body = exchange.getRequestBody().readAllBytes();
            // Cheap windowSecond observability — count bytes received
            receivedWindowSeconds.add((long) body.length);
            int status = httpStatusToReturn.get();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }
    }

    private static MockProducer<String, WorkerMetricBatch> newMockProducer(boolean autoComplete) {
        MockSchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
        KafkaAvroSerializer avroSerializer = new KafkaAvroSerializer(schemaRegistry);
        avroSerializer.configure(
                Map.of(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://reliability"),
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

    private static WorkerMetricBatch envelope(long sec) {
        Map<String, Long> statusCodes = new HashMap<>();
        statusCodes.put("200", 1L);
        WorkerMetricEntry entry = WorkerMetricEntry.newBuilder()
                .setLabel("GET /api/test")
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
                .setWorkerId("worker-reliability")
                .setRunId("reliability-drill")
                .setEntries(List.of(entry))
                .build();
    }
}
