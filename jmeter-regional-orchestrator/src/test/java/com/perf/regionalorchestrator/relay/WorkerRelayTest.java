package com.perf.regionalorchestrator.relay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkerRelay — forwards allow-listed calls to the worker, synthesises errors otherwise")
class WorkerRelayTest {

    private HttpServer worker;
    private int port;
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenUri = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenContentType = new AtomicReference<>();
    private final AtomicReference<String> seenActor = new AtomicReference<>();
    private final AtomicReference<String> seenRunId = new AtomicReference<>();

    @BeforeEach
    void startWorker() throws IOException {
        worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = worker.getAddress().getPort();
        worker.createContext("/", ex -> {
            seenMethod.set(ex.getRequestMethod());
            seenUri.set(ex.getRequestURI().toString());
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            seenActor.set(ex.getRequestHeaders().getFirst("X-Actor"));
            seenRunId.set(ex.getRequestHeaders().getFirst("X-Run-Id"));
            byte[] out = "{\"state\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(202, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        worker.start();
    }

    @AfterEach
    void stopWorker() {
        worker.stop(0);
    }

    private WorkerRelay relayTo(int targetPort) {
        return new WorkerRelay(name -> "http://127.0.0.1:" + targetPort, new RelayProperties(500, 2000));
    }

    @Test
    @DisplayName("POST api/v1/test is forwarded with method, query, body, content type and X-Actor; status and body come back verbatim")
    void forwardsAllowListedCall() {
        RelayResponse r = relayTo(port).relay(new RelayRequest(
                "payments-na-east-worker-1", "POST", "api/v1/test", "dryRun=true",
                "{\"runId\":\"R1\"}".getBytes(StandardCharsets.UTF_8), "application/json", "alice", "R1"));

        assertThat(r.status()).isEqualTo(202);
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo("{\"state\":\"RUNNING\"}");
        assertThat(r.contentType()).isEqualTo("application/json");
        assertThat(seenMethod.get()).isEqualTo("POST");
        assertThat(seenUri.get()).isEqualTo("/api/v1/test?dryRun=true");
        assertThat(seenBody.get()).isEqualTo("{\"runId\":\"R1\"}");
        assertThat(seenContentType.get()).isEqualTo("application/json");
        assertThat(seenActor.get()).isEqualTo("alice");
        assertThat(seenRunId.get()).isEqualTo("R1");
    }

    @Test
    @DisplayName("GET without a body forwards no body and no content type")
    void forwardsGet() {
        RelayResponse r = relayTo(port).relay(new RelayRequest(
                "payments-na-east-worker-1", "GET", "actuator/health", null, null, null, null, null));

        assertThat(r.status()).isEqualTo(202);
        assertThat(seenMethod.get()).isEqualTo("GET");
        assertThat(seenUri.get()).isEqualTo("/actuator/health");
        assertThat(seenBody.get()).isEmpty();
        assertThat(seenContentType.get()).isNull();
    }

    @Test
    @DisplayName("a path outside the allow-list is 403 PATH_NOT_ALLOWED and never reaches the worker")
    void refusesUnknownPath() {
        RelayResponse r = relayTo(port).relay(new RelayRequest(
                "payments-na-east-worker-1", "GET", "actuator/env", null, null, null, null, null));

        assertThat(r.status()).isEqualTo(403);
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("PATH_NOT_ALLOWED");
        assertThat(seenMethod.get()).isNull();
    }

    @Test
    @DisplayName("a pod name that is not a DNS-1123 label is 400 INVALID_POD_NAME — the relay cannot be aimed at another host")
    void refusesBadPodName() {
        RelayResponse r = relayTo(port).relay(new RelayRequest(
                "evil.example.com", "GET", "actuator/health", null, null, null, null, null));

        assertThat(r.status()).isEqualTo(400);
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("INVALID_POD_NAME");
        assertThat(seenMethod.get()).isNull();
    }

    @Test
    @DisplayName("an unreachable worker is 502 WORKER_UNREACHABLE in the platform error shape")
    void unreachableWorkerIs502() {
        worker.stop(0);
        RelayResponse r = relayTo(port).relay(new RelayRequest(
                "payments-na-east-worker-1", "GET", "actuator/health", null, null, null, null, null));

        assertThat(r.status()).isEqualTo(502);
        assertThat(r.contentType()).isEqualTo("application/json");
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("\"code\":\"WORKER_UNREACHABLE\"");
    }

    @Test
    @DisplayName("the allow-list is exactly the worker endpoints the global calls")
    void allowListIsExact() {
        WorkerRelay relay = relayTo(port);
        assertThat(relay.isAllowed("api/v1/test")).isTrue();
        assertThat(relay.isAllowed("api/v1/test/drain")).isTrue();
        assertThat(relay.isAllowed("api/v1/test/abort")).isTrue();
        assertThat(relay.isAllowed("api/v1/logs")).isTrue();
        assertThat(relay.isAllowed("actuator/health")).isTrue();
        assertThat(relay.isAllowed("api/v1/test/")).isFalse();
        assertThat(relay.isAllowed("api/v1/testPlan")).isFalse();
        assertThat(relay.isAllowed("")).isFalse();
        assertThat(relay.isAllowed(null)).isFalse();
    }
}
