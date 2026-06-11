package com.perf.globalorchestrator;

import com.perf.globalorchestrator.service.AiClient;
import com.perf.globalorchestrator.service.AiClient.AiResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI-1 / AI-2 — behaviour IT for the insights + comparison endpoints. The
 * Anthropic call is stubbed via a {@link MockitoBean} {@link AiClient} (we never
 * hit the real API in CI); everything else — the durable {@code aiResponse}
 * cache, terminal-run gating, 404s, AI_DISABLED — runs against a real Postgres.
 *
 * <p>Dual-Flyway setup mirrors {@link MetricsTimeseriesBatchIT} (both globalrun
 * + metrics migrations against one container with distinct history tables).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator AI insights — behaviour IT (AI-1/AI-2)")
class AiInsightsIT {

    private static final String RUN_JSON =
            "{\"summary\":\"Steady throughput at ~75 RPS.\","
                    + "\"findings\":[{\"severity\":\"warn\",\"title\":\"Latency tail\","
                    + "\"detail\":\"p99 climbed late in the run.\"}]}";

    private static final String COMPARE_JSON =
            "{\"summary\":\"Run B regressed average RT vs Run A.\","
                    + "\"findings\":[{\"metric\":\"avgRtMs\",\"verdict\":\"regression\",\"delta\":\"+12.3%\"}]}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL", POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER", () -> "postgres");
        registry.add("POSTGRES_PASSWORD", () -> "test");

        registry.add("POSTGRES_GLOBALRUN_URL", POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER", () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");

        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs", () -> "3600000");
    }

    @Autowired MockMvc mvc;
    @MockitoBean AiClient ai;

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSchemas() {
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun").toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .table("flyway_schema_history_globalrun")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();

        Path metrics = Paths.get("..", "postgres", "migrations", "metrics").toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + metrics)
                .table("flyway_schema_history_metrics")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();

        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void stubEnabled() {
        when(ai.model()).thenReturn("claude-test");
        when(ai.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"aiResponse\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"run\"");
    }

    private void insertCompletedRun(String runId) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                        + "(\"runId\", \"originRegion\", \"testPlanBlobId\", \"initiatedBy\", \"state\") "
                        + "VALUES (?, 'us-east-1', 'plan-1', 'it', 'COMPLETED')",
                runId);
    }

    // ── AI-1 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("terminal run: first call generates (fromCache=false), second is served from cache (no second Claude call)")
    void runInsights_cachesAndReads() throws Exception {
        String runId = "01J0000000000000000000ANS1";
        insertCompletedRun(runId);
        when(ai.complete(any(), any())).thenReturn(new AiResult(RUN_JSON, 100, 50));

        mvc.perform(post("/api/v1/runs/" + runId + "/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCache").value(false))
                .andExpect(jsonPath("$.summary").value("Steady throughput at ~75 RPS."))
                .andExpect(jsonPath("$.findings[0].severity").value("warn"))
                .andExpect(jsonPath("$.tokensIn").value(100));

        mvc.perform(post("/api/v1/runs/" + runId + "/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCache").value(true))
                .andExpect(jsonPath("$.summary").value("Steady throughput at ~75 RPS."));

        // The cache hit must NOT have re-billed Claude.
        verify(ai, times(1)).complete(any(), any());
    }

    @Test
    @DisplayName("?fresh=true bypasses the cache and re-bills Claude")
    void runInsights_freshBypassesCache() throws Exception {
        String runId = "01J0000000000000000000ANS2";
        insertCompletedRun(runId);
        when(ai.complete(any(), any())).thenReturn(new AiResult(RUN_JSON, 100, 50));

        mvc.perform(post("/api/v1/runs/" + runId + "/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCache").value(false));
        mvc.perform(post("/api/v1/runs/" + runId + "/insights?fresh=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCache").value(false));

        verify(ai, times(2)).complete(any(), any());
    }

    @Test
    @DisplayName("unknown run → 404 RUN_NOT_FOUND (no Claude call)")
    void runInsights_unknownRun_404() throws Exception {
        mvc.perform(post("/api/v1/runs/01J0000000000000000000N0PE/insights"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        verify(ai, never()).complete(any(), any());
    }

    @Test
    @DisplayName("no API key configured → 503 AI_DISABLED on a cache miss")
    void runInsights_disabled_503() throws Exception {
        String runId = "01J0000000000000000000ANS3";
        insertCompletedRun(runId);
        when(ai.isEnabled()).thenReturn(false);

        mvc.perform(post("/api/v1/runs/" + runId + "/insights"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_DISABLED"));
        verify(ai, never()).complete(any(), any());
    }

    // ── AI-2 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compare: first call generates, second is order-independent cache hit (no second Claude call)")
    void compareInsights_cachesAndReads() throws Exception {
        String a = "01J0000000000000000000CMP_A";
        String b = "01J0000000000000000000CMP_B";
        insertCompletedRun(a);
        insertCompletedRun(b);
        when(ai.complete(any(), any())).thenReturn(new AiResult(COMPARE_JSON, 200, 60));

        mvc.perform(post("/api/v1/runs/compare-insights?ids=" + a + "," + b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCache").value(false))
                .andExpect(jsonPath("$.runIds[0]").value(a))
                .andExpect(jsonPath("$.findings[0].verdict").value("regression"));

        // Reversed order must hit the same cache entry (sorted key).
        mvc.perform(post("/api/v1/runs/compare-insights?ids=" + b + "," + a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCache").value(true));

        verify(ai, times(1)).complete(any(), any());
    }

    @Test
    @DisplayName("compare with a missing run → 404 (refuse to compare against nothing)")
    void compareInsights_missingRun_404() throws Exception {
        String a = "01J0000000000000000000CMP_C";
        insertCompletedRun(a);
        mvc.perform(post("/api/v1/runs/compare-insights?ids=" + a + ",01J0000000000000000MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        verify(ai, never()).complete(any(), any());
    }
}
