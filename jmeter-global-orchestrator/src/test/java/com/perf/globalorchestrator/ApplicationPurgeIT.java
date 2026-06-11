package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.perf.globalorchestrator.client.DocumentServiceClient;
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
 * HARD-DELETE / purge Phase 2 — end-to-end behavior
 * IT for {@code POST /api/v1/applications/{id}/purge}. Drives real HTTP against a
 * Testcontainers Postgres (both schemas); document-service mocked.
 *
 * <p>Verifies a hidden app's whole footprint is physically removed: its runs
 * (rows + cascaded members/events + metrics + trend), its pod registry rows (the
 * RESTRICT FK is cleared first), capacity (cascade), health history, and the app
 * row — with one application tombstone. Plus the trash-first (409) + unknown
 * (404) guards.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator application-purge (hard delete) — behavior IT")
class ApplicationPurgeIT {

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

    private static final String TEST_PLAN_BLOB = "01HXC2VQK4M9N6P5T0YBX2WZ4Q";

    private String createApp(String name) throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(create.getResponse().getContentAsString()).get("applicationId").asText();
    }

    private void registerStubPod(String podId, String region, String applicationId) throws Exception {
        String body = "{\"podId\":\"" + podId + "\",\"region\":\"" + region + "\","
                + "\"baseUrl\":\"" + wireMock.baseUrl() + "\",\"applicationId\":\"" + applicationId + "\"}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private String launchTerminalRun(String app, String region) throws Exception {
        String body = String.format("""
                { "testPlanBlobId": "%s", "application": "%s",
                  "fleetAllocation": [ { "region": "%s", "count": 1 } ] }
                """, TEST_PLAN_BLOB, app, region);
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String runId = mapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
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

    private int countWhere(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    // ── 1. Happy path — purge a hidden app + its 2 runs ─────────────────

    @Test
    @DisplayName("purge a hidden app → runs, pods, capacity, health history, app row all gone; one app tombstone")
    void purgeRemovesWholeFootprint() throws Exception {
        String appId = createApp("apppurge-happy");
        String region = "apppurge-east";
        capacityRepo.upsert(appId, region, 2);
        registerStubPod("apppurge-1", region, appId);
        registerStubPod("apppurge-2", region, appId);
        String run1 = launchTerminalRun("apppurge-happy", region);
        String run2 = launchTerminalRun("apppurge-happy", region);
        long sec = Instant.now().getEpochSecond();
        seedMetricRow(run1, sec);
        seedMetricRow(run2, sec + 1);
        // A health-transition row for the app.
        jdbc.update("INSERT INTO \"globalOrchestrator\".\"applicationHealthHistory\" "
                + "(\"historyId\",\"applicationId\",\"status\") VALUES (?,?, 'HEALTHY')",
                "01HEALTHHIST0000000000000A", appId);
        Mockito.when(docClient.listResultBlobIds(Mockito.anyString())).thenReturn(List.of());

        // Sanity before.
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"pod\" WHERE \"applicationId\"=?", appId)).isEqualTo(2);
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"run\" WHERE \"runId\"=?", run1)).isEqualTo(1);

        // Hide the app first (trash). Its runs get re-tagged to the archived name.
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", appId))
                .andExpect(status().isNoContent());

        // Purge (empty trash).
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications/{id}/purge", appId)
                        .header("X-Actor", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"decommissioned\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(appId))
                .andExpect(jsonPath("$.runsPurged").value(2))
                .andExpect(jsonPath("$.metricRowsDeleted").value(2));

        // The whole footprint is gone.
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"application\" WHERE \"applicationId\"=?", appId)).isZero();
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"applicationCapacity\" WHERE \"applicationId\"=?", appId)).isZero();
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"pod\" WHERE \"applicationId\"=?", appId)).isZero();
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"applicationHealthHistory\" WHERE \"applicationId\"=?", appId)).isZero();
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"run\" WHERE \"runId\"=?", run1)).isZero();
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"run\" WHERE \"runId\"=?", run2)).isZero();
        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"runFleetMember\" WHERE \"runId\"=?", run1)).isZero();
        assertThat(countWhere("SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\"=?", run1)).isZero();
        assertThat(countWhere("SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\"=?", run2)).isZero();

        // One application tombstone, childRunsPurged=2.
        Integer tomb = jdbc.queryForObject(
                "SELECT \"childRunsPurged\" FROM \"globalOrchestrator\".\"purgeAudit\" "
                + "WHERE \"targetType\"='application' AND \"targetId\"=? AND \"actor\"='alice'",
                Integer.class, appId);
        assertThat(tomb).isEqualTo(2);

        // The shared testPlan blob was deleted exactly once (ref-counted across the 2 runs).
        Mockito.verify(docClient, Mockito.times(1)).deleteBlob(TEST_PLAN_BLOB);
    }

    // ── 2. Trash-first guard — visible app ──────────────────────────────

    @Test
    @DisplayName("purge an app that was never hidden → 409 APPLICATION_NOT_PURGEABLE, nothing deleted")
    void purgeVisibleAppIs409() throws Exception {
        String appId = createApp("apppurge-visible");

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications/{id}/purge", appId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_PURGEABLE"))
                .andExpect(jsonPath("$.applicationId").value(appId));

        assertThat(countWhere("SELECT count(*) FROM \"globalOrchestrator\".\"application\" WHERE \"applicationId\"=?", appId)).isEqualTo(1);
    }

    // ── 3. Unknown app ──────────────────────────────────────────────────

    @Test
    @DisplayName("purge an unknown applicationId → 404 APPLICATION_NOT_FOUND")
    void purgeUnknownIs404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications/{id}/purge",
                        "01ZZZZZZZZZZZZZZZZZZZZZZZZ")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
    }
}
