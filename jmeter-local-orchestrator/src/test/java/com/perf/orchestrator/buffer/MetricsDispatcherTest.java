package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.model.WorkerMetricEntry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AsyncMetricsDispatcher} — the HTTP-ingest publish
 * path .
 */
@DisplayName("MetricsDispatcher")
class MetricsDispatcherTest {

    private InMemoryMetricsBuffer buffer;
    private RecordingIngestClient ingest;
    private AsyncMetricsDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        buffer = new InMemoryMetricsBuffer();
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    private void wireDispatcher(RecordingIngestClient client) {
        wireDispatcher(client, AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY);
    }

    private void wireDispatcher(RecordingIngestClient client, int queueCapacity) {
        ingest = client;
        dispatcher = new AsyncMetricsDispatcher(
                buffer, ingest,
                java.time.Clock.systemUTC(),
                queueCapacity,
                Duration.ofMillis(50),     // tight retryInterval for fast tests
                Duration.ofMillis(200));   // short retryAfter for fast tests
    }

    // -----------------------------------------------------------------------
    // Happy path — offer → buffer → POST → delete
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("offer accepts envelope and returns true")
        void offer_accepts_and_returns_true() {
            wireDispatcher(RecordingIngestClient.alwaysAccepted());

            boolean accepted = dispatcher.offer(envelope(1L, "w-1", "GET /a"));

            assertThat(accepted).isTrue();
        }

        @Test
        @DisplayName("envelope flows through buffer, POSTs to ingest, buffer drains")
        void envelope_flows_through_buffer_to_ingest() {
            wireDispatcher(RecordingIngestClient.alwaysAccepted());

            dispatcher.offer(envelope(1L, "w-1", "GET /a"));

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        assertThat(ingest.callCount.get()).isGreaterThanOrEqualTo(1);
                        assertThat(buffer.depthEnvelopes()).isZero();
                    });
            assertThat(dispatcher.publishedCount()).isGreaterThanOrEqualTo(1L);
            assertThat(dispatcher.failedCount()).isZero();
        }

        @Test
        @DisplayName("offerAll accepts all envelopes when queue has room")
        void offerAll_accepts_all_when_room() {
            wireDispatcher(RecordingIngestClient.alwaysAccepted());

            int accepted = dispatcher.offerAll(List.of(
                    envelope(1L, "w-1", "GET /a"),
                    envelope(2L, "w-1", "GET /b"),
                    envelope(3L, "w-1", "GET /c")));

            assertThat(accepted).isEqualTo(3);
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes()).isZero());
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(dispatcher.publishedCount()).isEqualTo(3L));
        }
    }

    // -----------------------------------------------------------------------
    // Backpressure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("backpressure")
    class Backpressure {

        @Test
        @DisplayName("offer returns false when in-memory queue is full")
        void offer_returns_false_when_queue_full() {
            // An ingest client that never completes its futures — every publish
            // hangs, so the dispatch thread's buffer keeps growing but the
            // in-memory queue also backs up once handleNew slows down.
            RecordingIngestClient hanging = RecordingIngestClient.neverCompleting();
            wireDispatcher(hanging, /* queueCapacity */ 2);

            for (int i = 0; i < 2; i++) {
                dispatcher.offer(envelope(i, "w-1", "GET /a"));
            }
            int rejections = 0;
            for (int i = 0; i < 50; i++) {
                if (!dispatcher.offer(envelope(100 + i, "w-1", "GET /a"))) {
                    rejections++;
                }
            }

            assertThat(rejections).as("at least some offers must be rejected when queue is saturated")
                    .isGreaterThan(0);
        }
    }

    // -----------------------------------------------------------------------
    // Outcome mapping — RETRY / TERMINAL_REJECT
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ingest outcome mapping")
    class OutcomeMapping {

        @Test
        @DisplayName("RETRY (503) → envelope stays in buffer; sweeper republishes; later ACCEPTED drains it")
        void retry_then_accept_drains() {
            // First response 503, subsequent responses 202.
            AtomicInteger calls = new AtomicInteger();
            RecordingIngestClient flappy = RecordingIngestClient.fromSupplier(() ->
                    calls.incrementAndGet() == 1
                            ? HttpIngestResult.retry(503, "consumer down")
                            : HttpIngestResult.accepted());
            wireDispatcher(flappy);

            dispatcher.offer(envelope(1L, "w-1", "GET /a"));

            // First attempt fails with RETRY — envelope stays buffered.
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(flappy.callCount.get()).isGreaterThanOrEqualTo(1));

            // The retry sweeper (retryAfter=200ms) republishes → 202 → drains.
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes()).isZero());

            // The sweeper republished (call 2+) after the 503 — the fake's
            // call count is the retry evidence now that the counters are gone.
            assertThat(flappy.callCount.get()).isGreaterThanOrEqualTo(2);
            assertThat(dispatcher.publishedCount()).isGreaterThanOrEqualTo(1L);
        }

        @Test
        @DisplayName("TERMINAL_REJECT (400) → envelope deleted; failedCount bump")
        void terminal_reject_deletes_envelope() {
            wireDispatcher(RecordingIngestClient.alwaysReturning(
                    HttpIngestResult.terminalReject(400, "malformed")));

            dispatcher.offer(envelope(1L, "w-1", "GET /a"));

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(buffer.depthEnvelopes()).isZero());
            assertThat(dispatcher.failedCount()).isGreaterThanOrEqualTo(1L);
            assertThat(dispatcher.publishedCount()).isZero();
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
        void close_stops_thread() {
            wireDispatcher(RecordingIngestClient.alwaysAccepted());

            dispatcher.offer(envelope(1L, "w-1", "GET /a"));
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(ingest.callCount.get()).isGreaterThanOrEqualTo(1));

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

    @Nested
    @DisplayName("group routing + auth rejection (PRIVATE-CLOUD-ALIGNMENT Track 5)")
    class GroupAndAuth {

        @Test
        @DisplayName("the run's group reaches the ingest client with the envelope")
        void group_is_passed_through() {
            wireDispatcher(RecordingIngestClient.alwaysAccepted());
            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "cps");
            dispatcher.offer(envelope(2L, "w-1", "GET /a"));
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(ingest.callCount.get()).isEqualTo(2));
            assertThat(ingest.receivedGroups).containsExactly("cps", "(none)");
        }

        @Test
        @DisplayName("401 keeps the envelope buffered, counts it, and pauses posting for the auth-retry interval")
        void auth_reject_keeps_buffer_and_pauses() throws Exception {
            ingest = RecordingIngestClient.alwaysReturning(HttpIngestResult.authReject(401, "no token"));
            dispatcher = new AsyncMetricsDispatcher(buffer, ingest, java.time.Clock.systemUTC(),
                    AsyncMetricsDispatcher.DEFAULT_QUEUE_CAPACITY,
                    Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(30));
            dispatcher.offer(envelope(1L, "w-1", "GET /a"), "cps");
            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(dispatcher.authRejectedCount()).isEqualTo(1L));
            Thread.sleep(400);   // several retry sweeps — none may post while paused
            assertThat(ingest.callCount.get()).isEqualTo(1);
            assertThat(buffer.depthEnvelopes()).isEqualTo(1L);
            assertThat(dispatcher.failedCount()).isZero();
        }
    }

    /** Test fake recording ingest invocations and returning configurable outcomes. */
    private static final class RecordingIngestClient implements HttpIngestClient {
        final AtomicInteger callCount = new AtomicInteger();
        final ConcurrentLinkedQueue<WorkerMetricBatch> received = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> receivedGroups = new ConcurrentLinkedQueue<>();
        private final Supplier<HttpIngestResult> outcome; // null = never complete

        private RecordingIngestClient(Supplier<HttpIngestResult> outcome) { this.outcome = outcome; }

        static RecordingIngestClient alwaysAccepted() {
            return new RecordingIngestClient(HttpIngestResult::accepted);
        }
        static RecordingIngestClient alwaysReturning(HttpIngestResult fixed) {
            return new RecordingIngestClient(() -> fixed);
        }
        static RecordingIngestClient fromSupplier(Supplier<HttpIngestResult> supplier) {
            return new RecordingIngestClient(supplier);
        }
        static RecordingIngestClient neverCompleting() {
            return new RecordingIngestClient(null);
        }

        @Override
        public CompletableFuture<HttpIngestResult> send(WorkerMetricBatch envelope, String groupId) {
            callCount.incrementAndGet();
            received.add(envelope);
            receivedGroups.add(groupId == null ? "(none)" : groupId);
            if (outcome == null) {
                return new CompletableFuture<>(); // never completes
            }
            return CompletableFuture.completedFuture(outcome.get());
        }
    }

    private static WorkerMetricBatch envelope(long sec, String workerId, String label) {
        Map<String, Long> statusCodes = new HashMap<>();
        statusCodes.put("200", 1L);
        WorkerMetricEntry entry = new WorkerMetricEntry(
                label,
                1L,
                0L,
                0.0,
                10.0,
                10L,   // sumElapsedMs — 1 sample × 10 ms
                10.0,
                10.0,
                10.0,
                10.0,
                10.0,
                10.0,
                10L,
                100L,
                50L,
                statusCodes,
                1L);
        return new WorkerMetricBatch(
                sec,
                "2026/05/11 12:00:00",
                "us-east-1",
                workerId,
                "test-run",
                0L,
                List.of(entry));
    }
}
