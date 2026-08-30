package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Body of {@code POST /api/v1/runs/{runId}/scaleUp} — adds workers to an
 * already-RUNNING run. MID-TEST-SCALING Phase A.
 *
 * <p>Reuses {@link FleetAllocationEntry} for the per-region count + optional
 * {@code perNodeProperties}. The original run's {@code testPlanBlobId} and
 * {@code dataFilesBlobId} are sourced from the {@code ORCH_RUN}
 * row at the server, so the caller doesn't repeat them.
 *
 * <p>Properties are NOT inherited from existing fleet members — position-
 * based inheritance would be surprising. Callers that want extra {@code -J}
 * properties on the new workers must pass them in {@code allocations[i].perNodeProperties}.
 *
 * <p>Unknown fields are ignored so the wire schema can grow without
 * breaking older clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScaleUpRunRequest(
        List<FleetAllocationEntry> allocations) {

    public ScaleUpRunRequest {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }
}
