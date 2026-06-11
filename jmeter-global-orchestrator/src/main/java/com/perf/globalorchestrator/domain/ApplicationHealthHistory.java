package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * AUTOMATION Phase E — one health-transition record for an application
 * (a row in {@code globalOrchestrator.applicationHealthHistory}, Flyway V23).
 * Written only when an app's aggregate status CHANGES (not every poll), so the
 * log stays compact; the daily infra-readiness email reads the last 24h to
 * compute downtime windows.
 *
 * @param historyId     ULID primary key.
 * @param applicationId the application whose status changed.
 * @param status        the new {@link Application.HealthStatus} name.
 * @param changedAt     when the change was observed.
 */
public record ApplicationHealthHistory(
        String historyId,
        String applicationId,
        String status,
        Instant changedAt) {
}
