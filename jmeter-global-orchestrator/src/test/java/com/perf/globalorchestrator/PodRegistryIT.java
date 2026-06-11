package com.perf.globalorchestrator;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavior IT for Step 15's pod registry. Verifies the user-visible
 * contract:
 *
 * <ol>
 *   <li>POST /registerPod is idempotent (call twice, single row).</li>
 *   <li>POST /heartbeat refreshes lastHeartbeat + flips state from
 *       LOST → IDLE.</li>
 *   <li>POST /heartbeat for an unknown podId → 404 POD_NOT_REGISTERED.</li>
 *   <li>The sweeper-equivalent SQL (markLostBefore) flips a stale pod to
 *       LOST, and a follow-up heartbeat brings it back to IDLE.</li>
 *   <li>GET /pods returns the registry view.</li>
 * </ol>
 *
 * <p>Schedules are turned off here (extreme {@code lostAfterMs}) so the
 * test deterministically exercises the SQL paths without racing the
 * background sweeper. The full live-stack heartbeat → sweep → LOST
 * cycle is exercised against the running Docker stack as the Step 15
 * checkpoint.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false",
        "globalOrchestrator.pod.sweepInitialDelayMs=3600000",
        "globalOrchestrator.pod.lostAfterMs=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator pod registry — behavior IT")
class PodRegistryIT {

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
    }

    @BeforeAll
    static void migrate() {
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .load()
                .migrate();
    }

    @Autowired MockMvc mvc;

    /**
     * Use the superuser to peek/poke the pod table — the IT can't go
     * through the runtime app user for low-level state munging.
     */
    private JdbcTemplate adminJdbc() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static final String TEST_APP_ID = "podRegistryTestApp";

    /**
     * Phase 6b: pod.applicationId is NOT NULL with an FK to application, so a
     * pod can only register against a real app row. Insert one shared app
     * directly via the superuser (idempotent across test methods) — this IT
     * has no Kafka container, so going through POST /applications (which
     * provisions a per-app topic) isn't an option, and a pod-registry IT
     * shouldn't depend on the app-provisioning path anyway.
     */
    private String appId() {
        adminJdbc().update(
                "INSERT INTO \"globalOrchestrator\".\"application\" (\"applicationId\",\"name\") "
                + "VALUES (?, ?) ON CONFLICT (\"applicationId\") DO NOTHING",
                TEST_APP_ID, "podregistry-app");
        return TEST_APP_ID;
    }

    /** Registers an app-bound stub pod (baseUrl http://{podId}:8080) and asserts 200. */
    private void register(String podId, String region) throws Exception {
        mvc.perform(post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podId\":\"" + podId + "\",\"region\":\"" + region + "\","
                                + "\"baseUrl\":\"http://" + podId + ":8080\","
                                + "\"applicationId\":\"" + appId() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("register → idempotent re-register → heartbeat → 404 on unknown podId")
    void registerHeartbeatFlow() throws Exception {
        String podId = "orchestrator-itA";
        String body  = "{\"podId\":\"" + podId + "\",\"region\":\"us-east-1\","
                     + "\"baseUrl\":\"http://" + podId + ":8080\","
                     + "\"applicationId\":\"" + appId() + "\"}";

        mvc.perform(post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.podId").value(podId))
                .andExpect(jsonPath("$.state").value("IDLE"));

        // Idempotent re-register: another call → still 200, single row.
        mvc.perform(post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Long count = adminJdbc().queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\"=?",
                Long.class, podId);
        assertThat(count).isEqualTo(1L);

        // Heartbeat → 200.
        mvc.perform(post("/api/v1/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podId\":\"" + podId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));

        // Heartbeat for unknown podId → 404 POD_NOT_REGISTERED.
        mvc.perform(post("/api/v1/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podId\":\"never-existed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POD_NOT_REGISTERED"));
    }

    @Test
    @DisplayName("Phase 6b — POST /registerPod without applicationId → 400 INVALID_REQUEST")
    void registerRejectsMissingApplicationId() throws Exception {
        // The legacy null-app registration path is gone; applicationId is
        // required (pod.applicationId is NOT NULL as of migration V16).
        mvc.perform(post("/api/v1/registerPod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podId\":\"orchestrator-noapp\",\"region\":\"us-east-1\","
                                + "\"baseUrl\":\"http://orchestrator-noapp:8080\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("stale pod is flipped to LOST; follow-up heartbeat brings it back to IDLE")
    void staleSweepThenRecovery() throws Exception {
        String podId = "orchestrator-itB";
        register(podId, "us-east-1");

        // Force lastHeartbeat 2 hours into the past so the simulated
        // sweep-cutoff updates this row. The IT's actual sweeper is
        // disabled (sweepInitialDelayMs=3600000), so we run the
        // equivalent UPDATE manually.
        adminJdbc().update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"lastHeartbeat\" = ? "
                + "WHERE \"podId\"=?",
                Timestamp.from(Instant.now().minusSeconds(7200)), podId);

        // Run the same SQL the sweeper runs.
        int marked = adminJdbc().update(
                "UPDATE \"globalOrchestrator\".\"pod\" SET \"state\"='LOST' "
                + "WHERE \"state\"!='LOST' AND \"lastHeartbeat\" < ?",
                Timestamp.from(Instant.now().minusSeconds(90)));
        assertThat(marked).isGreaterThanOrEqualTo(1);

        String state = adminJdbc().queryForObject(
                "SELECT \"state\" FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\"=?",
                String.class, podId);
        assertThat(state).isEqualTo("LOST");

        // Recovery — heartbeat flips state back to IDLE.
        mvc.perform(post("/api/v1/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"podId\":\"" + podId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));

        String stateAfter = adminJdbc().queryForObject(
                "SELECT \"state\" FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\"=?",
                String.class, podId);
        assertThat(stateAfter).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("GET /pods lists every registered pod")
    void listPods() throws Exception {
        // Register a pair so the GET has something to return.
        for (String pod : new String[]{"orchestrator-listA", "orchestrator-listB"}) {
            register(pod, "us-east-1");
        }
        mvc.perform(get("/api/v1/pods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.podId=='orchestrator-listA')]").exists())
                .andExpect(jsonPath("$[?(@.podId=='orchestrator-listB')]").exists());
    }

    @Test
    @DisplayName("WORKER-HYGIENE Phase B — recordProvisionMetadata + incrementRunsServed round-trip")
    void recycleColumnsRoundTrip() throws Exception {
        String podId = "orchestrator-recycle-itA";
        // Register first — this inserts the placeholder row with runsServed=0,
        // imageDigest=NULL, provisionedAt=NULL.
        register(podId, "us-east-1");

        Long initialRuns = adminJdbc().queryForObject(
                "SELECT \"runsServed\" FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\"=?",
                Long.class, podId);
        String initialDigest = adminJdbc().queryForObject(
                "SELECT \"imageDigest\" FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\"=?",
                String.class, podId);
        Timestamp initialProvisioned = adminJdbc().queryForObject(
                "SELECT \"provisionedAt\" FROM \"globalOrchestrator\".\"pod\" WHERE \"podId\"=?",
                Timestamp.class, podId);
        assertThat(initialRuns).isZero();
        assertThat(initialDigest).isNull();
        assertThat(initialProvisioned).isNull();

        // Simulate the post-spin metadata back-fill from CapacityController.spin.
        Instant created = Instant.parse("2026-05-16T12:00:00Z");
        String digest = "sha256:deadbeefcafe";
        adminJdbc().update(
                "UPDATE \"globalOrchestrator\".\"pod\" "
                + "SET \"imageDigest\"=?, \"provisionedAt\"=? "
                + "WHERE \"podId\"=?",
                digest, Timestamp.from(created), podId);

        // Bump runsServed twice — same as two run claims.
        for (int i = 0; i < 2; i++) {
            int updated = adminJdbc().update(
                    "UPDATE \"globalOrchestrator\".\"pod\" "
                    + "SET \"runsServed\" = \"runsServed\" + 1 "
                    + "WHERE \"podId\"=?",
                    podId);
            assertThat(updated).isEqualTo(1);
        }

        // GET /pods should surface the new columns.
        mvc.perform(get("/api/v1/pods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.podId=='" + podId + "')].runsServed")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$[?(@.podId=='" + podId + "')].imageDigest")
                        .value(org.hamcrest.Matchers.hasItem(digest)));
    }

    @Test
    @DisplayName("GET /regions returns per-region capacity rollup")
    void regionCapacityRollup() throws Exception {
        // Two pods in region-rollup-east, one in region-rollup-west.
        for (String pod : new String[]{"orchestrator-rollupA", "orchestrator-rollupB"}) {
            register(pod, "region-rollup-east");
        }
        register("orchestrator-rollupC", "region-rollup-west");

        mvc.perform(get("/api/v1/regions"))
                .andExpect(status().isOk())
                // East has 2 pods, both IDLE, 0 lost.
                .andExpect(jsonPath("$[?(@.region=='region-rollup-east')].totalPods")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$[?(@.region=='region-rollup-east')].idlePods")
                        .value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$[?(@.region=='region-rollup-east')].lostPods")
                        .value(org.hamcrest.Matchers.hasItem(0)))
                // West has 1 pod.
                .andExpect(jsonPath("$[?(@.region=='region-rollup-west')].totalPods")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$[?(@.region=='region-rollup-west')].idlePods")
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }
}
