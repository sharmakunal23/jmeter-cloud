package com.perf.orchestrator.model;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("WorkerMetricBatch Avro schema")
class WorkerMetricBatchSchemaTest {

    private static Schema batchSchema;
    private static Schema entrySchema;

    @BeforeAll
    static void loadSchema() throws IOException {
        // Schema lives in the sibling kafka/ subsystem (canonical location).
        // Avro codegen generates WorkerMetricBatch + WorkerMetricEntry from the same .avsc.
        File avscFile = new File("../kafka/schemas/WorkerMetricBatch.avsc");
        batchSchema = new Schema.Parser().parse(avscFile);
        // The "entries" field is an array<WorkerMetricEntry>; pull the inlined record schema.
        entrySchema = batchSchema.getField("entries").schema().getElementType();
    }

    // -----------------------------------------------------------------------
    // Envelope structural validity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("envelope structure")
    class EnvelopeStructure {

        @Test
        @DisplayName("WorkerMetricBatch is a named RECORD type — required for Kafka Schema Registry compatibility")
        void batch_is_a_record_type() {
            assertThat(batchSchema.getType()).isEqualTo(Schema.Type.RECORD);
            assertThat(batchSchema.getName()).isEqualTo("WorkerMetricBatch");
        }

        @Test
        @DisplayName("lives in the correct namespace so generated classes land in the right package")
        void has_correct_namespace() {
            assertThat(batchSchema.getNamespace()).isEqualTo("com.perf.orchestrator");
        }

        @Test
        @DisplayName("carries a doc string explaining the envelope-vs-per-row distinction")
        void has_schema_documentation() {
            assertThat(batchSchema.getDoc()).isNotBlank();
        }

        @Test
        @DisplayName("envelope-level fields are the 5 shared-per-pod-window identifiers + joinedAtSecond + the entries array")
        void envelope_has_expected_top_level_fields() {
            Set<String> actualFields = batchSchema.getFields().stream()
                    .map(Schema.Field::name)
                    .collect(Collectors.toSet());

            assertThat(actualFields).containsExactlyInAnyOrder(
                    // Window identity (1)
                    "windowSecond",
                    // Window display (1)
                    "windowTimestamp",
                    // Pod identity (3)
                    "region", "workerId", "runId",
                    // MID-TEST-SCALING Phase C — epoch the worker joined at (1)
                    "joinedAtSecond",
                    // Per-label entries (1)
                    "entries"
            );
        }

        @Test
        @DisplayName("joinedAtSecond is a LONG with default 0 — additive BACKWARD-compatible field for mid-test scale-up joiners")
        void joined_at_second_is_long_with_default_zero() {
            Schema.Field f = batchSchema.getField("joinedAtSecond");
            assertThat(f).as("joinedAtSecond field must exist").isNotNull();
            assertThat(f.schema().getType()).isEqualTo(Schema.Type.LONG);
            // Avro stores numeric defaults as Long for long-typed fields.
            assertThat(f.defaultVal()).isEqualTo(0L);
        }

        @Test
        @DisplayName("every envelope-level field carries a doc string and a default")
        void envelope_fields_documented_and_defaulted() {
            assertSoftly(softly -> {
                batchSchema.getFields().forEach(f -> {
                    softly.assertThat(f.doc())
                            .as("Field '%s' must have doc", f.name())
                            .isNotBlank();
                    softly.assertThat(f.defaultVal())
                            .as("Field '%s' must have default for schema evolution", f.name())
                            .isNotNull();
                });
            });
        }

        @Test
        @DisplayName("identity fields are non-nullable strings — null would break Kafka keying on {region}|{workerId}")
        void identity_fields_are_non_nullable_strings() {
            List.of("workerId", "region", "runId").forEach(fieldName -> {
                Schema.Field field = batchSchema.getField(fieldName);
                assertThat(field).as("Field '%s' must exist", fieldName).isNotNull();
                assertThat(field.schema().getType()).isEqualTo(Schema.Type.STRING);
            });
        }

        @Test
        @DisplayName("windowSecond is a LONG — fits any Unix epoch second past and future")
        void window_second_is_long() {
            assertThat(batchSchema.getField("windowSecond").schema().getType())
                    .isEqualTo(Schema.Type.LONG);
        }

        @Test
        @DisplayName("entries is an array of WorkerMetricEntry — the envelope's payload shape")
        void entries_is_array_of_worker_metric_entry() {
            Schema entriesField = batchSchema.getField("entries").schema();
            assertThat(entriesField.getType()).isEqualTo(Schema.Type.ARRAY);
            assertThat(entriesField.getElementType().getType()).isEqualTo(Schema.Type.RECORD);
            assertThat(entriesField.getElementType().getName()).isEqualTo("WorkerMetricEntry");
        }
    }

    // -----------------------------------------------------------------------
    // Entry structural validity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("entry structure")
    class EntryStructure {

        @Test
        @DisplayName("WorkerMetricEntry is a RECORD with all per-label fields (label excluded from envelope)")
        void entry_has_expected_fields() {
            Set<String> actualFields = entrySchema.getFields().stream()
                    .map(Schema.Field::name)
                    .collect(Collectors.toSet());

            assertThat(actualFields).containsExactlyInAnyOrder(
                    // Request grouping
                    "label",
                    // Core throughput
                    "throughput", "errorCount", "errorRate",
                    // Latency
                    "avgRespTimeMs", "p50Ms", "p90Ms", "p95Ms", "p99Ms", "minMs", "maxMs", "rawMaxMs",
                    // Bandwidth
                    "bytesReceived", "bytesSent",
                    // HTTP breakdown
                    "statusCodes",
                    // Concurrency
                    "activeThreads"
            );
        }

        @Test
        @DisplayName("every entry-level field carries doc + default — same schema-evolution discipline as the envelope")
        void entry_fields_documented_and_defaulted() {
            assertSoftly(softly -> {
                entrySchema.getFields().forEach(f -> {
                    softly.assertThat(f.doc())
                            .as("Entry field '%s' must have doc", f.name())
                            .isNotBlank();
                    softly.assertThat(f.defaultVal())
                            .as("Entry field '%s' must have default", f.name())
                            .isNotNull();
                });
            });
        }

        @Test
        @DisplayName("counter fields are LONG — prevents overflow at high rps over long runs")
        void counter_fields_are_longs() {
            assertSoftly(softly -> {
                List.of("throughput", "errorCount", "bytesReceived", "bytesSent", "activeThreads", "rawMaxMs")
                        .forEach(name -> softly.assertThat(entrySchema.getField(name).schema().getType())
                                .as("Entry field '%s' must be LONG", name)
                                .isEqualTo(Schema.Type.LONG));
            });
        }

        @Test
        @DisplayName("metric fields are DOUBLE — preserves sub-millisecond precision in percentiles + averages")
        void metric_fields_are_doubles() {
            assertSoftly(softly -> {
                List.of("p50Ms", "p90Ms", "p95Ms", "p99Ms", "minMs", "maxMs", "errorRate", "avgRespTimeMs")
                        .forEach(name -> softly.assertThat(entrySchema.getField(name).schema().getType())
                                .as("Entry field '%s' must be DOUBLE", name)
                                .isEqualTo(Schema.Type.DOUBLE));
            });
        }

        @Test
        @DisplayName("statusCodes is a MAP<string, long> — same shape as the legacy WorkerMetric record")
        void status_codes_is_string_to_long_map() {
            Schema.Field field = entrySchema.getField("statusCodes");
            assertThat(field.schema().getType()).isEqualTo(Schema.Type.MAP);
            assertThat(field.schema().getValueType().getType()).isEqualTo(Schema.Type.LONG);
        }
    }

    // -----------------------------------------------------------------------
    // Binary encoding behaviour — the load-bearing claim of K-0:
    // "200-entry envelope at fleet scale is ~22 KB Avro binary, well under
    // Kafka's 1 MB message.max.bytes default."
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("binary encoding")
    class BinaryEncoding {

        @Test
        @DisplayName("round-trips a 200-entry envelope without data loss")
        void roundtrips_a_200_entry_envelope() throws IOException {
            WorkerMetricBatch original = buildEnvelope(200);

            byte[] bytes = encode(original);
            WorkerMetricBatch decoded = decode(bytes);

            assertSoftly(softly -> {
                softly.assertThat(decoded.getWindowSecond()).isEqualTo(original.getWindowSecond());
                softly.assertThat(decoded.getWindowTimestamp().toString())
                        .isEqualTo(original.getWindowTimestamp().toString());
                softly.assertThat(decoded.getRegion().toString()).isEqualTo(original.getRegion().toString());
                softly.assertThat(decoded.getWorkerId().toString()).isEqualTo(original.getWorkerId().toString());
                softly.assertThat(decoded.getRunId().toString()).isEqualTo(original.getRunId().toString());
                softly.assertThat(decoded.getEntries()).hasSize(200);

                // Spot-check the first and last entries to confirm array order is preserved.
                WorkerMetricEntry first = decoded.getEntries().get(0);
                softly.assertThat(first.getLabel().toString()).isEqualTo("GET /endpoint-0");
                softly.assertThat(first.getThroughput()).isEqualTo(1L);
                softly.assertThat(first.getP50Ms()).isEqualTo(28.0);

                WorkerMetricEntry last = decoded.getEntries().get(199);
                softly.assertThat(last.getLabel().toString()).isEqualTo("GET /endpoint-199");
            });
        }

        @Test
        @DisplayName("200-entry envelope fits comfortably under Kafka's 1 MB message.max.bytes default")
        void encoded_size_for_200_entries_is_well_under_kafka_default() throws IOException {
            byte[] bytes = encode(buildEnvelope(200));

            // Realistic bounds — single-byte varints for small counters, ~20 B for the
            // windowTimestamp string, ULID-shaped runId, 1-2 status codes per entry.
            // The 50 KB ceiling is generous; typical encodings land near 20-25 KB.
            // The point of this test isn't to micro-pin the size — it's to catch a
            // regression that would blow past Kafka's 1 MB default.
            assertThat(bytes.length)
                    .as("200-entry envelope at typical fleet load should be 10-50 KB")
                    .isBetween(10_000, 50_000);

            // And specifically: well under 1 MB.
            assertThat(bytes.length).isLessThan(1_048_576);
        }

        @Test
        @DisplayName("500-entry envelope (MAX_ENTRIES_PER_ENVELOPE) still fits under Kafka's 1 MB default")
        void encoded_size_for_max_envelope_stays_under_1mb() throws IOException {
            byte[] bytes = encode(buildEnvelope(500));

            // MAX_ENTRIES_PER_ENVELOPE is the producer-side split point (K-1).
            // At this cap, a single envelope must still fit — otherwise the cap is too high.
            assertThat(bytes.length).isLessThan(1_048_576);
        }

        @Test
        @DisplayName("empty envelope (no entries) encodes to a small bounded size")
        void encoded_size_for_empty_envelope_is_small() throws IOException {
            byte[] bytes = encode(buildEnvelope(0));

            // Just the 5 envelope fields + empty array marker. Should be < 200 B.
            assertThat(bytes.length).isLessThan(200);
        }

        // -----------------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------------

        private WorkerMetricBatch buildEnvelope(int entryCount) {
            List<WorkerMetricEntry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                Map<String, Long> statusCodes = new HashMap<>();
                statusCodes.put("200", 1L);

                entries.add(WorkerMetricEntry.newBuilder()
                        .setLabel("GET /endpoint-" + i)
                        .setThroughput(1L)
                        .setErrorCount(0L)
                        .setErrorRate(0.0)
                        .setAvgRespTimeMs(28.0)
                        .setP50Ms(28.0)
                        .setP90Ms(28.0)
                        .setP95Ms(28.0)
                        .setP99Ms(28.0)
                        .setMinMs(28.0)
                        .setMaxMs(28.0)
                        .setRawMaxMs(28L)
                        .setBytesReceived(255L)
                        .setBytesSent(152L)
                        .setStatusCodes(statusCodes)
                        .setActiveThreads(1L)
                        .build());
            }

            return WorkerMetricBatch.newBuilder()
                    .setWindowSecond(1778463913L)
                    .setWindowTimestamp("2026/05/11 01:45:13")
                    .setRegion("us-east-1")
                    .setWorkerId("jmeter-worker-local-east-1-0")
                    .setRunId("01KRABAFRVK8327KGTM03TV5AB")
                    .setEntries(entries)
                    .build();
        }

        private byte[] encode(WorkerMetricBatch batch) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            SpecificDatumWriter<WorkerMetricBatch> writer = new SpecificDatumWriter<>(WorkerMetricBatch.class);
            writer.write(batch, encoder);
            encoder.flush();
            return out.toByteArray();
        }

        private WorkerMetricBatch decode(byte[] bytes) throws IOException {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bytes), null);
            SpecificDatumReader<WorkerMetricBatch> reader = new SpecificDatumReader<>(WorkerMetricBatch.class);
            return reader.read(null, decoder);
        }
    }
}
