package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * HM-1 — behaviour IT for {@code GET /api/v1/runs/{runId}/timeseries}.
 *
 * <p>Sets up Postgres with BOTH {@code globalrun} and {@code metrics}
 * Flyway migrations, then for each test method seeds (a) a {@code Run}
 * row so the controller's existence check passes, and (b) a small
 * fleet of {@code metrics."workerMetric"} rows that exercise the
 * aggregation contract: per-second SUM, TPS-weighted means, JSONB
 * status-code merging.
 *
 * <p>Connects as the Postgres superuser for both INSERT (fixture
 * setup) and SELECT (repository query under test) — the production
 * role split (metricsWriter vs metricsReader) is exercised in the
 * canonical migration, not here, so the test stays focused on the
 * SQL semantics.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false",
        "metrics.timeseries.settleSeconds=5"   // pin the live-run trailing-trim margin (terminal runs ignore it)
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator metrics timeseries — behavior IT (HM-1)")
class MetricsTimeseriesIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        // Metrics + globalrun share one container — the metrics SCHEMA
        // lives alongside globalOrchestrator's tables (different schemas
        // in the same database is fine for tests; production runs them
        // as separate databases).
        registry.add("POSTGRES_METRICS_URL",          POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",                 () -> "postgres");
        registry.add("POSTGRES_PASSWORD",             () -> "test");

        registry.add("POSTGRES_GLOBALRUN_URL",        POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");

        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        // Disable the pod sweeper so it can't race the test wall-time.
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static JdbcTemplate jdbc;

    /**
     * Base epoch second for all test fixture rows. The metrics table is
     * partitioned weekly starting at {@code now()} (via the V1
     * migration's {@code ensureUpcomingPartitions(8)} call), so test
     * windowSeconds must fall in that range — synthetic small values
     * like 100 / 200 land outside every partition and the INSERT fails
     * with "no partition of relation". Tests use this base + small
     * offsets (sec, sec+1, …) for distinct windows.
     */
    static long baseSec;

    @BeforeAll
    static void migrateSchemas() {
        // Both schemas live in the same Postgres test container. Flyway
        // defaults to ONE flyway_schema_history table — pointing both
        // migration sets at it would collide on V1/V2 with different
        // checksums. Use distinct history tables (one per migration set)
        // so each is tracked independently. Production has separate
        // databases; this is a test-only consolidation.

        // globalrun migrations — needed because the controller validates
        // runId via runs.getRun(runId) which queries
        // "globalOrchestrator"."run".
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .table("flyway_schema_history_globalrun")
                .baselineOnMigrate(true)
                .baselineVersion("0")  // run V1 too — default of "1" would skip it
                .load()
                .migrate();

        // metrics migrations — needed for the actual query under test.
        Path metrics = Paths.get("..", "postgres", "migrations", "metrics")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + metrics)
                .table("flyway_schema_history_metrics")
                .baselineOnMigrate(true)
                .baselineVersion("0")  // run V1 too — default of "1" would skip it
                .load()
                .migrate();

        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        baseSec = Instant.now().getEpochSecond();
    }

    @AfterEach
    void cleanFixtures() {
        // Delete in dependency order. CASCADE on runFleetMember handles
        // the FK from /run if anything used it.
        jdbc.update("DELETE FROM metrics.\"workerMetric\"");
        jdbc.update("DELETE FROM \"globalOrchestrator\".\"run\"");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void insertRun(String runId) {
        insertRun(runId, "COMPLETED");
    }

    private void insertRun(String runId, String state) {
        jdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                + "(\"runId\", \"originRegion\", \"testPlanBlobId\", \"initiatedBy\", \"state\") "
                + "VALUES (?, 'us-east-1', 'plan-1', 'it', ?)",
                runId, state);
    }

    /**
     * Inserts a worker-metric row. {@code statusCodesJson} should be a
     * valid JSONB literal like {@code '{"200":5,"500":1}'}.
     *
     * <p>{@code avgRtMs} is the HM-1A-introduced per-row mean (sum of
     * sample elapsed / sample count). The percentile / min / max columns
     * get DISTINCT values derived from {@code avgRtMs} so the IT
     * catches a SQL bug that accidentally selects the wrong column —
     * if the repository ever read {@code p50Ms} when it meant
     * {@code avgRespTimeMs}, the values would diverge and the
     * controller-level assertion would fail.
     *
     * <p>The synthetic distribution mirrors typical perf-test shapes:
     * mean > median (right-skewed), p99 well above the mean, max
     * higher still.
     */
    private void insertMetric(String runId, String workerId, String label, long sec,
                              long throughput, long errorCount,
                              double avgRtMs, String statusCodesJson) {
        insertMetric(runId, workerId, label, sec, throughput, errorCount, avgRtMs,
                statusCodesJson, "us-east-1");
    }

    /** Region-aware overload — used by the {@code byRegion} breakdown test. */
    private void insertMetric(String runId, String workerId, String label, long sec,
                              long throughput, long errorCount,
                              double avgRtMs, String statusCodesJson, String region) {
        double p50 = avgRtMs * 0.7;                // median typically below mean for right-skewed perf data
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
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,?::jsonb,1)",
                runId, workerId, label, sec, Long.toString(sec * 1000),
                region,
                throughput, errorCount,
                throughput == 0 ? 0.0 : (double) errorCount / throughput,
                avgRtMs,
                p50, p90, p95, p99, min, max, rawMax,
                statusCodesJson);
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PREPARING / no-rows → 200 with empty arrays (polling-friendly, not 404)")
    void noMetrics_returnsEmptyArrays() throws Exception {
        String runId = "01J0000000000000000000HM01";
        insertRun(runId);

        MvcResult result = mvc.perform(get("/api/v1/runs/" + runId + "/timeseries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.bucketSize").value(1))
                .andExpect(jsonPath("$.fromSecond").doesNotExist())
                .andExpect(jsonPath("$.toSecond").doesNotExist())
                .andExpect(jsonPath("$.series.tps").isArray())
                .andExpect(jsonPath("$.series.tps").isEmpty())
                .andExpect(jsonPath("$.series.avgRtMs").isEmpty())
                .andExpect(jsonPath("$.series.errorPct").isEmpty())
                .andReturn();
        // statusCodes is a map and may be present as {} — assert via json
        // tree because jsonPath isn't great at "is empty object".
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("series").path("statusCodes").size()).isZero();
    }

    @Test
    @DisplayName("404 when runId doesn't exist in globalOrchestrator.run (validation before query)")
    void unknownRun_returns404() throws Exception {
        // A well-formed ULID that simply isn't in the run table — so the 404
        // comes from RunNotFoundException (the intent), not from the route's
        // ULID pattern rejecting an invalid char (the old "NORN" had an O).
        mvc.perform(get("/api/v1/runs/01J0000000000000000000NRNX/timeseries"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("aggregates throughput + status codes across (worker, label) rows per second")
    void aggregatesAcrossWorkersAndLabels() throws Exception {
        String runId = "01J0000000000000000000HM02";
        insertRun(runId);

        // Three distinct seconds, two labels, two workers per label —
        // exercises the per-windowSecond aggregation across the full
        // (worker × label) cross-product. Anchored at baseSec so they
        // fall inside one of V1's auto-created weekly partitions.
        long s0 = baseSec, s1 = baseSec + 1, s2 = baseSec + 2;

        // s0: 4 rows totalling tps=20, errors=2; status: 18×200, 2×500
        insertMetric(runId, "wA", "GET /a", s0, 5, 0, 10.0, "{\"200\":5}");
        insertMetric(runId, "wB", "GET /a", s0, 5, 0, 10.0, "{\"200\":5}");
        insertMetric(runId, "wA", "GET /b", s0, 5, 1, 20.0, "{\"200\":4,\"500\":1}");
        insertMetric(runId, "wB", "GET /b", s0, 5, 1, 20.0, "{\"200\":4,\"500\":1}");
        // s1: 2 rows, tps=10, errors=0
        insertMetric(runId, "wA", "GET /a", s1, 5, 0, 12.0, "{\"200\":5}");
        insertMetric(runId, "wB", "GET /a", s1, 5, 0, 12.0, "{\"200\":5}");
        // s2: 1 row, tps=2, errors=2 → 100% error
        insertMetric(runId, "wA", "GET /b", s2, 2, 2, 50.0, "{\"500\":2}");

        MvcResult result = mvc.perform(get("/api/v1/runs/" + runId + "/timeseries"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        assertThat(body.get("bucketSize").intValue()).isEqualTo(1);
        assertThat(body.get("fromSecond").longValue()).isEqualTo(s0);
        assertThat(body.get("toSecond").longValue()).isEqualTo(s2);

        JsonNode tps = body.path("series").path("tps");
        assertThat(tps).hasSize(3);
        assertThat(tps.get(0).get("sec").longValue()).isEqualTo(s0);
        assertThat(tps.get(0).get("v").doubleValue()).isEqualTo(20.0);
        assertThat(tps.get(1).get("v").doubleValue()).isEqualTo(10.0);
        assertThat(tps.get(2).get("v").doubleValue()).isEqualTo(2.0);

        JsonNode errPct = body.path("series").path("errorPct");
        // s0: 2 errors / 20 tps × 100 = 10%
        assertThat(errPct.get(0).get("v").doubleValue()).isEqualTo(10.0);
        // s1: 0 errors / 10 tps = 0%
        assertThat(errPct.get(1).get("v").doubleValue()).isEqualTo(0.0);
        // s2: 2 errors / 2 tps × 100 = 100%
        assertThat(errPct.get(2).get("v").doubleValue()).isEqualTo(100.0);

        JsonNode avgRt = body.path("series").path("avgRtMs");
        // s0: weighted mean of (10ms × 10 tps) + (20ms × 10 tps) over 20 = 15ms
        assertThat(avgRt.get(0).get("v").doubleValue()).isEqualTo(15.0);
        // s1: only label /a, avgRespTimeMs = 12ms across both workers → 12ms
        assertThat(avgRt.get(1).get("v").doubleValue()).isEqualTo(12.0);
        // s2: only one row at 50ms
        assertThat(avgRt.get(2).get("v").doubleValue()).isEqualTo(50.0);

        JsonNode statusJson = body.path("series").path("statusCodes");
        // 200 series across all 3 seconds: 18 + 10 + 0 = present at s0 and s1 only.
        JsonNode codes200 = statusJson.path("200");
        assertThat(codes200).hasSize(2);
        assertThat(codes200.get(0).get("sec").longValue()).isEqualTo(s0);
        assertThat(codes200.get(0).get("v").doubleValue()).isEqualTo(18.0);
        assertThat(codes200.get(1).get("sec").longValue()).isEqualTo(s1);
        assertThat(codes200.get(1).get("v").doubleValue()).isEqualTo(10.0);

        JsonNode codes500 = statusJson.path("500");
        assertThat(codes500).hasSize(2);
        assertThat(codes500.get(0).get("sec").longValue()).isEqualTo(s0);
        assertThat(codes500.get(0).get("v").doubleValue()).isEqualTo(2.0);
        assertThat(codes500.get(1).get("sec").longValue()).isEqualTo(s2);
        assertThat(codes500.get(1).get("v").doubleValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("only this run's rows are returned — sibling-run noise must not leak")
    void onlyMatchingRunIdSurfaces() throws Exception {
        String mineId = "01J0000000000000000000HM0M";
        String otherId = "01J0000000000000000000HM0X";
        insertRun(mineId);
        insertRun(otherId);

        insertMetric(mineId,  "wA", "GET /a", baseSec, 5, 0, 10.0, "{\"200\":5}");
        insertMetric(otherId, "wB", "GET /a", baseSec, 99, 99, 99.0, "{\"500\":99}");

        MvcResult result = mvc.perform(get("/api/v1/runs/" + mineId + "/timeseries"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        // sibling run's tps=99 must not bleed into mine.
        assertThat(body.path("series").path("tps")).hasSize(1);
        assertThat(body.path("series").path("tps").get(0).get("v").doubleValue()).isEqualTo(5.0);
        assertThat(body.path("series").path("statusCodes").has("500")).isFalse();
    }

    @Test
    @DisplayName("downsamples when raw seconds exceed 1500 — bucketSize > 1, point count bounded")
    void downsamplesLongRuns() throws Exception {
        String runId = "01J0000000000000000000HM0K";
        insertRun(runId);

        // Insert 1600 distinct seconds → exceeds the 1500 cap. Anchored
        // at baseSec so they fall in the auto-created weekly partition;
        // 1600 s ≈ 27 min, comfortably inside one partition.
        for (long sec = 0; sec < 1600; sec++) {
            insertMetric(runId, "wA", "GET /x", baseSec + sec, 10, 0, 5.0, "{\"200\":10}");
        }

        MvcResult result = mvc.perform(get("/api/v1/runs/" + runId + "/timeseries"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        int bucketSize = body.get("bucketSize").intValue();
        assertThat(bucketSize)
                .as("nice-bucket choice for 1600 raw points must be width=2 (800 buckets)")
                .isEqualTo(2);
        assertThat(body.path("series").path("tps").size())
                .as("returned points must be bounded by the bucket cap")
                .isLessThanOrEqualTo(1500);
        // Each raw second's tps is 10, so the per-second AVERAGE in
        // every bucket is also 10 — the y-axis stays in per-second
        // units across all bucket widths.
        for (JsonNode p : body.path("series").path("tps")) {
            assertThat(p.get("v").doubleValue()).isEqualTo(10.0);
        }
    }

    @Test
    @DisplayName("?window=5m returns only the last 300s, anchored at the run's latest second")
    void windowTruncatesToRecentSlice() throws Exception {
        String runId = "01J0000000000000000000HM0W";
        insertRun(runId);

        // 400 contiguous seconds → window=all is 400 points; window=5m (300s)
        // keeps the last 300, anchored at the max second (baseSec+399).
        for (long sec = 0; sec < 400; sec++) {
            insertMetric(runId, "wA", "GET /x", baseSec + sec, 10, 0, 5.0, "{\"200\":10}");
        }

        JsonNode all = json.readTree(mvc.perform(get("/api/v1/runs/" + runId + "/timeseries"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(all.path("series").path("tps")).hasSize(400);

        JsonNode windowed = json.readTree(mvc.perform(get("/api/v1/runs/" + runId + "/timeseries?window=5m"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode tps = windowed.path("series").path("tps");
        assertThat(tps).hasSize(300);
        // First point is maxSec - 299 = (baseSec+399) - 299 = baseSec+100.
        assertThat(tps.get(0).get("sec").longValue()).isEqualTo(baseSec + 100);
        assertThat(windowed.get("toSecond").longValue()).isEqualTo(baseSec + 399);
    }

    @Test
    @DisplayName("live run: the newest settleSeconds of data are trimmed (stable poll-to-poll); window=5m re-anchors at the settled edge")
    void liveRun_trimsUnsettledTrailingEdge() throws Exception {
        // RUNNING → live → the configured 5 s settle margin applies. The newest
        // few seconds are still being aggregated + ingested on a real run, so we
        // drop them; the chart then renders identical data on consecutive polls
        // instead of a wobbling trailing edge.
        String runId = "01J0000000000000000000HM0V";
        insertRun(runId, "RUNNING");

        // 400 contiguous seconds, max = baseSec+399. Settled edge = 399-5 = +394.
        for (long sec = 0; sec < 400; sec++) {
            insertMetric(runId, "wA", "GET /x", baseSec + sec, 10, 0, 5.0, "{\"200\":10}");
        }

        // Whole test: last 5 seconds (395..399) are trimmed → 395 points, ending +394.
        JsonNode all = json.readTree(mvc.perform(get("/api/v1/runs/" + runId + "/timeseries"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(all.path("series").path("tps")).hasSize(395);
        assertThat(all.get("toSecond").longValue()).isEqualTo(baseSec + 394);

        // window=5m re-anchors at the SETTLED max (+394), not the raw max (+399):
        // first point = 394 - 299 = baseSec+95, 300 points.
        JsonNode windowed = json.readTree(mvc.perform(get("/api/v1/runs/" + runId + "/timeseries?window=5m"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode tps = windowed.path("series").path("tps");
        assertThat(tps).hasSize(300);
        assertThat(tps.get(0).get("sec").longValue()).isEqualTo(baseSec + 95);
        assertThat(windowed.get("toSecond").longValue()).isEqualTo(baseSec + 394);
    }

    @Test
    @DisplayName("?window=bogus → 400 (only the fixed UI set is accepted)")
    void windowRejectsUnknownValue() throws Exception {
        String runId = "01J0000000000000000000HM0B";
        insertRun(runId);
        insertMetric(runId, "wA", "GET /x", baseSec, 10, 0, 5.0, "{\"200\":10}");
        mvc.perform(get("/api/v1/runs/" + runId + "/timeseries?window=7m"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("default (no byRegion) omits the regions map entirely")
    void defaultOmitsRegions() throws Exception {
        String runId = "01J0000000000000000000HM0D";
        insertRun(runId);
        insertMetric(runId, "wA", "GET /a", baseSec, 5, 0, 10.0, "{\"200\":5}", "us-east-1");

        MvcResult result = mvc.perform(get("/api/v1/runs/" + runId + "/timeseries"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        // @JsonInclude(NON_EMPTY) drops the empty map → field absent.
        assertThat(body.has("regions")).isFalse();
    }

    @Test
    @DisplayName("?byRegion=true splits per region; total === fold of regions")
    void byRegion_splitsAndFoldsToTotal() throws Exception {
        String runId = "01J0000000000000000000HM0R";
        insertRun(runId);

        // One second, two regions.
        //   us-east-1: tps=10, 0 errors, avgRt 20ms → status 10×200
        //   us-west-2: tps=5,  1 error,  avgRt 40ms → status 4×200, 1×500
        long s0 = baseSec;
        insertMetric(runId, "wEast", "GET /a", s0, 10, 0, 20.0, "{\"200\":10}", "us-east-1");
        insertMetric(runId, "wWest", "GET /a", s0, 5, 1, 40.0, "{\"200\":4,\"500\":1}", "us-west-2");

        MvcResult result = mvc.perform(get("/api/v1/runs/" + runId + "/timeseries?byRegion=true"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());

        // Total: tps = 15; weighted avg RT = (20×10 + 40×5)/15 = 26.666…;
        // error % = 1/15 × 100 = 6.666…; status 200 = 14, 500 = 1.
        assertThat(body.path("series").path("tps").get(0).get("v").doubleValue()).isEqualTo(15.0);
        assertThat(body.path("series").path("avgRtMs").get(0).get("v").doubleValue())
                .isCloseTo(26.6667, org.assertj.core.data.Offset.offset(0.01));
        assertThat(body.path("series").path("errorPct").get(0).get("v").doubleValue())
                .isCloseTo(6.6667, org.assertj.core.data.Offset.offset(0.01));
        assertThat(body.path("series").path("statusCodes").path("200").get(0).get("v").doubleValue())
                .isEqualTo(14.0);
        assertThat(body.path("series").path("statusCodes").path("500").get(0).get("v").doubleValue())
                .isEqualTo(1.0);

        JsonNode regions = body.path("regions");
        assertThat(regions.has("us-east-1")).isTrue();
        assertThat(regions.has("us-west-2")).isTrue();

        // us-east-1: tps=10, avgRt=20, 0% error, 200=10 (no 500).
        JsonNode east = regions.path("us-east-1");
        assertThat(east.path("tps").get(0).get("v").doubleValue()).isEqualTo(10.0);
        assertThat(east.path("avgRtMs").get(0).get("v").doubleValue()).isEqualTo(20.0);
        assertThat(east.path("errorPct").get(0).get("v").doubleValue()).isEqualTo(0.0);
        assertThat(east.path("statusCodes").path("200").get(0).get("v").doubleValue()).isEqualTo(10.0);
        assertThat(east.path("statusCodes").has("500")).isFalse();

        // us-west-2: tps=5, avgRt=40, 20% error, 200=4, 500=1.
        JsonNode west = regions.path("us-west-2");
        assertThat(west.path("tps").get(0).get("v").doubleValue()).isEqualTo(5.0);
        assertThat(west.path("avgRtMs").get(0).get("v").doubleValue()).isEqualTo(40.0);
        assertThat(west.path("errorPct").get(0).get("v").doubleValue()).isEqualTo(20.0);
        assertThat(west.path("statusCodes").path("500").get(0).get("v").doubleValue()).isEqualTo(1.0);
    }
}
