package com.perf.regionalorchestrator.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.regionalorchestrator.provision.PodProvisioner;
import com.perf.regionalorchestrator.relay.RelayRequest;
import com.perf.regionalorchestrator.relay.RelayResponse;
import com.perf.regionalorchestrator.relay.WorkerRelay;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * {@code /api/v1/workers/{podName}/{path}} → the worker's {@code /{path}},
 * method, query, body, content type and {@code X-Actor} carried through, the
 * worker's status and body returned as-is. {@link WorkerRelay} decides what is
 * allowed. Two additions the plain relay cannot do: {@code POST /workers/status}
 * polls many workers in one call, and {@code api/v1/logs} falls back to the
 * kubelet's container log when the worker itself no longer answers.
 */
@RestController
public class WorkerRelayController {

    public static final String PREFIX = "/api/v1/workers/";

    private final WorkerRelay relay;
    private final PodProvisioner provisioner;
    private final ObjectMapper mapper;
    /** Bounds a status batch's in-cluster fan-out — 500 named workers is 500 sockets otherwise. */
    private final Semaphore inFlight = new Semaphore(64);

    public WorkerRelayController(WorkerRelay relay, PodProvisioner provisioner, ObjectMapper mapper) {
        this.relay = relay;
        this.provisioner = provisioner;
        this.mapper = mapper;
    }

    /** One worker's answer inside a {@link #status} batch; {@code body} is the parsed JSON when the worker sent JSON. */
    public record WorkerStatus(String podName, int status, Object body) {}

    /**
     * {@code POST /api/v1/workers/status {podNames:[…]}} — the run-status poll
     * for a whole region in one hub round-trip: every worker's
     * {@code GET /api/v1/test} fetched in parallel in-cluster. A worker that
     * does not answer is reported with its relay status (502/504), never
     * dropped, so the caller can tell "no answer" from "no such member".
     */
    @PostMapping(PREFIX + "status")
    public List<WorkerStatus> status(@RequestBody StatusRequest request) {
        List<String> names = request.podNames() == null ? List.of() : request.podNames();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<WorkerStatus>> futures = names.stream()
                    .map(name -> pool.submit(() -> {
                        inFlight.acquireUninterruptibly();
                        try {
                            RelayResponse r = relay.relay(new RelayRequest(name, "GET", "api/v1/test", null, null, null, null, null));
                            return new WorkerStatus(name, r.status(), parse(r));
                        } finally {
                            inFlight.release();
                        }
                    }))
                    .toList();
            List<WorkerStatus> out = new ArrayList<>(names.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.add(futures.get(i).get());
                } catch (ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    out.add(new WorkerStatus(names.get(i), 502, null));
                }
            }
            return out;
        }
    }

    public record StatusRequest(List<String> podNames) {}

    private Object parse(RelayResponse r) {
        if (r.body() == null || r.body().length == 0) return null;
        String text = new String(r.body(), StandardCharsets.UTF_8);
        if (r.contentType() != null && r.contentType().contains("json")) {
            try {
                return mapper.readTree(text);
            } catch (IOException ignored) {
                // Fall through — hand back the raw text.
            }
        }
        return text;
    }

    @RequestMapping(value = PREFIX + "{podName}/**",
                    method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> relay(@PathVariable String podName,
                                        @RequestBody(required = false) byte[] body,
                                        HttpServletRequest request) {
        RelayResponse r = relay.relay(new RelayRequest(
                podName,
                request.getMethod(),
                subPath(request.getRequestURI(), podName),
                request.getQueryString(),
                body,
                request.getContentType(),
                request.getHeader("X-Actor"),
                request.getHeader("X-Run-Id")));
        String subPath = subPath(request.getRequestURI(), podName);
        if ("api/v1/logs".equals(subPath) && (r.status() == 502 || r.status() == 504)) {
            // The worker cannot answer — usually because it is dead, which is
            // exactly when its logs matter. The kubelet still has its stdout.
            int tail = parseTail(request.getParameter("tail"));
            return provisioner.podLog(podName, tail)
                    .map(log -> ResponseEntity.ok()
                            .contentType(MediaType.TEXT_PLAIN)
                            .header("X-Log-Source", "kubernetes")
                            .body(log.getBytes(StandardCharsets.UTF_8)))
                    .orElseGet(() -> ResponseEntity.status(r.status())
                            .contentType(MediaType.APPLICATION_JSON).body(r.body()));
        }
        ResponseEntity.BodyBuilder out = ResponseEntity.status(r.status());
        if (r.contentType() != null) {
            try {
                out.contentType(MediaType.parseMediaType(r.contentType()));
            } catch (IllegalArgumentException ignored) {
                // A worker sending a malformed content type still gets its body through.
            }
        }
        return out.body(r.body() == null ? new byte[0] : r.body());
    }

    static int parseTail(String raw) {
        try {
            return raw == null ? 200 : Math.min(10_000, Math.max(1, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return 200;
        }
    }

    /** Everything after {@code /api/v1/workers/{podName}/}, trailing slash trimmed. */
    static String subPath(String uri, String podName) {
        String marker = PREFIX + podName + "/";
        int at = uri.indexOf(marker);
        if (at < 0) return "";
        String rest = uri.substring(at + marker.length());
        return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
    }
}
