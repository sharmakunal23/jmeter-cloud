package com.perf.k8sorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One row from {@code globalOrchestrator.pod}. {@code podId} matches
 * {@code workerId} in {@code metrics."workerMetric"} so cross-table
 * joins work; {@code baseUrl} is what the global uses to fan out
 * {@code POST /api/v1/test} during a run.
 *
 * <p>{@code applicationId} (Phase 1 capacity rework) is the application
 * this pod is bound to. Nullable during the Phase 1 → Phase 6 migration
 * window for legacy static pods registered without {@code APPLICATION_ID}.
 *
 * <p>WORKER-HYGIENE Phase B adds three recycle-tracking fields:
 * <ul>
 *   <li>{@code runsServed} — incremented inside the run-claim transaction.
 *       Phase D's reconciler compares against {@code maxRunsPerPod}.</li>
 *   <li>{@code imageDigest} — captured at container-create time; NULL for
 *       legacy pods. Phase D's reconciler diffs against the current image
 *       digest to detect stale workers after a rebuild.</li>
 *   <li>{@code provisionedAt} — wall-clock at container create; NULL for
 *       legacy pods. Phase D anchors max-age checks here, not on
 *       {@code registeredAt} (which resets on local-orch restart).</li>
 * </ul>
 *
 * <p>STATIC-FLEET Phase 3 adds {@link PodSource} — who owns this worker's
 * lifecycle. It is a property of the row, not of the process: a deployment
 * flipped to {@code PROVISIONING_MODE=STATIC} still carries DYNAMIC rows
 * from before the flip, and those must not be treated as declared.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pod(
        String podId,
        String region,
        String baseUrl,
        PodState state,
        Instant lastHeartbeat,
        Instant registeredAt,
        String applicationId,
        long runsServed,
        String imageDigest,
        Instant provisionedAt,
        PodSource source) {

    public Pod {
        // Rows written before V29 read back as DYNAMIC via the column
        // default; normalise a null defensively so callers never branch on it.
        source = source == null ? PodSource.DYNAMIC : source;
    }
}
