package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * D-Capacity v2 — per-(application, region) operator-set max-pod budget.
 *
 * <p>Compute costs money on the cloud, so this is mandatory: an
 * application that hasn't allocated capacity in a region can't launch
 * runs there. Future feature: a "request more capacity" workflow lets
 * the application sponsor approve raising {@code maxAvailable}.
 *
 * <p>{@code maxAvailable} is the *provisioned ceiling*. The operator-
 * facing UI also shows {@code readyToUse} (currently powered-on idle
 * pods) and {@code inUse} (allocated to active runs); both are derived
 * at read-time so the schema stays narrow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationCapacity(
        String applicationId,
        String region,
        int maxAvailable,
        Instant createdAt,
        Instant updatedAt) {
}
