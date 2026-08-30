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
 * JSON with {@code Content-Type: application/json}; the canonical schema is
 * the metrics-consumer's {@code api/openapi.yaml}, pinned by the shared
 * golden-payload test. The run's application group rides as
 * {@code ?groupId=} on the request URL (never in the body): the consumer routes
 * the rows to that group's fact table. {@code METRICS_INGEST_AUTH} — the whole
 * header value, e.g. {@code Bearer <token>} — is sent as {@code Authorization}
 * when set (the hosted {@code ingest.auth}).
 *
 * <h2>Response mapping (the hosted retry matrix)</h2>
 * <ul>
 *   <li>{@code 2xx} → ACCEPTED (delete from the buffer).</li>
 *   <li>{@code 400 / 413 / 415 / 405} → TERMINAL_REJECT (the request itself is
 *       wrong — unknown group, malformed body, wrong media type; unchanged
 *       replays would fail the same way).</li>
 *   <li>{@code 401 / 403} → AUTH_REJECT (stay buffered; the dispatcher backs
 *       off until the token is rotated).</li>
 *   <li>{@code 429}, {@code 5xx}, I/O failures → RETRY (the buffer replays).</li>
 * </ul>
 */
public final class JdkHttpIngestClient implements HttpIngestClient {

    private static final Logger LOG = Logger.getLogger(JdkHttpIngestClient.class.getName());

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String GROUP_PARAM = "groupId";
    /** Jackson mapper is thread-safe — share a singleton. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI endpoint;
    private final String authorization;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JdkHttpIngestClient(String endpointUrl, Duration connectTimeout, Duration requestTimeout) {
        this(endpointUrl, null, connectTimeout, requestTimeout);
    }

    /**
     * @param authorization the whole {@code Authorization} header value
     *                      ({@code Bearer <token>}), or {@code null} / blank for none
     */
    public JdkHttpIngestClient(String endpointUrl, String authorization,
                               Duration connectTimeout, Duration requestTimeout) {
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
        this.authorization = authorization == null || authorization.isBlank() ? null : authorization.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    @Override
    public CompletableFuture<HttpIngestResult> send(WorkerMetricBatch envelope, String groupId) {
        Objects.requireNonNull(envelope, "envelope cannot be null");

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            // Serialisation should never fail for a record built by our own
            // aggregator (all doubles are finite by construction). Fail this
            // attempt with RETRY rather than looping forever — but log loudly.
            LOG.log(Level.WARNING, "Failed to serialise envelope for HTTP ingest — RETRY", e);
            return CompletableFuture.completedFuture(
                    HttpIngestResult.retry(0, "serialise: " + e.getMessage()));
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpointFor(endpoint, groupId))
                .timeout(requestTimeout)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Accept", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                .handle(JdkHttpIngestClient::mapResponse);
    }

    /**
     * The ingest URL for a group: {@code base} unchanged when there is no
     * group; otherwise the base with its {@code groupId} query parameter
     * replaced by (or extended with) {@code groupId=<group>}. A base URL that
     * already carries the group — the pre-Track-5 local shape — is therefore
     * still correct, and a buffered envelope from another run's group never
     * inherits this run's parameter.
     */
    static URI endpointFor(URI base, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return base;
        }
        StringBuilder query = new StringBuilder();
        String existing = base.getRawQuery();
        if (existing != null && !existing.isEmpty()) {
            for (String pair : existing.split("&")) {
                if (pair.isEmpty() || pair.equals(GROUP_PARAM) || pair.startsWith(GROUP_PARAM + "=")) {
                    continue;
                }
                if (query.length() > 0) query.append('&');
                query.append(pair);
            }
        }
        if (query.length() > 0) query.append('&');
        query.append(GROUP_PARAM).append('=').append(groupId);

        StringBuilder url = new StringBuilder();
        if (base.getScheme() != null) url.append(base.getScheme()).append("://");
        if (base.getRawAuthority() != null) url.append(base.getRawAuthority());
        if (base.getRawPath() != null) url.append(base.getRawPath());
        url.append('?').append(query);
        if (base.getRawFragment() != null) url.append('#').append(base.getRawFragment());
        return URI.create(url.toString());
    }

    private static HttpIngestResult mapResponse(HttpResponse<Void> response, Throwable error) {
        if (error != null) {
            // Network error / timeout / DNS failure — retry-worthy.
            return HttpIngestResult.retry(0, "io: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return HttpIngestResult.accepted(status);
        }
        if (status == 401 || status == 403) {
            return HttpIngestResult.authReject(status, "consumer refused the Authorization header");
        }
        if (status == 400 || status == 413 || status == 415 || status == 405) {
            return HttpIngestResult.terminalReject(status, "consumer rejected the request as malformed");
        }
        // 503, other 5xx, 429, anything else — retry-worthy.
        return HttpIngestResult.retry(status, "non-2xx status; will retry");
    }
}
