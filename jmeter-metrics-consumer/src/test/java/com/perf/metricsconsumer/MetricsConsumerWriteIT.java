package com.perf.metricsconsumer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * Behavior IT for the metrics-consumer's INSERT path (K-2 envelope shape).
 *
 * <p>Boots a real Postgres via Testcontainers and an embedded Kafka broker,
 * applies the canonical metrics migration with Flyway, and produces
 * Avro-encoded {@link WorkerMetricBatch} envelopes via Confluent's
 * {@code mock://} schema-registry scheme — no Confluent SR container needed.
 *
 * <p>Tests <strong>behavior</strong>, not exhaustive method coverage:
 * <ol>
 *   <li>End-to-end: producer publishes E envelopes carrying R rows total →
 *       exactly R rows land in Postgres with the right column values.</li>
 *   <li>Idempotency: republishing the same envelopes is a no-op (the
 *       PK collision drops dupes).</li>
 *   <li>Multi-entry envelope: one envelope with N entries explodes into
 *       N rows, all carrying the envelope's identity fields.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "metricsConsumer.schemaRegistryUrl=mock://metricsConsumerIT",
        "metricsConsumer.concurrency=1",
        "metricsConsumer.maxPollRecords=20",
        "metricsConsumer.maxRowsPerInsert=100",
        "metricsConsumer.fetchMaxWaitMs=200",
        // KAFKA-PER-APP Phase D — pattern subscription replaces the old
        // single-topic config. Match the IT's three pre-created topics
        // (the default + two per-app topics) without picking up the
        // EmbeddedKafka-internal __consumer_offsets / dlt topics.
        "metricsConsumer.topicPattern=jmeter\\.metrics\\.[^.]+$",
        "metricsConsumer.groupId=metricsConsumerWriteIT",
        // Disable Spring Boot's KafkaHealthIndicator probe — it can race the
        // EmbeddedKafka broker on test boot and emit noisy WARNs.
        "management.health.kafka.enabled=false",
        // The IT pre-migrates with Flyway in @BeforeAll as the superuser.
        // Spring Boot's auto-config runs Flyway again as the runtime user
        // (metricsWriter) which has no DDL privileges — turn it off here.
        "spring.flyway.enabled=false"
})
@EmbeddedKafka(partitions = 1, topics = {
        "jmeter.metrics.itDefault",
        "jmeter.metrics.testApp1",
        "jmeter.metrics.testApp2"
})
@Testcontainers
@DisplayName("metrics-consumer INSERT path — envelope behavior IT (K-2)")
class MetricsConsumerWriteIT {

    private static final String SCHEMA_REGISTRY_URL = "mock://metricsConsumerIT";
    /** Per-app topic for the original tests; the {@code [^.]+} clamp in the
     *  Phase-D pattern requires single-segment suffixes, so the pre-D
     *  {@code jmeter.metrics.perSecond.it} no longer matches. */
    private static final String TOPIC = "jmeter.metrics.itDefault";
    /** Phase-D test topics — must match the @EmbeddedKafka topics list. */
    private static final String TOPIC_APP_1 = "jmeter.metrics.testApp1";
    private static final String TOPIC_APP_2 = "jmeter.metrics.testApp2";

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

    @Autowired DataSource runtimeDataSource;

    @Test
    @DisplayName("runtime datasource connects as the metricsWriter app-user")
    void runtimeUserIsMetricsWriter() throws Exception {
        try (var conn = runtimeDataSource.getConnection()) {
            assertThat(conn.getMetaData().getUserName()).isEqualTo("metricsWriter");
        }
    }

    JdbcTemplate verifyJdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(ds);
    }

    @Test
    @DisplayName("publishes E envelopes (R rows) → R rows in Postgres; duplicates collapse via ON CONFLICT")
    void endToEndAndIdempotency() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        // 5 envelopes, 1 entry each → 5 rows. Exercises the simple per-entry shape.
        int envelopeCount = 5;
        int entriesPerEnvelope = 1;
        int expectedRows = envelopeCount * entriesPerEnvelope;

        try (Producer<String, WorkerMetricBatch> producer = newProducer()) {
            for (int i = 0; i < envelopeCount; i++) {
                WorkerMetricBatch env = sampleEnvelope(runId, "worker-1", baseSecond() + i,
                        List.of("GET /api/foo"));
                producer.send(new ProducerRecord<>(TOPIC,
                        env.getRunId() + "|" + env.getWorkerId() + "|" + env.getWindowSecond(),
                        env)).get();
            }
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Long rows = verifyJdbc().queryForObject(
                            "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                            Long.class, runId);
                    assertThat(rows).isEqualTo((long) expectedRows);
                });

        // Spot-check a row's columns survive the round-trip with the right
        // shape — JSONB statusCodes, doubles, longs, envelope-level identity
        // projected onto each entry.
        Map<String, Object> sample = verifyJdbc().queryForMap(
                "SELECT \"workerId\", \"region\", \"label\", "
                + "       \"throughput\", \"errorCount\", \"errorRate\", "
                + "       \"avgRespTimeMs\", \"p95Ms\", "
                + "       \"statusCodes\"::text AS \"statusCodes\" "
                + "FROM metrics.\"workerMetric\" WHERE \"runId\" = ? LIMIT 1",
                runId);
        assertThat(sample.get("workerId")).isEqualTo("worker-1");
        assertThat(sample.get("region")).isEqualTo("us-east-1");
        assertThat(sample.get("label")).isEqualTo("GET /api/foo");
        assertThat(sample.get("throughput")).isEqualTo(100L);
        assertThat(sample.get("errorCount")).isEqualTo(2L);
        assertThat(((Number) sample.get("errorRate")).doubleValue()).isCloseTo(0.02, within());
        assertThat(((Number) sample.get("avgRespTimeMs")).doubleValue()).isCloseTo(95.4, within());
        assertThat(((Number) sample.get("p95Ms")).doubleValue()).isCloseTo(150.5, within());
        assertThat((String) sample.get("statusCodes")).contains("\"200\": 98");
        assertThat((String) sample.get("statusCodes")).contains("\"500\": 2");

        // MID-TEST-SCALING Phase D — sampleEnvelope omits joinedAtSecond,
        // so the schema default (0) propagates through to the column. 0
        // is the original-fleet semantic.
        Long defaultJoinedAt = verifyJdbc().queryForObject(
                "SELECT \"joinedAtSecond\" FROM metrics.\"workerMetric\" WHERE \"runId\" = ? LIMIT 1",
                Long.class, runId);
        assertThat(defaultJoinedAt)
                .as("envelope without explicit joinedAtSecond must persist as 0 (original-fleet)")
                .isEqualTo(0L);

        // Republish a subset and assert no new rows land — the PK collision
        // path is exercised end-to-end (producer→consumer→writer→ON CONFLICT).
        try (Producer<String, WorkerMetricBatch> producer = newProducer()) {
            for (int i = 0; i < 3; i++) {
                WorkerMetricBatch env = sampleEnvelope(runId, "worker-1", baseSecond() + i,
                        List.of("GET /api/foo"));
                producer.send(new ProducerRecord<>(TOPIC,
                        env.getRunId() + "|" + env.getWorkerId() + "|" + env.getWindowSecond(),
                        env)).get();
            }
            producer.flush();
        }

        Thread.sleep(2_000);
        Long after = verifyJdbc().queryForObject(
                "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                Long.class, runId);
        assertThat(after).as("ON CONFLICT DO NOTHING must drop duplicates")
                .isEqualTo((long) expectedRows);
    }

    @Test
    @DisplayName("multi-entry envelope (K-2) — one envelope with 50 entries explodes into 50 rows")
    void multi_entry_envelope_explodes_into_rows() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        long sec = baseSecond() + 10_000; // distinct second window from the other test

        // One envelope, 50 distinct labels → 50 rows in Postgres post-explode.
        // Validates the K-2 envelope→row projection end-to-end.
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 50; i++) labels.add("GET /endpoint/" + i);

        try (Producer<String, WorkerMetricBatch> producer = newProducer()) {
            WorkerMetricBatch env = sampleEnvelope(runId, "worker-multi", sec, labels);
            producer.send(new ProducerRecord<>(TOPIC,
                    env.getRunId() + "|" + env.getWorkerId() + "|" + env.getWindowSecond(),
                    env)).get();
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Long rows = verifyJdbc().queryForObject(
                            "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                            Long.class, runId);
                    assertThat(rows)
                            .as("50 entries in 1 envelope must produce 50 rows in Postgres")
                            .isEqualTo(50L);
                });

        // All 50 rows must share the envelope's identity fields.
        Long distinctWorkers = verifyJdbc().queryForObject(
                "SELECT count(distinct \"workerId\") FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                Long.class, runId);
        assertThat(distinctWorkers).isEqualTo(1L);

        Long distinctLabels = verifyJdbc().queryForObject(
                "SELECT count(distinct \"label\") FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                Long.class, runId);
        assertThat(distinctLabels)
                .as("each envelope entry must produce a distinct row for its label")
                .isEqualTo(50L);
    }

    @Test
    @DisplayName("MID-TEST-SCALING Phase D — envelope with joinedAtSecond=42 lands with column = 42 on every row")
    void joined_at_second_propagates_to_column() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        long sec = baseSecond() + 20_000;
        long joinedAt = 42L;

        // One envelope, 3 entries, joinedAtSecond=42 (mid-test scale-up
        // joiner). All 3 rows should land with the column = 42.
        try (Producer<String, WorkerMetricBatch> producer = newProducer()) {
            WorkerMetricBatch env = sampleEnvelopeBuilder(runId, "worker-joiner", sec,
                    List.of("GET /a", "GET /b", "GET /c"))
                    .setJoinedAtSecond(joinedAt)
                    .build();
            producer.send(new ProducerRecord<>(TOPIC,
                    env.getRunId() + "|" + env.getWorkerId() + "|" + env.getWindowSecond(),
                    env)).get();
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Long rows = verifyJdbc().queryForObject(
                            "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                            Long.class, runId);
                    assertThat(rows).isEqualTo(3L);
                });

        Long matching = verifyJdbc().queryForObject(
                "SELECT count(*) FROM metrics.\"workerMetric\" "
                + "WHERE \"runId\" = ? AND \"joinedAtSecond\" = ?",
                Long.class, runId, joinedAt);
        assertThat(matching)
                .as("all 3 rows must carry joinedAtSecond = " + joinedAt)
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("KAFKA-PER-APP Phase D — pattern subscription consumes envelopes from two distinct per-app topics")
    void perAppTopicsBothLand() throws Exception {
        // Two distinct runIds, one per topic. Pattern subscription
        // should pick up both topics + write both runIds' rows into
        // metrics."workerMetric". The application binding is owned by
        // the globalrun side (joined via runId); the consumer doesn't
        // care about the topic-app mapping — it just consumes whatever
        // matches the pattern.
        String runIdApp1 = "run-app1-" + UUID.randomUUID();
        String runIdApp2 = "run-app2-" + UUID.randomUUID();
        long sec = baseSecond() + 30_000;

        try (Producer<String, WorkerMetricBatch> producer = newProducer()) {
            WorkerMetricBatch envApp1 = sampleEnvelope(runIdApp1, "worker-app1", sec,
                    List.of("GET /app1/foo"));
            producer.send(new ProducerRecord<>(TOPIC_APP_1,
                    envApp1.getRunId() + "|" + envApp1.getWorkerId() + "|" + envApp1.getWindowSecond(),
                    envApp1)).get();

            WorkerMetricBatch envApp2 = sampleEnvelope(runIdApp2, "worker-app2", sec,
                    List.of("GET /app2/bar"));
            producer.send(new ProducerRecord<>(TOPIC_APP_2,
                    envApp2.getRunId() + "|" + envApp2.getWorkerId() + "|" + envApp2.getWindowSecond(),
                    envApp2)).get();
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Long app1Rows = verifyJdbc().queryForObject(
                            "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                            Long.class, runIdApp1);
                    Long app2Rows = verifyJdbc().queryForObject(
                            "SELECT count(*) FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                            Long.class, runIdApp2);
                    assertThat(app1Rows)
                            .as("envelope from " + TOPIC_APP_1 + " must land")
                            .isEqualTo(1L);
                    assertThat(app2Rows)
                            .as("envelope from " + TOPIC_APP_2 + " must land")
                            .isEqualTo(1L);
                });

        // Spot-check identity isolation: each runId's row carries its
        // own workerId (no cross-topic contamination).
        String app1Worker = verifyJdbc().queryForObject(
                "SELECT \"workerId\" FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                String.class, runIdApp1);
        String app2Worker = verifyJdbc().queryForObject(
                "SELECT \"workerId\" FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                String.class, runIdApp2);
        assertThat(app1Worker).isEqualTo("worker-app1");
        assertThat(app2Worker).isEqualTo("worker-app2");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Producer<String, WorkerMetricBatch> newProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getProperty("spring.embedded.kafka.brokers"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);
        return new KafkaProducer<>(props);
    }

    private static long baseSecond() {
        // 2026-05-09 00:00:00 UTC, well inside the migration's seed-partition window.
        return 1_778_457_600L;
    }

    private static WorkerMetricBatch sampleEnvelope(String runId, String workerId, long sec,
                                                    List<String> labels) {
        return sampleEnvelopeBuilder(runId, workerId, sec, labels).build();
    }

    /** Same as {@link #sampleEnvelope} but returns the builder so callers can override fields. */
    private static WorkerMetricBatch.Builder sampleEnvelopeBuilder(
            String runId, String workerId, long sec, List<String> labels) {
        Map<String, Long> statusCodes = new LinkedHashMap<>();
        statusCodes.put("200", 98L);
        statusCodes.put("500", 2L);

        List<WorkerMetricEntry> entries = new ArrayList<>(labels.size());
        for (String label : labels) {
            entries.add(WorkerMetricEntry.newBuilder()
                    .setLabel(label)
                    .setThroughput(100)
                    .setErrorCount(2)
                    .setErrorRate(0.02)
                    .setAvgRespTimeMs(95.4)
                    .setP50Ms(80.0)
                    .setP90Ms(120.0)
                    .setP95Ms(150.5)
                    .setP99Ms(220.0)
                    .setMinMs(10.0)
                    .setMaxMs(500.0)
                    .setRawMaxMs(500L)
                    .setBytesReceived(1024L)
                    .setBytesSent(512L)
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
                .setEntries(entries);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
