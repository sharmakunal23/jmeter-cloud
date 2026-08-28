package com.perf.regionalorchestrator.relay;

import com.perf.regionalorchestrator.provision.PodNames;
import com.perf.regionalorchestrator.provision.PodProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

/**
 * Forwards one HTTP call from the hub to a worker inside this cluster, at
 * {@code {baseUrlFor(podName)}/{subPath}}. The allow-list is the security
 * boundary: only the worker endpoints the global orchestrator actually calls
 * are forwarded, and only to a DNS-1123 pod name — nothing here can be aimed
 * at another host.
 *
 * <p>A worker that cannot be reached answers {@code 502 WORKER_UNREACHABLE};
 * one that does not answer within the read timeout answers
 * {@code 504 WORKER_TIMEOUT}. Both bodies are the platform's
 * {@code {code, message}} JSON so the hub's client treats them like any
 * other failed worker call.
 */
@Component
public class WorkerRelay {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerRelay.class);

    /**
     * Worker paths the global calls (see its {@code LocalOrchestratorClient}).
     * Adding an endpoint there means adding it here, or every region 403s it.
     */
    public static final Set<String> ALLOWED_PATHS = Set.of(
            "actuator/health",
            "api/v1/test",
            "api/v1/test/drain",
            "api/v1/test/abort",
            "api/v1/logs");

    private final Function<String, String> baseUrlFor;
    private final RelayProperties props;
    private final HttpClient http;

    @Autowired
    public WorkerRelay(PodProvisioner provisioner, RelayProperties props) {
        this(provisioner::baseUrlFor, props);
    }

    WorkerRelay(Function<String, String> baseUrlFor, RelayProperties props) {
        this.baseUrlFor = baseUrlFor;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .build();
    }

    public boolean isAllowed(String subPath) {
        return subPath != null && ALLOWED_PATHS.contains(subPath);
    }

    public RelayResponse relay(RelayRequest req) {
        if (!PodNames.isValid(req.podName())) {
            return error(400, "INVALID_POD_NAME", "podName must be a DNS-1123 label");
        }
        if (!isAllowed(req.subPath())) {
            return error(403, "PATH_NOT_ALLOWED", "worker path is not relayed: " + req.subPath());
        }
        URI target = URI.create(baseUrlFor.apply(req.podName()) + "/" + req.subPath()
                + (req.query() == null || req.query().isBlank() ? "" : "?" + req.query()));

        HttpRequest.BodyPublisher publisher = req.body() == null || req.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(req.body());
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(props.readTimeoutMs()))
                .method(req.method(), publisher);
        if (req.contentType() != null && !req.contentType().isBlank()) {
            builder.header("Content-Type", req.contentType());
        }
        if (req.actor() != null && !req.actor().isBlank()) {
            builder.header("X-Actor", req.actor());
        }
        if (req.runId() != null && !req.runId().isBlank()) {
            builder.header("X-Run-Id", req.runId()); // the worker's MdcEnrichmentFilter keys its logs on it
        }
        try {
            HttpResponse<byte[]> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new RelayResponse(resp.statusCode(), resp.body(),
                    resp.headers().firstValue("Content-Type").orElse(null));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            // A connect that never completes is a dead or vanished worker, not a slow one.
            LOG.warn("relay {} {} to {} could not connect within {} ms", req.method(), req.subPath(), req.podName(), props.connectTimeoutMs());
            return error(502, "WORKER_UNREACHABLE", "worker " + req.podName() + " unreachable: connect timed out");
        } catch (HttpTimeoutException e) {
            LOG.warn("relay {} {} to {} timed out after {} ms", req.method(), req.subPath(), req.podName(), props.readTimeoutMs());
            return error(504, "WORKER_TIMEOUT", "worker " + req.podName() + " did not answer within " + props.readTimeoutMs() + " ms");
        } catch (IOException e) {
            LOG.warn("relay {} {} to {} failed: {}", req.method(), req.subPath(), req.podName(), e.toString());
            // ConnectException carries a null message — toString() keeps the class name.
            return error(502, "WORKER_UNREACHABLE", "worker " + req.podName() + " unreachable: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(502, "WORKER_UNREACHABLE", "relay interrupted");
        }
    }

    static RelayResponse error(int status, String code, String message) {
        String json = "{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}";
        return new RelayResponse(status, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }
}
