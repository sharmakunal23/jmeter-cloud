package com.perf.metricsconsumer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Behavior IT for the K-4 HTTP fallback ingest endpoint.
 *
 * <p>Boots the full Spring Boot context against a Testcontainers Postgres,
 * applies Flyway migrations as the superuser, then drives MockMvc with
 * Avro-encoded {@link WorkerMetricBatch} payloads.
 *
 * <p>The Kafka beans are mocked (we don't need a broker) so the consumer
 * boots quickly and the test stays focused on the HTTP path.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK, properties = {
        "metricsConsumer.schemaRegistryUrl=mock://ingestIT",
        "metricsConsumer.ingest.maxBodyBytes=4096", // tight cap so 413 is reachable
        "management.health.kafka.enabled=false",
        "spring.flyway.enabled=false",
        // Ensure the Kafka listener container doesn't start during the IT —
        // EmbeddedKafka isn't running here so the listener would crash on startup.
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@AutoConfigureMockMvc
@DisplayName("metrics-consumer HTTP /ingest — behavior IT (K-4)")
class IngestControllerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jmetercloud_metrics")
            .withUsername("postgres")
            .withPassword("test")
            .withInitScript("createTestUsers.sql");

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_METRICS_URL", POSTGRES::getJdbcUrl);
        registry.add("POSTGRES_USER",        () -> "metricsWriter");
        registry.add("POSTGRES_PASSWORD",    () -> "test");
    }

    @BeforeAll
    static void migrate() {
        Path migrationsPath = Paths.get("..", "postgres", "migrations", "metrics")
                .toAbsolutePath().normalize();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationsPath)
                .load()
                .migrate();
    }

    @Autowired MockMvc mockMvc;
    @Autowired DataSource runtimeDataSource;

    JdbcTemplate verifyJdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(ds);
    }

    private static final MediaType APPLICATION_AVRO = MediaType.parseMediaType("application/avro");

    @Test
    @DisplayName("happy path — 10-entry envelope returns 202 + rowsInserted=10; rows land in Postgres")
    void happy_path_envelope_lands_as_rows() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 10; i++) labels.add("GET /endpoint/" + i);
        WorkerMetricBatch envelope = sampleEnvelope(runId, "worker-http", baseSecond(), labels);
        byte[] body = encode(envelope);
        // sanity: this fixture must fit comfortably under the 4 KB test cap.
        assertThat(body.length).isLessThan(4096);

        mockMvc.perform(post("/api/v1/ingest")
                        .contentType(APPLICATION_AVRO)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("ACCEPTED"))
                .andExpect(jsonPath("$.rowsInserted").value(10));

        Long rows = verifyJdbc().queryForObject(
                "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                Long.class, runId);
        assertThat(rows).isEqualTo(10L);
    }

    @Test
    @DisplayName("idempotency — re-POSTing the same envelope returns rowsInserted=0; row count unchanged")
    void idempotent_repost() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        WorkerMetricBatch envelope = sampleEnvelope(runId, "worker-http", baseSecond(),
                List.of("GET /api/foo", "GET /api/bar"));
        byte[] body = encode(envelope);

        // First POST → 2 rows inserted
        mockMvc.perform(post("/api/v1/ingest").contentType(APPLICATION_AVRO).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowsInserted").value(2));

        // Second POST (same envelope) → 0 new rows, ON CONFLICT DO NOTHING
        mockMvc.perform(post("/api/v1/ingest").contentType(APPLICATION_AVRO).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowsInserted").value(0));

        // Total rows unchanged
        Long rows = verifyJdbc().queryForObject(
                "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                Long.class, runId);
        assertThat(rows).isEqualTo(2L);
    }

    @Test
    @DisplayName("413 PAYLOAD_TOO_LARGE — body exceeds the maxBodyBytes cap")
    void rejects_oversize_body() throws Exception {
        // Build an envelope that serializes well past the 4 KB test cap.
        // 100 entries × ~80 B Avro binary each ≈ 8 KB.
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 100; i++) labels.add("GET /endpoint/" + i);
        WorkerMetricBatch envelope = sampleEnvelope("run-toolarge", "worker-http",
                baseSecond(), labels);
        byte[] body = encode(envelope);
        assertThat(body.length).as("test fixture must exceed the cap").isGreaterThan(4096);

        mockMvc.perform(post("/api/v1/ingest").contentType(APPLICATION_AVRO).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("400 BAD_REQUEST — body is not valid Avro")
    void rejects_malformed_avro() throws Exception {
        byte[] junk = "this is not avro at all".getBytes();

        mockMvc.perform(post("/api/v1/ingest").contentType(APPLICATION_AVRO).content(junk))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AVRO"));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static long baseSecond() {
        return 1_778_457_600L;
    }

    private static byte[] encode(WorkerMetricBatch envelope) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
        SpecificDatumWriter<WorkerMetricBatch> writer = new SpecificDatumWriter<>(WorkerMetricBatch.class);
        writer.write(envelope, enc);
        enc.flush();
        return out.toByteArray();
    }

    private static WorkerMetricBatch sampleEnvelope(String runId, String workerId, long sec,
                                                    List<String> labels) {
        Map<String, Long> statusCodes = new LinkedHashMap<>();
        statusCodes.put("200", 98L);
        statusCodes.put("500", 2L);

        List<WorkerMetricEntry> entries = new ArrayList<>(labels.size());
        for (String label : labels) {
            entries.add(WorkerMetricEntry.newBuilder()
                    .setLabel(label)
                    .setThroughput(100).setErrorCount(2).setErrorRate(0.02)
                    .setAvgRespTimeMs(95.4)
                    .setP50Ms(80.0).setP90Ms(120.0).setP95Ms(150.5).setP99Ms(220.0)
                    .setMinMs(10.0).setMaxMs(500.0).setRawMaxMs(500L)
                    .setBytesReceived(1024L).setBytesSent(512L)
                    .setStatusCodes(statusCodes)
                    .setActiveThreads(50L)
                    .build());
        }
        return WorkerMetricBatch.newBuilder()
                .setRunId(runId)
                .setWorkerId(workerId)
                .setRegion("us-east-1")
                .setWindowSecond(sec)
                .setWindowTimestamp("2026/05/09 00:00:00")
                .setEntries(entries)
                .build();
    }
}
