package com.perf.globalorchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAFKA-PER-APP Phase B — end-to-end IT for the application-registry → Kafka
 * topic-lifecycle contract.
 *
 * <p>Spins up a real KRaft Kafka container alongside the existing
 * Testcontainers Postgres + Flyway-migration setup, then asserts:
 *
 * <ol>
 *   <li>{@code POST /api/v1/applications} creates the main topic
 *       ({@code jmeter.metrics.<name>}) AND its DLT
 *       ({@code jmeter.metrics.<name>.DLT}) on the broker before the
 *       201 Created response returns.</li>
 *   <li>{@code DELETE /api/v1/applications/{id}} is a SOFT delete — it
 *       hides the app (drops it from the listing) but DELETES the per-app
 *       topics from the broker (env-gated; default true here).</li>
 *   <li>The original name is FREED — the hidden row is renamed to an archived
 *       name, so re-registering the same name SUCCEEDS (201, fresh id) and the
 *       new app does NOT inherit the old runs; a second DELETE is idempotent
 *       (204).</li>
 *   <li>Hiding is blocked (409 {@code APPLICATION_HAS_ACTIVE_RUNS}) while the
 *       app has an active run; the run row is retained (re-tagged) either way.</li>
 *   <li>Topic-create failure (broker unreachable mid-flight) rolls back
 *       the application row so the registry never advertises an app
 *       whose topics don't exist.</li>
 * </ol>
 *
 * <p>The IT creates its own {@link AdminClient} pointed at the
 * Testcontainers broker so it can read the broker's view of the world
 * directly — independent of the global-orch's own provisioner — to keep
 * the assertion path honest.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("global-orchestrator application registry → Kafka topic lifecycle — behavior IT")
class ApplicationRegistryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_globalrun")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    /** Confluent platform image — testcontainers' legacy {@code KafkaContainer}
     *  module is built around this image's bootstrap script, which advertises
     *  the broker on a routable host:port without the Apache image's
     *  {@code 0.0.0.0} rejection. 7.6.x matches the Confluent line already
     *  pinned in {@code jmeter-local-orchestrator/pom.xml} for the Avro
     *  serializer. KRaft mode (no Zookeeper) keeps startup under 10 s. */
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withKraft();

    static AdminClient probeAdmin;

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL",          POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",                 () -> "postgres");
        registry.add("POSTGRES_PASSWORD",             () -> "test");
        registry.add("POSTGRES_GLOBALRUN_URL",        POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_GLOBALRUN_WRITER_USER",     () -> "globalOrchestratorWriter");
        registry.add("POSTGRES_GLOBALRUN_WRITER_PASSWORD", () -> "test");
        registry.add("GLOBAL_ORCHESTRATOR_REGION", () -> "us-east-1");
        // Disable sweeper so its background loop can't interfere with
        // capacity bookkeeping during this IT.
        registry.add("globalOrchestrator.pod.sweepInitialDelayMs", () -> "3600000");
        registry.add("globalOrchestrator.pod.lostAfterMs",         () -> "3600000");

        // KAFKA-PER-APP — point the global-orch's AdminClient bean at the
        // Testcontainers broker. Override the deleteTopicsOnAppDelete
        // gate explicitly so this IT can assert the cleanup path even if
        // the cloud-profile default ever changes underneath it.
        registry.add("globalOrchestrator.kafka.brokers",
                KAFKA::getBootstrapServers);
        registry.add("globalOrchestrator.kafka.deleteTopicsOnAppDelete",
                () -> "true");
        // Tighten the admin-call timeout so a misconfigured test doesn't
        // hang the suite for 15 s — a healthy local broker responds in
        // < 100 ms.
        registry.add("globalOrchestrator.kafka.adminTimeoutMs",
                () -> "5000");
    }

    @BeforeAll
    static void migrateAndStartProbe() {
        Path globalRun = Paths.get("..", "postgres", "migrations", "globalrun")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + globalRun)
                .load()
                .migrate();

        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        probeAdmin = AdminClient.create(p);
    }

    @AfterAll
    static void closeProbe() {
        if (probeAdmin != null) probeAdmin.close();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("runStateJdbcTemplate") JdbcTemplate runJdbc;

    @Test
    @DisplayName("POST /applications creates main + DLT topics on the broker before returning 201")
    void registerCreatesTopics() throws Exception {
        String name = "ckt-svc-create";
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
        assertThat(appId).hasSize(26);

        Set<String> topics = listTopics();
        assertThat(topics)
                .as("main topic created on the broker before 201 response")
                .contains("jmeter.metrics." + name);
        assertThat(topics)
                .as("DLT topic created on the broker before 201 response")
                .contains("jmeter.metrics." + name + ".DLT");
    }

    @Test
    @DisplayName("DELETE /applications/{id} removes both topics from the broker (env-gated, default true here)")
    void deleteRemovesTopics() throws Exception {
        String name = "ckt-svc-delete";
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();

        // Pre-condition — both topics exist.
        Set<String> before = listTopics();
        assertThat(before).contains("jmeter.metrics." + name);
        assertThat(before).contains("jmeter.metrics." + name + ".DLT");

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", appId))
                .andExpect(status().isNoContent());

        Set<String> after = listTopics();
        assertThat(after)
                .as("main topic removed after soft-delete")
                .doesNotContain("jmeter.metrics." + name);
        assertThat(after)
                .as("DLT topic removed after soft-delete")
                .doesNotContain("jmeter.metrics." + name + ".DLT");
        // Soft-deleted → drops out of the listing.
        assertThat(listContainsName(name))
                .as("hidden app no longer appears in GET /applications")
                .isFalse();
    }

    @Test
    @DisplayName("soft-delete frees the name — re-register succeeds with a fresh id (no old runs); second DELETE idempotent 204")
    void reRegisterAfterDeleteSucceeds() throws Exception {
        String name = "ckt-svc-recycle";

        String firstId = createApp(name);
        String oldRunId = seedRun(name, "COMPLETED");
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", firstId))
                .andExpect(status().isNoContent());
        assertThat(listContainsName(name))
                .as("hidden app drops out of the listing")
                .isFalse();

        // Name was freed (hidden row renamed) → re-register succeeds, fresh id.
        String secondId = createApp(name);
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(listContainsName(name)).isTrue();

        // The old run was re-tagged off the original name → the fresh app does
        // NOT inherit it. (Old run row still exists under the archived name.)
        String secondAppRuns = mvc.perform(MockMvcRequestBuilders.get("/api/v1/runs")
                        .param("application", name))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(secondAppRuns)
                .as("the re-registered app does not inherit the deleted app's runs")
                .doesNotContain(oldRunId);
        Integer retained = runJdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"run\" WHERE \"runId\"=?",
                Integer.class, oldRunId);
        assertThat(retained).as("old run row retained (archived)").isEqualTo(1);

        // Idempotent — hiding an already-hidden app is a 204 no-op.
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", firstId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("soft-delete is blocked (409) while the app has an active run; allowed once terminal, run row retained")
    void deleteBlockedWhileActiveRun() throws Exception {
        String name = "ckt-svc-activerun";
        String appId = createApp(name);
        String runId = seedRun(name, "RUNNING");

        // Active run present → 409, app still visible.
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", appId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_HAS_ACTIVE_RUNS"));
        assertThat(listContainsName(name))
                .as("app with an active run is NOT hidden")
                .isTrue();

        // Run reaches a terminal state → hide now succeeds.
        runJdbc.update("UPDATE \"globalOrchestrator\".\"run\" SET \"state\"='COMPLETED' WHERE \"runId\"=?", runId);
        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/applications/{id}", appId))
                .andExpect(status().isNoContent());
        assertThat(listContainsName(name)).isFalse();

        // Data retained — the run row survives the soft-delete.
        Integer runRows = runJdbc.queryForObject(
                "SELECT count(*) FROM \"globalOrchestrator\".\"run\" WHERE \"runId\"=?",
                Integer.class, runId);
        assertThat(runRows).as("run row retained after app hidden").isEqualTo(1);
    }

    @Test
    @DisplayName("idempotent create — pre-existing topic does not fail register (no row rollback)")
    void preExistingTopicIsIdempotent() throws Exception {
        // Pre-create the topic on the broker via the IT's own AdminClient
        // (simulates an operator who manually pre-provisioned, or a partial
        // failure on a previous attempt). Register should still succeed.
        String name = "ckt-svc-prexists";
        String main = "jmeter.metrics." + name;
        String dlt = "jmeter.metrics." + name + ".DLT";
        probeAdmin.createTopics(java.util.List.of(
                new org.apache.kafka.clients.admin.NewTopic(main, 3, (short) 1),
                new org.apache.kafka.clients.admin.NewTopic(dlt, 3, (short) 1)
        )).all().get(5, TimeUnit.SECONDS);

        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        String appId = mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
        assertThat(appId).hasSize(26);

        // Row landed; topics still on the broker.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
    }

    // ── WORKER-HYGIENE Phase C — recycle policy lifecycle ─────────────

    @Test
    @DisplayName("WORKER-HYGIENE Phase C — new app defaults to REUSE; PUT can set MAX_RUNS with maxRunsPerPod")
    void recyclePolicyDefaultAndUpdate() throws Exception {
        String name = "rec-policy-default";
        String appId = createApp(name);
        // Default policy is REUSE; thresholds null.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recyclePolicy").value("REUSE"))
                .andExpect(jsonPath("$.maxRunsPerPod").doesNotExist())
                .andExpect(jsonPath("$.podMaxAgeHours").doesNotExist());

        // Update to MAX_RUNS=5 — accepted.
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"MAX_RUNS\",\"maxRunsPerPod\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recyclePolicy").value("MAX_RUNS"))
                .andExpect(jsonPath("$.maxRunsPerPod").value(5));
    }

    @Test
    @DisplayName("WORKER-HYGIENE Phase C — invalid policy/threshold combinations are 400")
    void recyclePolicyValidation() throws Exception {
        String name = "rec-policy-bad";
        String appId = createApp(name);

        // MAX_RUNS without threshold → 400
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"MAX_RUNS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // REUSE with a threshold → 400
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"REUSE\",\"maxRunsPerPod\":3}"))
                .andExpect(status().isBadRequest());

        // MAX_AGE with maxRunsPerPod (wrong threshold) → 400
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"MAX_AGE\",\"maxRunsPerPod\":3,\"podMaxAgeHours\":1}"))
                .andExpect(status().isBadRequest());

        // BOTH requires both thresholds → 400 if one is null
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"BOTH\",\"maxRunsPerPod\":3}"))
                .andExpect(status().isBadRequest());

        // Unknown policy → 400
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());

        // Threshold without policy → 400
        mvc.perform(MockMvcRequestBuilders.put("/api/v1/applications/{id}", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"maxRunsPerPod\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("WORKER-HYGIENE Phase C — POST with explicit EVERY_RUN policy is accepted")
    void recyclePolicyOnCreate() throws Exception {
        String name = "rec-policy-create";
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"EVERY_RUN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recyclePolicy").value("EVERY_RUN"))
                .andReturn();
        String appId = mapper.readTree(res.getResponse().getContentAsString())
                .get("applicationId").asText();
        // Round-trip GET also surfaces it.
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recyclePolicy").value("EVERY_RUN"));
    }

    @Test
    @DisplayName("POST with DRAIN_AFTER_RUN policy is accepted (V17 — no thresholds, drain-no-replace)")
    void recyclePolicyDrainAfterRun() throws Exception {
        String name = "rec-policy-drain";
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"recyclePolicy\":\"DRAIN_AFTER_RUN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recyclePolicy").value("DRAIN_AFTER_RUN"))
                .andReturn();
        String appId = mapper.readTree(res.getResponse().getContentAsString())
                .get("applicationId").asText();
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications/{id}", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recyclePolicy").value("DRAIN_AFTER_RUN"));
        // Thresholds are forbidden for DRAIN_AFTER_RUN (mirrors REUSE / EVERY_RUN).
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "-bad\",\"recyclePolicy\":\"DRAIN_AFTER_RUN\",\"maxRunsPerPod\":3}"))
                .andExpect(status().isBadRequest());
    }

    private String createApp(String name) throws Exception {
        MvcResult create = mvc.perform(MockMvcRequestBuilders.post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"" + name + "\" }"))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(create.getResponse().getContentAsString())
                .get("applicationId").asText();
    }

    /** True iff GET /api/v1/applications returns a row with this name. */
    private boolean listContainsName(String name) throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/applications"))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode app : mapper.readTree(res.getResponse().getContentAsString())) {
            if (name.equals(app.path("name").asText())) return true;
        }
        return false;
    }

    /** Insert a minimal run row for {@code application} in the given state; returns its runId. */
    private String seedRun(String application, String state) {
        String runId = "run-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        runJdbc.update(
                "INSERT INTO \"globalOrchestrator\".\"run\" "
                + "(\"runId\",\"originRegion\",\"testPlanBlobId\",\"application\",\"initiatedBy\",\"state\") "
                + "VALUES (?,?,?,?,?,?)",
                runId, "us-east-1", "blob-test-plan", application, "it", state);
        return runId;
    }

    private static Set<String> listTopics() throws Exception {
        return probeAdmin.listTopics().names().get(5, TimeUnit.SECONDS);
    }
}
