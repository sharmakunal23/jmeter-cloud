package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * One persistent CRON schedule. Mirrors the {@code ORCH_CRON_JOB} row (V9) and
 * the UI contract in {@code jmeter-cloud-ui/src/api/automation.ts}
 * ({@code CronJobSummary}) — keep the field names aligned across all three so
 * the wire shape stays one edit wide.
 *
 * <p><b>A schedule belongs to an application group, never an application</b>
 * (AUTOMATION-3, 2026-08-31). The DB-claim scheduler
 * ({@code sweep.CronJobScheduler}) materialises {@link #nextFireAt}; when a tick
 * finds it due, {@code service.CronFireService} dispatches on {@link #kind}.
 *
 * @param cronJobId              ULID primary key.
 * @param name                   operator label; unique per group (and, for the
 *                               report kinds whose group is null, unique
 *                               platform-wide — Oracle lets only an all-NULL
 *                               key repeat).
 * @param groupId                the owning application group; null only for the report kinds.
 * @param workflowId             the workflow to launch; LAUNCH_WORKFLOW only.
 *                               Carries no FK: deleting a workflow leaves the
 *                               schedule, whose next fire reports FAILED rather
 *                               than vanishing unnoticed.
 * @param cronExpression         raw operator string (5-field unix or 6-field).
 * @param timeZone               IANA zone id; {@link #nextFireAt} is computed in it.
 * @param enabled                disabled schedules are never claimed by the sweep.
 * @param createdBy              X-Actor at create time (nullable).
 * @param createdAt              wall-clock at create.
 * @param lastFiredAt            last fire attempt time (nullable until first fire).
 * @param lastFiredExecutionId   the workflow execution the last fire started (nullable).
 * @param lastFireStatus         last {@link CronJobFireOutcome} name (nullable).
 * @param nextFireAt             materialised next trigger time, UTC (nullable when disabled).
 * @param claimedAt              in-flight fence stamped by the sweep; cleared on fire.
 * @param kind                   what the fire does.
 * @param region                 target cluster for SCALE_OUT / SCALE_IN; null otherwise.
 * @param recipients             comma-separated emails for the report kinds; null otherwise.
 * @param customSubject          optional custom email subject for report kinds; null → composer default.
 * @param customIntro            optional intro rendered above the report body; null → no intro.
 */
public record CronJob(
        String cronJobId,
        String name,
        String groupId,
        String workflowId,
        String cronExpression,
        String timeZone,
        boolean enabled,
        String createdBy,
        Instant createdAt,
        Instant lastFiredAt,
        String lastFiredExecutionId,
        String lastFireStatus,
        Instant nextFireAt,
        Instant claimedAt,
        CronJobKind kind,
        String region,
        String recipients,
        String customSubject,
        String customIntro) {
}
