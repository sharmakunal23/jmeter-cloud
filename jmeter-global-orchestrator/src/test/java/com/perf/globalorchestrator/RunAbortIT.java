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
 * Behavior IT for {@code POST /api/v1/runs/{runId}/abort} (BUG-3 fix).
 *
 * <p>Mirrors {@link RunScaleDownIT}'s harness: Testcontainers Postgres,
 * WireMock-stubbed local-orch on a random port, MockMvc.
 *
 * <ol>
 *   <li>Happy path — abort a RUNNING run: run + every member go ABORTED, the
 *       local-orch hard-kill RPC fires, an ABORT audit event is recorded with
 *       the operator actor, and — crucially — the run's pods are released so a
 *       fresh run can re-claim them (BUG-2/BUG-4: a zombie run no longer pins
 *       its pods).</li>
 *   <li>Idempotency — aborting an already-terminal run → 409
 *       RUN_NOT_ABORTABLE.</li>
 *   <li>Unknown runId → 404 RUN_NOT_FOUND.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator run-abort — behavior IT")
class RunAbortIT {

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
        // The hard-kill endpoint the run-abort path fans out to.
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test/abort"))
                .willReturn(WireMock.aResponse().withStatus(202)));
    }

    @AfterAll
    static void stopStub() {
        if (wireMock != null) wireMock.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.perf.globalorchestrator.repo.ApplicationCapacityRepository capacityRepo;
    @Autowired com.perf.globalorchestrator.repo.RunRepository runRepo;
    @Autowired com.perf.globalorchestrator.repo.PodRepository podRepo;
    @Autowired com.perf.globalorchestrator.service.RunService runService;
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
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }

    // ── 1. Happy path ─────────────────────────────────────────────────

    @Test
    @DisplayName("abort a RUNNING run → run + members ABORTED, ABORT event recorded, pods released for re-claim")
    void abortRollsRunTerminalAndReleasesPods() throws Exception {
        String appId = createApp("abort-happy");
        String region = "abort-happy-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("abort-happy-A", region, appId);
        registerStubPod("abort-happy-B", region, appId);

        String runId = launchRun("abort-happy", region, 2);

        MvcResult result = mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/runs/{runId}/abort", runId)
                                .header("X-Actor", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"reason\": \"stuck test\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ABORTED"))
                .andReturn();

        // Every member is ABORTED.
        JsonNode run = mapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode m : run.get("fleetMembers")) {
            assertThat(m.get("state").asText()).as("member %s", m.get("workerId").asText())
                    .isEqualTo("ABORTED");
        }

        // The hard-kill RPC fired at least once.
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test/abort")));

        // An ABORT audit event is recorded, attributed to the operator.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("ABORT"))
                .andExpect(jsonPath("$[0].actor").value("alice"))
                .andExpect(jsonPath("$[0].actorSource").value("headerActor"));

        // BUG-2/BUG-4: the run's pods are released — a fresh run re-claims both.
        String runId2 = launchRun("abort-happy", region, 2);
        assertThat(runId2).isNotEqualTo(runId);
    }

    // ── 2. Idempotency — already-terminal run ─────────────────────────

    @Test
    @DisplayName("abort an already-terminal run → 409 RUN_NOT_ABORTABLE")
    void abortTerminalRunIs409() throws Exception {
        String appId = createApp("abort-terminal");
        String region = "abort-terminal-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("abort-terminal-1", region, appId);
        String runId = launchRun("abort-terminal", region, 1);

        runRepo.updateRunState(runId,
                com.perf.globalorchestrator.domain.RunState.COMPLETED, "ci/forced");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/abort", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_ABORTABLE"))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.currentState").value("COMPLETED"));
    }

    // ── 3. Unknown runId ──────────────────────────────────────────────

    @Test
    @DisplayName("abort an unknown runId → 404 RUN_NOT_FOUND (no body required)")
    void abortUnknownRunIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/runs/{runId}/abort", "01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    // ── 4. Abort clears saveResults (reliability #1) ──────────────────

    @Test
    @DisplayName("abort clears saveResults → run stops advertising a Download-that-404s")
    void abortClearsSaveResults() throws Exception {
        String appId = createApp("abort-save");
        String region = "abort-save-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("abort-save-1", region, appId);

        // Launch WITH saveResults=true — the response echoes it.
        String body = String.format("""
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application":    "abort-save",
                  "fleetAllocation": [ { "region": "%s", "count": 1 } ],
                  "saveResults": true
                }
                """, region);
        MvcResult launched = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saveResults").value(true))
                .andReturn();
        String runId = mapper.readTree(launched.getResponse().getContentAsString())
                .get("runId").asText();

        // Aborting flips saveResults off — an aborted run never produced a
        // clean upload, so the UI's Download button (gated on saveResults)
        // must not appear.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/abort", runId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ABORTED"))
                .andExpect(jsonPath("$.saveResults").value(false));

        assertThat(runRepo.findByRunId(runId).orElseThrow().saveResults()).isFalse();
    }

    // ── 5. Lost worker cascades to member FAILED (reliability #2) ─────

    @Test
    @DisplayName("worker lost (pod LOST) → active member FAILED + run rolled up to FAILED")
    void lostWorkerCascadesToMemberFailed() throws Exception {
        String appId = createApp("lost-worker");
        String region = "lost-worker-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("lost-worker-1", region, appId);
        String runId = launchRun("lost-worker", region, 1);

        // Simulate the heartbeat sweeper finding the worker silent: a future
        // cutoff makes every registered pod's heartbeat "stale". The claimed
        // pod's state is still IDLE (the binding lives in runFleetMember), so
        // markLostBefore flips it to LOST — exactly the killed-worker case.
        int lost = podRepo.markLostBefore(java.time.Instant.now().plusSeconds(3600));
        assertThat(lost).isGreaterThanOrEqualTo(1);

        // The reaper cascades pod-LOST → member FAILED, then rolls the run up.
        runService.reapLostWorkerMembers("worker lost: no heartbeat within 90000 ms");

        var run = runRepo.findByRunId(runId).orElseThrow();
        assertThat(run.state().name()).isEqualTo("FAILED");
        assertThat(run.fleetMembers()).isNotEmpty();
        assertThat(run.fleetMembers()).allSatisfy(m -> {
            assertThat(m.state().name()).isEqualTo("FAILED");
            assertThat(m.stateReason()).contains("worker lost");
        });
    }

    // ── 6. Failed run clears saveResults (reliability #4) ─────────────

    @Test
    @DisplayName("run rolled up to FAILED clears saveResults → no Download button on a failed test")
    void failedRunClearsSaveResults() throws Exception {
        String appId = createApp("fail-save");
        String region = "fail-save-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("fail-save-1", region, appId);

        // Launch WITH saveResults=true.
        String body = String.format("""
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application":    "fail-save",
                  "fleetAllocation": [ { "region": "%s", "count": 1 } ],
                  "saveResults": true
                }
                """, region);
        MvcResult launched = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saveResults").value(true))
                .andReturn();
        String runId = mapper.readTree(launched.getResponse().getContentAsString())
                .get("runId").asText();

        // Drive the run to FAILED via the lost-worker cascade (no member ever
        // COMPLETED → rollUp returns FAILED). The refreshAndGet inside the
        // reaper is what clears saveResults on a non-COMPLETED terminal.
        int lost = podRepo.markLostBefore(java.time.Instant.now().plusSeconds(3600));
        assertThat(lost).isGreaterThanOrEqualTo(1);
        runService.reapLostWorkerMembers("worker lost: no heartbeat within 90000 ms");

        var run = runRepo.findByRunId(runId).orElseThrow();
        assertThat(run.state().name()).isEqualTo("FAILED");
        assertThat(run.saveResults()).as("FAILED run must not advertise a download").isFalse();
    }
}
