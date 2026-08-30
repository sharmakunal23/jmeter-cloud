package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/runs/{runId}/properties} (UX-DYNAMICS T5).
 * Omitting {@code workerIds} targets every ACCEPTED/RUNNING member; an id
 * that is not an active member rejects the whole request 400 (no partial
 * targeting surprises). Property rules mirror the worker's: keys
 * {@code [A-Za-z_][A-Za-z0-9_.]{0,63}}, values ≤ 256 chars, no control
 * characters, at most 50 entries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateRunPropertiesRequest(
        List<String> workerIds,
        Map<String, String> properties) {

    public UpdateRunPropertiesRequest {
        workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
    }
}
