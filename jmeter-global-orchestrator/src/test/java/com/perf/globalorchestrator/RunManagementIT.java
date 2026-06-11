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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavior IT for the run-management chain — the Step 14 checkpoint:
 * "POST /api/v1/runs with a fleet of 1 against the local stack creates a
 * run, fans out to orchestrator-1, and GET /api/v1/runs/{id}/status
 * reflects the local orchestrator's state transitions."
 *
 * <p>Uses Testcontainers Postgres (canonical Flyway migrations applied
 * as the superuser in {@code @BeforeAll}), and a WireMock-stubbed local
 * orchestrator on a random port to assert the fan-out wiring without
 * needing a real orchestrator container.
 *
 * <p>Tests <strong>behavior</strong> only — three scenarios:
 * <ol>
 *   <li>Happy path — POST → 201 + run JSON; fan-out hit on the stub;
 *       GET .../status rolls up the stub's RUNNING state.</li>
 *   <li>404 on unknown runId.</li>
 *   <li>400 on missing testPlanBlobId.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator run management — behavior IT")
class RunManagementIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        // The metrics datasource won't actually be hit by the run-launch
        // path; point it at the same container as a no-op so Spring's
        // health contributor finds something.
        registry.add("POSTGRES_METRICS_URL",          POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",                 () -> "postgres");
        registry.add("POSTGRES_PASSWORD",             () -> "test");

        // Run-state DS. Connect as the per-app role to lock in production
        // user-isolation semantics. Bypass Spring Boot Flyway (we
        // pre-migrate as superuser in @BeforeAll).
        registry.add("POSTGRES_GLOBALRUN_URL",        POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");

        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        // Step 15 — disable the sweeper while the IT runs so it can't
        // race the test by flipping the IT's freshly-registered pod to
        // LOST mid-test. (lostAfterMs above 1 hour > test wall-time.)
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        // Track F — small per-run cap so the FLEET_SIZE_EXCEEDED path
        // is reachable in a unit-test wall-time budget. Production
        // default is 100.
        registry.add("globalOrchestrator.maxFleetSizePerRun", () -> "5");
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

        // Default fan-out stub: local orchestrator returns 202 ACCEPTED
        // with a small JSON body — same shape the real one returns.
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"PREPARING\",\"startedAt\":null}")));
        // WORKER-HYGIENE Phase E — /actuator/health stub so the
        // spin-shortfall test's health-wait short-circuits immediately
        // instead of waiting through the default 60s timeout.
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/actuator/health"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"UP\"}")));
        // GET status stub: report RUNNING so the refreshAndGet path has
        // something to roll up.
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"RUNNING\","
                                + "\"startedAt\":\"2026-05-09T12:00:00Z\","
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
    /** D-Capacity v2 polish — capacity is sponsor-controlled; tests reach
     *  past the HTTP layer to seed budgets directly (mirrors what the
     *  future approval workflow will do). */
    @Autowired com.perf.globalorchestrator.repo.ApplicationCapacityRepository capacityRepo;
    /** KAFKA-PER-APP Phase B — ApplicationController now also creates a
     *  per-app Kafka topic on register (and deletes it on app delete).
     *  This IT focuses on run-management behavior, not Kafka topic
     *  lifecycle, so we neuter the provisioner with a no-op mock. The
     *  topic-create/delete contract is asserted end-to-end against a real
     *  Testcontainers broker in {@code ApplicationRegistryIT}. */
    @MockBean com.perf.globalorchestrator.kafka.KafkaTopicProvisioner topicProvisioner;
    /** WORKER-HYGIENE Phase E — spin-to-fill scenarios stub this so we don't
     *  need a docker daemon. The mock impl just inserts a fresh stub pod
     *  row pointing at the test WireMock and returns a synthetic SpinResult. */
    @MockBean com.perf.globalorchestrator.provision.PodSpinService spinService;

    /**
     * Step 15 — RunService claims pods from the registry, not from a
     * static URL list. Each happy-path test self-registers a stub pod
     * pointing at the WireMock baseUrl before launching the run.
     */
    private void registerStubPod(String podId) throws Exception {
        registerStubPod(podId, "us-east-1");
    }

    private void registerStubPod(String podId, String region) throws Exception {
        // Phase 6b: pod.applicationId is NOT NULL and POST /registerPod
        // rejects a missing applicationId, so every stub pod must bind to a
        // registered app. Callers that only need the pod to *exist* (the
        // cross-region claimIdle path, used by application-less runs) bind to
        // a shared default app; allocation-based tests pass their own app via
        // the 3-arg overload (see registerAppPod).
        registerStubPod(podId, region, ensureApp(DEFAULT_STUB_APP));
    }

    /**
     * Registers a stub pod bound to {@code applicationId}, pointing at the
     * test WireMock baseUrl. As of Phase 6b {@code applicationId} is required
     * (the legacy null-app pool is gone).
     */
    private void registerStubPod(String podId, String region, String applicationId) throws Exception {
        String body = "{\"podId\":\"" + podId + "\","
                + "\"region\":\"" + region + "\","
                + "\"baseUrl\":\"" + wireMock.baseUrl() + "\","
                + "\"applicationId\":\"" + applicationId + "\"}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ── Phase 6b helpers — every pod is bound to a registered application ──

    /** App-id cache so repeat ensureApp calls within one test don't 409. */
    private final java.util.Map<String, String> appIdCache = new java.util.HashMap<>();
    /** Monotonic suffix for auto-provisioned pod names in launchRun. */
    private int podSeq = 0;
    /** Default app for claimIdle-path tests whose pods just need to exist. */
    private static final String DEFAULT_STUB_APP = "default-stub-app";

    /**
     * Idempotent application create → applicationId. Tolerates a 409 from an
     * app a prior test method already registered (state is not rolled back
     * between methods in this IT).
     */
    private String ensureApp(String name) throws Exception {
        String cached = appIdCache.get(name);
        if (cached != null) return cached;
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andReturn();
        String id = res.getResponse().getStatus() == 201
                ? mapper.readTree(res.getResponse().getContentAsString()).get("applicationId").asText()
                : lookupAppId(name);
        appIdCache.put(name, id);
        return id;
    }

    private String lookupAppId(String name) throws Exception {
        JsonNode list = mapper.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications"))
                        .andReturn().getResponse().getContentAsString());
        for (JsonNode app : list) {
            if (name.equals(app.path("name").asText())) {
                return app.path("applicationId").asText();
            }
        }
        throw new IllegalStateException("application not found after create: " + name);
    }

    /** Ensure app + per-region capacity + a stub pod bound to the app; returns appId. */
    private String registerAppPod(String appName, String region, String podId, int max) throws Exception {
        String appId = ensureApp(appName);
        capacityRepo.upsert(appId, region, max);
        registerStubPod(podId, region, appId);
        return appId;
    }

    @Test
    @DisplayName("POST /runs → fan-out POST /test → run lands as RUNNING; GET /status reflects RUNNING")
    void happyPath() throws Exception {
        registerStubPod("orchestrator-1");
        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "fleetSize": 1,
                  "initiatedBy": "ci/RunManagementIT"
                }
                """;

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").exists())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.testPlanBlobId").value("01HXC2VQK4M9N6P5T0YBX2WZ4Q"))
                .andExpect(jsonPath("$.fleetMembers").isArray())
                .andExpect(jsonPath("$.fleetMembers[0].state").value("ACCEPTED"))
                .andReturn();

        JsonNode runJson = mapper.readTree(result.getResponse().getContentAsString());
        String runId = runJson.get("runId").asText();
        assertThat(runId).hasSize(26);

        // Fan-out call landed on the stub with our runId in the body.
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.equalToJson(
                        "{\"runId\":\"" + runId + "\","
                        + "\"region\":\"us-east-1\","
                        + "\"testPlanBlobId\":\"01HXC2VQK4M9N6P5T0YBX2WZ4Q\"}",
                        true, true)));

        // GET /status triggers refreshAndGet → live poll of /api/v1/test
        // → member state moves to RUNNING → run-level rolls up to RUNNING.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/status", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.members[0].state").value("RUNNING"));

        wireMock.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/test")));
    }

    @Test
    @DisplayName("GET /runs/{unknown} → 404 RUN_NOT_FOUND")
    void unknownRunIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}", "01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    @DisplayName("SECURITY S-3 — a malformed (non-ULID) runId is rejected at routing (404) before the controller/DB")
    void malformedRunIdRejectedAtRouting() throws Exception {
        // The {runId:<ULID>} path constraint means a garbage id never matches a
        // mapping, so it 404s at the router — no controller body, no DB round-trip.
        // Cheap rejection of injection-via-path / id-spray abuse.
        // Cases: wrong shape, too short, and a 26-char string with an excluded
        // Crockford letter ('I') — the last proves it's a character-class check,
        // not just a length check.
        for (String bad : new String[] {"notaulid", "01ZZZ", "0123456789012345678901234I"}) {
            mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/status", bad))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("POST /runs without testPlanBlobId → 400 INVALID_REQUEST")
    void missingTestPlanIs400() throws Exception {
        String body = """
                {"fleetSize": 1}
                """;
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ── Track F (Step 26) — multi-region fleet allocation ───────────────

    @Test
    @DisplayName("fleetAllocation across two regions claims one pod per region; both members ACCEPTED")
    void multiRegionHappyPath() throws Exception {
        String appId = ensureApp("alloc-multi-svc");
        capacityRepo.upsert(appId, "alloc-multi-east", 1);
        capacityRepo.upsert(appId, "alloc-multi-west", 1);
        registerStubPod("orchestrator-allocA-east", "alloc-multi-east", appId);
        registerStubPod("orchestrator-allocA-west", "alloc-multi-west", appId);

        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "alloc-multi-svc",
                  "fleetAllocation": [
                    { "region": "alloc-multi-east", "count": 1 },
                    { "region": "alloc-multi-west", "count": 1 }
                  ],
                  "initiatedBy": "ci/RunManagementIT"
                }
                """;

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.fleetMembers").isArray())
                .andExpect(jsonPath("$.fleetMembers.length()").value(2))
                .andExpect(jsonPath("$.fleetMembers[?(@.region=='alloc-multi-east')].state")
                        .value(org.hamcrest.Matchers.hasItem("ACCEPTED")))
                .andExpect(jsonPath("$.fleetMembers[?(@.region=='alloc-multi-west')].state")
                        .value(org.hamcrest.Matchers.hasItem("ACCEPTED")));

        // Regression: each worker must be told ITS OWN region in the fan-out
        // body — the local-orch stamps that onto every WorkerMetric it
        // publishes, which drives the UI's split-by-region view. A prior bug
        // sent the orchestrator's service-wide region (us-east-1) to EVERY
        // member, so a multi-region run's metrics all collapsed to one region.
        // Verify the stub received one /test with each region (not two
        // identical us-east-1 bodies).
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath("$.region", WireMock.equalTo("alloc-multi-east"))));
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath("$.region", WireMock.equalTo("alloc-multi-west"))));
    }

    @Test
    @DisplayName("strict mode: per-region shortfall → 503 with structured shortfall body; no rows persisted")
    void perRegionShortfallStrict() throws Exception {
        // Capacity max=2 so the per-(app,region) cap-check passes; only 1 pod
        // is Ready, so the claim shortfalls (claimed=1, requested=2) → 503.
        String appId = ensureApp("alloc-shortfall-svc");
        capacityRepo.upsert(appId, "alloc-shortfall-east", 2);
        registerStubPod("orchestrator-allocB-east", "alloc-shortfall-east", appId);

        // Snapshot the run count so we can assert nothing landed.
        Long before = mapper.readTree(
                mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs?limit=200"))
                        .andReturn().getResponse().getContentAsString())
                .size() * 1L;

        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "alloc-shortfall-svc",
                  "fleetAllocation": [
                    { "region": "alloc-shortfall-east", "count": 2 }
                  ]
                }
                """;

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"))
                .andExpect(jsonPath("$.shortfall[0].region").value("alloc-shortfall-east"))
                .andExpect(jsonPath("$.shortfall[0].requested").value(2))
                .andExpect(jsonPath("$.shortfall[0].claimed").value(1));

        Long after = mapper.readTree(
                mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs?limit=200"))
                        .andReturn().getResponse().getContentAsString())
                .size() * 1L;
        assertThat(after).isEqualTo(before);  // transaction rolled back → no run row
    }

    @Test
    @DisplayName("bestEffort=true: shortfall persists what's available; stateReason notes the deficit")
    void perRegionShortfallBestEffort() throws Exception {
        String appId = ensureApp("alloc-besteffort-svc");
        capacityRepo.upsert(appId, "alloc-besteffort-east", 2);
        registerStubPod("orchestrator-allocC-east", "alloc-besteffort-east", appId);

        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "alloc-besteffort-svc",
                  "fleetAllocation": [
                    { "region": "alloc-besteffort-east", "count": 2 }
                  ],
                  "initiatedBy": "ci/RunManagementIT"
                }
                """;

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs?bestEffort=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.fleetMembers.length()").value(1))
                .andExpect(jsonPath("$.fleetMembers[0].state").value("ACCEPTED"))
                .andExpect(jsonPath("$.stateReason",
                        org.hamcrest.Matchers.containsString("bestEffort claim")));
    }

    // ── WORKER-HYGIENE Phase E — spin-to-fill on shortfall ─────────────

    @Test
    @DisplayName("Phase E — spinShortfall=true spins the gap, retries claim, and lands as RUNNING")
    void spinShortfallFillsTheGap() throws Exception {
        // Register a real application so the spin-to-fill path has an
        // app context (legacy null-app path is excluded by design).
        String appName = "spin-shortfall-app";
        String appId = mapper.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + appName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString())
                .get("applicationId").asText();
        // Seed capacity = 2 in us-east-1 so a fleetSize=2 request fits
        // and the spin's provisioning cap-check passes.
        capacityRepo.replaceAll(appId, java.util.List.of(
                new com.perf.globalorchestrator.domain.ApplicationCapacity(
                        appId, "us-east-1", 2, null, null)));

        // Pre-register ONE pod bound to the app — claim returns 1, shortfall = 1.
        registerStubPod("spin-fill-east-1", "us-east-1", appId);

        // Stub spinService.spin to register a SECOND pod row (via the same
        // HTTP path the real local-orch uses on boot) and return a
        // synthetic SpinResult. The retry will then see 2 IDLE pods.
        org.mockito.Mockito.when(spinService.spin(
                        org.mockito.Mockito.eq(appId),
                        org.mockito.Mockito.eq(appName),
                        org.mockito.Mockito.eq("us-east-1")))
                .thenAnswer(inv -> {
                    String podName = "spin-fill-east-2";
                    registerStubPod(podName, "us-east-1", appId);
                    return new com.perf.globalorchestrator.provision.PodSpinService.SpinResult(
                            podName,
                            wireMock.baseUrl(),
                            "sha256:fake",
                            java.time.Instant.now());
                });

        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "%s",
                  "fleetAllocation": [
                    { "region": "us-east-1", "count": 2 }
                  ],
                  "spinShortfall": true
                }
                """.formatted(appName);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.fleetMembers.length()").value(2));

        // Verify the spin was actually called for the gap.
        org.mockito.Mockito.verify(spinService, org.mockito.Mockito.times(1))
                .spin(appId, appName, "us-east-1");
    }

    @Test
    @DisplayName("Phase E — spinShortfall=false (default) still 503s with structured shortfall body")
    void spinShortfallDefaultsToStrict() throws Exception {
        String appName = "spin-shortfall-strict-app";
        String appId = mapper.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + appName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString())
                .get("applicationId").asText();
        capacityRepo.replaceAll(appId, java.util.List.of(
                new com.perf.globalorchestrator.domain.ApplicationCapacity(
                        appId, "us-east-1", 2, null, null)));
        registerStubPod("spin-strict-east-1", "us-east-1", appId);

        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "%s",
                  "fleetAllocation": [
                    { "region": "us-east-1", "count": 2 }
                  ]
                }
                """.formatted(appName);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"))
                .andExpect(jsonPath("$.shortfall[0].region").value("us-east-1"))
                .andExpect(jsonPath("$.shortfall[0].requested").value(2))
                .andExpect(jsonPath("$.shortfall[0].claimed").value(1));

        // Spin was NOT called.
        org.mockito.Mockito.verify(spinService, org.mockito.Mockito.never())
                .spin(org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.anyString());
    }

    // ── Track G (Step 31) — per-node JMeter -J properties ─────────────

    @Test
    @DisplayName("perNodeProperties threads through fan-out body and persists on runFleetMember")
    void perNodeProperties() throws Exception {
        String appId = ensureApp("alloc-props-svc");
        capacityRepo.upsert(appId, "alloc-props-east", 2);
        registerStubPod("orchestrator-propsA-east-1", "alloc-props-east", appId);
        registerStubPod("orchestrator-propsA-east-2", "alloc-props-east", appId);

        // Two pods in one region with distinct property maps. The
        // global-orchestrator should send each pod its own properties
        // map in the fan-out body, persisted on the corresponding
        // runFleetMember row.
        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "alloc-props-svc",
                  "fleetAllocation": [
                    {
                      "region": "alloc-props-east",
                      "count": 2,
                      "perNodeProperties": [
                        { "REGION": "east", "USER_OFFSET": "0" },
                        { "REGION": "east", "USER_OFFSET": "500" }
                      ]
                    }
                  ],
                  "initiatedBy": "ci/RunManagementIT"
                }
                """;

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fleetMembers.length()").value(2))
                .andReturn();

        // Both pods were fanned out to with their own properties map.
        // WireMock state carries across tests in the class so we can't
        // assert an exact count; we assert presence of each expected
        // properties value in the request log.
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath(
                        "$.properties[?(@.USER_OFFSET == '0')]")));
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath(
                        "$.properties[?(@.USER_OFFSET == '500')]")));

        // Persistence: GET the run and assert each member's properties
        // round-trip through the JSONB column.
        JsonNode runJson = mapper.readTree(result.getResponse().getContentAsString());
        String runId = runJson.get("runId").asText();
        MvcResult getResult = mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode getJson = mapper.readTree(getResult.getResponse().getContentAsString());
        JsonNode members = getJson.get("fleetMembers");
        assertThat(members).hasSize(2);
        // At least one member has USER_OFFSET=0, at least one has 500.
        boolean sawZero  = false, sawFiveHundred = false;
        for (JsonNode m : members) {
            JsonNode p = m.get("properties");
            if (p == null) continue;
            String v = p.has("USER_OFFSET") ? p.get("USER_OFFSET").asText() : null;
            if ("0".equals(v))   sawZero = true;
            if ("500".equals(v)) sawFiveHundred = true;
        }
        assertThat(sawZero).as("at least one member has USER_OFFSET=0").isTrue();
        assertThat(sawFiveHundred).as("at least one member has USER_OFFSET=500").isTrue();
    }

    @Test
    @DisplayName("unconfigured region → 409 APPLICATION_CAPACITY_EXCEEDED; total cap exceeded → 400 FLEET_SIZE_EXCEEDED")
    void allocationValidationErrors() throws Exception {
        // FLEET_SIZE fires before any app/capacity logic; the second request
        // exercises the per-(app,region) capacity-grid region validator that
        // replaced the legacy null-app UNKNOWN_REGION pre-check in Phase 6b.
        String appId = ensureApp("alloc-validation-svc");
        capacityRepo.upsert(appId, "alloc-cap-east", 5);
        registerStubPod("orchestrator-allocD-cap", "alloc-cap-east", appId);

        // FLEET_SIZE_EXCEEDED — count 10 > test-config cap of 5 (checked
        // before application resolution, so no application field is needed).
        String over = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "fleetAllocation": [
                    { "region": "alloc-cap-east", "count": 10 }
                  ]
                }
                """;
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FLEET_SIZE_EXCEEDED"))
                .andExpect(jsonPath("$.requested").value(10))
                .andExpect(jsonPath("$.max").value(5));

        // A registered app targeting a region with no capacity row → 409
        // APPLICATION_CAPACITY_EXCEEDED. (Auto-seed only covers us-east /
        // us-west, so never-configured-region has no row.)
        String unconfigured = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "alloc-validation-svc",
                  "fleetAllocation": [
                    { "region": "never-configured-region", "count": 1 }
                  ]
                }
                """;
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unconfigured))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.region").value("never-configured-region"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("no capacity configured")));
    }

    @Test
    @DisplayName("UI-D3 — application is persisted on the run record + round-trips on GET")
    void applicationFieldRoundTrips() throws Exception {
        registerAppPod("checkout-svc", "ui-d3-app-east", "orchestrator-app-east", 1);
        String body = """
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application":    "checkout-svc",
                  "fleetAllocation": [
                    { "region": "ui-d3-app-east", "count": 1 }
                  ]
                }
                """;
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.application").value("checkout-svc"))
                .andReturn();
        String runId = mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();

        // Round-trip via GET /runs/{id}.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("checkout-svc"));
    }

    // ── KAFKA-PER-APP Phase C — per-run topic dispatch ───────────────────

    @Test
    @DisplayName("KAFKA-PER-APP Phase C — registered-app run sends kafkaTopic = jmeter.metrics.<app> in the fan-out body")
    void perAppKafkaTopicInFanoutBody() throws Exception {
        registerAppPod("perapp-svc", "perapp-region-east", "orchestrator-perapp-east", 1);
        String runId = mapper.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "perapp-svc",
                                  "fleetAllocation": [
                                    { "region": "perapp-region-east", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString())
                .get("runId").asText();

        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath(
                        "$.[?(@.runId == '" + runId + "' && @.kafkaTopic == 'jmeter.metrics.perapp-svc')]")));
    }

    @Test
    @DisplayName("KAFKA-PER-APP Phase C — untagged run (no application) omits kafkaTopic (local-orch falls back to env default)")
    void legacyRunOmitsKafkaTopic() throws Exception {
        // An untagged run carries no application, so it uses the cross-region
        // claimIdle path (no fleetAllocation) — the only way to launch a run
        // without an app now that allocation-based runs require a registered
        // one (Phase 6b). The stub pod binds to the default app, but claimIdle
        // is app-agnostic, so the untagged run still claims it.
        registerStubPod("orchestrator-legacy-east", "legacy-region-east");
        String runId = mapper.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "fleetSize": 1 }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString())
                .get("runId").asText();

        // The fan-out body for an untagged run must not carry kafkaTopic — the
        // local-orch falls back to its KAFKA_TOPIC env default
        // (jmeter.metrics.perSecond) for the pre-Phase-A wire shape until
        // Phase E retires the legacy topic.
        wireMock.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/test"))
                .withRequestBody(WireMock.matchingJsonPath(
                        "$.[?(@.runId == '" + runId + "' && !@.kafkaTopic)]")));
    }

    @Test
    @DisplayName("UI-D3 — ?application= filters listings; ?offset+limit paginate; X-Total-Count header drives the paginator")
    void applicationFilterAndPagination() throws Exception {
        // Launch 3 runs against `payment-api` and 2 against `search-svc`.
        // launchRun self-provisions one app-bound pod per call (each claim
        // turns a pod RUNNING and it stays that way for the rest of the test).
        for (int i = 0; i < 3; i++) launchRun("payment-api", "ui-d3-region-east");
        for (int i = 0; i < 2; i++) launchRun("search-svc",  "ui-d3-region-east");

        // Filter to payment-api only.
        MvcResult paymentResult = mvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/runs?application=payment-api&limit=25"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andReturn();
        var paymentBody = mapper.readTree(paymentResult.getResponse().getContentAsString());
        assertThat(paymentBody.size()).isEqualTo(3);
        for (var n : paymentBody) {
            assertThat(n.get("application").asText()).isEqualTo("payment-api");
        }

        // Filter to search-svc only.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs?application=search-svc&limit=25"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$.length()").value(2));

        // Pagination — limit=2 against payment-api → page-1 has 2, page-2 has 1.
        mvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/runs?application=payment-api&offset=0&limit=2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/runs?application=payment-api&offset=2&limit=2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$.length()").value(1));

        // No application filter → unfiltered total includes all 5 (plus any
        // runs from prior tests in this class). At least 5 rows.
        MvcResult allResult = mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs?limit=200"))
                .andExpect(status().isOk())
                .andReturn();
        long total = Long.parseLong(allResult.getResponse().getHeader("X-Total-Count"));
        assertThat(total).isGreaterThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("D-AppRegistry — POST /applications round-trips; duplicate name → 409; bad name → 400")
    void applicationRegistryCrud() throws Exception {
        // Round-trip create.
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "appreg-checkout",
                                  "sealId": "CKT-001",
                                  "description": "checkout team",
                                  "healthEndpoints": ["https://example.com/healthz"] }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("appreg-checkout"))
                .andExpect(jsonPath("$.sealId").value("CKT-001"))
                .andExpect(jsonPath("$.healthEndpoints[0]").value("https://example.com/healthz"))
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
        assertThat(appId).hasSize(26);

        // Round-trip via GET /:id.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("checkout team"));

        // Duplicate name → 409.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"appreg-checkout\", \"description\": \"clash\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NAME_TAKEN"));

        // Bad name regex → 400.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"Caps-Are-Bad\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Non-http endpoint → 400.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "appreg-nonhttp",
                                  "healthEndpoints": ["ftp://nope"] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("D-AppRegistry — PUT updates editable fields; DELETE is idempotent (204 on unknown id)")
    void applicationRegistryUpdateAndDelete() throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"appreg-edit\", \"description\": \"v1\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();

        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "appreg-edit",
                                  "sealId": "EDIT-002",
                                  "description": "v2",
                                  "healthEndpoints": ["http://localhost:9000/h"] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("v2"));

        // DELETE on unknown id is still 204 (idempotent).
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", "01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
                .andExpect(status().isNoContent());

        // DELETE on the real id removes it.
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", appId))
                .andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications/{id}", appId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("D-Capacity v2 — per-region maxAvailable enforced (409 with region in body)")
    void perRegionCapacityExceeded() throws Exception {
        // Register the app — capacity in the body is IGNORED post v2-polish
        // (capacity is sponsor-controlled). Test seeds maxAvailable=1 in
        // cap-region directly via the repository (mirrors what the future
        // sponsor-approval workflow will do).
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"cap-svc\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
        capacityRepo.upsert(appId, "cap-region", 1);

        // 2 stub pods so capacity (not pod availability) is the first ceiling.
        // Phase 4: pods are bound to the registered app — registered-app
        // runs only claim from their own pool.
        registerStubPod("orchestrator-cap-1", "cap-region", appId);
        registerStubPod("orchestrator-cap-2", "cap-region", appId);

        // First run claims 1 — fits, succeeds.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "cap-svc",
                                  "fleetAllocation": [
                                    { "region": "cap-region", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isCreated());

        // Second run requests 1 more — 2/1 > cap → 409.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "cap-svc",
                                  "fleetAllocation": [
                                    { "region": "cap-region", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.application").value("cap-svc"))
                .andExpect(jsonPath("$.region").value("cap-region"))
                .andExpect(jsonPath("$.max").value(1))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.requested").value(1));
    }

    @Test
    @DisplayName("D-Capacity v2 — request against an unconfigured region → 409")
    void noCapacityForRegion() throws Exception {
        // App auto-seeds at 0 for us-east + us-west; the run targets a
        // region that has no capacity row at all. The pod is bound to the app
        // (Phase 6b requires it) but is never reached — the capacity-grid
        // check rejects the run before any claim.
        String appId = ensureApp("noregion-svc");
        registerStubPod("orchestrator-noregion-1", "unconfigured-region", appId);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "noregion-svc",
                                  "fleetAllocation": [
                                    { "region": "unconfigured-region", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.region").value("unconfigured-region"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no capacity configured")));
    }

    @Test
    @DisplayName("D-Capacity v2 polish — newly-registered apps auto-seed us-east + us-west at 0 (operator-set is gone)")
    void registerAutoSeedsZeroCapacity() throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        // Body's `capacity` field is ignored — sponsor-controlled.
                        .content("{ \"name\": \"autoseed-svc\", \"capacity\": [{ \"region\": \"x\", \"maxAvailable\": 99 }] }"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = mapper.readTree(create.getResponse().getContentAsString());
        // One seeded row (us-east-1) at 0; the ignored x:99 is nowhere.
        // Operators add the other USA regions via the Capacity region picker.
        JsonNode cap = body.get("capacity");
        assertThat(cap.size()).isEqualTo(1);
        assertThat(cap.get(0).get("region").asText()).isEqualTo("us-east-1");
        assertThat(cap.get(0).get("maxAvailable").asInt()).isZero();
    }

    @Test
    @DisplayName("Phase 3 capacity rework — PUT /applications/.../capacity/{region} sets maxAvailable directly")
    void putCapacityMaxAvailable() throws Exception {
        // Replaces the legacy requestCapacityIncreaseStub (sponsor-gate test)
        // dropped during the Phase 3 capacity rework — the operator now sets
        // maxAvailable directly via PUT, no sponsor approval workflow.
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"req-svc\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
        mvc.perform(MockMvcRequestBuilders.put(
                        "/api/v1/applications/{id}/capacity/{region}", appId, "us-east")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"maxAvailable\": 50 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(appId))
                .andExpect(jsonPath("$.region").value("us-east"))
                .andExpect(jsonPath("$.maxAvailable").value(50));
    }

    @Test
    @DisplayName("Region picker — add region via PUT, remove via DELETE; drain-first 409 when workers exist")
    void addAndRemoveRegion() throws Exception {
        String appId = createApp("region-picker-svc");
        // Add two regions via PUT (upsert creates the capacity rows).
        for (String r : new String[]{"us-west-2", "us-east-2"}) {
            mvc.perform(MockMvcRequestBuilders.put(
                            "/api/v1/applications/{id}/capacity/{region}", appId, r)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"maxAvailable\": 0 }"))
                    .andExpect(status().isOk());
        }

        // A worker provisioned in us-west-2 blocks removal → 409 REGION_NOT_EMPTY.
        registerStubPod("region-picker-worker-1", "us-west-2", appId);
        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/applications/{id}/capacity/{region}", appId, "us-west-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGION_NOT_EMPTY"));

        // A pod-free region (us-east-2) removes cleanly (204), then 404 on re-delete.
        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/applications/{id}/capacity/{region}", appId, "us-east-2"))
                .andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.delete(
                        "/api/v1/applications/{id}/capacity/{region}", appId, "us-east-2"))
                .andExpect(status().isNotFound());
    }

    // ── Phase 4 of the capacity rework — app-bound run claim ─────────

    @Test
    @DisplayName("Phase 4 — registered-app run cannot claim another registered app's pods (isolation)")
    void appBoundClaimIsolatesRegisteredApps() throws Exception {
        // Two registered apps in the same region. App A has 1 pod;
        // App B has 1 pod. Launching a run for App A with fleet=1 must
        // claim App A's pod — never App B's — even though both are
        // IDLE in the same region.
        String appAId = createApp("isolation-app-a");
        String appBId = createApp("isolation-app-b");
        String region = "isolation-region";
        capacityRepo.upsert(appAId, region, 1);
        capacityRepo.upsert(appBId, region, 1);
        registerStubPod("iso-app-a-worker-1", region, appAId);
        registerStubPod("iso-app-b-worker-1", region, appBId);

        // The fleet-members workerId proves A's pod (not B's) was claimed —
        // a jsonPath value mismatch would also catch any cross-app claim.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "isolation-app-a",
                                  "fleetAllocation": [
                                    { "region": "isolation-region", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fleetMembers[0].workerId").value("iso-app-a-worker-1"));
    }

    @Test
    @DisplayName("Phase 4 — registered-app run with no Ready pods returns 503 INSUFFICIENT_CAPACITY (no cross-app theft)")
    void appBoundClaimRejectsWhenAppHasNoPods() throws Exception {
        // App A has Max=1 but no pods provisioned (cap-check passes since
        // active=0+1<=1). Another app has a Ready pod in the same region
        // — it must NOT be claimable by App A. Result: shortfall → 503.
        String appAId = createApp("no-pods-app-a");
        String appBId = createApp("no-pods-app-b");
        String region = "no-pods-region";
        capacityRepo.upsert(appAId, region, 1);
        capacityRepo.upsert(appBId, region, 1);
        // Only B has a pod.
        registerStubPod("no-pods-app-b-worker-1", region, appBId);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "no-pods-app-a",
                                  "fleetAllocation": [
                                    { "region": "no-pods-region", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));
    }

    @Test
    @DisplayName("Phase 6b — allocation run naming an unregistered application → 409 (legacy null-app pool is gone)")
    void allocationRunRequiresRegisteredApp() throws Exception {
        // A pod exists in the region, but the run names an application that
        // was never registered. Before Phase 6b this fell through to the
        // legacy null-app pool; now there is no pool to fall back to, so the
        // run is rejected with 409 APPLICATION_CAPACITY_EXCEEDED.
        String region = "phase6b-claim-region";
        registerStubPod("phase6b-pod", region);   // default-app bound

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                                  "application":    "not-a-registered-app",
                                  "fleetAllocation": [
                                    { "region": "phase6b-claim-region", "count": 1 }
                                  ] }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unregistered application")));
    }

    private String createApp(String name) throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
    }

    private void launchRun(String application, String region) throws Exception {
        // Phase 6b: an allocation-based run claims from its app's own pod
        // pool, so provision one app-bound pod (+ ensure app & capacity)
        // per launch. Capacity is generous so repeated launches for the
        // same app don't trip the per-(app,region) cap.
        String appId = ensureApp(application);
        capacityRepo.upsert(appId, region, 50);
        registerStubPod(application + "-" + region + "-pod-" + (podSeq++), region, appId);
        String body = String.format("""
                {
                  "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application":    "%s",
                  "fleetAllocation": [
                    { "region": "%s", "count": 1 }
                  ]
                }
                """, application, region);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
