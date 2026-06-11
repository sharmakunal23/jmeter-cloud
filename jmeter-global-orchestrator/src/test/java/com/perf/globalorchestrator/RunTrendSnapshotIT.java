package com.perf.globalorchestrator;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.domain.Run;
import com.perf.globalorchestrator.domain.RunState;
import com.perf.globalorchestrator.service.RunService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTOMATION Phase F — IT for the runTrend snapshot. Mirrors
 * {@link MetricsCachingIT}'s harness (both globalrun + metrics schemas in one
 * Testcontainers Postgres, the local-orchestrator client mocked) and asserts
 * that when {@link RunService#refreshAndGet} observes a run transition into
 * COMPLETED, it writes exactly one {@code globalOrchestrator.runTrend} row with
 * the run's fleet-wide aggregate — and that a non-COMPLETED terminal (FAILED)
 * does NOT snapshot.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@Testcontainers
@DisplayName("global-orchestrator runTrend snapshot — behavior IT (AUTOMATION Phase F)")
class RunTrendSnapshotIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL",   POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",          () -> "postgres");
        registry.add("POSTGRES_PASSWORD",      () -> "test");
        registry.add("POSTGRES_GLOBALRUN_URL", POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");
        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        registry.add("globalOrchestrator.automation.sweepInitialDelayMs", () -> "3600000");
    }

    @Autowired RunService runService;
    @MockitoBean LocalOrchestratorClient localClient;

    static JdbcTemplate jdbc;
    static long baseSec;

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
        baseSec = Instant.now().getEpochSecond();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM metrics.\"workerMetric\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"runFleetMember\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"runTrend\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"run\"");
    }

    @Test
    @DisplayName("COMPLETED transition writes one runTrend row with the fleet aggregate")
    void completedRunSnapshotted() {
        String runId = "01J0000000000000000000TREN1";
        insertRun(runId, "trend-app", "RUNNING");
        insertMember(runId, "wA", "RUNNING", "http://pod-a");
        // 3 windows × throughput 10 → 30 samples over a 3s span = 10 rps; p95 = 22 ms.
        insertMetric(runId, baseSec);
        insertMetric(runId, baseSec + 1);
        insertMetric(runId, baseSec + 2);

        // The single member reports COMPLETED → the run rolls up to COMPLETED.
        Mockito.when(localClient.getTestStatus(ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(Map.of("state", "COMPLETED")));

        Run run = runService.refreshAndGet(runId);
        assertThat(run.state()).isEqualTo(RunState.COMPLETED);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"runTrend\" WHERE \"runId\"=?",
                Integer.class, runId);
        assertThat(rows).isEqualTo(1);

        Map<String, Object> trend = jdbc.queryForMap(
                "SELECT \"applicationName\",\"p95Ms\",\"throughputRps\",\"errorRate\" "
                + "FROM \"globalOrchestrator\".\"runTrend\" WHERE \"runId\"=?", runId);
        assertThat(trend.get("applicationName")).isEqualTo("trend-app");
        assertThat(((Number) trend.get("p95Ms")).doubleValue()).isEqualTo(22.0);
        assertThat(((Number) trend.get("throughputRps")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) trend.get("errorRate")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("FAILED transition does NOT snapshot (only clean COMPLETED is a baseline)")
    void failedRunNotSnapshotted() {
        String runId = "01J0000000000000000000TREN2";
        insertRun(runId, "trend-app", "RUNNING");
        insertMember(runId, "wA", "RUNNING", "http://pod-a");
        insertMetric(runId, baseSec);

        Mockito.when(localClient.getTestStatus(ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(Map.of("state", "FAILED")));

        Run run = runService.refreshAndGet(runId);
        assertThat(run.state()).isEqualTo(RunState.FAILED);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"runTrend\" WHERE \"runId\"=?",
                Integer.class, runId);
        assertThat(rows).isEqualTo(0);
    }

    // ── Fixtures (direct JDBC — same shape as MetricsCachingIT) ─────────

    private void insertRun(String runId, String application, String state) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                + "(\"runId\",\"originRegion\",\"testPlanBlobId\",\"application\",\"initiatedBy\",\"state\") "
                + "VALUES (?, 'us-east-1', 'plan-1', ?, 'it', ?)",
                runId, application, state);
    }

    private void insertMember(String runId, String workerId, String state, String podBaseUrl) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"runFleetMember\" "
                + "(\"runId\",\"workerId\",\"region\",\"state\",\"podBaseUrl\","
                + " \"createdAt\",\"properties\",\"joinedAtSecond\") "
                + "VALUES (?,?,'us-east-1',?,?, now(), '{}'::jsonb, 0)",
                runId, workerId, state, podBaseUrl);
    }

    private void insertMetric(String runId, long sec) {
        jdbc.update(
                "INSERT INTO metrics.\"workerMetric\" "
                + "(\"runId\", \"workerId\", \"label\", \"windowSecond\", \"windowTimestamp\", "
                + " \"region\", \"throughput\", \"errorCount\", \"errorRate\", \"avgRespTimeMs\", "
                + " \"p50Ms\", \"p90Ms\", \"p95Ms\", \"p99Ms\", \"minMs\", \"maxMs\", \"rawMaxMs\", "
                + " \"bytesReceived\", \"bytesSent\", \"statusCodes\", \"activeThreads\") "
                + "VALUES (?, 'wA', 'GET /a', ?, ?, 'us-east-1', 10, 0, 0, 12.0, "
                + " 8, 18, 22, 30, 4, 36, 36, 0, 0, '{\"200\":10}'::jsonb, 1)",
                runId, sec, Long.toString(sec * 1000));
    }
}
