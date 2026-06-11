package com.perf.globalorchestrator;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Save Results reconciliation IT — pins the fix for "the Download-results icon
 * shows but the events timeline never shows RESULTS_SAVED."
 *
 * <p>A worker's JTL upload finishes AFTER the run goes terminal, and the
 * run-detail UI stops polling {@code GET /status} the instant the run is
 * COMPLETED — so nothing observes {@code uploadState=UPLOADED} and the audit
 * event is never written. {@link RunService#reconcileResultsSaved} (driven by
 * {@code ResultsSavedSweeper}) closes that gap by re-polling COMPLETED +
 * {@code saveResults} runs that still have a worker missing its event.
 *
 * <p>Mirrors {@link RunTrendSnapshotIT}'s harness; the live sweeper is disabled
 * (huge initial delay) so the test drives reconciliation deterministically.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@Testcontainers
@DisplayName("global-orchestrator Save Results reconciliation — behavior IT")
class ResultsSavedReconcileIT {

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
        // Disable every background sweeper so the test owns the timing.
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
        registry.add("globalOrchestrator.automation.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.run.resultsSavedSweepInitialDelayMs", () -> "3600000");
    }

    @Autowired RunService runService;
    @MockitoBean LocalOrchestratorClient localClient;

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

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"runEvent\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"runFleetMember\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"run\"");
    }

    @Test
    @DisplayName("COMPLETED saveResults run whose worker reports UPLOADED gets exactly one RESULTS_SAVED — and re-running doesn't duplicate")
    void reconcileRecordsResultsSavedOnceAndIsIdempotent() {
        String runId = "01J0000000000000000000RSAV1";
        insertCompletedSaveResultsRun(runId);
        insertMember(runId, "wA", "COMPLETED", "http://pod-a");

        // The worker has finished uploading — state COMPLETED, uploadState UPLOADED.
        Mockito.when(localClient.getTestStatus(ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(Map.of(
                        "runId", runId,
                        "state", "COMPLETED",
                        "uploadState", "UPLOADED",
                        "uploadTarget", "documentService://results/trend-app/" + runId)));

        // Before reconciliation the run is a candidate (member with no event yet).
        assertThat(awaitingRunIds()).contains(runId);

        runService.reconcileResultsSaved(Duration.ofHours(1));
        assertThat(resultsSavedEventCount(runId)).isEqualTo(1);

        // Durable dedup: a second pass (a restart-equivalent — the in-memory
        // marker is bypassed because the DB already records it) writes no dup.
        runService.reconcileResultsSaved(Duration.ofHours(1));
        assertThat(resultsSavedEventCount(runId)).isEqualTo(1);

        // …and the run drops out of the candidate set once fully reconciled.
        assertThat(awaitingRunIds()).doesNotContain(runId);
    }

    @Test
    @DisplayName("upload still in flight (uploadState UPLOADING) records nothing yet — run stays a candidate")
    void noEventWhileUploadInFlight() {
        String runId = "01J0000000000000000000RSAV2";
        insertCompletedSaveResultsRun(runId);
        insertMember(runId, "wA", "COMPLETED", "http://pod-a");

        Mockito.when(localClient.getTestStatus(ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(Map.of(
                        "runId", runId, "state", "COMPLETED", "uploadState", "UPLOADING")));

        runService.reconcileResultsSaved(Duration.ofHours(1));
        assertThat(resultsSavedEventCount(runId)).isZero();
        assertThat(awaitingRunIds()).contains(runId);

        // The upload lands → the next sweep records it.
        Mockito.when(localClient.getTestStatus(ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(Map.of(
                        "runId", runId, "state", "COMPLETED", "uploadState", "UPLOADED")));
        runService.reconcileResultsSaved(Duration.ofHours(1));
        assertThat(resultsSavedEventCount(runId)).isEqualTo(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<String> awaitingRunIds() {
        // Exercise the candidate query directly through the service path used by
        // the sweeper: reconcile is a no-op when empty, so we read the repo view.
        return jdbc.queryForList(
                "SELECT r.\"runId\" FROM \"globalOrchestrator\".\"run\" r "
                + "WHERE r.\"state\"='COMPLETED' AND r.\"saveResults\"=true "
                + "  AND (SELECT count(*) FROM \"globalOrchestrator\".\"runFleetMember\" m "
                + "         WHERE m.\"runId\"=r.\"runId\" AND m.\"podBaseUrl\" IS NOT NULL) "
                + "      > (SELECT count(DISTINCT e.\"payload\"->>'workerId') "
                + "           FROM \"globalOrchestrator\".\"runEvent\" e "
                + "           WHERE e.\"runId\"=r.\"runId\" AND e.\"eventType\"='RESULTS_SAVED')",
                String.class);
    }

    private int resultsSavedEventCount(String runId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"runEvent\" "
                + "WHERE \"runId\"=? AND \"eventType\"='RESULTS_SAVED'",
                Integer.class, runId);
        return n == null ? 0 : n;
    }

    private void insertCompletedSaveResultsRun(String runId) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                + "(\"runId\",\"originRegion\",\"testPlanBlobId\",\"application\",\"initiatedBy\","
                + " \"state\",\"saveResults\",\"completedAt\") "
                + "VALUES (?, 'us-east-1', 'plan-1', 'trend-app', 'it', 'COMPLETED', true, now())",
                runId);
    }

    private void insertMember(String runId, String workerId, String state, String podBaseUrl) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"runFleetMember\" "
                + "(\"runId\",\"workerId\",\"region\",\"state\",\"podBaseUrl\","
                + " \"createdAt\",\"properties\",\"joinedAtSecond\") "
                + "VALUES (?,?,'us-east-1',?,?, now(), '{}'::jsonb, 0)",
                runId, workerId, state, podBaseUrl);
    }
}
