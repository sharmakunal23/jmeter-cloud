package com.perf.globalorchestrator.domain;

/**
 * What a CRON fire does; {@code CronFireService} dispatches on it.
 *
 * <ul>
 *   <li>{@link #LAUNCH_RUN} — launch a saved template through
 *       {@code RunService.startRun}. Requires {@code templateBlobId};
 *       {@code region} is unused, since the template's fleetAllocation picks
 *       the regions.</li>
 *   <li>{@link #DRAIN_REGION} — drain every IDLE worker in
 *       {@code (applicationName, region)} without replacement. Skips IN_USE
 *       workers and is a no-op when {@code application.alwaysOn}. Requires
 *       {@code region}.</li>
 *   <li>{@link #PROVISION_REGION} — bring {@code (applicationName, region)}
 *       back up to {@code applicationCapacity.maxAvailable}. Requires
 *       {@code region}.</li>
 *   <li>{@link #INFRA_READINESS} / {@link #DAILY_REPORT} — platform-wide report
 *       singletons: no application, template or region, just a cron and
 *       recipients. They ride the same HA-safe scheduler and email the result.</li>
 * </ul>
 *
 * <p>Pairing {@code DRAIN_REGION} in the evening with {@code PROVISION_REGION}
 * in the morning is the intended overnight cost-saving shape.
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
