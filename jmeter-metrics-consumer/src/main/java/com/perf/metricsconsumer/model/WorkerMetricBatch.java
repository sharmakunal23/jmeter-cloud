package com.perf.metricsconsumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The metrics-ingest wire envelope — one per {@code (workerId, windowSecond)},
 * POSTed as {@code application/json} by each local-orchestrator worker.
 *
 * <p>The canonical definition is this service's {@code api/openapi.yaml}
 * ({@code #/components/schemas/WorkerMetricBatch}). The producer keeps its own
 * structurally identical record — the repo has no shared module by convention —
 * and a golden-payload test in both trees pins the two together.
 *
 * <p>Tolerant reader: unknown fields are ignored, so a producer may add a field
 * before this side knows it. Removing or renaming one rebuilds both sides.
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
