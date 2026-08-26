package com.perf.k8sorchestrator.domain;

import java.time.Instant;

/**
 * One row of {@code cronJobFireHistory}: the record of a single
 * CRON fire attempt. Append-only; surfaced on the schedule's detail page so an
 * operator can see why a window was SKIPPED or FAILED.
 *
 * @param fireId      ULID primary key.
 * @param cronJobId   the schedule that fired (FK-less — survives deletion).
 * @param firedAt     when the attempt ran.
 * @param outcome     {@link CronJobFireOutcome} name.
 * @param runId       the launched run (only for LAUNCHED; null otherwise).
 * @param errorReason short diagnostic for SKIPPED/FAILED (null for LAUNCHED).
 */
public record CronJobFire(
        String fireId,
        String cronJobId,
        Instant firedAt,
        String outcome,
        String runId,
        String errorReason) {
}
