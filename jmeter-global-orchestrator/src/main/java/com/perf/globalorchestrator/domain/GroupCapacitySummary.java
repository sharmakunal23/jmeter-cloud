package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * One (group, region) row of {@code GET /api/v1/applicationGroups/capacitySummary}
 * — the Capacity list's whole table in a single response.
 *
 * <p>It exists because the per-(group, region) {@code /capacity/{region}/pods}
 * call reaches the region's Kubernetes API for live container status, and a
 * list page reading it once per row polled that API {@code groups × regions}
 * times per tick. Nothing here needs the substrate: every count comes from
 * {@code ORCH_POD} and {@code ORCH_GROUP_CAPACITY}. The drill-in page still
 * uses the per-region call, where per-pod container status is the point.
 *
 * @param ready          {@code provisioned - inUse}, matching the per-region snapshot's own arithmetic
 * @param lastActivityAt newest heartbeat across the pool, or null when it is empty
 */
public record GroupCapacitySummary(
        String groupId,
        String region,
        int maxAvailable,
        long provisioned,
        long ready,
        long inUse,
        Instant lastActivityAt) {
}
