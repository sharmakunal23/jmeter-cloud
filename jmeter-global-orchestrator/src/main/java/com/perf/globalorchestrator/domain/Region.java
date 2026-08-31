package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * A registered cluster ({@code ORCH_REGION}, CLUSTER-CAPACITY): the
 * regional-orchestrator endpoint the hub validated at registration and the
 * worker ceiling the groups' reservations must fit under. {@code region} stays
 * the axis name everywhere — "cluster" is the UI's display word.
 *
 * @param maxWorkers     the cluster's ceiling; SUM of the groups'
 *                       {@code ORCH_GROUP_CAPACITY.MAX_AVAILABLE} rows for this
 *                       region never exceeds it
 * @param lastProbeStatus {@code PASS} | {@code FAIL} | null — the on-demand
 *                       test-provisioning probe's last verdict
 */
public record Region(
        String region,
        String label,
        String regionalUrl,
        int maxWorkers,
        Instant lastValidatedAt,
        Instant lastProbeAt,
        String lastProbeStatus,
        String lastProbeDetail,
        Instant createdAt,
        Instant updatedAt) {
}
