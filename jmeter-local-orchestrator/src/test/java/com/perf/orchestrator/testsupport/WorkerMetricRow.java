package com.perf.orchestrator.testsupport;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only flat projection of a {@link WorkerMetricBatch} envelope's entry, for
 * assertions that pre-date the K-1 envelope shape and want per-row readability.
 *
 * <p>The K-1 publish path emits envelopes, not per-row records. Tests that need
 * to filter or extract by individual {@code (workerId, label, windowSecond)}
 * triples use {@link #flatten(List)} to turn a list of envelopes into a list of
 * synthetic per-row records — same field names and getters as the deleted
 * {@code WorkerMetricDto} so existing assertions keep their shape.
 *
 * <p>This is a test-support type only; production code never sees it.
 */
public record WorkerMetricRow(
        long windowSecond,
        String windowTimestamp,
        String region,
        String workerId,
        String runId,
        String label,
        long throughput,
        long errorCount,
        double errorRate,
        double avgRespTimeMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms,
        double minMs,
        double maxMs,
        long rawMaxMs,
        long bytesReceived,
        long bytesSent,
        Map<String, Long> statusCodes,
        long activeThreads
) {

    /**
     * Projects one envelope entry onto a flat per-row shape, copying the
     * envelope's identity fields onto the entry's per-label fields. Mirrors
     * the consumer-side explode logic.
     */
    public static WorkerMetricRow from(WorkerMetricBatch env, WorkerMetricEntry entry) {
        return new WorkerMetricRow(
                env.getWindowSecond(),
                env.getWindowTimestamp().toString(),
                env.getRegion().toString(),
                env.getWorkerId().toString(),
                env.getRunId().toString(),
                entry.getLabel().toString(),
                entry.getThroughput(),
                entry.getErrorCount(),
                entry.getErrorRate(),
                entry.getAvgRespTimeMs(),
                entry.getP50Ms(),
                entry.getP90Ms(),
                entry.getP95Ms(),
                entry.getP99Ms(),
                entry.getMinMs(),
                entry.getMaxMs(),
                entry.getRawMaxMs(),
                entry.getBytesReceived(),
                entry.getBytesSent(),
                copyStatusCodes(entry.getStatusCodes()),
                entry.getActiveThreads()
        );
    }

    /**
     * Flattens a list of envelopes into a list of per-row records — preserves
     * envelope order and per-envelope entry order. The resulting list has size
     * {@code sum of envelope.entries.size()}.
     */
    public static List<WorkerMetricRow> flatten(List<WorkerMetricBatch> envelopes) {
        List<WorkerMetricRow> out = new ArrayList<>();
        for (WorkerMetricBatch env : envelopes) {
            for (WorkerMetricEntry entry : env.getEntries()) {
                out.add(from(env, entry));
            }
        }
        return out;
    }

    private static Map<String, Long> copyStatusCodes(Map<? extends CharSequence, Long> raw) {
        Map<String, Long> copy = new HashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> copy.put(k.toString(), v));
        }
        return Map.copyOf(copy);
    }
}
