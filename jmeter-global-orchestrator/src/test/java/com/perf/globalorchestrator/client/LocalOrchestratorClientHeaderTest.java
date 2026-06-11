package com.perf.globalorchestrator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBSERVABILITY Phase D — verifies the X-Run-Id header is set on
 * outbound fanout calls. Uses the JDK's bundled HttpServer (zero new
 * deps) to capture inbound headers without booting WireMock.
 *
 * <p>This test runs against a real socket, not a mock — that catches
 * regressions where the header is added to the builder but the builder
 * isn't passed forward (e.g. someone reverts to the local {@code req}
 * variable).
 */
class LocalOrchestratorClientHeaderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> capturedRunIdHeader = new AtomicReference<>();
    private LocalOrchestratorClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedRunIdHeader.set(exchange.getRequestHeaders().getFirst("X-Run-Id"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new LocalOrchestratorClient(new ObjectMapper());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void startTestSetsXRunIdHeaderWhenRunIdProvided() {
        client.startTest("run-abc-123", baseUrl, Map.of("foo", "bar"));
        assertThat(capturedRunIdHeader.get()).isEqualTo("run-abc-123");
    }

    @Test
    void startTestOmitsXRunIdHeaderWhenRunIdNull() {
        client.startTest(null, baseUrl, Map.of("foo", "bar"));
        assertThat(capturedRunIdHeader.get()).isNull();
    }

    @Test
    void startTestOmitsXRunIdHeaderWhenRunIdBlank() {
        client.startTest("   ", baseUrl, Map.of("foo", "bar"));
        assertThat(capturedRunIdHeader.get()).isNull();
    }

    @Test
    void drainTestSetsXRunIdHeaderWhenRunIdProvided() {
        client.drainTest("run-xyz-789", baseUrl);
        assertThat(capturedRunIdHeader.get()).isEqualTo("run-xyz-789");
    }

    @Test
    void drainTestOmitsXRunIdHeaderWhenRunIdNull() {
        client.drainTest(null, baseUrl);
        assertThat(capturedRunIdHeader.get()).isNull();
    }
}
