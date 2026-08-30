package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.model.WorkerMetricEntry;
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

@DisplayName("JdkHttpIngestClient")
class JdkHttpIngestClientTest {

    private HttpServer server;
    private int port;
    private JdkHttpIngestClient client;

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

    private JdkHttpIngestClient newClient() {
        client = new JdkHttpIngestClient(
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
            HttpIngestResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.ACCEPTED);
            assertThat(result.statusCode()).isEqualTo(202);
        }

        @Test
        @DisplayName("400 → TERMINAL_REJECT (don't retry — payload is corrupt)")
        void status_400_maps_to_terminal_reject() throws Exception {
            startServerReturning(400);
            HttpIngestResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.TERMINAL_REJECT);
            assertThat(result.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("413 → TERMINAL_REJECT (don't retry — too large for endpoint)")
        void status_413_maps_to_terminal_reject() throws Exception {
            startServerReturning(413);
            HttpIngestResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.TERMINAL_REJECT);
            assertThat(result.statusCode()).isEqualTo(413);
        }

        @Test
        @DisplayName("503 → RETRY (transient — sweep will pick up later)")
        void status_503_maps_to_retry() throws Exception {
            startServerReturning(503);
            HttpIngestResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.RETRY);
            assertThat(result.statusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("500 → RETRY (any unexpected status maps to retry, not terminal)")
        void status_500_maps_to_retry() throws Exception {
            startServerReturning(500);
            HttpIngestResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.RETRY);
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
            HttpIngestResult result = newClient().send(envelope(1L)).get();

            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.RETRY);
            assertThat(result.statusCode()).isEqualTo(0);
            assertThat(result.detail()).contains("io:");
        }
    }

    @Nested
    @DisplayName("request shape")
    class RequestShape {

        @Test
        @DisplayName("POSTs application/json whose body decodes back into the envelope")
        void posts_json_body() throws Exception {
            AtomicReference<String> capturedContentType = new AtomicReference<>();
            AtomicReference<String> capturedMethod = new AtomicReference<>();
            AtomicReference<String> capturedPath = new AtomicReference<>();
            AtomicReference<byte[]> capturedBody = new AtomicReference<>();

            startServer(exchange -> {
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                capturedMethod.set(exchange.getRequestMethod());
                capturedPath.set(exchange.getRequestURI().getPath());
                capturedBody.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });

            newClient().send(envelope(1L)).get();

            // JSON-INGEST: the wire is readable — decode the captured body and
            // assert the envelope round-trips, not just that bytes flowed.
            com.perf.orchestrator.model.WorkerMetricBatch decoded =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(capturedBody.get(),
                                    com.perf.orchestrator.model.WorkerMetricBatch.class);

            assertSoftly(softly -> {
                softly.assertThat(capturedMethod.get()).isEqualTo("POST");
                softly.assertThat(capturedPath.get()).isEqualTo("/api/v1/ingest");
                softly.assertThat(capturedContentType.get()).isEqualTo("application/json");
                softly.assertThat(decoded.windowSecond()).isEqualTo(1L);
                softly.assertThat(decoded.entries()).hasSize(1);
            });
        }
    }

    @Nested
    @DisplayName("group routing + bearer (PRIVATE-CLOUD-ALIGNMENT Track 5)")
    class GroupAndAuth {

        @Test
        @DisplayName("endpointFor appends ?groupId=, replaces an existing one, and leaves the URL alone without a group")
        void endpoint_for_group() {
            java.net.URI base = java.net.URI.create("http://c:8083/api/v1/ingest");
            assertThat(JdkHttpIngestClient.endpointFor(base, null)).isEqualTo(base);
            assertThat(JdkHttpIngestClient.endpointFor(base, "cps").toString())
                    .isEqualTo("http://c:8083/api/v1/ingest?groupId=cps");
            assertThat(JdkHttpIngestClient.endpointFor(java.net.URI.create("http://c:8083/api/v1/ingest?groupId=demo"), "cps").toString())
                    .isEqualTo("http://c:8083/api/v1/ingest?groupId=cps");
            assertThat(JdkHttpIngestClient.endpointFor(java.net.URI.create("http://c:8083/api/v1/ingest?x=1&groupId=demo"), "cps").toString())
                    .isEqualTo("http://c:8083/api/v1/ingest?x=1&groupId=cps");
        }

        @Test
        @DisplayName("the POST carries ?groupId= and the Authorization header verbatim")
        void posts_group_and_auth() throws Exception {
            AtomicReference<String> query = new AtomicReference<>();
            AtomicReference<String> auth = new AtomicReference<>();
            startServer(exchange -> {
                query.set(exchange.getRequestURI().getRawQuery());
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });
            client = new JdkHttpIngestClient("http://127.0.0.1:" + port + "/api/v1/ingest",
                    "Bearer s3cret", Duration.ofSeconds(2), Duration.ofSeconds(2));
            HttpIngestResult result = client.send(envelope(1L), "cps").get();
            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.ACCEPTED);
            assertThat(query.get()).isEqualTo("groupId=cps");
            assertThat(auth.get()).isEqualTo("Bearer s3cret");
        }

        @Test
        @DisplayName("401 / 403 map to AUTH_REJECT — the data stays buffered")
        void auth_statuses_map_to_auth_reject() throws Exception {
            startServerReturning(401);
            HttpIngestResult result = newClient().send(envelope(1L)).get();
            assertThat(result.outcome()).isEqualTo(HttpIngestResult.Outcome.AUTH_REJECT);
            assertThat(result.statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("415 maps to TERMINAL_REJECT; 429 maps to RETRY")
        void media_type_and_throttle() throws Exception {
            startServerReturning(415);
            assertThat(newClient().send(envelope(1L)).get().outcome()).isEqualTo(HttpIngestResult.Outcome.TERMINAL_REJECT);
            server.stop(0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            startServerReturning(429);
            assertThat(newClient().send(envelope(1L)).get().outcome()).isEqualTo(HttpIngestResult.Outcome.RETRY);
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
        WorkerMetricEntry entry = new WorkerMetricEntry(
                "GET /api/test",
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
                "worker-1",
                "test-run",
                0L,
                List.of(entry));
    }
}
