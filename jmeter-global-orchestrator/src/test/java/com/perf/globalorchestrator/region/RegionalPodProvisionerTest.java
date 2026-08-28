package com.perf.globalorchestrator.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.client.RegionalClient;
import com.perf.globalorchestrator.provision.PodSpec;
import com.perf.globalorchestrator.provision.ProvisionResult;
import com.perf.globalorchestrator.provision.ProvisionedPod;
import com.perf.globalorchestrator.provision.RegionalPodProvisioner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RegionalPodProvisioner — every PodProvisioner verb becomes a regional API call")
class RegionalPodProvisionerTest {

    private HttpServer regional;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private RegionRegistry registry;
    private RegionalPodProvisioner provisioner;
    private RegionProbe probe;

    @BeforeEach
    void startRegional() throws IOException {
        regional = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        regional.createContext("/", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().toString();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(method + " " + path + (body.isEmpty() ? "" : " " + body));
            int status = 200;
            String out;
            if (path.equals("/api/v1/capabilities")) {
                out = "{\"region\":\"na-east\",\"namespace\":\"jmeter-cloud\",\"headlessService\":\"workers\","
                        + "\"image\":\"jmeter-local-orchestrator:v2\",\"localOrchestratorPort\":8080,\"version\":\"dev\"}";
            } else if (path.equals("/api/v1/pods") && method.equals("POST")) {
                out = "{\"baseUrl\":\"http://payments-na-east-worker-1.workers:8080\",\"imageDigest\":\"jmeter-local-orchestrator:v2\","
                        + "\"createdAt\":\"2026-08-28T10:00:00Z\"}";
            } else if (path.startsWith("/api/v1/pods?applicationId=")) {
                out = "[{\"podName\":\"payments-na-east-worker-1\",\"applicationId\":\"APP\",\"region\":\"na-east\","
                        + "\"status\":\"running\",\"startedAt\":\"2026-08-28T10:00:00Z\",\"imageDigest\":\"jmeter-local-orchestrator:v2\"}]";
            } else if (path.equals("/api/v1/pods/payments-na-east-worker-1") && method.equals("GET")) {
                out = "{\"podName\":\"payments-na-east-worker-1\",\"exists\":true,\"running\":true}";
            } else if (path.equals("/api/v1/pods/ghost/restart")) {
                status = 404; out = "{\"code\":\"POD_NOT_FOUND\",\"message\":\"Pod ghost does not exist; cannot restart\"}";
            } else if (path.equals("/api/v1/pods/forbidden")) {
                status = 502; out = "{\"code\":\"CLUSTER_API_ERROR\",\"message\":\"forbidden\"}";
            } else {
                status = 204; out = "";
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            if (!out.isEmpty()) ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.isEmpty() ? -1 : bytes.length);
            if (!out.isEmpty()) ex.getResponseBody().write(bytes);
            ex.close();
        });
        regional.start();
        String url = "http://127.0.0.1:" + regional.getAddress().getPort();
        registry = new RegionRegistry(new RegionProperties("na-east=" + url + ",na-west"));
        RegionalClient client = new RegionalClient(new ObjectMapper(), 500, 2000);
        provisioner = new RegionalPodProvisioner(registry, client);
        probe = new RegionProbe(registry, client);
    }

    @AfterEach
    void stopRegional() {
        regional.stop(0);
    }

    @Test
    @DisplayName("createAndStart POSTs the spec and returns the regional's ProvisionResult")
    void createAndStart() {
        ProvisionResult r = provisioner.createAndStart(new PodSpec("payments-na-east-worker-1", "APP", "payments", "na-east"));

        assertThat(r.baseUrl()).isEqualTo("http://payments-na-east-worker-1.workers:8080");
        assertThat(r.imageDigest()).isEqualTo("jmeter-local-orchestrator:v2");
        assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-08-28T10:00:00Z"));
        assertThat(seen).singleElement().asString()
                .startsWith("POST /api/v1/pods ")
                .contains("\"podName\":\"payments-na-east-worker-1\"")
                .contains("\"region\":\"na-east\"");
    }

    @Test
    @DisplayName("exists / isRunning / listFor / stop / start / restart / delete map to the regional routes")
    void verbs() {
        assertThat(provisioner.exists("na-east", "payments-na-east-worker-1")).isTrue();
        assertThat(provisioner.isRunning("na-east", "payments-na-east-worker-1")).isTrue();
        List<ProvisionedPod> pods = provisioner.listFor("APP", "na-east");
        assertThat(pods).singleElement().satisfies(p -> {
            assertThat(p.podName()).isEqualTo("payments-na-east-worker-1");
            assertThat(p.status()).isEqualTo("running");
            assertThat(p.imageDigest()).isEqualTo("jmeter-local-orchestrator:v2");
        });
        provisioner.stop("na-east", "w-1");
        provisioner.start("na-east", "w-1");
        provisioner.restart("na-east", "w-1");
        provisioner.stopAndRemove("na-east", "w-1");

        assertThat(seen).containsExactly(
                "GET /api/v1/pods/payments-na-east-worker-1",
                "GET /api/v1/pods/payments-na-east-worker-1",
                "GET /api/v1/pods?applicationId=APP&region=na-east",
                "POST /api/v1/pods/w-1/stop",
                "POST /api/v1/pods/w-1/start",
                "POST /api/v1/pods/w-1/restart",
                "DELETE /api/v1/pods/w-1");
    }

    @Test
    @DisplayName("listFor across all regions asks every routed region and skips the direct ones")
    void listForAllRegions() {
        assertThat(provisioner.listFor("APP", null)).hasSize(1);
        assertThat(seen).containsExactly("GET /api/v1/pods?applicationId=APP&region=na-east");
    }

    @Test
    @DisplayName("a direct region cannot provision — RegionUnavailableException names the REGIONS fix")
    void directRegionCannotProvision() {
        assertThatThrownBy(() -> provisioner.createAndStart(new PodSpec("w-1", "APP", "payments", "na-west")))
                .isInstanceOf(RegionUnavailableException.class)
                .hasMessageContaining("na-west=http://");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("404 POD_NOT_FOUND is the interface's IllegalStateException; other errors are RegionalCallException")
    void errorMapping() {
        assertThatThrownBy(() -> provisioner.restart("na-east", "ghost"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
        assertThatThrownBy(() -> provisioner.stopAndRemove("na-east", "forbidden"))
                .isInstanceOf(RegionalCallException.class)
                .hasMessageContaining("CLUSTER_API_ERROR");
    }

    @Test
    @DisplayName("image digest and baseUrl shape come from the probed capabilities; before the first probe they are null / the platform default")
    void probeFeedsDigestAndBaseUrl() {
        assertThat(provisioner.currentImageDigest("na-east")).isNull();
        assertThat(provisioner.baseUrlFor("na-east", "w-1")).isEqualTo("http://w-1.workers:8080");
        assertThat(registry.statusOf("na-east").orElseThrow().reachable()).isNull();

        probe.probe();

        assertThat(provisioner.currentImageDigest("na-east")).isEqualTo("jmeter-local-orchestrator:v2");
        assertThat(provisioner.baseUrlFor("na-east", "w-1")).isEqualTo("http://w-1.workers:8080");
        RegionStatus status = registry.statusOf("na-east").orElseThrow();
        assertThat(status.reachable()).isTrue();
        assertThat(status.lastSeenAt()).isNotNull();
        assertThat(registry.statusOf("na-west").orElseThrow().routed()).isFalse();
    }

    @Test
    @DisplayName("an unreachable region is marked so, with the error, and provisioning there fails fast")
    void unreachableRegion() {
        regional.stop(0);

        probe.probe();
        assertThat(registry.statusOf("na-east").orElseThrow().reachable())
                .as("one miss is a WAN blip, not an outage")
                .isNull();
        probe.probe();
        probe.probe();

        RegionStatus status = registry.statusOf("na-east").orElseThrow();
        assertThat(status.reachable()).isFalse();
        assertThat(status.lastError()).contains("unreachable");
        assertThatThrownBy(() -> provisioner.exists("na-east", "w-1"))
                .isInstanceOf(RegionUnavailableException.class);
    }
}
