package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("JdkHttpFallbackClient")
class JdkHttpFallbackClientTest {

    private HttpServer server;
    private int port;
    private JdkHttpFallbackClient client;

    @BeforeEach
    void setUp() throws IOException {
        // Bind to ephemeral port (0). The JDK HttpServer is single-threaded
        // by default with executor=null — fine for these tests.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (client != null) client.close();
    }

    private JdkHttpFallbackClient newClient() {
        client = new JdkHttpFallbackClient(
                "http://127.0.0.1:" + port + "/api/v1/ingest",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        return client;
    }

    @Nested
    @DisplayName("status code mapping")
    class StatusMapping {

        @Test
        @DisplayName("202 → ACCEPTED")
        void status_202_maps_to_accepted() throws Exception {
            startServerReturning(202);
            HttpFallbackResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpFallbackResult.Outcome.ACCEPTED);
            assertThat(result.statusCode()).isEqualTo(202);
        }

        @Test
        @DisplayName("400 → TERMINAL_REJECT (don't retry — payload is corrupt)")
        void status_400_maps_to_terminal_reject() throws Exception {
            startServerReturning(400);
            HttpFallbackResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpFallbackResult.Outcome.TERMINAL_REJECT);
            assertThat(result.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("413 → TERMINAL_REJECT (don't retry — too large for endpoint)")
        void status_413_maps_to_terminal_reject() throws Exception {
            startServerReturning(413);
            HttpFallbackResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpFallbackResult.Outcome.TERMINAL_REJECT);
            assertThat(result.statusCode()).isEqualTo(413);
        }

        @Test
        @DisplayName("503 → RETRY (transient — sweep will pick up later)")
        void status_503_maps_to_retry() throws Exception {
            startServerReturning(503);
            HttpFallbackResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpFallbackResult.Outcome.RETRY);
            assertThat(result.statusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("500 → RETRY (any unexpected status maps to retry, not terminal)")
        void status_500_maps_to_retry() throws Exception {
            startServerReturning(500);
            HttpFallbackResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpFallbackResult.Outcome.RETRY);
        }
    }

    @Nested
    @DisplayName("network errors")
    class NetworkErrors {

        @Test
        @DisplayName("connection refused (server not started) → RETRY")
        void connection_refused_maps_to_retry() throws Exception {
            // Don't start the server — the bind from setUp gives us a port number,
            // but server.start() is what makes it accept connections. Skip start().
            HttpFallbackResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpFallbackResult.Outcome.RETRY);
            assertThat(result.statusCode()).isEqualTo(0);
            assertThat(result.detail()).contains("io:");
        }
    }

    @Nested
    @DisplayName("request shape")
    class RequestShape {

        @Test
        @DisplayName("POSTs application/avro with the envelope's serialised binary")
        void posts_avro_binary() throws Exception {
            AtomicReference<String> capturedContentType = new AtomicReference<>();
            AtomicReference<String> capturedMethod = new AtomicReference<>();
            AtomicReference<String> capturedPath = new AtomicReference<>();
            AtomicInteger capturedBodyBytes = new AtomicInteger();

            startServer(exchange -> {
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                capturedMethod.set(exchange.getRequestMethod());
                capturedPath.set(exchange.getRequestURI().getPath());
                byte[] body = exchange.getRequestBody().readAllBytes();
                capturedBodyBytes.set(body.length);
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });

            newClient().send(envelope(1L)).get();

            assertSoftly(softly -> {
                softly.assertThat(capturedMethod.get()).isEqualTo("POST");
                softly.assertThat(capturedPath.get()).isEqualTo("/api/v1/ingest");
                softly.assertThat(capturedContentType.get()).isEqualTo("application/avro");
                softly.assertThat(capturedBodyBytes.get())
                        .as("body should contain non-zero Avro binary")
                        .isGreaterThan(0);
            });
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void startServerReturning(int status) {
        startServer(exchange -> {
            // Drain the body so the connection can complete cleanly.
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    private void startServer(HttpHandler handler) {
        server.createContext("/api/v1/ingest", handler);
        server.setExecutor(null);
        server.start();
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
                .setWorkerId("worker-1")
                .setRunId("test-run")
                .setEntries(List.of(entry))
                .build();
    }
}
