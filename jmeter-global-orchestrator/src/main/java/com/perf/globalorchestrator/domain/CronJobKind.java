package com.perf.globalorchestrator.domain;

/**
 * AUTOMATION Phase C — what a CRON fire DOES. Stored on {@code cronJob.kind}
 * (Flyway V22); the {@code CronFireService} dispatches on this.
 *
 * <ul>
 *   <li>{@link #LAUNCH_RUN} — fire a saved template via
 *       {@code RunService.startRun}. Phase A+B behaviour. Requires
 *       {@code templateBlobId}; {@code region} is unused (the run picks
 *       regions from the template's fleetAllocation).</li>
 *   <li>{@link #DRAIN_REGION} — drain every IDLE worker in
 *       {@code (applicationName, region)} without replacement via
 *       {@code PodRecycler.recycle(..., DRAIN_AFTER_RUN)}. Skips IN_USE
 *       workers (existing recycler safeguard) and is a no-op for
 *       {@code application.alwaysOn=true} (production-like apps). Requires
 *       {@code region}.</li>
 *   <li>{@link #PROVISION_REGION} — bring {@code (applicationName, region)}
 *       back up to {@code applicationCapacity.maxAvailable} via
 *       {@code PodSpinService.spin}. Requires {@code region}.</li>
 * </ul>
 *
 * <p>Operators schedule {@code DRAIN_REGION at 19:00 UTC} +
 * {@code PROVISION_REGION at 06:00 UTC} per (app, region) for overnight
 * cost saving (operator goal #4).
 *
 * <p>Phase E/D add two <b>platform-wide report</b> kinds — singletons with no
 * application / template / region, just a cron + recipients. They ride the same
 * HA-safe scheduler and email the result:
 * <ul>
 *   <li>{@link #INFRA_READINESS} (goal #2) — daily "all healthy" / list-of-failures.</li>
 *   <li>{@link #DAILY_REPORT} (goal #1, Phase D) — daily perf-test summary.</li>
 * </ul>
 */
public enum CronJobKind {
    LAUNCH_RUN,
    DRAIN_REGION,
    PROVISION_REGION,
    INFRA_READINESS,
    DAILY_REPORT;

    /** True for the platform-wide report kinds (no app/template/region; carry recipients). */
    public boolean isReport() {
        return this == INFRA_READINESS || this == DAILY_REPORT;
    }
}
