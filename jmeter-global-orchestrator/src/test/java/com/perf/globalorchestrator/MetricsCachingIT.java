package com.perf.globalorchestrator;

import com.perf.globalorchestrator.client.LocalOrchestratorClient;
import com.perf.globalorchestrator.client.LocalOrchestratorClient.LogsResult;
import com.perf.globalorchestrator.repo.MetricsRollupRepository;
import com.perf.globalorchestrator.repo.MetricsTimeseriesRepository;
import com.perf.globalorchestrator.repo.RunRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CACHE C-1 — end-to-end IT for the terminal-run metrics cache, asserted through
 * the real HTTP surface. Runs against a Testcontainers Postgres (so the repo's
 * SQL really executes on a miss) with the {@code simple} cache provider (from
 * the test {@code application-local.yml} — no Redis needed; the gating is
 * provider-agnostic). The two metrics repositories are {@link MockitoSpyBean
 * spies} so their invocation count is the proxy for "did SQL run": a cache hit =
 * the spy was NOT re-invoked.
 *
 * <p>Pins the controller wiring the unit test can't: that {@code RunController}
 * loads the run and passes its real {@code state()} (terminal vs active) into
 * the caching service. The behaviour of the cache itself is unit-tested in
 * {@code CachingMetricsServiceTest}.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false",
        "metrics.timeseries.settleSeconds=5"   // pin the live-run trailing-trim margin for the verifies below
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator terminal-run metrics cache — behavior IT (CACHE C-1)")
class MetricsCachingIT {

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
    }

    @Autowired MockMvc mvc;
    @Autowired CacheManager cacheManager;
    @MockitoSpyBean MetricsTimeseriesRepository timeseriesRepo;
    @MockitoSpyBean MetricsRollupRepository rollupRepo;
    @MockitoSpyBean RunRepository runRepo;
    @MockitoBean LocalOrchestratorClient localClient;   // C-5 — stub the log proxy target

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

    @BeforeEach
    void clearCaches() {
        // The ConcurrentMapCacheManager is a context singleton — clear it so a
        // runId cached by one test method doesn't leak into the next. (The spies
        // are auto-reset between methods by Spring's MockitoBean support.)
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM metrics.\"workerMetric\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"run\"");
    }

    private void insertRun(String runId, String state) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                + "(\"runId\", \"originRegion\", \"testPlanBlobId\", \"initiatedBy\", \"state\") "
                + "VALUES (?, 'us-east-1', 'plan-1', 'it', ?)",
                runId, state);
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

    @Test
    @DisplayName("terminal run: /timeseries runs SQL once across two requests")
    void terminalTimeseries_cachedAcrossRequests() throws Exception {
        String runId = "01J000000000000000000CACH1";
        insertRun(runId, "COMPLETED");
        insertMetric(runId, baseSec);

        mvc.perform(get("/api/v1/runs/" + runId + "/timeseries")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + runId + "/timeseries")).andExpect(status().isOk());

        verify(timeseriesRepo, times(1)).timeseries(runId, false, null, 0);
    }

    @Test
    @DisplayName("active run: /timeseries runs SQL on every request (never cached)")
    void activeTimeseries_bypassesCache() throws Exception {
        String runId = "01J000000000000000000CACH2";
        insertRun(runId, "RUNNING");
        insertMetric(runId, baseSec);

        mvc.perform(get("/api/v1/runs/" + runId + "/timeseries")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + runId + "/timeseries")).andExpect(status().isOk());

        verify(timeseriesRepo, times(2)).timeseries(runId, false, null, 5);
    }

    @Test
    @DisplayName("terminal run: /metrics rollup runs SQL once across two requests")
    void terminalRollup_cachedAcrossRequests() throws Exception {
        String runId = "01J000000000000000000CACH3";
        insertRun(runId, "FAILED");
        insertMetric(runId, baseSec);

        mvc.perform(get("/api/v1/runs/" + runId + "/metrics")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + runId + "/metrics")).andExpect(status().isOk());

        verify(rollupRepo, times(1)).rollupByLabel(runId);
    }

    @Test
    @DisplayName("C-2 terminal run: GET /runs/{id} reads the row once across two requests")
    void terminalRunMetadata_cachedAcrossRequests() throws Exception {
        String runId = "01J000000000000000000CACM1";
        insertRun(runId, "COMPLETED");

        mvc.perform(get("/api/v1/runs/" + runId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + runId)).andExpect(status().isOk());

        verify(runRepo, times(1)).findByRunId(runId);
    }

    @Test
    @DisplayName("C-2 active run: GET /runs/{id} reads the row on every request (never cached)")
    void activeRunMetadata_notCached() throws Exception {
        String runId = "01J000000000000000000CACM2";
        insertRun(runId, "RUNNING");

        mvc.perform(get("/api/v1/runs/" + runId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + runId)).andExpect(status().isOk());

        verify(runRepo, times(2)).findByRunId(runId);
    }

    @Test
    @DisplayName("C-5 terminal member: /logs contacts the local orchestrator once across two reads")
    void terminalMemberLogs_cachedAcrossRequests() throws Exception {
        String runId = "01J000000000000000000CACK1";
        insertRun(runId, "COMPLETED");
        insertMember(runId, "wkr-1", "DRAINED", "http://wkr-1:8080");
        Mockito.when(localClient.getLogs(Mockito.anyString(), Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(new LogsResult(200, "frozen log body"));

        String url = "/api/v1/runs/" + runId + "/members/wkr-1/logs?stream=console&tail=200";
        mvc.perform(get(url)).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isOk());

        verify(localClient, times(1)).getLogs("http://wkr-1:8080", 200, "console");
    }

    @Test
    @DisplayName("C-5 active member: /logs contacts the local orchestrator on every read")
    void activeMemberLogs_notCached() throws Exception {
        String runId = "01J000000000000000000CACK2";
        insertRun(runId, "RUNNING");
        insertMember(runId, "wkr-9", "RUNNING", "http://wkr-9:8080");
        Mockito.when(localClient.getLogs(Mockito.anyString(), Mockito.anyInt(), Mockito.anyString()))
                .thenReturn(new LogsResult(200, "live log body"));

        String url = "/api/v1/runs/" + runId + "/members/wkr-9/logs?stream=console&tail=200";
        mvc.perform(get(url)).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isOk());

        verify(localClient, times(2)).getLogs("http://wkr-9:8080", 200, "console");
    }

    @Test
    @DisplayName("comparison batch of two terminal runs: zero SQL on the second request")
    void terminalBatch_cachedAcrossRequests() throws Exception {
        String a = "01J000000000000000000CACHA";
        String b = "01J000000000000000000CACHB";
        insertRun(a, "COMPLETED");
        insertRun(b, "ABORTED");
        insertMetric(a, baseSec);
        insertMetric(b, baseSec);

        String url = "/api/v1/runs/timeseries?ids=" + a + "," + b;
        mvc.perform(get(url)).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isOk());

        verify(timeseriesRepo, times(1)).timeseries(a, false, null, 0);
        verify(timeseriesRepo, times(1)).timeseries(b, false, null, 0);
        Mockito.verifyNoMoreInteractions(timeseriesRepo);
    }
}
