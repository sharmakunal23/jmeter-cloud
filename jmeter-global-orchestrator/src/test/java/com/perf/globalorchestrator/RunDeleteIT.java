package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Behavior IT for {@code DELETE /api/v1/runs/{runId}} — soft-delete ("hide")
 * for runs (reliability #3). Mirrors {@link RunAbortIT}'s harness.
 *
 * <ol>
 *   <li>Happy path — hide a TERMINAL run: it drops out of the default listing,
 *       remains reachable via {@code ?includeHidden=true} (data retained), and a
 *       DELETE audit event is recorded with the operator actor.</li>
 *   <li>Safety — hide a still-active run → 409 RUN_NOT_DELETABLE.</li>
 *   <li>Unknown runId → 404 RUN_NOT_FOUND.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator run-delete (soft-delete) — behavior IT")
class RunDeleteIT {

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
                                + "\"startedAt\":\"2026-05-27T12:00:00Z\","
                                + "\"completedAt\":null,\"jmeterAlive\":true}")));
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
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }

    private boolean runListed(String runId, boolean includeHidden) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs")
                        .param("includeHidden", Boolean.toString(includeHidden))
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode r : mapper.readTree(res.getResponse().getContentAsString())) {
            if (runId.equals(r.get("runId").asText())) return true;
        }
        return false;
    }

    // ── 1. Happy path — hide a terminal run ───────────────────────────

    @Test
    @DisplayName("delete a terminal run → hidden from default listing, retained under includeHidden, DELETE event recorded")
    void deleteTerminalRunHidesItButRetainsData() throws Exception {
        String appId = createApp("del-happy");
        String region = "del-happy-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("del-happy-1", region, appId);
        String runId = launchRun("del-happy", region, 1);
        runRepo.updateRunState(runId,
                com.perf.globalorchestrator.domain.RunState.COMPLETED, "ci/forced");

        // Visible before delete.
        assertThat(runListed(runId, false)).isTrue();

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/runs/{runId}", runId)
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"old smoke run\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));

        // Gone from the default listing, but retained under includeHidden.
        assertThat(runListed(runId, false)).isFalse();
        assertThat(runListed(runId, true)).isTrue();

        // The row + audit trail are intact; a DELETE event is recorded for alice.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("DELETE"))
                .andExpect(jsonPath("$[0].actor").value("alice"))
                .andExpect(jsonPath("$[0].actorSource").value("headerActor"));
    }

    // ── 2. Safety — active run can't be hidden ────────────────────────

    @Test
    @DisplayName("delete a still-active run → 409 RUN_NOT_DELETABLE")
    void deleteActiveRunIs409() throws Exception {
        String appId = createApp("del-active");
        String region = "del-active-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("del-active-1", region, appId);
        String runId = launchRun("del-active", region, 1);

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/runs/{runId}", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_DELETABLE"))
                .andExpect(jsonPath("$.runId").value(runId));

        // Still visible — the hide was rejected.
        assertThat(runListed(runId, false)).isTrue();
    }

    // ── 3. Unknown runId ──────────────────────────────────────────────

    @Test
    @DisplayName("delete an unknown runId → 404 RUN_NOT_FOUND (no body required)")
    void deleteUnknownRunIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/runs/{runId}", "01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }
}
