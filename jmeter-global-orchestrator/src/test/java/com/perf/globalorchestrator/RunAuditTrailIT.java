package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.RunEvent;
import com.perf.globalorchestrator.domain.RunEventType;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.repo.RunEventRepository;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUDIT-TRAIL Phase D — behavior IT for the per-run audit timeline.
 *
 * <p>Mirrors the {@link RunScaleUpIT} harness: Testcontainers Postgres
 * (canonical Flyway migrations applied as superuser in {@code @BeforeAll} —
 * V15 creates {@code runEvent}), a WireMock-stubbed local orchestrator,
 * MockMvc for the controller layer.
 *
 * <p>Covers the Phase D + quality-bar gates:
 * <ol>
 *   <li>Full lifecycle — launch (alice) → scaleUp (bob) → scaleDown (carol)
 *       → {@code GET /events} returns the 3-event timeline newest-first with
 *       correct actors, actorSource, results, and payload shapes.</li>
 *   <li>Rejected actions ARE recorded — a capacity-exceeded scaleUp emits a
 *       {@code SCALE_UP} event with {@code result="rejected:APPLICATION_CAPACITY_EXCEEDED"}.</li>
 *   <li>Audit invariant — a forced failure mid-mutation rolls back the
 *       members AND the event together (zero new events, decision #7).</li>
 *   <li>A single explicit-workerId drain is classified {@code DRAIN_WORKER}.</li>
 *   <li>No X-Actor header → {@code actor="anonymous"}, {@code actorSource="anonymous"}.</li>
 *   <li>Idempotency — a same-eventId insert is silently dropped.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator audit trail — behavior IT")
class RunAuditTrailIT {

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
                .willReturn(WireMock.aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"PREPARING\",\"startedAt\":null}")));
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"RUNNING\","
                                + "\"startedAt\":\"2026-05-26T12:00:00Z\","
                                + "\"completedAt\":null,\"elapsedMs\":1000,"
                                + "\"rowsIngested\":42,\"windowsPublished\":3,"
                                + "\"kafkaSendErrors\":0,\"jmeterAlive\":true}")));
        // Drain endpoint stub returns 202 ACCEPTED.
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test/drain"))
                .willReturn(WireMock.aResponse().withStatus(202)
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
    /** SpyBean so the rollback test can force insert() to throw for one event type. */
    @SpyBean RunEventRepository auditEvents;
    @MockBean com.perf.globalorchestrator.kafka.KafkaTopicProvisioner topicProvisioner;

    // ── harness helpers ───────────────────────────────────────────────────

    private String createApp(String name) throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(create.getResponse().getContentAsString()).get("applicationId").asText();
    }

    private void registerStubPod(String podId, String region, String applicationId) throws Exception {
        String body = "{\"podId\":\"" + podId + "\",\"region\":\"" + region
                + "\",\"baseUrl\":\"" + wireMock.baseUrl() + "\""
                + (applicationId == null ? "" : ",\"applicationId\":\"" + applicationId + "\"") + "}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private String launchRun(String app, String region, int count, String actor) throws Exception {
        String body = String.format("""
                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q", "application": "%s",
                  "fleetAllocation": [ { "region": "%s", "count": %d } ] }
                """, app, region, count);
        var req = MockMvcRequestBuilders.post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (actor != null) req = req.header("X-Actor", actor);
        MvcResult r = mvc.perform(req)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("runId").asText();
    }

    private JsonNode getEvents(String runId) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private static JsonNode eventOfType(JsonNode events, String type) {
        for (JsonNode e : events) {
            if (type.equals(e.path("eventType").asText())) return e;
        }
        return null;
    }

    // ── 1. Full lifecycle timeline ─────────────────────────────────────────

    @Test
    @DisplayName("launch(alice) → scaleUp(bob) → scaleDown(carol) → GET /events returns the 3-event timeline newest-first")
    void fullLifecycleTimeline() throws Exception {
        String appId = createApp("audit-life");
        String region = "audit-life-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("audit-life-1", region, appId);
        registerStubPod("audit-life-2", region, appId);
        registerStubPod("audit-life-3", region, appId);

        String runId = launchRun("audit-life", region, 1, "alice");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .header("X-Actor", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{ \"allocations\": [ { \"region\": \"%s\", \"count\": 2 } ] }", region)))
                .andExpect(status().isOk());

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .header("X-Actor", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{ \"allocations\": [ { \"region\": \"%s\", \"count\": 1 } ] }", region)))
                .andExpect(status().isOk());

        JsonNode events = getEvents(runId);
        assertThat(events).hasSize(3);

        // Newest-first ordering: occurredAt is monotonically non-increasing.
        Instant prev = null;
        for (JsonNode e : events) {
            Instant cur = Instant.parse(e.get("occurredAt").asText());
            if (prev != null) assertThat(cur).isBeforeOrEqualTo(prev);
            prev = cur;
        }

        JsonNode start = eventOfType(events, "RUN_START");
        assertThat(start).isNotNull();
        assertThat(start.get("actor").asText()).isEqualTo("alice");
        assertThat(start.get("actorSource").asText()).isEqualTo(Actor.SOURCE_HEADER);
        assertThat(start.get("result").asText()).isEqualTo("ok");
        assertThat(start.get("payload").get("application").asText()).isEqualTo("audit-life");
        assertThat(start.get("payload").get("granted").asInt()).isEqualTo(1);
        assertThat(start.get("payload").get("fleetAllocation").get(0).get("region").asText()).isEqualTo(region);

        JsonNode up = eventOfType(events, "SCALE_UP");
        assertThat(up).isNotNull();
        assertThat(up.get("actor").asText()).isEqualTo("bob");
        assertThat(up.get("result").asText()).isEqualTo("ok");
        assertThat(up.get("payload").get("requested").asInt()).isEqualTo(2);
        assertThat(up.get("payload").get("granted").asInt()).isEqualTo(2);
        assertThat(up.get("payload").get("partial").asBoolean()).isFalse();

        JsonNode down = eventOfType(events, "SCALE_DOWN");
        assertThat(down).isNotNull();
        assertThat(down.get("actor").asText()).isEqualTo("carol");
        assertThat(down.get("result").asText()).isEqualTo("ok");
        assertThat(down.get("payload").get("drained")).hasSize(1);
    }

    // ── 1b. Pagination ──────────────────────────────────────────────────────

    @Test
    @DisplayName("events are paginated newest-first with X-Total-Count")
    void paginatesNewestFirstWithTotalCount() throws Exception {
        String appId = createApp("audit-page");
        String region = "audit-page-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("audit-page-1", region, appId);
        registerStubPod("audit-page-2", region, appId);
        registerStubPod("audit-page-3", region, appId);

        String runId = launchRun("audit-page", region, 1, "alice");          // RUN_START
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .header("X-Actor", "bob").contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{ \"allocations\": [ { \"region\": \"%s\", \"count\": 1 } ] }", region)))
                .andExpect(status().isOk());                                  // SCALE_UP
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .header("X-Actor", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{ \"allocations\": [ { \"region\": \"%s\", \"count\": 1 } ] }", region)))
                .andExpect(status().isOk());                                  // SCALE_DOWN

        // Page 1: limit 2 → the two newest events, total count = 3 on the header.
        MvcResult page1 = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/runs/{runId}/events?offset=0&limit=2", runId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andReturn();
        JsonNode p1 = mapper.readTree(page1.getResponse().getContentAsString());
        assertThat(p1).hasSize(2);
        // Newest first: SCALE_DOWN then SCALE_UP.
        assertThat(p1.get(0).get("eventType").asText()).isEqualTo("SCALE_DOWN");
        assertThat(p1.get(1).get("eventType").asText()).isEqualTo("SCALE_UP");

        // Page 2: offset 2 → the oldest remaining event (RUN_START), same total.
        MvcResult page2 = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/runs/{runId}/events?offset=2&limit=2", runId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andReturn();
        JsonNode p2 = mapper.readTree(page2.getResponse().getContentAsString());
        assertThat(p2).hasSize(1);
        assertThat(p2.get(0).get("eventType").asText()).isEqualTo("RUN_START");
        assertThat(p2.get(0).get("actor").asText()).isEqualTo("alice");
    }

    // ── 1c. Run-terminal event (platform-detected, system actor) ────────────

    @Test
    @DisplayName("when the platform detects the run completed, it records a RUN_COMPLETED (System) event")
    void runCompletionEmitsSystemTerminalEvent() throws Exception {
        String appId = createApp("audit-term");
        String region = "audit-term-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("audit-term-1", region, appId);
        String runId = launchRun("audit-term", region, 1, "alice");

        // Make the worker's next status poll report COMPLETED. No other test
        // polls GET /status, so overriding the shared RUNNING stub here is safe
        // (WireMock returns the most-recently-registered matching stub).
        wireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"COMPLETED\","
                                + "\"startedAt\":\"2026-05-26T12:00:00Z\","
                                + "\"completedAt\":\"2026-05-26T12:05:00Z\",\"elapsedMs\":1000,"
                                + "\"rowsIngested\":42,\"windowsPublished\":3,"
                                + "\"kafkaSendErrors\":0,\"jmeterAlive\":false}")));

        // GET /status triggers the lazy refresh → members COMPLETED → run rolls
        // up to COMPLETED → one terminal event.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/status", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        JsonNode done = eventOfType(getEvents(runId), "RUN_COMPLETED");
        assertThat(done).as("RUN_COMPLETED event present").isNotNull();
        assertThat(done.get("actorSource").asText()).isEqualTo(Actor.SOURCE_SYSTEM);
        assertThat(done.get("result").asText()).isEqualTo("ok");
        assertThat(done.get("payload").get("finalState").asText()).isEqualTo("COMPLETED");

        // Re-polling does NOT emit a second terminal event (run is already terminal).
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/status", runId))
                .andExpect(status().isOk());
        long completedCount = java.util.stream.StreamSupport
                .stream(getEvents(runId).spliterator(), false)
                .filter(e -> "RUN_COMPLETED".equals(e.get("eventType").asText()))
                .count();
        assertThat(completedCount).isEqualTo(1L);
    }

    // ── 1d. Recycle attribution query (drives PodRecycler's WORKERS_RECYCLED) ─

    @Test
    @DisplayName("findMostRecentRunIdForWorker resolves a worker's last run (recycle attribution)")
    void mostRecentRunForWorkerResolves() throws Exception {
        String appId = createApp("audit-mrr");
        String region = "audit-mrr-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("audit-mrr-1", region, appId);
        String runId = launchRun("audit-mrr", region, 1, "alice");

        assertThat(runRepo.findMostRecentRunIdForWorker("audit-mrr-1")).contains(runId);
        assertThat(runRepo.findMostRecentRunIdForWorker("no-such-worker")).isEmpty();
    }

    // ── 2. Rejected actions are recorded ────────────────────────────────────

    @Test
    @DisplayName("capacity-exceeded scaleUp records a SCALE_UP event with result rejected:APPLICATION_CAPACITY_EXCEEDED")
    void rejectedScaleUpIsRecorded() throws Exception {
        String appId = createApp("audit-reject");
        String region = "audit-reject-east";
        capacityRepo.upsert(appId, region, 1);  // cap of 1
        registerStubPod("audit-reject-1", region, appId);
        registerStubPod("audit-reject-2", region, appId);

        String runId = launchRun("audit-reject", region, 1, "dave");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .header("X-Actor", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{ \"allocations\": [ { \"region\": \"%s\", \"count\": 1 } ] }", region)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_CAPACITY_EXCEEDED"));

        JsonNode events = getEvents(runId);
        JsonNode up = eventOfType(events, "SCALE_UP");
        assertThat(up).as("the rejected scaleUp is still on the timeline").isNotNull();
        assertThat(up.get("result").asText()).isEqualTo("rejected:APPLICATION_CAPACITY_EXCEEDED");
        assertThat(up.get("actor").asText()).isEqualTo("dave");
        // RUN_START is also present and is "ok".
        assertThat(eventOfType(events, "RUN_START").get("result").asText()).isEqualTo("ok");
    }

    // ── 3. Audit invariant — rolled-back mutation emits zero events ─────────

    @Test
    @DisplayName("a forced failure mid-mutation rolls back the new members AND the event together")
    void rolledBackMutationEmitsZeroEvents() throws Exception {
        String appId = createApp("audit-rollback");
        String region = "audit-rollback-east";
        capacityRepo.upsert(appId, region, 3);
        registerStubPod("audit-rollback-1", region, appId);
        registerStubPod("audit-rollback-2", region, appId);

        // Launch succeeds (RUN_START inserted for real — does not match the
        // SCALE_UP-only throw stub set up below).
        String runId = launchRun("audit-rollback", region, 1, "erin");

        // Force the SCALE_UP audit insert to blow up inside the claim
        // transaction. The mutation (member insert) + the event must roll
        // back together.
        Mockito.doThrow(new RuntimeException("ci-forced-rollback"))
                .when(auditEvents).insert(Mockito.argThat(e -> e != null && e.eventType() == RunEventType.SCALE_UP));

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleUp", runId)
                        .header("X-Actor", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{ \"allocations\": [ { \"region\": \"%s\", \"count\": 1 } ] }", region)))
                .andExpect(status().isInternalServerError());

        // Exactly one event survives (the RUN_START); zero SCALE_UP events.
        var surviving = auditEvents.findByRunId(runId);
        assertThat(surviving).hasSize(1);
        assertThat(surviving.get(0).eventType()).isEqualTo(RunEventType.RUN_START);
        // The scale-up member row rolled back too — still just the original fleet.
        assertThat(runRepo.findByRunId(runId).orElseThrow().fleetMembers()).hasSize(1);
    }

    // ── 4. Single-worker drain → DRAIN_WORKER ──────────────────────────────

    @Test
    @DisplayName("draining one explicit workerId is classified DRAIN_WORKER")
    void singleWorkerDrainRecordsDrainWorker() throws Exception {
        String appId = createApp("audit-drain");
        String region = "audit-drain-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("audit-drain-1", region, appId);

        String runId = launchRun("audit-drain", region, 1, "frank");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{runId}/scaleDown", runId)
                        .header("X-Actor", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"workerIds\": [ \"audit-drain-1\" ] }"))
                .andExpect(status().isOk());

        JsonNode events = getEvents(runId);
        assertThat(eventOfType(events, "SCALE_DOWN")).as("not classified as SCALE_DOWN").isNull();
        JsonNode drain = eventOfType(events, "DRAIN_WORKER");
        assertThat(drain).isNotNull();
        assertThat(drain.get("payload").get("workerId").asText()).isEqualTo("audit-drain-1");
        assertThat(drain.get("result").asText()).isEqualTo("ok");
    }

    // ── 5. Anonymous actor when no header ───────────────────────────────────

    @Test
    @DisplayName("no X-Actor header → actor=anonymous, actorSource=anonymous")
    void anonymousActorWhenHeaderAbsent() throws Exception {
        String appId = createApp("audit-anon");
        String region = "audit-anon-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("audit-anon-1", region, appId);

        String runId = launchRun("audit-anon", region, 1, null);  // no X-Actor

        JsonNode start = eventOfType(getEvents(runId), "RUN_START");
        assertThat(start.get("actor").asText()).isEqualTo(Actor.ANONYMOUS);
        assertThat(start.get("actorSource").asText()).isEqualTo(Actor.SOURCE_ANONYMOUS);
    }

    // ── 6. Idempotency on eventId ───────────────────────────────────────────

    @Test
    @DisplayName("a second insert with the same eventId is silently dropped")
    void idempotentOnEventId() throws Exception {
        String appId = createApp("audit-idem");
        String region = "audit-idem-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod("audit-idem-1", region, appId);
        String runId = launchRun("audit-idem", region, 1, "gina");

        String eventId = Ulid.generate();
        RunEvent e = new RunEvent(eventId, runId, RunEventType.STOP, "gina",
                Actor.SOURCE_HEADER, Map.of("note", "x"), "ok", Instant.now());
        auditEvents.insert(e);
        auditEvents.insert(e);  // same eventId again

        long withThatId = auditEvents.findByRunId(runId).stream()
                .filter(ev -> eventId.equals(ev.eventId()))
                .count();
        assertThat(withThatId).isEqualTo(1L);
    }

    // ── 7. Unknown run → 404 ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /events for an unknown runId → 404 RUN_NOT_FOUND")
    void eventsForUnknownRunIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs/{runId}/events", "01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }
}
