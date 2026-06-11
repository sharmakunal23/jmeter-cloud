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
 * MID-TEST-SCALING Phase A — behavior IT for
 * {@code POST /api/v1/runs/{runId}/scaleUp}.
 *
 * <p>Mirrors the {@link RunManagementIT} harness shape: Testcontainers
 * Postgres (canonical Flyway migrations applied as superuser in
 * {@code @BeforeAll}), WireMock-stubbed local orchestrator on a random
 * port, MockMvc for the controller layer.
 *
 * <p>Scenarios cover the Phase A acceptance gate:
 * <ol>
 *   <li>Happy path — launch 1-pod run, scaleUp +2 → 3 members ACCEPTED;
 *       new members carry non-null {@code joinedAtSecond}.</li>
 *   <li>Capacity gate — scaleUp that exceeds per-(app, region)
 *       maxAvailable returns 409 APPLICATION_CAPACITY_EXCEEDED.</li>
 *   <li>Run state gate — scaleUp against a non-RUNNING run returns 409
 *       RUN_NOT_SCALABLE.</li>
 *   <li>App binding gate — scaleUp against a run launched without
 *       {@code application} returns 409 RUN_NOT_SCALABLE_NO_APPLICATION.</li>
 *   <li>bestEffort=true partial fulfilment — fewer pods than requested
 *       still adds workers; response carries {@code partial=true}.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator scale-up — behavior IT")
class RunScaleUpIT {

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
        // Disable sweeper so it can't flip stub pods to LOST mid-test.
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
    /** Reliability Round 3 — mock the spinner so the spinShortfall path
     *  registers a stub pod instead of driving real Docker. */
    @MockBean com.perf.globalorchestrator.provision.PodSpinService spinService;

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

    /** Launch a run for {@code app} in {@code region} with {@code count} workers; assert RUNNING; return runId. */
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

    // ── 1. Happy path ─────────────────────────────────────────────────

    @Test
    @DisplayName("scaleUp +2 → response.granted=2, requested=2, partial=false; new members carry joinedAtSecond")
    void happyPath() throws Exception {
        String appId = createApp("scaleup-happy");
        String region = "scaleup-happy-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("scaleup-happy-1", region, appId);
        registerStubPod("scaleup-happy-2", region, appId);
        registerStubPod("scaleup-happy-3", region, appId);

        String runId = launchRun("scaleup-happy", region, 1);

        // Scale up +2 in the same region.
        String scaleBody = String.format("""
                {
                  "allocations": [
                    { "region": "%s", "count": 2 }
                  ]
                }
                """, region);
        MvcResult result = mvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.granted").value(2))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.run.state").value("RUNNING"))
                .andExpect(jsonPath("$.run.fleetMembers.length()").value(3))
                .andReturn();

        // The two new members carry joinedAtSecond (>= 0); the original
        // member's value remains null.
        JsonNode runJson = mapper.readTree(result.getResponse().getContentAsString()).get("run");
        int joined = 0, original = 0;
        for (JsonNode m : runJson.get("fleetMembers")) {
            JsonNode jas = m.get("joinedAtSecond");
            if (jas == null || jas.isNull()) original++;
            else { joined++; assertThat(jas.asLong()).isGreaterThanOrEqualTo(0L); }
        }
        assertThat(original).as("one original-fleet member").isEqualTo(1);
        assertThat(joined).as("two scale-up joiners").isEqualTo(2);
    }

    // ── 2. Capacity gate ──────────────────────────────────────────────

    @Test
    @DisplayName("scaleUp that exceeds per-(app, region) maxAvailable → 409 APPLICATION_CAPACITY_EXCEEDED")
    void capacityCapBlocksScaleUp() throws Exception {
        String appId = createApp("scaleup-capgate");
        String region = "scaleup-capgate-east";
        capacityRepo.upsert(appId, region, 1);  // cap of 1
        registerStubPod("scaleup-capgate-1", region, appId);
        // Register a second pod — capacity (not pod availability) should
        // be the first ceiling.
        registerStubPod("scaleup-capgate-2", region, appId);

        // Initial run takes the 1-pod budget.
        String runId = launchRun("scaleup-capgate", region, 1);

        // Scale up +1 — would push active to 2 > cap 1 → 409.
        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.application").value("scaleup-capgate"))
                .andExpect(jsonPath("$.region").value(region))
                .andExpect(jsonPath("$.max").value(1))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.requested").value(1));
    }

    // ── 3. Run state gate ─────────────────────────────────────────────

    @Test
    @DisplayName("scaleUp against a terminal run → 409 RUN_NOT_SCALABLE")
    void scaleUpAgainstTerminalRun() throws Exception {
        String appId = createApp("scaleup-state");
        String region = "scaleup-state-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("scaleup-state-1", region, appId);

        String runId = launchRun("scaleup-state", region, 1);

        // Force the run terminal directly via the repo. Mirrors what
        // the rollup logic does on the natural completion path; we
        // shortcut here so the test doesn't have to wait for the stub
        // local-orch to report COMPLETED.
        runRepo.updateRunState(runId,
                com.perf.globalorchestrator.domain.RunState.COMPLETED, "ci/forced");

        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_SCALABLE"))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.currentState").value("COMPLETED"));
    }

    // ── 4. App binding gate ───────────────────────────────────────────

    @Test
    @DisplayName("scaleUp against a run launched without `application` → 409 RUN_NOT_SCALABLE_NO_APPLICATION")
    void scaleUpRequiresApplication() throws Exception {
        // An untagged run (no `application`) can only be launched via the
        // cross-region claimIdle path (fleetSize, no fleetAllocation) — as of
        // Phase 6b an allocation-based run requires a registered app. The host
        // pod must still bind to a real app (pod.applicationId is NOT NULL),
        // but claimIdle is app-agnostic so the untagged run claims it anyway.
        String hostAppId = createApp("scaleup-noapp-host");
        String region = "scaleup-noapp-east";
        registerStubPod("scaleup-noapp-1", region, hostAppId);
        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "fleetSize": 1
                }
                """;
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn();
        String runId = mapper.readTree(result.getResponse().getContentAsString())
                .get("runId").asText();

        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_SCALABLE_NO_APPLICATION"))
                .andExpect(jsonPath("$.runId").value(runId));
    }

    // ── 5. bestEffort partial fulfilment ──────────────────────────────

    @Test
    @DisplayName("scaleUp ?bestEffort=true with insufficient pods → granted < requested + partial=true")
    void bestEffortPartial() throws Exception {
        String appId = createApp("scaleup-besteffort");
        String region = "scaleup-besteffort-east";
        // Cap is high so capacity isn't the gate; pod availability is.
        capacityRepo.upsert(appId, region, 5);
        registerStubPod("scaleup-besteffort-1", region, appId);
        registerStubPod("scaleup-besteffort-2", region, appId);

        // Initial run takes 1 of 2 pods.
        String runId = launchRun("scaleup-besteffort", region, 1);

        // Request +3 with bestEffort — only 1 pod left, should grant 1.
        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 3 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp?bestEffort=true", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.granted").value(1))
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.stateReason",
                        org.hamcrest.Matchers.containsString("bestEffort scaleUp")))
                .andExpect(jsonPath("$.run.fleetMembers.length()").value(2));
    }

    // ── 6. Unknown runId ──────────────────────────────────────────────

    @Test
    @DisplayName("scaleUp against unknown runId → 404 RUN_NOT_FOUND")
    void unknownRunIs404() throws Exception {
        String scaleBody = """
                { "allocations": [ { "region": "any", "count": 1 } ] }
                """;
        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/runs/{runId}/scaleUp", "01ZZZZZZZZZZZZZZZZZZZZZZZZ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    // ── 8. spinShortfall — provision the gap (up to max capacity) ─────

    @Test
    @DisplayName("scaleUp ?spinShortfall=true spins the missing pods, retries, and lands all members")
    void spinShortfallFillsTheGapOnScaleUp() throws Exception {
        String appId = createApp("scaleup-spin");
        String region = "scaleup-spin-east";
        // Cap = 2: one pod for the launch, one headroom to spin into.
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("scaleup-spin-1", region, appId);

        // Launch claims the only IDLE pod → 0 left.
        String runId = launchRun("scaleup-spin", region, 1);

        // spin registers a SECOND pod (same path the real local-orch uses on
        // boot), so the retry sees 1 fresh IDLE pod.
        org.mockito.Mockito.when(spinService.spin(
                        org.mockito.Mockito.eq(appId),
                        org.mockito.Mockito.eq("scaleup-spin"),
                        org.mockito.Mockito.eq(region)))
                .thenAnswer(inv -> {
                    registerStubPod("scaleup-spin-2", region, appId);
                    return new com.perf.globalorchestrator.provision.PodSpinService.SpinResult(
                            "scaleup-spin-2", wireMock.baseUrl(), "sha256:fake",
                            java.time.Instant.now());
                });

        // Request +1 with spinShortfall — no IDLE pods, so the claim short-falls,
        // the gap is spun, and the retry grants the worker (partial=false).
        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/runs/{runId}/scaleUp?spinShortfall=true", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.granted").value(1))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.run.fleetMembers.length()").value(2));

        org.mockito.Mockito.verify(spinService, org.mockito.Mockito.times(1))
                .spin(appId, "scaleup-spin", region);
    }

    @Test
    @DisplayName("scaleUp ?spinShortfall=false (default) still 503s with structured shortfall (no spin)")
    void scaleUpStrictStill503sWithoutSpin() throws Exception {
        String appId = createApp("scaleup-spin-strict");
        String region = "scaleup-spin-strict-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("scaleup-spin-strict-1", region, appId);
        String runId = launchRun("scaleup-spin-strict", region, 1);

        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"))
                .andExpect(jsonPath("$.shortfall[0].region").value(region))
                .andExpect(jsonPath("$.shortfall[0].requested").value(1))
                .andExpect(jsonPath("$.shortfall[0].claimed").value(0));

        org.mockito.Mockito.verify(spinService, org.mockito.Mockito.never())
                .spin(org.mockito.Mockito.anyString(),
                      org.mockito.Mockito.anyString(),
                      org.mockito.Mockito.anyString());
    }

    // ── 9. Data files forwarded to scale-up workers (#2) ──────────────

    @Test
    @DisplayName("scaleUp fan-out carries the run's dataFilesBlobId → new workers stage the same data files")
    void scaleUpForwardsDataFiles() throws Exception {
        String appId = createApp("scaleup-datafiles");
        String region = "scaleup-datafiles-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("scaleup-datafiles-1", region, appId);
        registerStubPod("scaleup-datafiles-2", region, appId);

        // Launch WITH a data-files blob persisted on the run row.
        String dataBlob = "01HXDF000000000000000000ZZ";
        String launchBody = String.format("""
                {
                  "testPlanBlobId":  "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "dataFilesBlobId": "%s",
                  "application":     "scaleup-datafiles",
                  "fleetAllocation": [ { "region": "%s", "count": 1 } ]
                }
                """, dataBlob, region);
        MvcResult launched = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(launchBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn();
        String runId = mapper.readTree(launched.getResponse().getContentAsString())
                .get("runId").asText();

        // Scale up +1 — the new worker's fan-out body must carry the SAME
        // dataFilesBlobId (sourced from the persisted run row), so the local
        // orchestrator stages the data files before launching JMeter.
        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON).content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(1));

        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath("$.runId", WireMock.equalTo(runId)))
                .withRequestBody(WireMock.matchingJsonPath("$.joinedAtSecond"))
                .withRequestBody(WireMock.matchingJsonPath("$.dataFilesBlobId", WireMock.equalTo(dataBlob))));
    }

    // ── 7. Phase C — joinedAtSecond on fan-out body ──────────────────

    @Test
    @DisplayName("MID-TEST-SCALING Phase C — scaleUp fan-out body carries joinedAtSecond; original-fleet fan-out omits it")
    void scaleUpFanOutCarriesJoinedAtSecond() throws Exception {
        String appId = createApp("scaleup-joinedatsec");
        String region = "scaleup-joinedatsec-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("scaleup-joinedatsec-1", region, appId);
        registerStubPod("scaleup-joinedatsec-2", region, appId);

        String runId = launchRun("scaleup-joinedatsec", region, 1);

        // Original-fleet member's fan-out body must NOT carry joinedAtSecond
        // (the local-orch defaults to 0 — the intended original-fleet semantic).
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath("$.runId", WireMock.equalTo(runId)))
                .withRequestBody(WireMock.notMatching(".*joinedAtSecond.*")));

        // Scale up +1 — the new member's fan-out body MUST carry joinedAtSecond.
        String scaleBody = String.format("""
                { "allocations": [ { "region": "%s", "count": 1 } ] }
                """, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scaleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(1));

        // The scale-up fan-out (the second POST /api/v1/test for this run)
        // must carry a non-null joinedAtSecond (>= 0).
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath("$.runId", WireMock.equalTo(runId)))
                .withRequestBody(WireMock.matchingJsonPath("$.joinedAtSecond")));
    }
}
