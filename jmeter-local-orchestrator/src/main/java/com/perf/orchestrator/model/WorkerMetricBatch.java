package com.perf.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The metrics-ingest wire envelope — one per {@code (workerId, windowSecond)},
 * built by {@code TumblingWindowAggregator} every second and POSTed to the
 * metrics-consumer's {@code POST /api/v1/ingest} as {@code application/json}.
 *
 * <p><b>Contract:</b> the canonical wire definition is the metrics-consumer's
 * {@code api/openapi.yaml} ({@code #/components/schemas/WorkerMetricBatch}).
 * The consumer keeps a structurally identical record — per the repo's
 * no-shared-module rule — and both sides pin the shape with the same
 * golden-payload round-trip test ({@code goldenWorkerMetricBatch.json},
 * duplicated verbatim in both test trees).
 *
 * <p>Tolerant reader ({@code @JsonIgnoreProperties}) matters on this side for
 * the disk buffer's boot recovery — a downgraded build reading a newer
 * build's buffered envelopes skips unknown fields instead of dying.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerMetricBatch(
        long windowSecond,
        String windowTimestamp,
        String region,
        String workerId,
        String runId,
        long joinedAtSecond,
        List<WorkerMetricEntry> entries) {
}
