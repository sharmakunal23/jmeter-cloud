package com.perf.metricsconsumer.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON-INGEST wire-contract pin (JI-2). The producer
 * ({@code jmeter-local-orchestrator}) and this service each keep a
 * structurally identical record pair — per the repo's no-shared-module rule
 * — and both sides run this same golden-payload round-trip against their own
 * copy. The golden file {@code goldenWorkerMetricBatch.json} is duplicated
 * verbatim in both test trees: if either side renames or drops a field, its
 * copy of this test breaks before the wire does. The canonical schema
 * definition lives in this service's {@code api/openapi.yaml}.
 */
@DisplayName("JSON-INGEST wire contract — golden payload round-trip")
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
        // SCHEMA-OPT Phase 2 — the exact total, and the value actually stored.
        assertThat(e.sumElapsedMs()).isEqualTo(46_704L);
        assertThat(e.resolvedSumElapsedMs()).isEqualTo(46_704L);
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
    @DisplayName("tolerant reader — unknown fields from a newer producer are ignored, not fatal")
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

    @Test
    @DisplayName("pre-Phase-2 producer (no sumElapsedMs) reconstructs the sum from avgRespTimeMs")
    void olderProducerFallsBackToTheDerivedSum() throws Exception {
        // Deliberately the SAME payload as the tolerant-reader case above: a
        // worker built before SCHEMA-OPT Phase 2 simply has no sumElapsedMs key.
        String preP2 = """
                {"windowSecond": 1, "windowTimestamp": "t", "region": "r",
                 "workerId": "w", "runId": "run", "joinedAtSecond": 0,
                 "entries": [{"label": "L", "throughput": 200, "errorCount": 0,
                   "errorRate": 0.0, "avgRespTimeMs": 12.5, "p50Ms": 1.0,
                   "p90Ms": 1.0, "p95Ms": 1.0, "p99Ms": 1.0, "minMs": 1.0,
                   "maxMs": 1.0, "rawMaxMs": 1, "bytesReceived": 0,
                   "bytesSent": 0, "statusCodes": {"200": 200},
                   "activeThreads": 1}]}
                """;
        WorkerMetricEntry e = MAPPER.readValue(preP2, WorkerMetricBatch.class).entries().get(0);

        assertThat(e.sumElapsedMs()).as("absent on the wire").isNull();
        // 12.5 × 200 — the exact total that producer's row already implied, so
        // the upgrade costs nothing and loses nothing it had before.
        assertThat(e.resolvedSumElapsedMs()).isEqualTo(2_500L);
    }

    @Test
    @DisplayName("a genuinely zero sum is stored as zero, not mistaken for an absent field")
    void zeroSumIsDistinctFromAbsent() throws Exception {
        // The reason the record component is Long and not long: a window of
        // sub-millisecond samples reports 0, and a primitive would make that
        // indistinguishable from "older producer" and silently re-derive it.
        String zeroSum = """
                {"windowSecond": 1, "windowTimestamp": "t", "region": "r",
                 "workerId": "w", "runId": "run", "joinedAtSecond": 0,
                 "entries": [{"label": "L", "throughput": 40, "errorCount": 0,
                   "errorRate": 0.0, "avgRespTimeMs": 0.0, "sumElapsedMs": 0,
                   "p50Ms": 0.0, "p90Ms": 0.0, "p95Ms": 0.0, "p99Ms": 0.0,
                   "minMs": 0.0, "maxMs": 0.0, "rawMaxMs": 0, "bytesReceived": 0,
                   "bytesSent": 0, "statusCodes": {"200": 40},
                   "activeThreads": 1}]}
                """;
        WorkerMetricEntry e = MAPPER.readValue(zeroSum, WorkerMetricBatch.class).entries().get(0);

        assertThat(e.sumElapsedMs()).as("present and zero").isZero();
        assertThat(e.resolvedSumElapsedMs()).isZero();
    }
}
