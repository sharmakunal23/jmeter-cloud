package com.perf.orchestrator.storage;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Behavior tests for {@link DocumentServiceArtifactSource} — exercises
 * the bug-fix that wires document-service blob fetches into the
 * pre-run staging path. Verifies the request shape (URL, method, auth
 * header) and the error mappings (404 → IOException with
 * "no blob with id…" so the start-rejection envelope is informative).
 */
@DisplayName("DocumentServiceArtifactSource — fetch shape + error mapping")
class DocumentServiceArtifactSourceTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastPath   = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastAuth   = new AtomicReference<>();
    private final AtomicInteger callCount            = new AtomicInteger(0);
    private volatile int      handlerStatus = 200;
    private volatile byte[]   handlerBody   = new byte[0];

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // Single context whose handler reads `handlerStatus` / `handlerBody`
        // — tests mutate those fields rather than re-registering contexts
        // (HttpServer can't remove a context that wasn't created yet, and
        // this avoids the brittleness of recreating it per test).
        server.createContext("/api/v1/blob/", ex -> {
            callCount.incrementAndGet();
            lastPath.set(ex.getRequestURI().getPath());
            lastMethod.set(ex.getRequestMethod());
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(handlerStatus, handlerBody.length);
            try (var os = ex.getResponseBody()) { os.write(handlerBody); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private OrchestratorConfig configFor(String authHeader) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "doc-art-src-test",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done"
        ));
        env.put("DOCUMENT_SERVICE_URL", "http://127.0.0.1:" + port);
        env.put("DOCUMENT_SERVICE_TIMEOUT_SECONDS", "5");
        if (authHeader != null) env.put("DOCUMENT_SERVICE_AUTH_HEADER", authHeader);
        return OrchestratorConfig.from(env);
    }

    private void serve(int status, byte[] body) {
        handlerStatus = status;
        handlerBody = body;
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("GET /api/v1/blob/{id} → returns the bytes as an InputStream")
        void fetches_blob_by_id() throws IOException {
            byte[] payload = "<jmeterTestPlan>...</jmeterTestPlan>".getBytes(StandardCharsets.UTF_8);
            serve(200, payload);

            DocumentServiceArtifactSource src = new DocumentServiceArtifactSource(configFor(null));
            Optional<InputStream> result = src.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of("blobId", "01J0BLOB123")));

            assertThat(result).isPresent();
            byte[] read;
            try (InputStream in = result.get()) {
                read = in.readAllBytes();
            }
            assertSoftly(softly -> {
                softly.assertThat(read).isEqualTo(payload);
                softly.assertThat(lastMethod.get()).isEqualTo("GET");
                softly.assertThat(lastPath.get()).isEqualTo("/api/v1/blob/01J0BLOB123");
                softly.assertThat(callCount.get()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("forwards configured auth header verbatim (DOCUMENT_SERVICE_AUTH_HEADER)")
        void forwards_auth_header() throws IOException {
            serve(200, new byte[0]);

            DocumentServiceArtifactSource src = new DocumentServiceArtifactSource(
                    configFor("Authorization: Bearer abc123"));
            try (InputStream in = src.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of("blobId", "01J0BLOB"))).orElseThrow()) {
                in.readAllBytes();
            }

            assertThat(lastAuth.get()).isEqualTo("Bearer abc123");
        }
    }

    @Nested
    @DisplayName("missing-blobId path")
    class MissingBlobId {

        @Test
        @DisplayName("FetchSpec without a blobId → empty Optional (no HTTP call) — preserves legacy HTTP_UPLOAD flow")
        void empty_when_no_blob_id() throws IOException {
            // Server intentionally NOT registered — if the fetch tried
            // a request the test would 404 + the assertion below would fail.
            DocumentServiceArtifactSource src = new DocumentServiceArtifactSource(configFor(null));
            Optional<InputStream> result = src.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of()));

            assertSoftly(softly -> {
                softly.assertThat(result).isEmpty();
                softly.assertThat(callCount.get()).isZero();
            });
        }

        @Test
        @DisplayName("blank blobId is treated the same as missing")
        void empty_when_blank_blob_id() throws IOException {
            DocumentServiceArtifactSource src = new DocumentServiceArtifactSource(configFor(null));
            assertThat(src.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of("blobId", "")))).isEmpty();
            assertThat(callCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("error mapping")
    class ErrorMapping {

        @Test
        @DisplayName("404 → IOException with a hint about verifying the blobId — never silently falls back to a stale local plan")
        void four_oh_four_throws_with_hint() {
            serve(404, "not found".getBytes(StandardCharsets.UTF_8));
            DocumentServiceArtifactSource src = new DocumentServiceArtifactSource(configFor(null));

            assertThatThrownBy(() -> src.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of("blobId", "01J0NOTFOUND"))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no blob with id 01J0NOTFOUND")
                    .hasMessageContaining("HTTP 404");
        }

        @Test
        @DisplayName("5xx → IOException with the status code so the run-launch surface can map to ARTIFACT_FETCH_FAILED")
        void five_xx_throws() {
            serve(503, new byte[0]);
            DocumentServiceArtifactSource src = new DocumentServiceArtifactSource(configFor(null));

            assertThatThrownBy(() -> src.fetch(
                    ArtifactSource.KIND_TEST_PLAN,
                    new FetchSpec("run-1", Map.of("blobId", "01J0BLOB"))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("HTTP 503");
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {

        @Test
        @DisplayName("blank DOCUMENT_SERVICE_URL → IllegalArgumentException at construction (fail fast)")
        void blank_url_fails_fast() {
            Map<String, String> env = new HashMap<>(Map.of(
                    "POD_NAME",            "jmeter-worker-0",
                    "TEST_REGION",         "us-east-1",
                    "RUN_ID",              "doc-art-src-test",
                    "JTL_PATH",            "/results/results.jtl",
                    "SENTINEL_PATH",       "/results/.done"
            ));
            env.put("DOCUMENT_SERVICE_URL", "");

            OrchestratorConfig cfg = OrchestratorConfig.from(env);
            assertThatThrownBy(() -> new DocumentServiceArtifactSource(cfg))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCUMENT_SERVICE_URL must be set");
        }
    }
}
