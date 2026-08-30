package com.perf.globalorchestrator.health;

import com.perf.globalorchestrator.domain.RegionCapacity;
import com.perf.globalorchestrator.health.PlatformHealth.Component;
import com.perf.globalorchestrator.provision.ProvisioningMode;
import com.perf.globalorchestrator.provision.ProvisioningProperties;
import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionStatus;
import com.perf.globalorchestrator.repo.PodRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The platform-health tree against stub actuators and a mocked region registry:
 * the consumer's idle ingest is a fact not a failure, a dead regional makes its
 * region DOWN and the platform DEGRADED, a lost worker is DEGRADED, and an
 * unreachable service is DOWN with the reason — all without a probe blocking.
 */
class PlatformHealthServiceTest {

    private HttpServer consumer, docs;
    private RegionRegistry regions;
    private PodRepository pods;
    private ProvisioningProperties provisioning;

    @BeforeEach
    void setUp() throws Exception {
        consumer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        docs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        regions = mock(RegionRegistry.class);
        pods = mock(PodRepository.class);
        provisioning = mock(ProvisioningProperties.class);
        when(provisioning.mode()).thenReturn(ProvisioningMode.DYNAMIC);
    }

    @AfterEach
    void tearDown() {
        consumer.stop(0);
        docs.stop(0);
    }

    private void serve(HttpServer s, String path, int status, String body) {
        s.createContext(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
    }

    private PlatformHealthService service(String consumerUrl, String docsUrl) {
        return new PlatformHealthService(
                () -> Health.up().withDetail("database", "Oracle").build(),
                regions, pods, provisioning, consumerUrl, docsUrl, Duration.ofSeconds(2));
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private static Component byId(List<Component> cs, String id) {
        return cs.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("everything up: the consumer's idle ingest is a fact, the document-service's free space is the detail, regions serve")
    void allUp() {
        serve(consumer, "/actuator/health", 503, """
                {"status":"DOWN","components":{"db":{"status":"UP","details":{"database":"Oracle"}},
                 "ingestProgress":{"status":"DOWN","details":{"lastBatchAgeSeconds":720,"totalBatchesProcessed":61}}}}""");
        serve(docs, "/actuator/health/readiness", 200, """
                {"status":"UP","components":{"readinessState":{"status":"UP"},
                 "storage":{"status":"UP","details":{"usableBytes":774266000000}}}}""");
        when(regions.all()).thenReturn(List.of(
                new RegionStatus("na-east", "http://na-east:30088", true, true, Instant.now(), null,
                        new RegionCapabilities("na-east", "ns", "workers", "img", 8080, "dev", 3))));
        when(pods.regionCapacities()).thenReturn(List.of(new RegionCapacity("na-east", 2, 1, 0)));

        PlatformHealth h = service(url(consumer), url(docs)).probeAll();
        assertThat(h.status()).isEqualTo("UP");
        Component hub = byId(h.components(), "global-orchestrator");
        assertThat(hub.status()).isEqualTo("UP");
        assertThat(hub.detail()).isEqualTo("provisioning DYNAMIC");
        Component mc = byId(h.components(), "metrics-consumer");
        assertThat(mc.status()).isEqualTo("UP");                       // db UP; the aggregate 503 is idle-ness, not failure
        assertThat(mc.detail()).isEqualTo("idle — last envelope 12 min ago (normal between runs)");
        assertThat(mc.latencyMs()).isNotNull();
        Component ds = byId(h.components(), "document-service");
        assertThat(ds.status()).isEqualTo("UP");
        assertThat(ds.detail()).isEqualTo("721 GB free");
        Component dcs = byId(h.components(), "regions");
        assertThat(dcs.status()).isEqualTo("UP");
        Component east = byId(dcs.components(), "region.na-east");
        assertThat(east.status()).isEqualTo("UP");
        assertThat(byId(east.components(), "region.na-east.regional-orchestrator").detail()).isEqualTo("version dev · room for 3 more worker(s)");
        assertThat(byId(east.components(), "region.na-east.workers").detail()).isEqualTo("1 idle · 1 busy");
    }

    @Test
    @DisplayName("a dead regional makes its region DOWN and the platform DEGRADED; a lost worker degrades its region; an unreachable service is DOWN with the reason")
    void degradedAndDown() {
        serve(consumer, "/actuator/health", 503, """
                {"status":"DOWN","components":{"db":{"status":"DOWN","details":{"error":"ORA-12541 no listener"}}}}""");
        // document-service: nothing listening → connect refused
        when(regions.all()).thenReturn(List.of(
                new RegionStatus("na-east", "http://na-east:30088", true, false, null, "HTTP connect timed out", null),
                new RegionStatus("na-west", "http://na-west:30088", true, true, Instant.now(), null,
                        new RegionCapabilities("na-west", "ns", "workers", "img", 8080, "dev")),
                new RegionStatus("lab", null, false, null, null, null, null)));
        when(pods.regionCapacities()).thenReturn(List.of(
                new RegionCapacity("na-west", 3, 2, 1), new RegionCapacity("lab", 1, 1, 0)));

        int free = docs.getAddress().getPort();
        docs.stop(0);
        PlatformHealth h = service(url(consumer), "http://127.0.0.1:" + free).probeAll();

        assertThat(h.status()).isEqualTo("DOWN");
        assertThat(byId(h.components(), "metrics-consumer").status()).isEqualTo("DOWN");
        assertThat(byId(h.components(), "metrics-consumer").detail()).isEqualTo("database down");
        Component ds = byId(h.components(), "document-service");
        assertThat(ds.status()).isEqualTo("DOWN");
        assertThat(ds.detail()).startsWith("unreachable:");
        Component dcs = byId(h.components(), "regions");
        assertThat(dcs.status()).isEqualTo("DEGRADED");
        assertThat(dcs.detail()).isEqualTo("1 of 3 region(s) down");
        Component east = byId(dcs.components(), "region.na-east");
        assertThat(east.status()).isEqualTo("DOWN");
        assertThat(byId(east.components(), "region.na-east.regional-orchestrator").detail()).isEqualTo("HTTP connect timed out");
        Component west = byId(dcs.components(), "region.na-west");
        assertThat(west.status()).isEqualTo("DEGRADED");
        assertThat(byId(west.components(), "region.na-west.workers").detail()).isEqualTo("2 idle · 0 busy · 1 lost");
        Component lab = byId(dcs.components(), "region.lab");
        assertThat(lab.status()).isEqualTo("UP");
        assertThat(lab.components()).extracting(Component::kind).containsExactly("workers");
        assertThat(lab.detail()).isEqualTo("direct — operator-declared workers");
    }

    @Test
    @DisplayName("the snapshot is UNKNOWN until the first refresh and never blocks a reader")
    void snapshotBeforeRefresh() {
        when(regions.all()).thenReturn(List.of());
        when(pods.regionCapacities()).thenReturn(List.of());
        PlatformHealthService svc = service("http://127.0.0.1:1", "http://127.0.0.1:1");
        assertThat(svc.snapshot().status()).isEqualTo("UNKNOWN");
        svc.refresh();
        assertThat(svc.snapshot().status()).isEqualTo("DOWN");
        assertThat(byId(svc.snapshot().components(), "regions").detail()).isEqualTo("no regions configured");
        assertThat(PlatformHealthService.humanBytes(774_266_000_000L)).isEqualTo("721 GB");
        assertThat(((Function<String, String>) PlatformHealthService::mapStatus).apply("OUT_OF_SERVICE")).isEqualTo("DOWN");
    }
}
