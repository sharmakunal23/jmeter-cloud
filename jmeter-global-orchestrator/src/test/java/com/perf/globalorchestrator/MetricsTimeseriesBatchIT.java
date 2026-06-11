package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HM-5 — behaviour IT for {@code GET /api/v1/runs/timeseries?ids=A,B}
 * (the Phase 2 batch endpoint feeding the side-by-side comparison view).
 *
 * <p>Re-uses the same dual-Flyway setup as {@link MetricsTimeseriesIT}
 * — both globalrun and metrics migrations against one Postgres
 * container with distinct history tables. Validation, partial-200
 * shape and the strict {@code ids.size() == 2} cap are the deltas
 * versus the per-run IT.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator metrics timeseries batch — behavior IT (HM-5)")
class MetricsTimeseriesBatchIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

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
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static JdbcTemplate jdbc;
    static long baseSec;

    @BeforeAll
    static void migrateSchemas() {
        // See MetricsTimeseriesIT for the why on the dual-history-table
        // setup — both migration sets share one container and would
        // collide on V1/V2 with different checksums otherwise.
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .table("flyway_schema_history_globalrun")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        Path metrics = Paths.get("..", "postgres", "migrations", "metrics")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + metrics)
                .table("flyway_schema_history_metrics")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        baseSec = Instant.now().getEpochSecond();
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM metrics.\"workerMetric\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"run\"");
    }

    private void insertRun(String runId) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                + "(\"runId\", \"originRegion\", \"testPlanBlobId\", \"initiatedBy\", \"state\") "
                + "VALUES (?, 'us-east-1', 'plan-1', 'it', 'COMPLETED')",
                runId);
    }

    private void insertMetric(String runId, String workerId, String label, long sec,
                              long throughput, long errorCount,
                              double avgRtMs, String statusCodesJson) {
        // Distinct percentile values per row — see MetricsTimeseriesIT
        // for the rationale (defends against a SQL bug that selects the
        // wrong column).
        double p50 = avgRtMs * 0.7;
        double p90 = avgRtMs * 1.5;
        double p95 = avgRtMs * 1.8;
        double p99 = avgRtMs * 2.5;
        double min = Math.max(1.0, avgRtMs * 0.3);
        double max = avgRtMs * 3.0;
        long  rawMax = (long) max;
        jdbc.update(
                "INSERT INTO metrics.\"workerMetric\" "
                + "(\"runId\", \"workerId\", \"label\", \"windowSecond\", \"windowTimestamp\", "
                + " \"region\", \"throughput\", \"errorCount\", \"errorRate\", "
                + " \"avgRespTimeMs\", "
                + " \"p50Ms\", \"p90Ms\", \"p95Ms\", \"p99Ms\", \"minMs\", \"maxMs\", \"rawMaxMs\", "
                + " \"bytesReceived\", \"bytesSent\", \"statusCodes\", \"activeThreads\") "
                + "VALUES (?,?,?,?,?,'us-east-1',?,?,?,?,?,?,?,?,?,?,?,0,0,?::jsonb,1)",
                runId, workerId, label, sec, Long.toString(sec * 1000),
                throughput, errorCount,
                throughput == 0 ? 0.0 : (double) errorCount / throughput,
                avgRtMs,
                p50, p90, p95, p99, min, max, rawMax,
                statusCodesJson);
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path: 2 known runs return both timeseries, missing list empty, response order matches query order")
    void happyPath_returnsBothRuns() throws Exception {
        String runA = "01J0000000000000000000HM5A";
        String runB = "01J0000000000000000000HM5B";
        insertRun(runA);
        insertRun(runB);

        // Distinct shapes per run so a swap bug would be obvious:
        //   runA — 2 windows, tps 10 + 20, no errors
        //   runB — 1 window,  tps 5,        50% errors
        insertMetric(runA, "wA", "GET /a", baseSec,     10, 0, 8.0,  "{\"200\":10}");
        insertMetric(runA, "wA", "GET /a", baseSec + 1, 20, 0, 9.0,  "{\"200\":20}");
        insertMetric(runB, "wB", "GET /b", baseSec,     4,  2, 30.0, "{\"200\":2,\"500\":2}");

        MvcResult result = mvc.perform(get("/api/v1/runs/timeseries?ids=" + runA + "," + runB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").isArray())
                .andExpect(jsonPath("$.missing").isEmpty())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        JsonNode runs = body.path("runs");
        assertThat(runs.has(runA)).as("runs map keyed by runId").isTrue();
        assertThat(runs.has(runB)).isTrue();

        // Response order should match the order the operator submitted —
        // the UI's left/right column expectation depends on it.
        java.util.List<String> orderedKeys = new java.util.ArrayList<>();
        runs.fieldNames().forEachRemaining(orderedKeys::add);
        assertThat(orderedKeys).containsExactly(runA, runB);

        JsonNode aTps = runs.path(runA).path("series").path("tps");
        assertThat(aTps).hasSize(2);
        assertThat(aTps.get(0).get("v").doubleValue()).isEqualTo(10.0);
        assertThat(aTps.get(1).get("v").doubleValue()).isEqualTo(20.0);

        JsonNode bTps = runs.path(runB).path("series").path("tps");
        assertThat(bTps).hasSize(1);
        assertThat(bTps.get(0).get("v").doubleValue()).isEqualTo(4.0);

        // Error % isolation: runA must be 0 in both windows; runB must be 50.
        JsonNode aErr = runs.path(runA).path("series").path("errorPct");
        assertThat(aErr.get(0).get("v").doubleValue()).isEqualTo(0.0);
        assertThat(aErr.get(1).get("v").doubleValue()).isEqualTo(0.0);
        JsonNode bErr = runs.path(runB).path("series").path("errorPct");
        assertThat(bErr.get(0).get("v").doubleValue()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("partial-200: one missing id is reported in `missing`, the present id still returns its timeseries")
    void oneMissing_returnsPartial() throws Exception {
        String present = "01J0000000000000000000HM5P";
        String missing = "01J00000000000000000NONE_R";
        insertRun(present);
        insertMetric(present, "wA", "GET /a", baseSec, 7, 0, 11.0, "{\"200\":7}");

        MvcResult result = mvc.perform(get("/api/v1/runs/timeseries?ids=" + present + "," + missing))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        assertThat(body.path("runs").has(present)).isTrue();
        assertThat(body.path("runs").has(missing)).isFalse();
        JsonNode missArr = body.path("missing");
        assertThat(missArr).hasSize(1);
        assertThat(missArr.get(0).asText()).isEqualTo(missing);

        // Sanity: the present run's data is intact.
        JsonNode tps = body.path("runs").path(present).path("series").path("tps");
        assertThat(tps).hasSize(1);
        assertThat(tps.get(0).get("v").doubleValue()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("both missing: empty runs map + both ids in missing list — still 200 (UI surfaces a structured 'no data' message)")
    void bothMissing_returnsEmptyRunsAndFullMissing() throws Exception {
        String a = "01J0000000000000000000NOPE_A";
        String b = "01J0000000000000000000NOPE_B";

        MvcResult result = mvc.perform(get("/api/v1/runs/timeseries?ids=" + a + "," + b))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        assertThat(body.path("runs").size()).isZero();
        JsonNode miss = body.path("missing");
        assertThat(miss).hasSize(2);
        // Order preserved.
        assertThat(miss.get(0).asText()).isEqualTo(a);
        assertThat(miss.get(1).asText()).isEqualTo(b);
    }

    @Test
    @DisplayName("400 when ids param is missing or blank")
    void blankIds_returns400() throws Exception {
        // Param entirely absent.
        mvc.perform(get("/api/v1/runs/timeseries"))
                .andExpect(status().isBadRequest());
        // Param present but empty.
        mvc.perform(get("/api/v1/runs/timeseries?ids="))
                .andExpect(status().isBadRequest());
        // Param present but only whitespace + commas.
        mvc.perform(get("/api/v1/runs/timeseries?ids=,,"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("400 when ids count is not exactly 2 (covers 1 and 3, including the dedupe edge)")
    void wrongIdCount_returns400() throws Exception {
        // 1 id.
        mvc.perform(get("/api/v1/runs/timeseries?ids=01J00000000000000000ONLY1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exactly 2")));

        // 3 distinct ids.
        mvc.perform(get("/api/v1/runs/timeseries?ids=A,B,C"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 2 raw entries that dedupe to 1 — should also 400, because the
        // operator probably meant to pick a second different run.
        mvc.perform(get("/api/v1/runs/timeseries?ids=DUPE,DUPE"))
                .andExpect(status().isBadRequest());
    }
}
