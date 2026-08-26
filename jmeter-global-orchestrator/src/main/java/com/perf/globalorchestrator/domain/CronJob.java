package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * One persistent CRON schedule. Mirrors the
 * {@code globalOrchestrator.cronJob} row (Flyway V20) and the UI contract in
 * {@code jmeter-cloud-ui/src/api/automation.ts} ({@code CronJobSummary}) —
 * keep the field names aligned across all three so the wire shape stays the
 * one-line-swap the UI stub promised.
 *
 * <p>A schedule pairs a saved Template ({@link #templateBlobId}) with a cron
 * expression + timezone. The DB-claim scheduler
 * ({@code sweep.CronJobScheduler}) materialises {@link #nextFireAt}; when a
 * tick finds it due, {@code service.CronFireService} fetches the template and
 * launches a run through the normal {@code RunService.startRun} path.
 *
 * @param cronJobId       ULID primary key.
 * @param name            operator label; unique per application.
 * @param applicationName run application key (validated against the registry).
 * @param templateBlobId  document-service blob (X-Type=template).
 * @param cronExpression  raw operator string (5-field unix or 6-field).
 * @param timeZone        IANA zone id; {@link #nextFireAt} is computed in it.
 * @param enabled         disabled schedules are never claimed by the sweep.
 * @param createdBy       X-Actor at create time (nullable).
 * @param createdAt       wall-clock at create.
 * @param lastFiredAt     last fire attempt time (nullable until first fire).
 * @param lastFiredRunId  runId from the last LAUNCHED fire (nullable).
 * @param lastFireStatus  last {@link CronJobFireOutcome} name (nullable).
 * @param nextFireAt      materialised next trigger time, UTC (nullable when disabled).
 * @param claimedAt       in-flight fence stamped by the sweep; cleared on fire.
 * @param kind            what the fire does. Required;
 *                        LAUNCH_RUN for legacy rows via the V22 default.
 * @param region          target region for DRAIN/PROVISION;
 *                        null for LAUNCH_RUN (template fleetAllocation drives regions).
 * @param recipients      comma-separated emails for report
 *                        kinds (INFRA_READINESS / DAILY_REPORT); null otherwise.
 *                        {@code applicationName} is null for report kinds (platform-wide).
 * @param customSubject   optional custom email subject for report
 *                        kinds (V25); null → the composer's default subject.
 * @param customIntro     optional intro/note rendered above the
 *                        report body for report kinds (V25); null → no intro.
 */
public record CronJob(
        String cronJobId,
        String name,
        String applicationName,
        String templateBlobId,
        String cronExpression,
        String timeZone,
        boolean enabled,
        String createdBy,
        Instant createdAt,
        Instant lastFiredAt,
        String lastFiredRunId,
        String lastFireStatus,
        Instant nextFireAt,
        Instant claimedAt,
        CronJobKind kind,
        String region,
        String recipients,
        String customSubject,
        String customIntro) {
}
