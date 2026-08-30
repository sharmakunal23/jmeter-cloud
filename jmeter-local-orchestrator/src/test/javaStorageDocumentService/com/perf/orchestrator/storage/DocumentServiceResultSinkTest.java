package com.perf.orchestrator.storage;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Verifies the production {@link DocumentServiceResultSink} against a JDK
 * {@link HttpServer} stub — no third-party HTTP-mock dep, no real network.
 * Lives under {@code src/test/javaStorageDocumentService/} so it's only built
 * with {@code -Pstorage-docservice}, mirroring its production counterpart.
 */
@DisplayName("DocumentServiceResultSink — PUT shape, retries, header injection")
class DocumentServiceResultSinkTest {

    @TempDir Path tempDir;

    private HttpServer stub;
    private int port;

    @BeforeEach
    void start_stub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = stub.getAddress().getPort();
        // Each test installs its own context handler.
    }

    @AfterEach
    void stop_stub() {
        if (stub != null) stub.stop(0);
    }

    // -----------------------------------------------------------------------
    // PUT shape
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("request shape")
    class RequestShape {

        @Test
        @DisplayName("uploads the gzipped JTL with the documented headers and 2xx status returns the target URL")
        void posts_documented_shape_and_returns_target() throws Exception {
            AtomicReference<RecordedRequest> seen = new AtomicReference<>();
            stub.createContext("/api/v1/blob", exchange -> {
                seen.set(record(exchange));
                respond(exchange, 201, "{\"blobId\":\"01HBLOB\"}");
            });
            stub.start();

            Path file = writeFile("hello-gz");
            DocumentServiceResultSink sink = new DocumentServiceResultSink(configWith(Map.of(
                    "DOCUMENT_SERVICE_URL",         "http://127.0.0.1:" + port,
                    "DOCUMENT_SERVICE_AUTH_HEADER", "Authorization: Bearer secret",
                    "DOCUMENT_SERVICE_TIMEOUT_S",   "5",
                    "DOCUMENT_SERVICE_RETRY_COUNT", "0",
                    "RESULT_SINK",                  "DOCUMENT_SERVICE",
                    "AUTO_UPLOAD_RESULTS",          "true")));

            UploadResult result = sink.upload("checkout-svc", "run-9", "worker-0", file);
            long expectedSize = Files.size(file);
            byte[] expectedBytes = Files.readAllBytes(file);
            RecordedRequest req = seen.get();

            assertSoftly(softly -> {
                softly.assertThat(result.skipped()).isFalse();
                softly.assertThat(result.target()).isEqualTo("doc-service://results-run-9-worker-0.jtl.gz");
                softly.assertThat(result.sizeBytes()).isEqualTo(expectedSize);
                softly.assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);

                softly.assertThat(req.method).isEqualTo("POST");
                softly.assertThat(req.headers.get("X-Type")).isEqualTo(List.of("result"));
                softly.assertThat(req.headers.get("X-Application")).isEqualTo(List.of("checkout-svc"));
                softly.assertThat(req.headers.get("X-Name"))
                        .isEqualTo(List.of("results-run-9-worker-0.jtl.gz"));
                softly.assertThat(req.headers.get("X-Run-Id")).isEqualTo(List.of("run-9"));
                softly.assertThat(req.headers.get("X-Worker-Id")).isEqualTo(List.of("worker-0"));
                softly.assertThat(req.headers.get("Authorization"))
                        .as("auth header split at first colon and applied verbatim")
                        .isEqualTo(List.of("Bearer secret"));
                softly.assertThat(req.bodyBytes).isEqualTo(expectedBytes);
            });
        }
    }

    // -----------------------------------------------------------------------
    // Errors
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("non-2xx response surfaces as IOException — caller's retry logic handles it")
        void non_2xx_throws_ioexception() throws Exception {
            stub.createContext("/api/v1/blob", ex -> respond(ex, 500, "internal error"));
            stub.start();

            Path file = writeFile("data");
            DocumentServiceResultSink sink = new DocumentServiceResultSink(configWith(Map.of(
                    "DOCUMENT_SERVICE_URL", "http://127.0.0.1:" + port,
                    "DOCUMENT_SERVICE_TIMEOUT_S", "5",
                    "RESULT_SINK", "DOCUMENT_SERVICE",
                    "AUTO_UPLOAD_RESULTS", "true",
                    "DOCUMENT_SERVICE_RETRY_COUNT", "0")));

            assertThatThrownBy(() -> sink.upload("checkout-svc", "run-1", "worker-0", file))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 500");
        }

        @Test
        @DisplayName("missing file → IOException without making any network call")
        void missing_file_throws_immediately() {
            DocumentServiceResultSink sink = new DocumentServiceResultSink(configWith(Map.of(
                    "DOCUMENT_SERVICE_URL", "http://127.0.0.1:" + port,
                    "DOCUMENT_SERVICE_TIMEOUT_S", "5",
                    "RESULT_SINK", "DOCUMENT_SERVICE",
                    "AUTO_UPLOAD_RESULTS", "true",
                    "DOCUMENT_SERVICE_RETRY_COUNT", "0")));

            assertThatThrownBy(() -> sink.upload("app", "r", "w", tempDir.resolve("nope.gz")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    // -----------------------------------------------------------------------
    // Auth header parsing
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("auth header parsing")
    class AuthHeaderParsing {

        @Test
        @DisplayName("a value without ':' is treated as the Authorization value (Bearer <token>)")
        void colonless_value_becomes_authorization() {
            HttpRequest.Builder b = HttpRequest.newBuilder(java.net.URI.create("http://x"));
            DocumentServiceResultSink.applyAuthHeader(b, "Bearer xyz");
            HttpRequest req = b.GET().build();

            assertThat(req.headers().firstValue("Authorization")).hasValue("Bearer xyz");
        }

        @Test
        @DisplayName("blank or null value adds no header — unauthenticated services Just Work")
        void blank_value_adds_no_header() {
            HttpRequest.Builder b1 = HttpRequest.newBuilder(java.net.URI.create("http://x"));
            HttpRequest.Builder b2 = HttpRequest.newBuilder(java.net.URI.create("http://x"));
            DocumentServiceResultSink.applyAuthHeader(b1, null);
            DocumentServiceResultSink.applyAuthHeader(b2, "");

            assertSoftly(softly -> {
                softly.assertThat(b1.GET().build().headers().map()).doesNotContainKey("Authorization");
                softly.assertThat(b2.GET().build().headers().map()).doesNotContainKey("Authorization");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Construction guard
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects an empty DOCUMENT_SERVICE_URL — fail at boot, not on the first upload")
        void rejects_blank_url() {
            assertThatThrownBy(() -> new DocumentServiceResultSink(configWith(Map.of(
                    "DOCUMENT_SERVICE_URL", "",
                    "RESULT_SINK", "DOCUMENT_SERVICE"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCUMENT_SERVICE_URL");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path writeFile(String contents) throws IOException {
        Path f = tempDir.resolve("results.jtl.gz");
        Files.writeString(f, contents);
        return f;
    }

    private static OrchestratorConfig configWith(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "doc-test",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done"
        ));
        env.putAll(overrides);
        return OrchestratorConfig.from(env);
    }

    private record RecordedRequest(String method, Map<String, List<String>> headers, byte[] bodyBytes) { }

    private static RecordedRequest record(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders(),
                    in.readAllBytes());
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
