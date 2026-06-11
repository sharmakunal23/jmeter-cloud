package com.perf.globalorchestrator.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.perf.globalorchestrator.domain.Run;

import java.util.List;

/**
 * Body of {@code POST /api/v1/runs/{runId}/scaleDown} response.
 * MID-TEST-SCALING Phase B.
 *
 * <p>{@code run} is the post-scale snapshot — drained members appear in
 * state {@code DRAINING} (pending convergence) or {@code DRAINED}
 * (already drained, e.g., racy). {@code drained} lists the workerIds
 * the request actually targeted; {@code skipped} lists targets that
 * were already terminal / unknown / unreachable, with a one-line reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScaleDownRunResponse(
        Run run,
        List<String> drained,
        List<SkippedTarget> skipped) {

    public ScaleDownRunResponse {
        drained = drained == null ? List.of() : List.copyOf(drained);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    public record SkippedTarget(String workerId, String reason) {}
}
