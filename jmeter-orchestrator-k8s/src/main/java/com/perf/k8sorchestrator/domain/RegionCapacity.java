package com.perf.k8sorchestrator.domain;

/**
 * Per-region capacity rollup served by {@code GET /api/v1/regions}.
 * Counts come from {@code globalOrchestrator.pod} grouped by
 * {@code region}; {@code idlePods} excludes pods already claimed by an
 * active {@code runFleetMember} so the UI sees true availability.
 */
public record RegionCapacity(
        String region,
        long totalPods,
        long idlePods,
        long lostPods) {
}
