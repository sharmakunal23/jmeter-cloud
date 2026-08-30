package com.perf.globalorchestrator.http;

import java.util.List;

/**
 * Response of {@code POST /api/v1/runs/{runId}/properties}: one result row
 * per targeted worker (the dialog renders ✓/✗ per row), plus the applied
 * key set. A partial failure is a 200 — the per-worker rows carry the truth.
 */
public record UpdateRunPropertiesResponse(
        String runId,
        int requested,
        List<WorkerResult> results,
        List<String> applied) {

    /** One worker's outcome; {@code error} is null on success. */
    public record WorkerResult(String workerId, boolean ok, int statusCode, String error) {}
}
