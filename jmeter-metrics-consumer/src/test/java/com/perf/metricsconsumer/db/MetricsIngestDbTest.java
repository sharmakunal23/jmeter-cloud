package com.perf.metricsconsumer.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.maintenance.RetentionJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The metrics schema's contract, exercised through the one door —
 * {@code POST /api/v1/ingest} — against the real migration on Oracle Free:
 * rows land once under replay and under concurrent writers, the rollups agree
 * with a rebuild from raw, and retention drops what it should and nothing else.
 */
@SpringBootTest(properties = "metricsConsumer.maxRowsPerChunk=100")
@AutoConfigureMockMvc
@DisplayName("metrics schema on Oracle — ingest contract")
class MetricsIngestDbTest extends OracleDbTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired RetentionJob retentionJob;

    private final JdbcTemplate owner = owner();

    // ── fixtures ─────────────────────────────────────────────────────────

    private static String runId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static long nowSecond() {
        return Instant.now().getEpochSecond();
    }

    /** One envelope: {@code labels} entries of {@code throughput} samples each, 5% errors, three status codes. */
    private static Map<String, Object> envelope(String runId, String workerId, String region,
                                                long windowSecond, int labels, long throughput) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < labels; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("label", "label-" + i);
            e.put("throughput", throughput);
            e.put("errorCount", throughput / 20);
            e.put("sumElapsedMs", throughput * 250);
            e.put("p50Ms", 200.0); e.put("p90Ms", 400.0); e.put("p95Ms", 500.0); e.put("p99Ms", 900.0);
            e.put("maxMs", 1500.0); e.put("rawMaxMs", 1537);
            e.put("bytesReceived", throughput * 100); e.put("bytesSent", throughput * 20);
            e.put("activeThreads", 10);
            Map<String, Long> codes = new LinkedHashMap<>();
            codes.put("200", throughput - throughput / 20);
            codes.put("500", throughput / 40);
            codes.put("Non HTTP response code: java.net.SocketTimeoutException", throughput / 20 - throughput / 40);
            e.put("statusCodes", codes);
            entries.add(e);
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("windowSecond", windowSecond);
        env.put("windowTimestamp", Instant.ofEpochSecond(windowSecond).toString());
        env.put("region", region);
        env.put("workerId", workerId);
        env.put("runId", runId);
        env.put("entries", entries);
        return env;
    }

    private MvcResult ingest(Map<String, Object> envelope) throws Exception {
        return mvc.perform(post("/api/v1/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsBytes(envelope)))
                .andReturn();
    }

    private int rowsInserted(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        return MAPPER.readTree(r.getResponse().getContentAsString()).get("rowsInserted").asInt();
    }

    private long count(String table, String runId) {
        Long n = owner.queryForObject(
                "SELECT COUNT(*) FROM metrics.\"" + table + "\" WHERE \"runId\" = ?", Long.class, runId);
        return n == null ? 0 : n;
    }

    private long sum(String table, String column, String runId) {
        Long n = owner.queryForObject(
                "SELECT NVL(SUM(\"" + column + "\"), 0) FROM metrics.\"" + table + "\" WHERE \"runId\" = ?",
                Long.class, runId);
        return n == null ? 0 : n;
    }

    // ── contract ─────────────────────────────────────────────────────────

    @Test
    void the_migration_applied_and_every_object_is_valid() {
        Integer invalid = owner.queryForObject(
                "SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID'", Integer.class);
        assertThat(invalid).isZero();
        List<String> tables = owner.queryForList(
                "SELECT table_name FROM user_tables ORDER BY 1", String.class);
        assertThat(tables).contains("workerMetric", "workerMetricStatus", "runSecond", "runSecondStatus",
                "runLabel", "workerMetricStage", "workerMetricStatusStage", "maintenanceLock");
    }

    @Test
    void rows_land_once_and_a_replay_is_a_noop() throws Exception {
        String run = runId();
        long t = nowSecond();
        List<Map<String, Object>> batch = List.of(
                envelope(run, "w1", "na-east", t, 3, 100),
                envelope(run, "w2", "na-east", t, 3, 100),
                envelope(run, "w1", "na-east", t + 1, 3, 50));

        int landed = 0;
        for (Map<String, Object> e : batch) landed += rowsInserted(ingest(e));
        assertThat(landed).isEqualTo(9);

        int replayed = 0;
        for (Map<String, Object> e : batch) replayed += rowsInserted(ingest(e));
        assertThat(replayed).isZero();

        assertThat(count("workerMetric", run)).isEqualTo(9);
        assertThat(count("workerMetricStatus", run)).isEqualTo(27);
        assertThat(count("runSecond", run)).isEqualTo(2);
        assertThat(count("runLabel", run)).isEqualTo(3);
        // rollups absorbed each row exactly once
        assertThat(sum("runSecond", "samples", run)).isEqualTo(sum("workerMetric", "throughput", run)).isEqualTo(750);
        assertThat(sum("runSecondStatus", "n", run)).isEqualTo(sum("workerMetricStatus", "n", run));
        assertThat(sum("runLabel", "sumElapsedMs", run)).isEqualTo(750 * 250);
    }

    @Test
    void concurrent_writers_land_each_key_exactly_once() throws Exception {
        String run = runId();
        long t = nowSecond();
        List<Map<String, Object>> envelopes = List.of(
                envelope(run, "w1", "na-west", t, 4, 80),
                envelope(run, "w2", "na-west", t, 4, 80),
                envelope(run, "w1", "na-west", t + 1, 4, 80));
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Integer>>> results = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            results.add(pool.submit(() -> {
                go.await();
                List<Integer> statuses = new ArrayList<>();
                for (Map<String, Object> e : envelopes) statuses.add(ingest(e).getResponse().getStatus());
                return statuses;
            }));
        }
        go.countDown();
        List<Integer> all = new ArrayList<>();
        for (Future<List<Integer>> f : results) all.addAll(f.get());
        pool.shutdown();

        // Every attempt is either accepted or told to retry — never a terminal error.
        assertThat(all).allMatch(s -> s == 202 || s == 503);
        assertThat(count("workerMetric", run)).isEqualTo(12);
        assertThat(sum("runSecond", "samples", run)).isEqualTo(12 * 80);
        assertThat(sum("runSecond", "rowCount", run)).isEqualTo(12);
        assertThat(sum("runLabel", "rowCount", run)).isEqualTo(12);
        assertThat(sum("runSecondStatus", "n", run)).isEqualTo(sum("workerMetricStatus", "n", run));
    }

    @Test
    void delta_rollups_equal_a_rebuild_from_raw() throws Exception {
        String run = runId();
        long t = nowSecond();
        for (String region : List.of("na-east", "na-west")) {
            for (int s = 0; s < 5; s++) {
                ingest(envelope(run, "w-" + region + "-1", region, t + s, 2, 60));
                ingest(envelope(run, "w-" + region + "-2", region, t + s, 2, 30));
            }
        }
        ingest(envelope(run, "w-na-east-1", "na-east", t + 2, 2, 60));   // a replay in the middle
        assertThat(count("workerMetric", run)).isEqualTo(40);

        owner.execute("CREATE TABLE metrics.\"tmpRunSecond\" AS SELECT * FROM metrics.\"runSecond\"");
        owner.execute("CREATE TABLE metrics.\"tmpRunSecondStatus\" AS SELECT * FROM metrics.\"runSecondStatus\"");
        owner.execute("CREATE TABLE metrics.\"tmpRunLabel\" AS SELECT * FROM metrics.\"runLabel\"");
        try {
            Integer seconds = owner.execute(
                    "BEGIN metrics.\"metricsIngest\".\"rebuildRunRollups\"(?, ?); END;",
                    (CallableStatementCallback<Integer>) cs -> {
                        cs.setString(1, run);
                        cs.registerOutParameter(2, Types.NUMERIC);
                        cs.execute();
                        return cs.getInt(2);
                    });
            assertThat(seconds).isEqualTo(10);   // 5 seconds × 2 regions
            for (String table : List.of("runSecond", "runSecondStatus", "runLabel")) {
                String tmp = "tmp" + Character.toUpperCase(table.charAt(0)) + table.substring(1);
                Integer diff = owner.queryForObject(
                        "SELECT COUNT(*) FROM ((SELECT * FROM metrics.\"" + tmp + "\" MINUS SELECT * FROM metrics.\"" + table + "\")"
                        + " UNION ALL (SELECT * FROM metrics.\"" + table + "\" MINUS SELECT * FROM metrics.\"" + tmp + "\"))",
                        Integer.class);
                assertThat(diff).as(table + " delta vs rebuild").isZero();
            }
        } finally {
            owner.execute("DROP TABLE metrics.\"tmpRunSecond\"");
            owner.execute("DROP TABLE metrics.\"tmpRunSecondStatus\"");
            owner.execute("DROP TABLE metrics.\"tmpRunLabel\"");
        }
    }

    @Test
    void retention_drops_expired_weeks_keeps_the_current_one_and_rebuild_refuses_what_it_cannot_cover() throws Exception {
        String old = runId();
        String middleAged = runId();
        String current = runId();
        long twoYearsAgo = nowSecond() - 730L * 86400;
        long sixtyDaysAgo = nowSecond() - 60L * 86400;
        assertThat(rowsInserted(ingest(envelope(old, "w1", "na-east", twoYearsAgo, 1, 10)))).isEqualTo(1);
        assertThat(rowsInserted(ingest(envelope(middleAged, "w1", "na-east", sixtyDaysAgo, 1, 10)))).isEqualTo(1);
        assertThat(rowsInserted(ingest(envelope(current, "w1", "na-east", nowSecond(), 1, 10)))).isEqualTo(1);

        RetentionJob.RetentionResult result = retentionJob.runRetention();

        assertThat(result.skipped()).isFalse();
        assertThat(result.rawPartitionsDropped()).isNotEmpty();
        assertThat(result.rollupPartitionsDropped()).isNotEmpty();
        // 2 years old: raw and rollups gone.
        assertThat(count("workerMetric", old)).isZero();
        assertThat(count("runSecond", old)).isZero();
        assertThat(count("runLabel", old)).isZero();
        // 60 days old: past raw retention (30 d), inside rollup retention (52 w).
        assertThat(count("workerMetric", middleAged)).isZero();
        assertThat(count("runLabel", middleAged)).isEqualTo(1);
        assertThat(count("workerMetric", current)).isEqualTo(1);
        assertThat(count("runLabel", current)).isEqualTo(1);

        // The rollups are now the only complete record of the 60-day-old run:
        // a rebuild from (absent) raw must refuse rather than wipe them.
        assertThatThrownBy(() -> owner.execute(
                "BEGIN metrics.\"metricsIngest\".\"rebuildRunRollups\"(?, ?); END;",
                (CallableStatementCallback<Integer>) cs -> {
                    cs.setString(1, middleAged);
                    cs.registerOutParameter(2, Types.NUMERIC);
                    cs.execute();
                    return cs.getInt(2);
                }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORA-20002");
        assertThat(count("runLabel", middleAged)).isEqualTo(1);
    }
}
