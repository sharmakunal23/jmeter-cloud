package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.perf.globalorchestrator.sweep.CronJobScheduler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * AUTOMATION Phase A+B — end-to-end IT for the CRON-schedule surface. Mirrors
 * {@link RunManagementIT}'s harness (Testcontainers Postgres + Flyway, a
 * WireMock that doubles as BOTH the document-service blob API and the
 * local-orchestrator fan-out target, mocked Kafka/spin beans), and asserts:
 *
 * <ol>
 *   <li>create → list ({@code {items:[…]}}) → get; {@code nextFireAt} seeded;
 *       {@code createdBy} from {@code X-Actor}; duplicate (app,name) → 409.</li>
 *   <li>validation — bad cron → 400 INVALID_CRON, unknown app → 400
 *       UNKNOWN_APPLICATION, unfetchable template → 400 TEMPLATE_UNAVAILABLE.</li>
 *   <li>enable / disable toggles {@code enabled} + {@code nextFireAt}.</li>
 *   <li>{@code fireNow} launches a run (LAUNCHED) and records last-fire +
 *       a history row.</li>
 *   <li>the DB-claim sweep fires a due schedule as the {@code system:scheduler}
 *       actor and advances {@code nextFireAt} catch-up-once (next FUTURE slot).</li>
 *   <li>{@code fireNow} with no capacity → SKIPPED (not a hard failure).</li>
 *   <li>delete → 204, then 404.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator CRON-schedule lifecycle — behavior IT")
class CronJobLifecycleIT {

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
        // Disable both background sweeps — we drive firing deterministically
        // via fireNow + a direct scheduler.sweep() call. (delay > test wall-time.)
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        registry.add("globalOrchestrator.automation.sweepInitialDelayMs", () -> "3600000");
        // The scheduler's DocumentServiceClient fetches templates from WireMock.
        registry.add("documentService.baseUrl", () -> wireMock.baseUrl());
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
        // Local-orchestrator fan-out target (the run launch fans out here).
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"PREPARING\",\"startedAt\":null}")));
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/actuator/health"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"UP\"}")));
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"RUNNING\",\"startedAt\":\"2026-05-27T12:00:00Z\","
                                + "\"completedAt\":null,\"elapsedMs\":1000,\"rowsIngested\":1,"
                                + "\"windowsPublished\":1,\"kafkaSendErrors\":0,\"jmeterAlive\":true}")));
    }

    @AfterAll
    static void stopStub() {
        if (wireMock != null) wireMock.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired com.perf.globalorchestrator.repo.ApplicationCapacityRepository capacityRepo;
    @Autowired CronJobScheduler scheduler;
    @Autowired @Qualifier("runStateJdbcTemplate") JdbcTemplate jdbc;
    @MockBean com.perf.globalorchestrator.kafka.KafkaTopicProvisioner topicProvisioner;
    @MockBean com.perf.globalorchestrator.provision.PodSpinService spinService;
    // Phase E — capture report emails instead of sending them (no MailHog in the IT).
    @MockBean com.perf.globalorchestrator.email.EmailSender emailSender;

    private final java.util.Map<String, String> appIdCache = new java.util.HashMap<>();

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("create → list → get; nextFireAt seeded; createdBy from X-Actor; duplicate → 409")
    void createListGet() throws Exception {
        ensureApp("cron-crud-svc");
        stubTemplate("tpl-crud", "cron-crud-svc", "us-east-1");

        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .header("X-Actor", "kunal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cronBody("nightly-crud", "cron-crud-svc", "tpl-crud", "0 2 * * *", "UTC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cronJobId").exists())
                .andExpect(jsonPath("$.name").value("nightly-crud"))
                .andExpect(jsonPath("$.applicationName").value("cron-crud-svc"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.createdBy").value("kunal"))
                .andExpect(jsonPath("$.nextFireAt").exists())
                .andReturn();
        String id = field(create, "cronJobId");
        assertThat(id).hasSize(26);

        // List returns the {items:[…]} shape the UI stub expects.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs?application=cron-crud-svc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].cronJobId").value(id));

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("nightly-crud"));

        // Duplicate (applicationName, name) → 409.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cronBody("nightly-crud", "cron-crud-svc", "tpl-crud", "0 3 * * *", "UTC")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CRON_JOB_CONFLICT"));
    }

    @Test
    @DisplayName("validation — bad cron → 400 INVALID_CRON, unknown app → 400 UNKNOWN_APPLICATION, missing template → 400 TEMPLATE_UNAVAILABLE")
    void validation() throws Exception {
        ensureApp("cron-valid-svc");
        stubTemplate("tpl-valid", "cron-valid-svc", "us-east-1");

        // Bad cron (app is valid → cron validation is reached).
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cronBody("bad-cron", "cron-valid-svc", "tpl-valid", "not a cron", "UTC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CRON"));

        // Unknown application.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cronBody("ghost", "no-such-app", "tpl-valid", "0 2 * * *", "UTC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_APPLICATION"));

        // Unfetchable template (WireMock 404s an unstubbed blob).
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cronBody("missing-tpl", "cron-valid-svc", "tpl-does-not-exist", "0 2 * * *", "UTC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("enable / disable toggles enabled + clears/sets nextFireAt")
    void enableDisable() throws Exception {
        ensureApp("cron-toggle-svc");
        stubTemplate("tpl-toggle", "cron-toggle-svc", "us-east-1");
        String id = createCron("toggle", "cron-toggle-svc", "tpl-toggle", "0 2 * * *");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/disable", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextFireAt").doesNotExist());

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/enable", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextFireAt").exists());
    }

    @Test
    @DisplayName("skipNext advances nextFireAt one occurrence; disabled → 409 NOTHING_TO_SKIP")
    void skipNext() throws Exception {
        ensureApp("cron-skip-svc");
        stubTemplate("tpl-skip", "cron-skip-svc", "us-east-1");
        String id = createCron("skip-me", "cron-skip-svc", "tpl-skip", "0 2 * * *");

        MvcResult before = mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isOk()).andReturn();
        String firstNext = field(before, "nextFireAt");

        MvcResult after = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/skipNext", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();
        // Daily 2am → the next slot is one day later, so the advanced fire sorts after.
        assertThat(field(after, "nextFireAt")).isGreaterThan(firstNext);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/disable", id))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/skipNext", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_SKIP"));
    }

    @Test
    @DisplayName("fireNow launches a run (LAUNCHED) and records last-fire + a history row")
    void fireNowLaunches() throws Exception {
        registerAppPod("cron-fire-svc", "us-east-1", "cron-fire-pod", 1);
        stubTemplate("tpl-fire", "cron-fire-svc", "us-east-1");
        String id = createCron("fire-me", "cron-fire-svc", "tpl-fire", "0 2 * * *");

        MvcResult fire = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id)
                        .header("X-Actor", "kunal"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("LAUNCHED"))
                .andExpect(jsonPath("$.runId").exists())
                .andReturn();
        String runId = field(fire, "runId");
        assertThat(runId).hasSize(26);

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastFireStatus").value("LAUNCHED"))
                .andExpect(jsonPath("$.lastFiredRunId").value(runId));

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("LAUNCHED"))
                .andExpect(jsonPath("$.items[0].runId").value(runId));
    }

    @Test
    @DisplayName("DB-claim sweep fires a due schedule as system:scheduler and advances nextFireAt catch-up-once")
    void sweepFiresDueAsSystem() throws Exception {
        registerAppPod("cron-sweep-svc", "us-east-1", "cron-sweep-pod", 1);
        stubTemplate("tpl-sweep", "cron-sweep-svc", "us-east-1");
        String id = createCron("sweep-me", "cron-sweep-svc", "tpl-sweep", "0 2 * * *");

        // Force the schedule due (nextFireAt in the past), then run the sweep
        // directly — exercises claimDue (FOR UPDATE SKIP LOCKED) + fire.
        jdbc.update("UPDATE \"globalOrchestrator\".\"cronJob\" "
                + "SET \"nextFireAt\" = now() - interval '1 minute', \"claimedAt\" = NULL "
                + "WHERE \"cronJobId\"=?", id);

        scheduler.sweep();

        // Fired as LAUNCHED; nextFireAt advanced to a FUTURE slot (catch-up-once).
        MvcResult after = mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastFireStatus").value("LAUNCHED"))
                .andExpect(jsonPath("$.lastFiredRunId").exists())
                .andExpect(jsonPath("$.nextFireAt").exists())
                .andReturn();
        String runId = field(after, "lastFiredRunId");
        String nextFireAt = field(after, "nextFireAt");
        assertThat(java.time.Instant.parse(nextFireAt))
                .as("nextFireAt advanced to a future slot")
                .isAfter(java.time.Instant.now());

        // The launched run's audit trail attributes the start to system:scheduler.
        // GET /runs/{id}/events returns a bare JSON array (count in X-Total-Count).
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType=='RUN_START')].actor")
                        .value(org.hamcrest.Matchers.hasItem("scheduler")))
                .andExpect(jsonPath("$[?(@.eventType=='RUN_START')].actorSource")
                        .value(org.hamcrest.Matchers.hasItem("system")));
    }

    @Test
    @DisplayName("fireNow with no capacity → SKIPPED (not a hard failure)")
    void fireNowSkippedNoCapacity() throws Exception {
        ensureApp("cron-nocap-svc"); // registered, but no capacity / no pods
        stubTemplate("tpl-nocap", "cron-nocap-svc", "us-east-1");
        String id = createCron("nocap", "cron-nocap-svc", "tpl-nocap", "0 2 * * *");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SKIPPED"));

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("SKIPPED"));
    }

    // ── Phase C — kind dispatch (DRAIN_REGION / PROVISION_REGION) + alwaysOn ──

    @Test
    @DisplayName("Phase C — DRAIN_REGION without region → 400 INVALID_REQUEST; unconfigured region → 400 REGION_NOT_CONFIGURED")
    void createDrainRegionValidation() throws Exception {
        // App auto-seeds a us-east-1 capacity row (max 0), so use a NON-seeded
        // region (us-west-2) for the "not configured" case.
        ensureApp("cron-drain-val");
        // Missing region.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kindBody("drain-no-region", "cron-drain-val",
                                "DRAIN_REGION", /* region */ null, "0 19 * * *")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // Region with no capacity row → REGION_NOT_CONFIGURED.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kindBody("drain-bad-region", "cron-drain-val",
                                "DRAIN_REGION", "us-west-2", "0 19 * * *")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("Phase C — DRAIN_REGION round-trip: kind/region persisted, templateBlobId null")
    void createDrainRegionRoundTrip() throws Exception {
        String appId = ensureApp("cron-drain-rt");
        capacityRepo.upsert(appId, "us-east-1", 2);
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kindBody("nightly-drain", "cron-drain-rt",
                                "DRAIN_REGION", "us-east-1", "0 19 * * *")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("DRAIN_REGION"))
                .andExpect(jsonPath("$.region").value("us-east-1"))
                .andExpect(jsonPath("$.templateBlobId").doesNotExist())
                .andReturn();
        String id = field(res, "cronJobId");
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DRAIN_REGION"))
                .andExpect(jsonPath("$.region").value("us-east-1"));
    }

    @Test
    @DisplayName("Phase C — DRAIN_REGION on alwaysOn app fires SKIPPED (production-like protection)")
    void fireDrainAlwaysOnSkips() throws Exception {
        String appId = ensureApp("cron-drain-alwayson");
        capacityRepo.upsert(appId, "us-east-1", 2);
        // Flip alwaysOn on via PUT.
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cron-drain-alwayson\",\"alwaysOn\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alwaysOn").value(true));
        String id = createDrainCron("alwayson-drain", "cron-drain-alwayson", "us-east-1");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("alwaysOn")));
    }

    @Test
    @DisplayName("Phase C — DRAIN_REGION with no IDLE workers fires LAUNCHED with 'drained 0/0' detail")
    void fireDrainNoIdleWorkers() throws Exception {
        String appId = ensureApp("cron-drain-empty");
        capacityRepo.upsert(appId, "us-east-1", 2); // no pods registered
        String id = createDrainCron("empty-drain", "cron-drain-empty", "us-east-1");

        MvcResult fire = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("LAUNCHED"))
                .andReturn();
        // The fire-history reason carries the summary detail.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("LAUNCHED"))
                .andExpect(jsonPath("$.items[0].errorReason").value(org.hamcrest.Matchers.containsString("drained 0/0")));
        assertThat(field(fire, "runId")).isNull();
    }

    @Test
    @DisplayName("Phase C — PROVISION_REGION fires spinService.spin once per missing worker (gap = max - current)")
    void fireProvisionSpinsForGap() throws Exception {
        String appId = ensureApp("cron-prov");
        capacityRepo.upsert(appId, "us-east-1", 2); // 2 max, 0 current → gap 2
        // Stub spinService to return a synthetic SpinResult (the IT doesn't care about the value).
        org.mockito.Mockito.when(spinService.spin(
                org.mockito.ArgumentMatchers.eq(appId),
                org.mockito.ArgumentMatchers.eq("cron-prov"),
                org.mockito.ArgumentMatchers.eq("us-east-1")))
            .thenReturn(new com.perf.globalorchestrator.provision.PodSpinService.SpinResult(
                    "stub-pod", wireMock.baseUrl(), "stub-digest", java.time.Instant.now()));
        String id = createCronWithKind("provision-up", "cron-prov", "us-east-1", "PROVISION_REGION");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("LAUNCHED"));

        org.mockito.Mockito.verify(spinService, org.mockito.Mockito.times(2))
                .spin(appId, "cron-prov", "us-east-1");
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}/history", id))
                .andExpect(jsonPath("$.items[0].errorReason").value(
                        org.hamcrest.Matchers.containsString("provisioned 2/2")));
    }

    // ── Phase E — INFRA_READINESS report kind ──────────────────────────────

    @Test
    @DisplayName("Phase E — INFRA_READINESS round-trips: null app, recipients stored, no template/region")
    void createInfraReadinessRoundTrip() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .header("X-Actor", "kunal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"daily-infra\",\"kind\":\"INFRA_READINESS\","
                                + "\"cronExpression\":\"0 7 * * *\",\"timeZone\":\"UTC\","
                                + "\"recipients\":\"ops@example.com, sre@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("INFRA_READINESS"))
                .andExpect(jsonPath("$.applicationName").doesNotExist())
                .andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.templateBlobId").doesNotExist())
                .andExpect(jsonPath("$.recipients").value("ops@example.com, sre@example.com"))
                .andReturn();
        String id = field(res, "cronJobId");
        // Platform-name uniqueness: a second INFRA_READINESS with the same name → 409.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"daily-infra\",\"kind\":\"INFRA_READINESS\","
                                + "\"cronExpression\":\"0 8 * * *\",\"recipients\":\"x@y.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CRON_JOB_CONFLICT"));
        assertThat(id).hasSize(26);
    }

    @Test
    @DisplayName("Phase E — fireNow INFRA_READINESS composes + emails (LAUNCHED); history records it")
    void fireInfraReadinessSendsEmail() throws Exception {
        org.mockito.Mockito.when(emailSender.backend()).thenReturn("smtp");
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"infra-fire\",\"kind\":\"INFRA_READINESS\","
                                + "\"cronExpression\":\"0 7 * * *\",\"recipients\":\"ops@example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = field(res, "cronJobId");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("LAUNCHED"));

        org.mockito.Mockito.verify(emailSender).send(
                org.mockito.ArgumentMatchers.eq(java.util.List.of("ops@example.com")),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}/history", id))
                .andExpect(jsonPath("$.items[0].outcome").value("LAUNCHED"));
    }

    @Test
    @DisplayName("Phase E — fireNow INFRA_READINESS with no recipients → SKIPPED (no email)")
    void fireInfraReadinessNoRecipientsSkips() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"infra-norcpt\",\"kind\":\"INFRA_READINESS\","
                                + "\"cronExpression\":\"0 7 * * *\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = field(res, "cronJobId");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("recipients")));
        org.mockito.Mockito.verifyNoInteractions(emailSender);
    }

    // ── Phase D — DAILY_REPORT report kind ─────────────────────────────────

    @Test
    @DisplayName("Phase D — DAILY_REPORT round-trips: null app, recipients stored, no template/region")
    void createDailyReportRoundTrip() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .header("X-Actor", "kunal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"daily-perf\",\"kind\":\"DAILY_REPORT\","
                                + "\"cronExpression\":\"0 6 * * *\",\"timeZone\":\"UTC\","
                                + "\"recipients\":\"perf@example.com\","
                                + "\"customSubject\":\"Nightly perf — prod\","
                                + "\"customIntro\":\"Heads up team, overnight summary.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("DAILY_REPORT"))
                .andExpect(jsonPath("$.applicationName").doesNotExist())
                .andExpect(jsonPath("$.region").doesNotExist())
                .andExpect(jsonPath("$.templateBlobId").doesNotExist())
                .andExpect(jsonPath("$.recipients").value("perf@example.com"))
                .andExpect(jsonPath("$.customSubject").value("Nightly perf — prod"))
                .andExpect(jsonPath("$.customIntro").value("Heads up team, overnight summary."))
                .andReturn();
        String id = field(res, "cronJobId");
        assertThat(id).hasSize(26);
        // Round-trips on a fresh GET too (persisted, not just echoed).
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customSubject").value("Nightly perf — prod"))
                .andExpect(jsonPath("$.customIntro").value("Heads up team, overnight summary."));
    }

    @Test
    @DisplayName("Phase D — fireNow DAILY_REPORT composes + emails (LAUNCHED); no recipients → SKIPPED")
    void fireDailyReportSendsEmail() throws Exception {
        org.mockito.Mockito.when(emailSender.backend()).thenReturn("smtp");
        // With recipients → LAUNCHED + email sent.
        MvcResult withRcpt = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"daily-fire\",\"kind\":\"DAILY_REPORT\","
                                + "\"cronExpression\":\"0 6 * * *\",\"recipients\":\"perf@example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", field(withRcpt, "cronJobId")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("LAUNCHED"))
                .andExpect(jsonPath("$.runId").doesNotExist());
        org.mockito.Mockito.verify(emailSender).send(
                org.mockito.ArgumentMatchers.eq(java.util.List.of("perf@example.com")),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        // Without recipients → SKIPPED (no email).
        MvcResult noRcpt = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"daily-norcpt\",\"kind\":\"DAILY_REPORT\","
                                + "\"cronExpression\":\"0 6 * * *\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs/{id}/fireNow", field(noRcpt, "cronJobId")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("recipients")));
    }

    @Test
    @DisplayName("Phase E/D — report preview endpoints render subject + html + report")
    void reportPreviews() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/automation/reports/infraReadiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").exists())
                .andExpect(jsonPath("$.html").exists())
                .andExpect(jsonPath("$.report.backends").isArray())
                .andExpect(jsonPath("$.report.allClear").exists());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/automation/reports/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").exists())
                .andExpect(jsonPath("$.html").exists())
                .andExpect(jsonPath("$.report.apps").isArray())
                .andExpect(jsonPath("$.report.totalRuns").exists());
        // Custom subject/intro query params preview unsaved tailoring exactly as it sends.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/automation/reports/daily")
                        .param("customSubject", "My custom subject")
                        .param("customIntro", "An intro the operator typed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("My custom subject"))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("An intro the operator typed")));
    }

    @Test
    @DisplayName("delete → 204, then 404")
    void deleteThen404() throws Exception {
        ensureApp("cron-del-svc");
        stubTemplate("tpl-del", "cron-del-svc", "us-east-1");
        String id = createCron("delete-me", "cron-del-svc", "tpl-del", "0 2 * * *");

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/cronJobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CRON_JOB_NOT_FOUND"));
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private String field(MvcResult res, String name) throws Exception {
        JsonNode node = mapper.readTree(res.getResponse().getContentAsString()).get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String cronBody(String name, String app, String tpl, String cron, String tz) {
        return "{\"name\":\"" + name + "\",\"applicationName\":\"" + app + "\","
                + "\"templateBlobId\":\"" + tpl + "\",\"cronExpression\":\"" + cron + "\","
                + "\"timeZone\":\"" + tz + "\"}";
    }

    private String createCron(String name, String app, String tpl, String cron) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cronBody(name, app, tpl, cron, "UTC")))
                .andExpect(status().isCreated())
                .andReturn();
        return field(res, "cronJobId");
    }

    /** Phase C — DRAIN/PROVISION request body (no template; region required). */
    private static String kindBody(String name, String app, String kind, String region, String cron) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(name).append("\",")
                .append("\"applicationName\":\"").append(app).append("\",")
                .append("\"cronExpression\":\"").append(cron).append("\",")
                .append("\"timeZone\":\"UTC\",\"kind\":\"").append(kind).append("\"");
        if (region != null) sb.append(",\"region\":\"").append(region).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String createDrainCron(String name, String app, String region) throws Exception {
        return createCronWithKind(name, app, region, "DRAIN_REGION");
    }

    private String createCronWithKind(String name, String app, String region, String kind) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/cronJobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kindBody(name, app, kind, region, "0 19 * * *")))
                .andExpect(status().isCreated())
                .andReturn();
        return field(res, "cronJobId");
    }

    /** Stub document-service's blob GET to return a TemplateBody for {@code blobId}. */
    private void stubTemplate(String blobId, String application, String region) {
        String body = "{\"v\":1,\"application\":\"" + application + "\","
                + "\"testPlanBlobId\":\"01HXC2VQK4M9N6P5T0YBX2WZ4Q\","
                + "\"fleetAllocation\":[{\"region\":\"" + region + "\",\"count\":1}],"
                + "\"saveResults\":false}";
        wireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/blob/" + blobId))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

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
            if (name.equals(app.path("name").asText())) return app.path("applicationId").asText();
        }
        throw new IllegalStateException("application not found after create: " + name);
    }

    private String registerAppPod(String appName, String region, String podId, int max) throws Exception {
        String appId = ensureApp(appName);
        capacityRepo.upsert(appId, region, max);
        String body = "{\"podId\":\"" + podId + "\",\"region\":\"" + region + "\","
                + "\"baseUrl\":\"" + wireMock.baseUrl() + "\",\"applicationId\":\"" + appId + "\"}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        return appId;
    }
}
