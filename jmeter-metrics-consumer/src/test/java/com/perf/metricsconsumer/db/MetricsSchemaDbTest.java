package com.perf.metricsconsumer.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metrics schema's SQL contract on a fresh Oracle Free — the shared V1
 * plus the rendered {@code cps} and {@code demo} group bundles — driven with
 * the exact statements the hosted consumer issues (§5 dimension resolution,
 * §6 the 21-column hinted insert) and the nightly archive procedure. Every
 * statement runs as the owner with unqualified names, exactly like the
 * consumer's session.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("metrics schema on Oracle — groups, dimensions, first-write-wins facts, archive")
class MetricsSchemaDbTest extends OracleDbTestSupport {

    private static final JdbcTemplate db = owner();
    /** A current, 15 s-aligned window: anything older than hotDays is (correctly) archived by test 4. */
    private static final long NOW = (System.currentTimeMillis() / 1000L / 15L) * 15L;

    private static final String INSERT_FACT =
            "INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(CPS_METRICS(RUN_ID,WORKER_ID,LABEL_ID,WINDOW_SECOND)) */ "
            + "INTO CPS_METRICS (RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND, THROUGHPUT, ERROR_COUNT, AVG_MS, "
            + "P50_MS, P90_MS, P95_MS, P99_MS, MIN_MS, MAX_MS, BYTES_RECV, BYTES_SENT, "
            + "HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX, HTTP_OTHER, ACTIVE_THREADS) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    @Test
    @Order(1)
    void every_object_is_valid_and_both_groups_are_registered() {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID'", Integer.class)).isZero();
        assertThat(db.queryForList("SELECT object_name FROM user_objects WHERE object_type = 'TABLE' ORDER BY 1", String.class))
                .contains("LABEL", "RUN", "WORKER", "GROUP_REGISTRY", "METRICS_H_AUDIT",
                        "CPS_METRICS", "CPS_METRICS_H", "CPS_METRICS_H_STAGE",
                        "DEMO_METRICS", "DEMO_METRICS_H", "DEMO_METRICS_H_STAGE");
        // The routing row exactly as the consumer's GroupRegistry reads it.
        Map<String, Object> cps = db.queryForMap(
                "SELECT GROUP_ID, TABLE_PREFIX, METRICS_TABLE, METRICS_HIST_TABLE, CLASSIFY_FN FROM GROUP_REGISTRY WHERE GROUP_ID = ? AND ENABLED = 1", "cps");
        assertThat(cps).containsEntry("TABLE_PREFIX", "CPS").containsEntry("METRICS_TABLE", "CPS_METRICS")
                .containsEntry("METRICS_HIST_TABLE", "CPS_METRICS_H").containsEntry("CLASSIFY_FN", "CPS_CLASSIFY_LABEL");
        // The hint is validated by Oracle: the PK must be the only unique index on the fact table.
        assertThat(db.queryForList("SELECT index_name FROM user_indexes WHERE table_name = 'CPS_METRICS' AND uniqueness = 'UNIQUE'", String.class))
                .containsExactly("CPS_METRICS_PK");
        assertThat(db.queryForList("SELECT column_name FROM user_ind_columns WHERE index_name = 'CPS_METRICS_PK' ORDER BY column_position", String.class))
                .containsExactly("RUN_ID", "WORKER_ID", "LABEL_ID", "WINDOW_SECOND");
        assertThat(db.queryForObject("SELECT job_name FROM user_scheduler_jobs WHERE job_name = 'CPS_NIGHTLY_MAINT'", String.class))
                .isEqualTo("CPS_NIGHTLY_MAINT");
    }

    @Test
    @Order(2)
    void classifier_and_run_key_normalisation_follow_the_hosted_rules() {
        assertThat(db.queryForObject("SELECT CPS_CLASSIFY_LABEL('TG5 checkout') FROM dual", String.class)).isEqualTo("CPS-PCI");
        assertThat(db.queryForObject("SELECT CPS_CLASSIFY_LABEL('TG2 login') FROM dual", String.class)).isEqualTo("CPS");
        assertThat(db.queryForObject("SELECT CPS_CLASSIFY_LABEL('unknown') FROM dual", String.class)).isEqualTo("OTHER");
        assertThat(db.queryForObject("SELECT DEMO_CLASSIFY_LABEL('cart add') FROM dual", String.class)).isEqualTo("CHECKOUT");

        db.update("INSERT INTO RUN (GROUP_ID, RUN_KEY, FIRST_SEEN) VALUES (?, ?, ?)", "CPS", "MA_cps-2026-08-29_3_S1P2", 1L);
        assertThat(db.queryForObject("SELECT BASE_RUN_KEY FROM RUN WHERE GROUP_ID = ? AND RUN_KEY = ?", String.class,
                "CPS", "MA_cps-2026-08-29_3_S1P2")).isEqualTo("cps-2026-08-29");
    }

    @Test
    @Order(3)
    void facts_are_first_write_wins_and_the_reader_query_prunes_partitions() {
        long runId = resolveRun("run-2026-05-09-001", NOW);
        long workerId = resolveWorker(runId, "worker-1", "us-east-1");
        long foo = resolveLabel("GET /api/foo", NOW);
        long bar = resolveLabel("POST /api/bar", NOW);
        assertThat(db.queryForObject("SELECT APPLICATION FROM LABEL WHERE LABEL_ID = ?", String.class, foo)).isEqualTo("OTHER");

        int[] first = db.batchUpdate(INSERT_FACT, List.<Object[]>of(
                fact(runId, workerId, foo, NOW, 105, 2, 95.4, 98, 1, 0, 2, 4),
                fact(runId, workerId, bar, NOW, 40, 0, 61.2, 40, 0, 0, 0, 0)));
        assertThat(first).containsExactly(1, 1);

        // A replay of the whole envelope. The writer first asks which labels of this
        // (run, worker, window) already landed — one PK-prefix probe — and inserts
        // only the rest, so a batch never carries a known duplicate (see the tripwire
        // test below for why). Here nothing is left to insert.
        List<Long> landed = db.queryForList(
                "SELECT LABEL_ID FROM CPS_METRICS WHERE RUN_ID = ? AND WORKER_ID = ? AND WINDOW_SECOND = ?",
                Long.class, runId, workerId, NOW);
        assertThat(landed).containsExactlyInAnyOrder(foo, bar);
        // A duplicate that slips past the probe (a concurrent replica) is a single-row
        // insert: the hint suppresses it, the count is 0, the first write stays.
        assertThat(db.update(INSERT_FACT, fact(runId, workerId, foo, NOW, 999, 0, 1.0, 999, 0, 0, 0, 0))).isZero();
        assertThat(db.queryForObject("SELECT THROUGHPUT FROM CPS_METRICS WHERE RUN_ID = ? AND WORKER_ID = ? AND LABEL_ID = ? AND WINDOW_SECOND = ?",
                Integer.class, runId, workerId, foo, NOW)).isEqualTo(105);   // first write wins
        // A second worker in the same window is a distinct key.
        long workerId2 = resolveWorker(runId, "worker-2", "us-west-1");
        assertThat(db.batchUpdate(INSERT_FACT, List.<Object[]>of(fact(runId, workerId2, foo, NOW, 50, 0, 80.0, 50, 0, 0, 0, 0)))).containsExactly(1);

        // The panel query the readers run — throughput-weighted, faceted by application.
        List<Map<String, Object>> byApp = db.queryForList(
                "SELECT l.APPLICATION AS app, SUM(f.THROUGHPUT) AS tp, SUM(f.AVG_MS * f.THROUGHPUT) / NULLIF(SUM(f.THROUGHPUT), 0) AS avg_ms "
                + "FROM CPS_METRICS f JOIN LABEL l ON l.LABEL_ID = f.LABEL_ID "
                + "WHERE f.RUN_ID = ? AND f.WINDOW_SECOND BETWEEN ? AND ? GROUP BY l.APPLICATION",
                runId, NOW, NOW + 15);
        assertThat(byApp).hasSize(1);
        assertThat(((Number) byApp.get(0).get("TP")).longValue()).isEqualTo(195);

        // The plan: partition pruned on WINDOW_SECOND and the local index used, per the oracle-sql skill.
        // PLAN_TABLE is session-scoped, so explain and display on the same connection.
        String plan = db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("EXPLAIN PLAN FOR SELECT SUM(THROUGHPUT) FROM CPS_METRICS WHERE RUN_ID = " + runId
                        + " AND LABEL_ID = " + foo + " AND WINDOW_SECOND BETWEEN " + NOW + " AND " + (NOW + 3600));
                StringBuilder out = new StringBuilder();
                try (java.sql.ResultSet rs = st.executeQuery(
                        "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, NULL, 'BASIC +PARTITION'))")) {
                    while (rs.next()) out.append(rs.getString(1)).append('\n');
                }
                return out.toString();
            }
        });
        assertThat(plan).contains("PARTITION RANGE").doesNotContain("PARTITION RANGE ALL").doesNotContain("TABLE ACCESS FULL");
        assertThat(plan).contains("CPS_METRICS_RUN_LBL_IDX");
    }

    @Test
    @Order(4)
    void archive_collapses_an_aged_day_into_history_and_drops_the_source_partition() {
        // A day well past hotDays: 2024-01-10, two workers, one label, two windows.
        long day = 1704844800L;
        long runId = resolveRun("archived-run", day);
        long w1 = resolveWorker(runId, "w1", "na-east");
        long w2 = resolveWorker(runId, "w2", "na-east");
        long label = resolveLabel("TG1 old", day);
        db.batchUpdate(INSERT_FACT, List.<Object[]>of(
                fact(runId, w1, label, day, 10, 1, 100.0, 9, 0, 1, 0, 0),
                fact(runId, w2, label, day, 30, 0, 200.0, 30, 0, 0, 0, 0),
                fact(runId, w1, label, day + 15, 20, 0, 50.0, 20, 0, 0, 0, 0)));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_tab_partitions WHERE table_name = 'CPS_METRICS'", Integer.class)).isGreaterThanOrEqualTo(3);

        db.execute("BEGIN CPS_ARCHIVE_TO_H(7, 0, 15, 0); END;");

        List<Map<String, Object>> hist = db.queryForList(
                "SELECT WINDOW_SECOND, WORKER_COUNT, THROUGHPUT, ERROR_COUNT, AVG_MS FROM CPS_METRICS_H WHERE RUN_ID = ? AND LABEL_ID = ? ORDER BY WINDOW_SECOND",
                runId, label);
        assertThat(hist).hasSize(2);
        assertThat(((Number) hist.get(0).get("WORKER_COUNT")).intValue()).isEqualTo(2);
        assertThat(((Number) hist.get(0).get("THROUGHPUT")).longValue()).isEqualTo(40);
        assertThat(((Number) hist.get(0).get("ERROR_COUNT")).longValue()).isEqualTo(1);
        assertThat(((Number) hist.get(0).get("AVG_MS")).doubleValue()).isEqualTo(175.0);   // (10*100 + 30*200) / 40
        assertThat(db.queryForObject("SELECT COUNT(*) FROM CPS_METRICS WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?", Integer.class, runId, day, day + 86400)).isZero();
        assertThat(db.queryForObject("SELECT STATUS FROM METRICS_H_AUDIT WHERE SOURCE_TABLE = 'CPS_METRICS' AND PARTITION_HIGH_VALUE = ?", String.class, day + 86400 - (day % 86400)))
                .isEqualTo("COMPLETE");
        // The current run's hot day is untouched (scoped to this test's run: the ingest suite shares the container).
        long currentRun = resolveRun("run-2026-05-09-001", NOW);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM CPS_METRICS WHERE RUN_ID = ? AND WINDOW_SECOND = ?", Integer.class, currentRun, NOW)).isEqualTo(3);

        // Prune past historyDays: the archived day is older than 30 days, so it goes.
        db.execute("BEGIN CPS_PRUNE_H(30, 0); END;");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM CPS_METRICS_H WHERE RUN_ID = ?", Integer.class, runId)).isZero();
        db.execute("BEGIN CPS_MAINTAIN; END;");
    }

    @Test
    @Order(5)
    void groups_are_isolated_and_the_bundle_is_idempotent() {
        long runId = resolveRun("run-2026-05-09-001", NOW);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM DEMO_METRICS WHERE RUN_ID = ?", Integer.class, runId)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM GROUP_REGISTRY", Integer.class)).isEqualTo(2);
        // Re-applying the rendered bundle changes nothing (Flyway does this on every checksum change).
        migrate("CARDZATE_DB_GRAF", "oracle/migrations");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_objects WHERE status <> 'VALID'", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM CPS_METRICS WHERE RUN_ID = ?", Integer.class, runId)).isEqualTo(3);
        // Readers see the tables, purgers may delete facts, nobody else exists.
        assertThat(db.queryForList("SELECT grantee || ':' || privilege FROM user_tab_privs WHERE table_name = 'CPS_METRICS' ORDER BY 1", String.class))
                .containsExactly("METRICS_PURGER:DELETE", "METRICS_PURGER:SELECT", "METRICS_READER:SELECT");
    }

    @Test
    @Order(6)
    void tripwire_a_jdbc_batch_with_a_suppressed_duplicate_is_an_ora_00600_on_this_oracle_build() {
        // Oracle Free 26ai (23.26.2) + ojdbc 23.7: a JDBC array insert of >= 2 rows in
        // which IGNORE_ROW_ON_DUPKEY_INDEX suppresses a row raises ORA-00600
        // [qerltcUserIterGet_0, <row index>] and kills the session. A one-row batch,
        // a single executeUpdate and PL/SQL FORALL are all correct, and the hosted
        // consumer's batch path works on its Oracle version — so the writer keeps a
        // known duplicate out of every batch (the probe above). When this test starts
        // FAILING, the image no longer has the bug and the probe becomes optional.
        long runId = resolveRun("tripwire-run", NOW);
        long workerId = resolveWorker(runId, "w", "na-east");
        long label = resolveLabel("TG3 tripwire", NOW);
        assertThat(db.update(INSERT_FACT, fact(runId, workerId, label, NOW, 1, 0, 1.0, 1, 0, 0, 0, 0))).isEqualTo(1);
        long other = resolveLabel("TG3 tripwire other", NOW);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.batchUpdate(INSERT_FACT, List.<Object[]>of(
                        fact(runId, workerId, other, NOW, 1, 0, 1.0, 1, 0, 0, 0, 0),
                        fact(runId, workerId, label, NOW, 1, 0, 1.0, 1, 0, 0, 0, 0))))
                .hasMessageContaining("ORA-00600").hasMessageContaining("qerltcUserIterGet_0");
        // The single-row form of the same duplicate is fine (fresh connection: owner() is unpooled).
        assertThat(db.update(INSERT_FACT, fact(runId, workerId, label, NOW, 1, 0, 1.0, 1, 0, 0, 0, 0))).isZero();
    }

    // ── the hosted consumer's dimension resolution, verbatim ────────────

    private static long resolveRun(String runKey, long firstSeen) {
        List<Long> found = db.queryForList("SELECT RUN_ID FROM RUN WHERE GROUP_ID = ? AND RUN_KEY = ?", Long.class, "CPS", runKey);
        if (found.isEmpty()) {
            db.update("INSERT INTO RUN (GROUP_ID, RUN_KEY, FIRST_SEEN) VALUES (?, ?, ?)", "CPS", runKey, firstSeen);
            found = db.queryForList("SELECT RUN_ID FROM RUN WHERE GROUP_ID = ? AND RUN_KEY = ?", Long.class, "CPS", runKey);
        }
        return found.get(0);
    }

    private static long resolveWorker(long runId, String workerKey, String region) {
        List<Long> found = db.queryForList("SELECT WORKER_ID FROM WORKER WHERE RUN_ID = ? AND WORKER_KEY = ?", Long.class, runId, workerKey);
        if (found.isEmpty()) {
            db.update("INSERT INTO WORKER (RUN_ID, GROUP_ID, WORKER_KEY, REGION, JOINED_AT_SECOND) VALUES (?, ?, ?, ?, ?)", runId, "CPS", workerKey, region, 0L);
            found = db.queryForList("SELECT WORKER_ID FROM WORKER WHERE RUN_ID = ? AND WORKER_KEY = ?", Long.class, runId, workerKey);
        }
        return found.get(0);
    }

    private static long resolveLabel(String label, long firstSeen) {
        List<Long> found = db.queryForList("SELECT LABEL_ID FROM LABEL WHERE GROUP_ID = ? AND LABEL_KEY = ?", Long.class, "CPS", label);
        if (found.isEmpty()) {
            db.update("INSERT INTO LABEL (GROUP_ID, LABEL_KEY, APPLICATION, FIRST_SEEN) VALUES (?, ?, CPS_CLASSIFY_LABEL(?), ?)", "CPS", label, label, firstSeen);
            found = db.queryForList("SELECT LABEL_ID FROM LABEL WHERE GROUP_ID = ? AND LABEL_KEY = ?", Long.class, "CPS", label);
        }
        return found.get(0);
    }

    /** One fact row in the consumer's 21-column bind order. */
    private static Object[] fact(long runId, long workerId, long labelId, long windowSecond,
                                 long throughput, long errors, double avgMs,
                                 long h2xx, long h3xx, long h4xx, long h5xx, long hOther) {
        return new Object[] {
                runId, workerId, labelId, windowSecond,
                throughput, errors, avgMs,
                avgMs * 0.8, avgMs * 1.2, avgMs * 1.5, avgMs * 2.0, 10.0, 500.0,
                1024L, 512L,
                h2xx, h3xx, h4xx, h5xx, hOther,
                50L };
    }
}
