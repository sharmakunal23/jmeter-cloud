package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavior IT for the stale-binding tolerance on
 * {@code DELETE /api/v1/applications/{id}/capacity/{region}/pods/{podName}}
 * (BUG-2 / BUG-4 fix).
 *
 * <p>{@link com.perf.globalorchestrator.provision.PodProvisioner} is mocked so
 * {@code isRunning} is deterministic (no Docker daemon in the IT) and
 * {@code stopAndRemove} is a no-op.
 *
 * <ol>
 *   <li>Pod bound to a run whose container IS running → 409 POD_IN_USE
 *       (unchanged — a genuinely busy pod still can't be drained).</li>
 *   <li>Pod bound to a run whose container is GONE → 200, the stale member
 *       binding is released, the pod row is deleted.</li>
 *   <li>After a stale-drain, re-registering a same-name pod reads as READY,
 *       not IN_USE — proving the released binding no longer pins it even
 *       though the zombie run is still non-terminal (BUG-4).</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator capacity drain — stale-binding IT")
class CapacityDrainIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL",          POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",                 () -> "postgres");
        registry.add("POSTGRES_PASSWORD",             () -> "test");
        registry.add("POSTGRES_GLOBALRUN_URL",        POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");
        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        registry.add("globalOrchestrator.maxFleetSizePerRun", () -> "10");
    }

    @BeforeAll
    static void migrateAndStartStub() {
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .load()
                .migrate();

        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"PREPARING\",\"startedAt\":null}")));
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"RUNNING\","
                                + "\"startedAt\":\"2026-05-27T12:00:00Z\",\"jmeterAlive\":true}")));
    }

    @AfterAll
    static void stopStub() {
        if (wireMock != null) wireMock.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.perf.globalorchestrator.repo.ApplicationCapacityRepository capacityRepo;
    @Autowired com.perf.globalorchestrator.repo.RunRepository runRepo;
    @MockBean com.perf.globalorchestrator.kafka.KafkaTopicProvisioner topicProvisioner;
    /** Deterministic container-state + no-op stop/remove (no Docker daemon here). */
    @MockBean com.perf.globalorchestrator.provision.PodProvisioner provisioner;

    private String createApp(String name) throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
    }

    private void registerStubPod(String podId, String region, String applicationId) throws Exception {
        String body = "{\"podId\":\"" + podId + "\",\"region\":\"" + region + "\","
                + "\"baseUrl\":\"" + wireMock.baseUrl() + "\","
                + "\"applicationId\":\"" + applicationId + "\"}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String launchRun(String app, String region, int count) throws Exception {
        String body = String.format("""
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application":    "%s",
                  "fleetAllocation": [ { "region": "%s", "count": %d } ]
                }
                """, app, region, count);
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }

    // ── 1. Container still running → 409 (unchanged) ──────────────────

    @Test
    @DisplayName("drain a pod bound to a run with a LIVE container → 409 POD_IN_USE")
    void liveContainerStillBlocksDrain() throws Exception {
        String appId = createApp("drain-live");
        String region = "drain-live-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("drain-live-1", region, appId);
        launchRun("drain-live", region, 1);

        Mockito.when(provisioner.isRunning("drain-live-1")).thenReturn(true);

        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/applications/{a}/capacity/{r}/pods/{p}", appId, region, "drain-live-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POD_IN_USE"))
                .andExpect(jsonPath("$.blockedBy.runId").isNotEmpty());
    }

    // ── 2. Container gone → stale binding released, drain proceeds ─────

    @Test
    @DisplayName("drain a pod bound to a run whose container is GONE → 200 + staleBindingReleased, member ABORTED, row deleted")
    void deadContainerReleasesStaleBinding() throws Exception {
        String appId = createApp("drain-stale");
        String region = "drain-stale-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("drain-stale-1", region, appId);
        String runId = launchRun("drain-stale", region, 1);

        Mockito.when(provisioner.isRunning("drain-stale-1")).thenReturn(false);

        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/applications/{a}/capacity/{r}/pods/{p}", appId, region, "drain-stale-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drained").value(true))
                .andExpect(jsonPath("$.staleBindingReleased").value(true));

        // The dead member binding was released (ABORTED), so the run no longer
        // pins the pod even though the run row itself is still non-terminal.
        var run = runRepo.findByRunId(runId).orElseThrow();
        assertThat(run.fleetMembers())
                .as("the drained worker's member is ABORTED")
                .anySatisfy(m -> {
                    assertThat(m.workerId()).isEqualTo("drain-stale-1");
                    assertThat(m.state().name()).isEqualTo("ABORTED");
                });

        // Pod row was deleted — the (app, region) now shows zero provisioned.
        mvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/applications/{a}/capacity/{r}/pods", appId, region))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(0));
    }

    // ── 3. BUG-4 — re-spun same-name pod reads READY, not IN_USE ──────

    @Test
    @DisplayName("after a stale-drain, a re-registered same-name pod reads READY (not re-bound to the zombie run)")
    void respunPodIsNotReboundToZombieRun() throws Exception {
        String appId = createApp("drain-respin");
        String region = "drain-respin-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("drain-respin-1", region, appId);
        launchRun("drain-respin", region, 1);

        Mockito.when(provisioner.isRunning("drain-respin-1")).thenReturn(false);

        // Stale-drain (zombie run left RUNNING on purpose — we do NOT abort it).
        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/applications/{a}/capacity/{r}/pods/{p}", appId, region, "drain-respin-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staleBindingReleased").value(true));

        // Re-spin the same worker name.
        registerStubPod("drain-respin-1", region, appId);

        mvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/applications/{a}/capacity/{r}/pods", appId, region))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioned").value(1))
                .andExpect(jsonPath("$.inUse").value(0))
                .andExpect(jsonPath("$.pods[0].podName").value("drain-respin-1"))
                .andExpect(jsonPath("$.pods[0].state").value("READY"));
    }
}
