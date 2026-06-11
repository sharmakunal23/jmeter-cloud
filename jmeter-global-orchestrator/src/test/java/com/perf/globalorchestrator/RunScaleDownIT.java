package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MID-TEST-SCALING Phase B — behavior IT for
 * {@code POST /api/v1/runs/{runId}/scaleDown}.
 *
 * <p>Mirrors {@link RunScaleUpIT} harness shape: Testcontainers Postgres,
 * WireMock-stubbed local-orch on a random port, MockMvc.
 *
 * <p>Scenarios cover the Phase B acceptance gate:
 * <ol>
 *   <li>Happy path — drain workerId-A explicitly, B stays RUNNING; run
 *       state stays RUNNING; drained list contains A; A's member state
 *       is DRAINING.</li>
 *   <li>Allocations path — youngest-by-default selection picks the
 *       most-recently-created RUNNING members.</li>
 *   <li>Capacity counter — DRAINING workers count toward
 *       {@code countActivePodsForAppRegion}; DRAINED do not.</li>
 *   <li>Drained pod cannot be re-claimed by another run while DRAINING
 *       (active filter includes DRAINING).</li>
 *   <li>Run-not-RUNNING gate → 409 RUN_NOT_SCALABLE.</li>
 *   <li>Run-not-found → 404 RUN_NOT_FOUND.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator scale-down — behavior IT")
class RunScaleDownIT {

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
                                + "\"startedAt\":\"2026-05-15T12:00:00Z\","
                                + "\"completedAt\":null,\"elapsedMs\":1000,"
                                + "\"rowsIngested\":42,\"windowsPublished\":3,"
                                + "\"kafkaSendErrors\":0,\"jmeterAlive\":true}")));
        // MID-TEST-SCALING Phase B — drain endpoint stub returns 202 ACCEPTED.
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test/drain"))
                .willReturn(WireMock.aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")));
    }

    @AfterAll
    static void stopStub() {
        if (wireMock != null) wireMock.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.perf.globalorchestrator.repo.ApplicationCapacityRepository capacityRepo;
    @Autowired com.perf.globalorchestrator.repo.RunRepository runRepo;
    /** KAFKA-PER-APP Phase B — neuter the per-app topic provisioner (no
     *  Kafka container in this IT; topic-lifecycle assertions live in
     *  ApplicationRegistryIT). */
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
        StringBuilder body = new StringBuilder();
        body.append("{\"podId\":\"").append(podId).append("\",")
            .append("\"region\":\"").append(region).append("\",")
            .append("\"baseUrl\":\"").append(wireMock.baseUrl()).append("\"");
        if (applicationId != null) {
            body.append(",\"applicationId\":\"").append(applicationId).append("\"");
        }
        body.append("}");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk());
    }

    private String launchRun(String app, String region, int count) throws Exception {
        String body = String.format("""
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application":    "%s",
                  "fleetAllocation": [
                    { "region": "%s", "count": %d }
                  ]
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

    // ── 1. Happy path with explicit workerIds ─────────────────────────

    @Test
    @DisplayName("scaleDown {workerIds:[A]} → A goes DRAINING, B stays RUNNING, run stays RUNNING")
    void happyPathExplicitWorkerIds() throws Exception {
        String appId = createApp("scaledown-happy");
        String region = "scaledown-happy-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("scaledown-happy-A", region, appId);
        registerStubPod("scaledown-happy-B", region, appId);

        String runId = launchRun("scaledown-happy", region, 2);

        // Drain A explicitly.
        String scaleBody = """
                { "workerIds": ["scaledown-happy-A"] }
                """;
        MvcResult result = mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drained.length()").value(1))
                .andExpect(jsonPath("$.drained[0]").value("scaledown-happy-A"))
                .andExpect(jsonPath("$.skipped.length()").value(0))
                .andExpect(jsonPath("$.run.state").value("RUNNING"))
                .andReturn();

        // A's member state is DRAINING; B remains ACCEPTED/RUNNING.
        JsonNode runJson = mapper.readTree(result.getResponse().getContentAsString()).get("run");
        boolean sawDraining = false;
        boolean sawRunning  = false;
        for (JsonNode m : runJson.get("fleetMembers")) {
            String id = m.get("workerId").asText();
            String state = m.get("state").asText();
            if ("scaledown-happy-A".equals(id) && "DRAINING".equals(state)) sawDraining = true;
            if ("scaledown-happy-B".equals(id)
                    && ("RUNNING".equals(state) || "ACCEPTED".equals(state))) sawRunning = true;
        }
        assertThat(sawDraining).as("A is DRAINING").isTrue();
        assertThat(sawRunning).as("B is RUNNING/ACCEPTED").isTrue();

        // The drain endpoint on the local-orch was hit.
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test/drain")));
    }

    // ── 2. allocations path — youngest-by-default ─────────────────────

    @Test
    @DisplayName("scaleDown {allocations:[{region, count:1}]} picks the most-recently-created RUNNING worker")
    void allocationsPathPicksYoungest() throws Exception {
        String appId = createApp("scaledown-alloc");
        String region = "scaledown-alloc-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("scaledown-alloc-old",   region, appId);
        registerStubPod("scaledown-alloc-young", region, appId);

        // Two members in this run; createdAt timestamps order them.
        String runId = launchRun("scaledown-alloc", region, 2);

        // Drain count=1 → service picks the youngest (most-recently-created).
        // Both rows are created in the same transaction, so either is valid;
        // we just assert ONE was drained and the other is not.
        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        MvcResult result = mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drained.length()").value(1))
                .andExpect(jsonPath("$.run.state").value("RUNNING"))
                .andReturn();

        JsonNode runJson = mapper.readTree(result.getResponse().getContentAsString()).get("run");
        int drainingCount = 0, otherCount = 0;
        for (JsonNode m : runJson.get("fleetMembers")) {
            if ("DRAINING".equals(m.get("state").asText())) drainingCount++;
            else otherCount++;
        }
        assertThat(drainingCount).as("exactly one member DRAINING").isEqualTo(1);
        assertThat(otherCount).as("other member not DRAINING").isEqualTo(1);
    }

    // ── 3. Capacity counter includes DRAINING ─────────────────────────

    @Test
    @DisplayName("a DRAINING worker still counts toward per-(app,region) capacity — scale-up that exceeds Max is rejected")
    void drainingCountsTowardCapacity() throws Exception {
        String appId = createApp("scaledown-capgate");
        String region = "scaledown-capgate-east";
        capacityRepo.upsert(appId, region, 1);  // tight cap
        registerStubPod("scaledown-capgate-1", region, appId);
        registerStubPod("scaledown-capgate-2", region, appId);

        String runId = launchRun("scaledown-capgate", region, 1);

        // Drain via allocations path — avoids hard-coding which workerId
        // got claimed (claim ordering is lastHeartbeat DESC, not registration).
        String drainBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(drainBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drained.length()").value(1));

        // Try to scale up +1 — DRAINING still counts (1 active + 1 requested
        // > 1 cap), so the cap-gate must fire.
        String scaleUpBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleUpBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.active").value(1));
    }

    // ── 4. Run state gate ─────────────────────────────────────────────

    @Test
    @DisplayName("scaleDown against terminal run → 409 RUN_NOT_SCALABLE")
    void scaleDownAgainstTerminalRun() throws Exception {
        String appId = createApp("scaledown-state");
        String region = "scaledown-state-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("scaledown-state-1", region, appId);

        String runId = launchRun("scaledown-state", region, 1);

        runRepo.updateRunState(runId,
                com.perf.globalorchestrator.domain.RunState.COMPLETED, "ci/forced");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"workerIds\": [\"scaledown-state-1\"] }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_SCALABLE"))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.currentState").value("COMPLETED"));
    }

    // ── 5. Validation: must supply exactly one of workerIds/allocations ──

    @Test
    @DisplayName("scaleDown with neither workerIds nor allocations → 400 INVALID_REQUEST")
    void emptyBodyIs400() throws Exception {
        String appId = createApp("scaledown-emptybody");
        String region = "scaledown-emptybody-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("scaledown-emptybody-1", region, appId);
        String runId = launchRun("scaledown-emptybody", region, 1);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("scaleDown with both workerIds AND allocations → 400 INVALID_REQUEST")
    void bothPathsIs400() throws Exception {
        String appId = createApp("scaledown-bothpaths");
        String region = "scaledown-bothpaths-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("scaledown-bothpaths-1", region, appId);
        String runId = launchRun("scaledown-bothpaths", region, 1);

        String body = String.format("""
                {
                  "workerIds": ["scaledown-bothpaths-1"],
                  "allocations": [ { "region": "%s", "count": 1 } ]
                }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ── 6. Unknown runId ──────────────────────────────────────────────

    @Test
    @DisplayName("scaleDown against unknown runId → 404 RUN_NOT_FOUND")
    void unknownRunIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/runs/{runId}/scaleDown", "01ZZZZZZZZZZZZZZZZZZZZZZZZ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"workerIds\": [\"any\"] }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    // ── 7. Unknown workerId in this run ───────────────────────────────

    @Test
    @DisplayName("scaleDown with workerId that's not a member of this run → 400 INVALID_REQUEST")
    void unknownWorkerIs400() throws Exception {
        String appId = createApp("scaledown-unknownworker");
        String region = "scaledown-unknownworker-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("scaledown-unknownworker-1", region, appId);
        String runId = launchRun("scaledown-unknownworker", region, 1);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"workerIds\": [\"some-other-pod\"] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
