package com.perf.k8sorchestrator.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.perf.k8sorchestrator.domain.Run;

/**
 * Body of {@code POST /api/v1/runs/{runId}/scaleUp} response.
 * MID-TEST-SCALING Phase A.
 *
 * <p>Returns the post-scale {@link Run} (with all members — original +
 * newly added) plus a small ledger of what the operator asked for vs.
 * what was granted. {@code partial} is true iff {@code granted < requested}
 * and {@code bestEffort=true} was used.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScaleUpRunResponse(
        Run run,
        int requested,
        int granted,
        boolean partial,
        String stateReason) {
}
