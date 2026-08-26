package com.perf.orchestrator.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON-INGEST wire-contract pin (JI-2). The consumer
 * ({@code jmeter-metrics-consumer}) keeps a structurally identical record
 * pair — per the repo's no-shared-module rule — and both sides run this same
 * golden-payload round-trip against their own copy. The golden file
 * {@code goldenWorkerMetricBatch.json} is duplicated verbatim in both test
 * trees: if either side renames or drops a field, its copy of this test
 * breaks before the wire does. The canonical schema definition lives in the
 * consumer's {@code api/openapi.yaml}.
 */
@DisplayName("JSON-INGEST wire contract — golden payload round-trip (producer side)")
class WireContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] golden() throws Exception {
        try (InputStream in = WireContractTest.class
                .getResourceAsStream("/goldenWorkerMetricBatch.json")) {
            assertThat(in).as("golden resource must exist").isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("golden JSON decodes into the record with every field intact")
    void goldenDecodesFully() throws Exception {
        WorkerMetricBatch env = MAPPER.readValue(golden(), WorkerMetricBatch.class);

        assertThat(env.windowSecond()).isEqualTo(1784600000L);
        assertThat(env.windowTimestamp()).isEqualTo("2026-07-20T21:33:20Z");
        assertThat(env.region()).isEqualTo("us-east-1");
        assertThat(env.workerId()).isEqualTo("acaps-us-east-1-worker-1");
        assertThat(env.runId()).isEqualTo("01KY1535CHHF9WG5HHZETVEQBZ");
        assertThat(env.joinedAtSecond()).isEqualTo(4L);
        assertThat(env.entries()).hasSize(1);

        WorkerMetricEntry e = env.entries().get(0);
        assertThat(e.label()).isEqualTo("GET /api/v1/checkout/items");
        assertThat(e.throughput()).isEqualTo(256L);
        assertThat(e.errorCount()).isEqualTo(4L);
        assertThat(e.errorRate()).isEqualTo(0.015625);
        assertThat(e.avgRespTimeMs()).isEqualTo(182.4375);
        // SCHEMA-OPT Phase 2 — the exact total the consumer now stores. Kept
        // consistent with avgRespTimeMs × throughput (182.4375 × 256) so the
        // golden stays internally coherent for a reader that uses either.
        assertThat(e.sumElapsedMs()).isEqualTo(46_704L);
        assertThat(e.p50Ms()).isEqualTo(151.0);
        assertThat(e.p90Ms()).isEqualTo(310.0);
        assertThat(e.p95Ms()).isEqualTo(402.0);
        assertThat(e.p99Ms()).isEqualTo(688.0);
        assertThat(e.minMs()).isEqualTo(42.0);
        assertThat(e.maxMs()).isEqualTo(1201.0);
        assertThat(e.rawMaxMs()).isEqualTo(1201L);
        assertThat(e.bytesReceived()).isEqualTo(1_284_552L);
        assertThat(e.bytesSent()).isEqualTo(96_412L);
        assertThat(e.statusCodes()).containsExactlyInAnyOrderEntriesOf(
                Map.of("200", 240L, "404", 3L, "500", 1L));
        assertThat(e.activeThreads()).isEqualTo(50L);
    }

    @Test
    @DisplayName("decode → re-serialize is JSON-tree-identical to the golden (no renamed/dropped fields)")
    void roundTripMatchesGoldenTree() throws Exception {
        WorkerMetricBatch env = MAPPER.readValue(golden(), WorkerMetricBatch.class);
        JsonNode reSerialized = MAPPER.readTree(MAPPER.writeValueAsBytes(env));
        JsonNode goldenTree = MAPPER.readTree(golden());
        assertThat(reSerialized).isEqualTo(goldenTree);
    }

    @Test
    @DisplayName("tolerant reader — unknown fields (newer build's buffered envelopes) are ignored")
    void unknownFieldsAreIgnored() throws Exception {
        String withExtras = """
                {"windowSecond": 1, "windowTimestamp": "t", "region": "r",
                 "workerId": "w", "runId": "run", "joinedAtSecond": 0,
                 "aFutureEnvelopeField": true,
                 "entries": [{"label": "L", "throughput": 1, "errorCount": 0,
                   "errorRate": 0.0, "avgRespTimeMs": 1.0, "p50Ms": 1.0,
                   "p90Ms": 1.0, "p95Ms": 1.0, "p99Ms": 1.0, "minMs": 1.0,
                   "maxMs": 1.0, "rawMaxMs": 1, "bytesReceived": 0,
                   "bytesSent": 0, "statusCodes": {"200": 1},
                   "activeThreads": 1, "aFutureEntryField": "x"}]}
                """;
        WorkerMetricBatch env = MAPPER.readValue(withExtras, WorkerMetricBatch.class);
        assertThat(env.runId()).isEqualTo("run");
        assertThat(env.entries().get(0).label()).isEqualTo("L");
    }
}
