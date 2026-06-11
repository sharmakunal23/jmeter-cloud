package com.perf.orchestrator.storage;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ArtifactSource} that fetches blobs from the Document Service via
 * {@code GET ${DOCUMENT_SERVICE_URL}/api/v1/blob/{blobId}}. Compiled only
 * under {@code -Pstorage-docservice}; sibling of the existing
 * {@link DocumentServiceResultSink} (upload path).
 *
 * <p><b>The bug this fixes (2026-05-10).</b> Before this class was wired,
 * the orchestrator only updated its {@code plan.jmx} when something
 * directly POSTed to {@code /api/v1/testPlan}. The global-orchestrator's
 * {@code POST /api/v1/runs} kicked the run with whatever was already
 * staged on the pod — operators uploading a fresh blob via {@code /blobs}
 * + launching from {@code /runs/new} silently ran the previous test
 * plan. With this source wired in, the orchestrator pulls the right
 * blob from document-service whenever {@link FetchSpec#params()} carries
 * a {@code blobId}.
 *
 * <p><b>FetchSpec contract.</b> Caller passes
 * {@code FetchSpec(runId, Map.of("blobId", "01J…"))}. Empty / missing
 * {@code blobId} → {@link Optional#empty()} (legacy flow: caller falls
 * back to the locally-staged file).
 *
 * <p><b>Streaming.</b> Returns an {@link InputStream} of the blob bytes;
 * the caller (typically {@code ArtifactStager.storeTestPlan}) is
 * responsible for closing it. The wrapped {@link HttpResponse.BodyHandlers#ofInputStream}
 * keeps the body unbuffered so multi-MB plans don't materialise in
 * memory before staging.
 */
public final class DocumentServiceArtifactSource implements ArtifactSource {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentServiceArtifactSource.class);

    private final URI baseUri;
    private final String authHeader;
    private final HttpClient client;
    private final Duration requestTimeout;

    public DocumentServiceArtifactSource(OrchestratorConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getDocumentServiceUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "DOCUMENT_SERVICE_URL must be set when constructing DocumentServiceArtifactSource");
        }
        String base = config.getDocumentServiceUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUri        = URI.create(base + "/api/v1/blob/");
        this.authHeader     = config.getDocumentServiceAuthHeader();
        this.requestTimeout = Duration.ofSeconds(config.getDocumentServiceTimeoutSeconds());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * @param kind {@link ArtifactSource#KIND_TEST_PLAN} or
     *             {@link ArtifactSource#KIND_DATA_FILES} — currently
     *             ignored by this implementation (the blobId in the
     *             spec uniquely identifies the artifact). Logged for
     *             traceability.
     * @param spec must carry a {@code "blobId"} key in
     *             {@link FetchSpec#params()}; missing → empty Optional.
     */
    @Override
    public Optional<InputStream> fetch(String kind, FetchSpec spec) throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(spec, "spec");
        Map<String, String> params = spec.params();
        String blobId = params.get("blobId");
        if (blobId == null || blobId.isBlank()) {
            // Empty Optional is the correct "no artifact configured for
            // this kind" signal — the caller will fall back to the
            // locally-staged file (preserves the legacy HTTP_UPLOAD path).
            return Optional.empty();
        }

        URI target = baseUri.resolve(URLEncoder.encode(blobId, StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .GET();
        if (authHeader != null && !authHeader.isBlank()) {
            // Format expected: "Authorization: Bearer …" or any other
            // header pair separated by ": ". Mirrors the DocumentServiceResultSink
            // contract for symmetry.
            int sep = authHeader.indexOf(':');
            if (sep > 0) {
                b.header(authHeader.substring(0, sep).trim(),
                         authHeader.substring(sep + 1).trim());
            }
        }

        LOG.info("Fetching {} blob {} for run {}", kind, blobId, spec.runId());
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + kind + " blob " + blobId, ie);
        }
        int sc = resp.statusCode();
        if (sc == 404) {
            // 404 surfaces as IOException so the caller can map to a
            // start-rejection (NO_TEST_PLAN-style); we don't silently
            // fall through to "use the stale local plan" — that's the
            // exact bug this class exists to prevent.
            try { resp.body().close(); } catch (IOException ignore) { /* best-effort */ }
            throw new IOException("Document Service has no blob with id " + blobId
                    + " (HTTP 404). Verify the blobId and that the upload completed.");
        }
        if (sc < 200 || sc >= 300) {
            try { resp.body().close(); } catch (IOException ignore) { /* best-effort */ }
            throw new IOException("Document Service returned HTTP " + sc
                    + " for blob " + blobId);
        }
        return Optional.of(resp.body());
    }
}
