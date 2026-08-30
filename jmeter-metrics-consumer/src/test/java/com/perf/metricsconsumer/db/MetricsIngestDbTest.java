package com.perf.metricsconsumer.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The ingest contract through the one door — {@code POST /api/v1/ingest?groupId=}
 * — against the real schema on Oracle Free: rows land once under replay and
 * under concurrent writers, the dimensions carry the group's prefix and the
 * classifier's application, status codes fold, and two groups never share a
 * table.
 */
@SpringBootTest(properties = "metricsConsumer.maxRowsPerInsert=100")
@AutoConfigureMockMvc
@DisplayName("ingest on Oracle — group routing, dimensions, first-write-wins, concurrency")
class MetricsIngestDbTest extends OracleDbTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A current, 15 s-aligned window (an old one would be archived by the schema test's nightly pass). */
    private static final long NOW = (System.currentTimeMillis() / 1000L / 15L) * 15L;

    @Autowired MockMvc mvc;
    private final JdbcTemplate db = owner();

    private static String runKey() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static Map<String, Object> envelope(String runKey, String workerId, String region, long windowSecond, List<String> labels, long throughput) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String label : labels) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("label", label);
            e.put("throughput", throughput);
            e.put("errorCount", throughput / 20);
            e.put("errorRate", 0.05);
            e.put("avgRespTimeMs", 250.0);
            e.put("sumElapsedMs", throughput * 250);
            e.put("p50Ms", 200.0); e.put("p90Ms", 400.0); e.put("p95Ms", 500.0); e.put("p99Ms", 900.0);
            e.put("minMs", 10.0); e.put("maxMs", 1500.0); e.put("rawMaxMs", 1503);
            e.put("bytesReceived", 10_000); e.put("bytesSent", 2_000);
            e.put("statusCodes", Map.of("200", throughput - throughput / 20, "500", throughput / 40, "Non HTTP response code: x", throughput / 40));
            e.put("activeThreads", 10);
            entries.add(e);
        }
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("windowSecond", windowSecond);
        env.put("windowTimestamp", "2026-08-29T00:00:00Z");
        env.put("region", region);
        env.put("workerId", workerId);
        env.put("runId", runKey);
        env.put("joinedAtSecond", 0);
        env.put("entries", entries);
        return env;
    }

    private int ingest(String groupId, Map<String, Object> envelope, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/ingest" + (groupId == null ? "" : "?groupId=" + groupId))
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsBytes(envelope)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return MAPPER.readTree(r.getResponse().getContentAsString()).get("rowsInserted").asInt();
    }

    private long runId(String prefix, String runKey) {
        return db.queryForObject("SELECT RUN_ID FROM RUN WHERE GROUP_ID = ? AND RUN_KEY = ?", Long.class, prefix, runKey);
    }

    @Test
    void rows_land_once_and_the_dimensions_carry_the_prefix_and_the_classifier() throws Exception {
        String run = runKey();
        List<String> labels = List.of("TG1 login", "TG5 pay", "checkout");
        assertThat(ingest("cps", envelope(run, "w1", "na-east", NOW, labels, 200), 202)).isEqualTo(3);
        // A whole-envelope replay with different values: nothing lands, first write stays.
        assertThat(ingest("cps", envelope(run, "w1", "na-east", NOW, labels, 999), 202)).isZero();

        long runId = runId("CPS", run);
        assertThat(db.queryForObject("SELECT BASE_RUN_KEY FROM RUN WHERE RUN_ID = ?", String.class, runId)).isEqualTo(run);
        Map<String, Object> worker = db.queryForMap("SELECT GROUP_ID, REGION, JOINED_AT_SECOND FROM WORKER WHERE RUN_ID = ? AND WORKER_KEY = ?", runId, "w1");
        assertThat(worker).containsEntry("GROUP_ID", "CPS").containsEntry("REGION", "na-east");
        assertThat(db.queryForList("SELECT APPLICATION FROM LABEL WHERE GROUP_ID = 'CPS' AND LABEL_KEY IN (?, ?, ?) ORDER BY LABEL_KEY", String.class,
                "TG1 login", "TG5 pay", "checkout")).containsExactly("CPS", "CPS-PCI", "OTHER");

        List<Map<String, Object>> facts = db.queryForList(
                "SELECT l.LABEL_KEY, f.THROUGHPUT, f.ERROR_COUNT, f.AVG_MS, f.MAX_MS, f.HTTP_2XX, f.HTTP_5XX, f.HTTP_OTHER "
                + "FROM CPS_METRICS f JOIN LABEL l ON l.LABEL_ID = f.LABEL_ID WHERE f.RUN_ID = ? AND f.WINDOW_SECOND = ? ORDER BY l.LABEL_KEY", runId, NOW);
        assertThat(facts).hasSize(3);
        Map<String, Object> first = facts.get(0);
        assertThat(((Number) first.get("THROUGHPUT")).longValue()).isEqualTo(200);      // not 999
        assertThat(((Number) first.get("ERROR_COUNT")).longValue()).isEqualTo(10);
        assertThat(((Number) first.get("AVG_MS")).doubleValue()).isEqualTo(250.0);
        assertThat(((Number) first.get("MAX_MS")).doubleValue()).isEqualTo(1503.0);     // rawMaxMs
        assertThat(((Number) first.get("HTTP_2XX")).longValue()).isEqualTo(190);
        assertThat(((Number) first.get("HTTP_5XX")).longValue()).isEqualTo(5);
        assertThat(((Number) first.get("HTTP_OTHER")).longValue()).isEqualTo(5);
        // A partial replay + new labels inserts exactly the new ones.
        assertThat(ingest("cps", envelope(run, "w1", "na-east", NOW, List.of("TG1 login", "TG2 new", "TG3 new"), 5), 202)).isEqualTo(2);
    }

    @Test
    void groups_route_to_their_own_tables_and_an_unknown_group_is_400() throws Exception {
        String run = runKey();
        assertThat(ingest("demo", envelope(run, "w1", "na-east", NOW, List.of("cart add", "search q"), 10), 202)).isEqualTo(2);
        long demoRun = runId("DEMO", run);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM DEMO_METRICS WHERE RUN_ID = ?", Integer.class, demoRun)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM CPS_METRICS WHERE RUN_ID = ?", Integer.class, demoRun)).isZero();
        assertThat(db.queryForList("SELECT RUN_ID FROM RUN WHERE GROUP_ID = 'CPS' AND RUN_KEY = ?", Long.class, run)).isEmpty();
        assertThat(db.queryForObject("SELECT APPLICATION FROM LABEL WHERE GROUP_ID = 'DEMO' AND LABEL_KEY = ?", String.class, "cart add")).isEqualTo("CHECKOUT");

        ingest("nope", envelope(run, "w1", "na-east", NOW, List.of("x"), 1), 400);
        ingest(null, envelope(run, "w1", "na-east", NOW, List.of("x"), 1), 400);
        ingest("", envelope(run, "w1", "na-east", NOW, List.of("x"), 1), 400);
    }

    @Test
    void concurrent_workers_and_a_label_race_land_exactly_once() throws Exception {
        String run = runKey();
        List<String> labels = List.of("TG4 race", "TG4 race2", "TG6 race3");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 1; w <= 6; w++) {
                String worker = "w" + w;
                results.add(pool.submit(() -> {
                    go.await();
                    return ingest("cps", envelope(run, worker, "na-east", NOW, labels, 50), 202);
                }));
            }
            go.countDown();
            int total = 0;
            for (Future<Integer> f : results) total += f.get();
            assertThat(total).isEqualTo(18);
        } finally {
            pool.shutdownNow();
        }
        long runId = runId("CPS", run);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM CPS_METRICS WHERE RUN_ID = ? AND WINDOW_SECOND = ?", Integer.class, runId, NOW)).isEqualTo(18);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM WORKER WHERE RUN_ID = ?", Integer.class, runId)).isEqualTo(6);
        // Six threads raced to create the same three labels: one row each.
        assertThat(db.queryForObject("SELECT COUNT(*) FROM LABEL WHERE GROUP_ID = 'CPS' AND LABEL_KEY IN (?, ?, ?)", Integer.class,
                "TG4 race", "TG4 race2", "TG6 race3")).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM RUN WHERE GROUP_ID = 'CPS' AND RUN_KEY = ?", Integer.class, run)).isEqualTo(1);
    }

    @Test
    void an_empty_envelope_is_202_with_zero_rows_and_creates_no_dimensions() throws Exception {
        String run = runKey();
        assertThat(ingest("cps", envelope(run, "w1", "na-east", NOW, List.of(), 1), 202)).isZero();
        assertThat(db.queryForList("SELECT RUN_ID FROM RUN WHERE GROUP_ID = 'CPS' AND RUN_KEY = ?", Long.class, run)).isEmpty();
    }
}
