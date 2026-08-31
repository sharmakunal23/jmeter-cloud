package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * One row of {@code ORCH_CRON_JOB_FIRE_HISTORY}: the record of a single CRON
 * fire attempt. Append-only; surfaced on the Automation page so an operator can
 * see why a window was SKIPPED or FAILED.
 *
 * @param fireId       ULID primary key.
 * @param cronJobId    the schedule that fired (FK-less — survives deletion).
 * @param firedAt      when the attempt ran.
 * @param outcome      {@link CronJobFireOutcome} name.
 * @param executionId  the workflow execution started (LAUNCH_WORKFLOW + LAUNCHED only; null otherwise).
 * @param errorReason  short diagnostic for SKIPPED/FAILED (null for LAUNCHED).
 */
public record CronJobFire(
        String fireId,
        String cronJobId,
        Instant firedAt,
        String outcome,
        String executionId,
        String errorReason) {
}
