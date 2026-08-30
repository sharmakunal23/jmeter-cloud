package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Per-(group, region) operator-set max-pod budget — the worker pool is the
 * application group's (GROUP-CAPACITY, 2026-08-30), so every application in
 * the group draws on the same rows.
 *
 * <p>Compute costs money on the cloud, so this is mandatory: a group that
 * hasn't allocated capacity in a region can't launch runs there.
 *
 * <p>{@code maxAvailable} is the *provisioned ceiling*. The operator-facing
 * UI also shows {@code readyToUse} (currently powered-on idle pods) and
 * {@code inUse} (allocated to active runs); both are derived at read-time so
 * the schema stays narrow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupCapacity(
        String groupId,
        String region,
        int maxAvailable,
        Instant createdAt,
        Instant updatedAt) {
}
