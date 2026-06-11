package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.perf.globalorchestrator.client.DocumentServiceClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HARD-DELETE / purge — end-to-end behavior IT for
 * {@code POST /api/v1/runs/{runId}/purge}, driving real HTTP → controller →
 * {@code RunPurgeService} → real datasources against a Testcontainers Postgres
 * holding BOTH schemas (globalrun + metrics). document-service is mocked.
 *
 * <p>Verifies the real SQL the unit test can't: the cross-DB metrics DELETE runs
 * as the {@code metricsPurger} role (V13 grants), the {@code run} row delete
 * cascades fleet members + audit events (V1/V15 FKs), {@code runTrend} drops via
 * the V27 DELETE grant, and a {@code purgeAudit} tombstone survives. Also pins
 * the trash-first + terminal guards (409 RUN_NOT_PURGEABLE).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator run-purge (hard delete) — behavior IT")
class RunPurgeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    static WireMockServer wireMock;
    static JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL",               POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",                      () -> "postgres");
        registry.add("POSTGRES_PASSWORD",                  () -> "test");
        registry.add("POSTGRES_GLOBALRUN_URL",             POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");
        // HARD-DELETE — the purge datasource connects as metricsPurger.
        registry.add("globalOrchestrator.metricsPurgeUrl",      POSTGRES::getJdbcUrl);
        registry.add("globalOrchestrator.metricsPurgeUser",     () -> "metricsPurger");
        registry.add("globalOrchestrator.metricsPurgePassword", () -> "test");
        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        registry.add("globalOrchestrator.automation.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.maxFleetSizePerRun", () -> "10");
    }

    @BeforeAll
    static void migrateAndStartStub() {
        for (String schema : List.of("globalrun", "metrics")) {
            Path dir = Paths.get("..", "postgres", "migrations", schema).toAbsolutePath().normalize();
            Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("filesystem:" + dir)
                    .table("flyway_schema_history_" + schema)
                    .baselineOnMigrate(true).baselineVersion("0")
                    .load().migrate();
        }
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/api/v1/test"))
                .willReturn(WireMock.aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"runId\":\"any\",\"state\":\"PREPARING\",\"startedAt\":null}")));
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
    @MockBean DocumentServiceClient docClient;

    // ── helpers (mirror RunDeleteIT) ────────────────────────────────────

    private String createApp(String name) throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(create.getResponse().getContentAsString()).get("applicationId").asText();
    }

    private void registerStubPod(String podId, String region, String applicationId) throws Exception {
        String body = "{\"podId\":\"" + podId + "\",\"region\":\"" + region + "\","
                + "\"baseUrl\":\"" + wireMock.baseUrl() + "\","
                + "\"applicationId\":\"" + applicationId + "\"}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private String launchRun(String app, String region) throws Exception {
        String body = String.format("""
                { "testPlanBlobId": "01HXC2VQK4M9N6P5T0YBX2WZ4Q",
                  "application": "%s",
                  "fleetAllocation": [ { "region": "%s", "count": 1 } ] }
                """, app, region);
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }

    /** Create app+pod+run, force it terminal (COMPLETED). Returns the runId. */
    private String terminalRun(String slug) throws Exception {
        String appId = createApp(slug);
        String region = slug + "-east";
        capacityRepo.upsert(appId, region, 1);
        registerStubPod(slug + "-1", region, appId);
        String runId = launchRun(slug, region);
        runRepo.updateRunState(runId, com.perf.globalorchestrator.domain.RunState.COMPLETED, "ci/forced");
        return runId;
    }

    private void seedMetricRow(String runId, long sec) {
        jdbc.update(
                "INSERT INTO metrics.\"workerMetric\" "
                + "(\"runId\",\"workerId\",\"label\",\"windowSecond\",\"windowTimestamp\",\"region\","
                + " \"throughput\",\"errorCount\",\"errorRate\",\"p50Ms\",\"p90Ms\",\"p95Ms\",\"p99Ms\","
                + " \"minMs\",\"maxMs\",\"rawMaxMs\",\"bytesReceived\",\"bytesSent\",\"activeThreads\") "
                + "VALUES (?,?,?,?,?,?, ?,?,?,?,?,?,?, ?,?,?,?,?,?)",
                runId, "w1", "home", sec, Instant.ofEpochSecond(sec).toString(), "us-east-1",
                10L, 0L, 0.0, 5.0, 18.0, 22.0, 30.0, 1.0, 40.0, 41L, 100L, 50L, 5L);
    }

    private void seedTrendAndAi(String runId) {
        jdbc.update("INSERT INTO \"globalOrchestrator\".\"runTrend\" "
                + "(\"runId\",\"applicationName\",\"p50Ms\",\"p95Ms\",\"p99Ms\",\"errorRate\",\"throughputRps\") "
                + "VALUES (?,?,?,?,?,?,?)", runId, "checkout", 5.0, 22.0, 30.0, 0.0, 10.0);
        jdbc.update("INSERT INTO \"globalOrchestrator\".\"aiResponse\" "
                + "(\"kind\",\"cacheKey\",\"promptVersion\",\"response\",\"model\",\"tokensIn\",\"tokensOut\") "
                + "VALUES ('runInsights', ?, 'v1', '{}'::jsonb, 'claude', 1, 1)", runId);
    }

    private int count(String table, String runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE \"runId\"=?", Integer.class, runId);
    }

    private boolean runListed(String runId) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs")
                        .param("includeHidden", "true").param("limit", "200"))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode r : mapper.readTree(res.getResponse().getContentAsString())) {
            if (runId.equals(r.get("runId").asText())) return true;
        }
        return false;
    }

    // ── 1. Happy path — purge a hidden terminal run ─────────────────────

    @Test
    @DisplayName("purge a hidden run → metrics, trend, AI, run row (+cascade) all gone; blobs deleted; tombstone written")
    void purgeRemovesEverything() throws Exception {
        String runId = terminalRun("purge-happy");
        long sec = Instant.now().getEpochSecond();
        seedMetricRow(runId, sec);
        seedMetricRow(runId, sec + 1);
        seedTrendAndAi(runId);
        // document-service: one result blob; deleteBlob is a no-op stub.
        Mockito.when(docClient.listResultBlobIds(runId)).thenReturn(List.of("01RESULTBLOB0000000000000A"));

        // Sanity — everything present before purge.
        assertThat(count("metrics.\"workerMetric\"", runId)).isEqualTo(2);
        assertThat(count("\"globalOrchestrator\".\"runTrend\"", runId)).isEqualTo(1);
        assertThat(count("\"globalOrchestrator\".\"runFleetMember\"", runId)).isEqualTo(1);

        // Must be hidden first (trash → empty trash).
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/runs/{id}", runId)
                        .header("X-Actor", "alice").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{id}/purge", runId)
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"reclaim space\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.metricRowsDeleted").value(2))
                .andExpect(jsonPath("$.blobStepComplete").value(true));

        // Every store is empty for this run.
        assertThat(count("metrics.\"workerMetric\"", runId)).isZero();
        assertThat(count("\"globalOrchestrator\".\"runTrend\"", runId)).isZero();
        assertThat(count("\"globalOrchestrator\".\"runFleetMember\"", runId)).isZero();   // FK cascade
        assertThat(count("\"globalOrchestrator\".\"runEvent\"", runId)).isZero();         // FK cascade
        assertThat(count("\"globalOrchestrator\".\"run\"", runId)).isZero();
        assertThat(runListed(runId)).isFalse();

        // AI cache gone; tombstone written.
        Integer ai = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"aiResponse\" WHERE \"cacheKey\"=?",
                Integer.class, runId);
        assertThat(ai).isZero();
        Integer tomb = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"purgeAudit\" "
                + "WHERE \"targetType\"='run' AND \"targetId\"=? AND \"actor\"='alice'",
                Integer.class, runId);
        assertThat(tomb).isEqualTo(1);

        // The result blob + the run's (unshared) testPlan blob were deleted.
        Mockito.verify(docClient).deleteBlob("01RESULTBLOB0000000000000A");
        Mockito.verify(docClient).deleteBlob("01HXC2VQK4M9N6P5T0YBX2WZ4Q");
    }

    // ── 2. Trash-first guard — terminal but not hidden ──────────────────

    @Test
    @DisplayName("purge a terminal run that was never hidden → 409 RUN_NOT_PURGEABLE, nothing deleted")
    void purgeUnhiddenIs409() throws Exception {
        String runId = terminalRun("purge-unhidden");
        long sec = Instant.now().getEpochSecond();
        seedMetricRow(runId, sec);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{id}/purge", runId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_PURGEABLE"))
                .andExpect(jsonPath("$.runId").value(runId));

        assertThat(count("metrics.\"workerMetric\"", runId)).isEqualTo(1);   // untouched
        assertThat(count("\"globalOrchestrator\".\"run\"", runId)).isEqualTo(1);
    }

    // ── 3. Unknown runId ────────────────────────────────────────────────

    @Test
    @DisplayName("purge an unknown runId → 404 RUN_NOT_FOUND")
    void purgeUnknownIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs/{id}/purge",
                        "01ZZZZZZZZZZZZZZZZZZZZZZZZ")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }
}
