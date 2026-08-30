package com.perf.orchestrator.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Production {@link IngestProbeClient} — issues an HTTP {@code OPTIONS}
 * request against the metrics-consumer's ingest URL.
 *
 * <p>{@code OPTIONS} is deliberate: Spring MVC answers it from the handler
 * mapping (200 + {@code Allow} header) without invoking the controller, so
 * the probe costs the consumer nothing — no body parse, no DB touch, no
 * access-log noise at WARN. <em>Any</em> HTTP response counts as reachable
 * — this is transport-level liveness, so only connect, timeout and DNS
 * failures report DOWN.
 */
public final class HttpIngestProbeClient implements IngestProbeClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpIngestProbeClient.class);

    private final URI endpoint;
    private final String authorization;
    private final HttpClient httpClient;

    private HttpIngestProbeClient(URI endpoint, String authorization, HttpClient httpClient) {
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.httpClient = httpClient;
    }

    public static HttpIngestProbeClient tryCreate(String ingestUrl, Duration connectTimeout) {
        return tryCreate(ingestUrl, connectTimeout, null);
    }

    /**
     * Returns {@code null} (probe permanently DOWN with
     * {@code ingest_probe_init_failed}) when the URL is malformed — same
     * fail-visible contract as the old AdminClient factory.
     */
    public static HttpIngestProbeClient tryCreate(String ingestUrl, Duration connectTimeout, String authorization) {
        try {
            URI uri = URI.create(ingestUrl);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .build();
            String auth = authorization == null || authorization.isBlank() ? null : authorization.trim();
            return new HttpIngestProbeClient(uri, auth, client);
        } catch (Exception e) {
            LOG.warn("Could not construct ingest probe client for '{}': {}", ingestUrl, e.toString());
            return null;
        }
    }

    @Override
    public Result checkReachable(Duration timeout) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(timeout)
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            // Any status code means the consumer's HTTP stack answered.
            return Result.up();
        } catch (java.net.http.HttpTimeoutException e) {
            return Result.unreachable("ingest_probe_timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unreachable("ingest_probe_interrupted");
        } catch (Exception e) {
            return Result.unreachable("ingest_unreachable");
        }
    }

    @Override
    public void close() {
        // JDK HttpClient needs no explicit close.
    }
}
