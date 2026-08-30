package com.perf.orchestrator.storage;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link ResultSink} that PUTs the gzipped JTL to the configured Document
 * Service. Compiled only under {@code -Pstorage-docservice} so the default
 * fat JAR doesn't carry a class that references unused config fields.
 *
 * <p>Uses {@link HttpClient} from the JDK so there's no new direct
 * dependency — the dep budget stays at 2/3.
 *
 * <h2>Request shape</h2>
 * Posts to the Document Service's canonical blob API ({@code POST /api/v1/blob})
 * so the result lands as a first-class, app-tagged blob — discoverable in the
 * Documents tab and aggregatable by the "download all results for this run"
 * endpoint.
 * <pre>
 *   POST ${DOCUMENT_SERVICE_URL}/api/v1/blob
 *   Content-Type:  application/octet-stream
 *   X-Type:        result
 *   X-Application: ${application}            (omitted when the run is untagged)
 *   X-Name:        results-${runId}-${workerId}.jtl.gz
 *   X-Run-Id:      ${runId}
 *   X-Worker-Id:   ${workerId}
 *   ${DOCUMENT_SERVICE_AUTH_HEADER}          (verbatim, when configured)
 *   Body: <gzipped JTL bytes, streamed from disk>
 * </pre>
 *
 * <h2>Response handling</h2>
 * 2xx → success; the response body is treated as opaque text.
 * Anything else → {@link IOException} so the caller's retry logic kicks in.
 */
public final class DocumentServiceResultSink implements ResultSink {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentServiceResultSink.class);

    private final URI uploadEndpoint;
    private final String authHeader;
    private final HttpClient client;
    private final Duration requestTimeout;

    public DocumentServiceResultSink(OrchestratorConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getDocumentServiceUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "DOCUMENT_SERVICE_URL must be set when constructing DocumentServiceResultSink");
        }
        String base = config.getDocumentServiceUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.uploadEndpoint = URI.create(base + "/api/v1/blob");
        this.authHeader     = config.getDocumentServiceAuthHeader();
        this.requestTimeout = Duration.ofSeconds(config.getDocumentServiceTimeoutSeconds());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public UploadResult upload(String application, String runId, String workerId, Path file) throws IOException {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            throw new IOException("upload file does not exist: " + file);
        }

        long size = Files.size(file);
        String docName = "results-" + runId + "-" + workerId + ".jtl.gz";

        HttpRequest.Builder b = HttpRequest.newBuilder(uploadEndpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/octet-stream")
                .header("X-Type",      "result")
                .header("X-Name",      docName)
                .header("X-Run-Id",    runId)
                .header("X-Worker-Id", workerId)
                .POST(HttpRequest.BodyPublishers.ofFile(file));
        if (application != null && !application.isBlank()) {
            b.header("X-Application", application);
        }

        applyAuthHeader(b, authHeader);

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("upload interrupted", ie);
        }

        long durationMs = System.currentTimeMillis() - start;
        int status = response.statusCode();
        if (status / 100 != 2) {
            throw new IOException("Document Service rejected upload (HTTP " + status + "): "
                    + truncate(response.body(), 256));
        }
        String target = "doc-service://" + docName;
        LOG.debug("Uploaded {} bytes to {} in {} ms", size, target, durationMs);
        return UploadResult.uploaded(target, size, durationMs);
    }

    /**
     * Splits the verbatim {@code DOCUMENT_SERVICE_AUTH_HEADER} value at the
     * first colon so we can use {@link HttpRequest.Builder#header(String, String)}.
     * Empty / blank values are ignored so unauthenticated services Just Work.
     */
    static void applyAuthHeader(HttpRequest.Builder b, String raw) {
        if (raw == null || raw.isBlank()) return;
        int colon = raw.indexOf(':');
        if (colon <= 0) {
            // Header value with no colon — assume the operator pasted "Bearer xxx"
            // and meant "Authorization: Bearer xxx".
            b.header("Authorization", raw.trim());
            return;
        }
        b.header(raw.substring(0, colon).trim(), raw.substring(colon + 1).trim());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
