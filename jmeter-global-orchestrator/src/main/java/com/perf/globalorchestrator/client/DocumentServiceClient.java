package com.perf.globalorchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.observability.ErrorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin HTTP client for document-service's blob API, used by the
 * scheduler to fetch a saved Template body at fire time. Uses the JDK
 * {@link HttpClient} (no extra deps), mirroring {@link LocalOrchestratorClient}.
 *
 * <p>document-service stores templates as ordinary blobs tagged
 * {@code X-Type=template}; {@code GET /api/v1/blob/{blobId}} returns the raw
 * stored bytes — for a template, the {@link TemplateBody} JSON. Anything other
 * than a 200 with a parseable body raises {@link TemplateUnavailableException},
 * which the controller maps to 400 at create time and {@code CronFireService}
 * records as a FAILED fire.
 */
@Component
public class DocumentServiceClient {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentServiceClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public DocumentServiceClient(
            ObjectMapper mapper,
            @Value("${documentService.baseUrl:http://document-service:8084}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Fetches the template body for {@code blobId}.
     *
     * @throws TemplateUnavailableException when the blob is missing (404),
     *         document-service errors (non-2xx), the body is not valid
     *         {@link TemplateBody} JSON, or document-service is unreachable.
     */
    public TemplateBody fetchTemplate(String blobId) {
        if (blobId == null || blobId.isBlank()) {
            throw new TemplateUnavailableException("templateBlobId is blank");
        }
        URI target = URI.create(baseUrl + "/api/v1/blob/"
                + URLEncoder.encode(blobId, StandardCharsets.UTF_8));
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Unreachable / interrupted — re-set the interrupt flag and surface
            // a typed failure so the caller can classify it.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            ErrorContext.logWarn(LOG,
                    "fetchTemplate blobId=" + blobId,
                    "document-service GET " + target + " failed",
                    e);
            throw new TemplateUnavailableException(
                    "document-service unreachable for template " + blobId + ": " + e);
        }
        if (resp.statusCode() == 404) {
            throw new TemplateUnavailableException("template not found: " + blobId);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new TemplateUnavailableException(
                    "document-service returned " + resp.statusCode() + " for template " + blobId);
        }
        try {
            TemplateBody body = mapper.readValue(resp.body(), TemplateBody.class);
            if (body.testPlanBlobId() == null || body.testPlanBlobId().isBlank()) {
                throw new TemplateUnavailableException(
                        "template " + blobId + " has no testPlanBlobId");
            }
            return body;
        } catch (TemplateUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateUnavailableException(
                    "template " + blobId + " body is not valid TemplateBody JSON: " + e.getMessage());
        }
    }

    /**
     * Readiness probe for the infra-readiness email.
     * Returns true when document-service's {@code /actuator/health} reports 2xx;
     * false on any non-2xx or unreachable. Never throws.
     */
    public boolean isHealthy() {
        URI target = URI.create(baseUrl + "/actuator/health");
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── HARD-DELETE / purge ────────────────────────────────────────────

    /** Page size for the blob listing scan; mirrors BlobController's cap. */
    private static final int LIST_PAGE = 500;
    /** Hard page cap (100k blobs) so a misbehaving listing can never loop forever. */
    private static final int LIST_MAX_PAGES = 200;

    /**
     * Lists the blobIds of every result blob belonging to {@code runId}.
     *
     * <p>Result blobs are uploaded with {@code X-Type=result} and a name shaped
     * {@code results-{runId}-{workerId}.jtl.gz}; since a runId is a hyphen-free
     * ULID, {@code results-{runId}-} is an unambiguous name prefix (the same scan
     * document-service's own {@code /blob/run/{runId}/archive} uses). Pages
     * through {@code GET /api/v1/blob?type=result} and filters by that prefix.
     *
     * @throws BlobAccessException when document-service is unreachable or returns
     *         a non-2xx — the caller decides whether to proceed (leaving the
     *         blobs as orphans for a future retention sweep) or abort.
     */
    public List<String> listResultBlobIds(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        String prefix = "results-" + runId + "-";
        List<String> blobIds = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < LIST_MAX_PAGES; page++) {
            URI target = URI.create(baseUrl + "/api/v1/blob?type=result&offset=" + offset
                    + "&limit=" + LIST_PAGE);
            HttpResponse<String> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(target)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new BlobAccessException("document-service GET " + target + " failed: " + e);
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BlobAccessException(
                        "document-service returned " + resp.statusCode() + " listing result blobs");
            }
            int countThisPage;
            try {
                JsonNode items = mapper.readTree(resp.body()).path("items");
                countThisPage = items.size();
                for (JsonNode item : items) {
                    String name = item.path("name").asText(null);
                    String blobId = item.path("blobId").asText(null);
                    if (blobId != null && name != null && name.startsWith(prefix)) {
                        blobIds.add(blobId);
                    }
                }
            } catch (Exception e) {
                throw new BlobAccessException("failed to parse blob listing: " + e.getMessage());
            }
            if (countThisPage < LIST_PAGE) break;   // last page
            offset += LIST_PAGE;
        }
        return blobIds;
    }

    /**
     * Deletes one blob. Idempotent on the document-service side (a missing blob
     * still returns 204), so a re-run of a partially-completed purge is safe.
     *
     * @throws BlobAccessException when document-service is unreachable or returns
     *         a non-2xx status.
     */
    public void deleteBlob(String blobId) {
        if (blobId == null || blobId.isBlank()) return;
        URI target = URI.create(baseUrl + "/api/v1/blob/"
                + URLEncoder.encode(blobId, StandardCharsets.UTF_8));
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(REQUEST_TIMEOUT)
                    .DELETE()
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BlobAccessException("document-service DELETE " + target + " failed: " + e);
        }
        // 404 counts as success — the blob is already gone, which is the goal.
        if (resp.statusCode() != 404 && (resp.statusCode() < 200 || resp.statusCode() >= 300)) {
            throw new BlobAccessException(
                    "document-service returned " + resp.statusCode() + " deleting blob " + blobId);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * document-service was unreachable or errored on a blob list/delete during a
     * purge. The purge service catches this, records the blob step as
     * incomplete, and proceeds with the DB cleanup (orphaned blobs are reclaimed
     * by a future retention sweep) rather than wedging the whole purge.
     */
    public static class BlobAccessException extends RuntimeException {
        public BlobAccessException(String message) { super(message); }
    }

    /**
     * The template could not be fetched or parsed. Controller maps to 400
     * {@code TEMPLATE_UNAVAILABLE} at create/update; a fire records FAILED.
     */
    public static class TemplateUnavailableException extends RuntimeException {
        public TemplateUnavailableException(String message) { super(message); }
    }
}
