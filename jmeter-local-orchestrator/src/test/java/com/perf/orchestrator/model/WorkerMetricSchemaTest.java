package com.perf.orchestrator.model;

import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("WorkerMetric Avro schema")
class WorkerMetricSchemaTest {

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        // Load from source tree — Avro codegen (Section 5) will generate the Java
        // class from this same file, so testing the file directly keeps them in sync.
        // Lives in the sibling kafka/ subsystem (canonical schema location).
        File avscFile = new File("../kafka/schemas/WorkerMetric.avsc");
        schema = new Schema.Parser().parse(avscFile);
    }

    // -----------------------------------------------------------------------
    // Schema structural validity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("schema structure")
    class SchemaStructure {

        @Test
        @DisplayName("is a named RECORD type — required for Kafka Schema Registry compatibility")
        void is_a_record_type() {
            assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
        }

        @Test
        @DisplayName("lives in the correct namespace so generated classes land in the right package")
        void has_correct_namespace() {
            assertThat(schema.getNamespace()).isEqualTo("com.perf.orchestrator");
        }

        @Test
        @DisplayName("carries a doc string — required for consumers to understand fields without reading source")
        void has_schema_documentation() {
            assertThat(schema.getDoc())
                    .as("Schema-level doc is required for Schema Registry discoverability")
                    .isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // Field completeness behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("field completeness")
    class FieldCompleteness {

        @Test
        @DisplayName("contains all fields that downstream consumers (Grafana, Snowflake) depend on")
        void contains_all_required_fields() {
            Set<String> actualFields = schema.getFields().stream()
                    .map(Schema.Field::name)
                    .collect(Collectors.toSet());

            assertThat(actualFields).containsAll(Set.of(
                    // Window identity
                    "windowSecond", "windowTimestamp",
                    // Worker identity — used for per-pod filtering in Grafana
                    "region", "workerId", "runId",
                    // Request grouping
                    "label",
                    // Core throughput metrics
                    "throughput", "errorCount", "errorRate",
                    // Latency distribution — all five percentiles required by SLA dashboards
                    "p50Ms", "p90Ms", "p95Ms", "p99Ms",
                    // Boundary values — maxMs is clamped, rawMaxMs is the true extreme
                    "minMs", "maxMs", "rawMaxMs",
                    // Bandwidth accounting
                    "bytesReceived", "bytesSent",
                    // HTTP breakdown — needed for status-code Grafana panels
                    "statusCodes",
                    // Concurrency snapshot
                    "activeThreads"
            ));
        }

        @Test
        @DisplayName("every field carries a doc string — prevents undocumented fields reaching Schema Registry")
        void every_field_is_documented() {
            List<String> undocumentedFields = schema.getFields().stream()
                    .filter(f -> f.doc() == null || f.doc().isBlank())
                    .map(Schema.Field::name)
                    .toList();

            assertThat(undocumentedFields)
                    .as("Fields without documentation: %s", undocumentedFields)
                    .isEmpty();
        }

        @Test
        @DisplayName("every field has a default value — required for forward-compatible schema evolution (B3)")
        void every_field_has_a_default() {
            // Avro schema evolution rule: new fields added to a schema must have defaults
            // so existing consumers can read old messages. Without defaults, adding a field
            // breaks all consumers that receive messages written by an older producer.
            // This test ensures no one accidentally adds a field without a default.
            List<String> fieldsWithoutDefault = schema.getFields().stream()
                    .filter(f -> f.defaultVal() == null)
                    .map(Schema.Field::name)
                    .toList();

            assertThat(fieldsWithoutDefault)
                    .as("Every field must have a default for schema evolution compatibility: %s",
                            fieldsWithoutDefault)
                    .isEmpty();
        }

        @Test
        @DisplayName("contains rawMaxMs field — unclamped true maximum for outlier detection (B17)")
        void contains_raw_max_ms_field() {
            assertThat(schema.getField("rawMaxMs"))
                    .as("rawMaxMs must be present — maxMs is clamped by HDRHistogram, " +
                        "rawMaxMs preserves the true maximum for extreme timeout detection")
                    .isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // Field type contract behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("field type contracts")
    class FieldTypeContracts {

        @Test
        @DisplayName("worker identity fields are non-nullable strings — null would break Kafka keying")
        void identity_fields_are_non_nullable_strings() {
            // If any identity field were a nullable union, a missing env var could
            // produce a null key in Kafka, routing the message to an unpredictable partition.
            List.of("workerId", "region", "runId", "label").forEach(fieldName -> {
                Schema.Field field = schema.getField(fieldName);
                assertThat(field).as("Field '%s' must exist", fieldName).isNotNull();
                assertThat(field.schema().getType())
                        .as("Field '%s' must be a non-nullable STRING", fieldName)
                        .isEqualTo(Schema.Type.STRING);
            });
        }

        @Test
        @DisplayName("windowSecond is a LONG — fits any Unix epoch second past and future")
        void window_second_is_long() {
            assertThat(schema.getField("windowSecond").schema().getType())
                    .isEqualTo(Schema.Type.LONG);
        }

        @Test
        @DisplayName("throughput, errorCount, bytesReceived, bytesSent are LONGs — prevents overflow at high rps")
        void counter_fields_are_longs() {
            // At 333 req/s for 10h: 333 × 36_000 = ~12M requests per pod.
            // An int (max ~2.1B) would overflow after ~6M requests at peak; LONG is safe.
            assertSoftly(softly -> {
                List.of("throughput", "errorCount", "bytesReceived", "bytesSent", "activeThreads")
                        .forEach(name -> softly.assertThat(schema.getField(name).schema().getType())
                                .as("Field '%s' must be LONG to avoid overflow", name)
                                .isEqualTo(Schema.Type.LONG));
            });
        }

        @Test
        @DisplayName("all percentile and rate fields are DOUBLEs — preserves sub-millisecond precision")
        void metric_fields_are_doubles() {
            assertSoftly(softly -> {
                List.of("p50Ms", "p90Ms", "p95Ms", "p99Ms", "minMs", "maxMs", "errorRate")
                        .forEach(name -> softly.assertThat(schema.getField(name).schema().getType())
                                .as("Field '%s' must be DOUBLE for precision", name)
                                .isEqualTo(Schema.Type.DOUBLE));
            });
        }

        @Test
        @DisplayName("rawMaxMs is a LONG — preserves exact millisecond value without floating-point rounding")
        void raw_max_ms_is_long() {
            // rawMaxMs stores the raw JTL elapsed value (already in whole milliseconds).
            // A LONG is more appropriate than DOUBLE because there is no sub-millisecond
            // precision to preserve, and LONG avoids floating-point representation errors
            // for large values (e.g. 3,700,000ms).
            assertThat(schema.getField("rawMaxMs").schema().getType())
                    .isEqualTo(Schema.Type.LONG);
        }

        @Test
        @DisplayName("statusCodes is a MAP<string, long> — supports arbitrary HTTP and non-HTTP response codes")
        void status_codes_is_a_string_to_long_map() {
            // Keys are raw JTL responseCode strings — they may be numeric ("200", "503")
            // or JMeter error strings ("Non HTTP response code: ..."). A MAP<string, long>
            // handles both without requiring an enum or a fixed schema per code.
            Schema.Field field = schema.getField("statusCodes");
            assertThat(field.schema().getType())
                    .as("statusCodes must be a MAP")
                    .isEqualTo(Schema.Type.MAP);
            assertThat(field.schema().getValueType().getType())
                    .as("statusCodes values must be LONG to hold large counts")
                    .isEqualTo(Schema.Type.LONG);
        }
    }
}
