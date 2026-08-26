package com.perf.orchestrator.buffer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.orchestrator.model.WorkerMetricBatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link HttpIngestClient} backed by the JDK's {@link HttpClient} — zero new
 * dependencies, async by construction.
 *
 * <h2>Wire format</h2>
 * JSON with {@code Content-Type: application/json}. The canonical schema is
 * the metrics-consumer's
 * {@code api/openapi.yaml}; both sides keep structurally identical records
 * pinned by the shared golden-payload test, and the shape is fixed per
 * deploy (no registry — a mismatch is a coordinated-rebuild bug).
 */
public final class JdkHttpIngestClient implements HttpIngestClient {

    private static final Logger LOG = Logger.getLogger(JdkHttpIngestClient.class.getName());

    private static final String JSON_CONTENT_TYPE = "application/json";
    /** Jackson mapper is thread-safe — share a singleton. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JdkHttpIngestClient(String endpointUrl, Duration connectTimeout, Duration requestTimeout) {
        Objects.requireNonNull(endpointUrl, "endpointUrl cannot be null");
        Objects.requireNonNull(connectTimeout, "connectTimeout cannot be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout cannot be null");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.endpoint = URI.create(endpointUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public CompletableFuture<HttpIngestResult> send(WorkerMetricBatch envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            // Serialisation should never fail for a record built by our own
            // aggregator (all doubles are finite by construction — JI-7).
            // Fail this attempt with RETRY so the retry path doesn't loop forever
            // on a single bad envelope — but log loudly.
            LOG.log(Level.WARNING, "Failed to serialise envelope for HTTP ingest — RETRY", e);
            return CompletableFuture.completedFuture(
                    HttpIngestResult.retry(0, "serialise: " + e.getMessage()));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle(JdkHttpIngestClient::mapResponse);
    }

    private static HttpIngestResult mapResponse(HttpResponse<Void> response, Throwable error) {
        if (error != null) {
            // Network error / timeout / DNS failure — treat as retry-worthy.
            return HttpIngestResult.retry(0, "io: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
        int status = response.statusCode();
        if (status == 202) {
            return HttpIngestResult.accepted();
        }
        if (status == 400 || status == 413) {
            return HttpIngestResult.terminalReject(status, "consumer rejected as malformed");
        }
        // 503, 5xx, 429, anything else — retry-worthy.
        return HttpIngestResult.retry(status, "non-202 status; will retry");
    }
}
