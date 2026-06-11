package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Production {@link HttpFallbackClient} backed by the JDK {@link HttpClient}.
 *
 * <p>Serialises the envelope to raw Avro binary (no Schema Registry — the
 * `WorkerMetricBatch` schema is fixed at deploy time per K-0) and POSTs to
 * {@code metrics-consumer:8083/api/v1/ingest} (or wherever the configured
 * endpoint points).
 *
 * <p><b>Status mapping</b> — translates K-4's response codes:
 * <ul>
 *   <li>202 → {@link HttpFallbackResult.Outcome#ACCEPTED}</li>
 *   <li>400 / 413 → {@link HttpFallbackResult.Outcome#TERMINAL_REJECT}</li>
 *   <li>everything else (5xx, 429, network errors, timeout) → {@link HttpFallbackResult.Outcome#RETRY}</li>
 * </ul>
 */
public final class JdkHttpFallbackClient implements HttpFallbackClient {

    private static final Logger LOG = Logger.getLogger(JdkHttpFallbackClient.class.getName());

    private static final String AVRO_CONTENT_TYPE = "application/avro";
    private static final SpecificDatumWriter<WorkerMetricBatch> WRITER =
            new SpecificDatumWriter<>(WorkerMetricBatch.class);

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JdkHttpFallbackClient(String endpointUrl, Duration connectTimeout, Duration requestTimeout) {
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
    public CompletableFuture<HttpFallbackResult> send(WorkerMetricBatch envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");

        byte[] body;
        try {
            body = serialize(envelope);
        } catch (IOException e) {
            // Serialisation should never fail for a valid Avro object built by us.
            // Fail this attempt with RETRY so the retry path doesn't loop forever
            // on a single bad envelope — but log loudly.
            LOG.log(Level.WARNING, "Failed to serialise envelope for HTTP fallback — RETRY", e);
            return CompletableFuture.completedFuture(
                    HttpFallbackResult.retry(0, "serialise: " + e.getMessage()));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", AVRO_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle(JdkHttpFallbackClient::mapResponse);
    }

    private static HttpFallbackResult mapResponse(HttpResponse<Void> response, Throwable error) {
        if (error != null) {
            // Network error / timeout / DNS failure — treat as retry-worthy.
            return HttpFallbackResult.retry(0, "io: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
        int status = response.statusCode();
        if (status == 202) {
            return HttpFallbackResult.accepted();
        }
        if (status == 400 || status == 413) {
            return HttpFallbackResult.terminalReject(status, "consumer rejected as malformed");
        }
        // 503, 5xx, 429, anything else — retry-worthy.
        return HttpFallbackResult.retry(status, "non-202 status; will retry");
    }

    private static byte[] serialize(WorkerMetricBatch envelope) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(baos, null);
        WRITER.write(envelope, enc);
        enc.flush();
        return baos.toByteArray();
    }
}
