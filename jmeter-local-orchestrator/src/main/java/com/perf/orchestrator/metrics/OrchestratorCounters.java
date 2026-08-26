package com.perf.orchestrator.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot of the orchestrator's own counters — the source of truth for
 * both {@code GET /api/v1/metrics/orchestrator} (JSON) and
 * {@code GET /api/v1/metrics} (Prometheus exposition).
 *
 * <p>Built from {@code CurrentRun}'s snapshot plus a few process-level
 * facts ({@code diskFreeBytes}). All fields are non-negative; counters
 * are cumulative since process start.
 */
public record OrchestratorCounters(
        long rowsParsedTotal,
        long windowsPublishedTotal,
        long publishErrorsTotal,
        long publishLastAckEpochMs,
        long uploadInflightBytes,
        long diskFreeBytes,
        long offsetSaveFailuresTotal) {

    /** Stable JSON shape — keys match {@code OrchestratorMetrics} in the OpenAPI spec. */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowsParsedTotal",         rowsParsedTotal);
        out.put("windowsPublishedTotal",   windowsPublishedTotal);
        out.put("publishErrorsTotal",      publishErrorsTotal);
        out.put("publishLastAckEpochMs",   publishLastAckEpochMs);
        out.put("uploadInflightBytes",     uploadInflightBytes);
        out.put("diskFreeBytes",           diskFreeBytes);
        out.put("offsetSaveFailuresTotal", offsetSaveFailuresTotal);
        return out;
    }
}
