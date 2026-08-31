package com.perf.globalorchestrator.domain;

/**
 * What a CRON fire does; {@code CronFireService} dispatches on it. Three
 * families, one per section of the Automation page.
 *
 * <ul>
 *   <li>{@link #LAUNCH_WORKFLOW} — launch a saved workflow through
 *       {@code WorkflowService.launch}. Requires {@code groupId} +
 *       {@code workflowId}. This is the <b>only</b> kind that launches work: a
 *       one-node workflow is exactly "fire a saved template", so the retired
 *       {@code LAUNCH_RUN} bought a second way to say the same thing.</li>
 *   <li>{@link #SCALE_OUT} — bring {@code (groupId, region)} up to
 *       {@code ORCH_GROUP_CAPACITY.MAX_AVAILABLE}. Requires {@code region}.</li>
 *   <li>{@link #SCALE_IN} — release every IDLE worker in
 *       {@code (groupId, region)} without replacement. Skips IN_USE workers and
 *       is a no-op when {@code applicationGroup.alwaysOn}. Requires
 *       {@code region}.</li>
 *   <li>{@link #INFRA_READINESS} / {@link #DAILY_REPORT} — platform-wide report
 *       singletons: no group, workflow or region, just a cron and recipients.
 *       They ride the same HA-safe scheduler and email the result.</li>
 * </ul>
 *
 * <p>Pairing {@code SCALE_IN} in the evening with {@code SCALE_OUT} in the
 * morning is the intended overnight cost-saving shape.
 */
public enum CronJobKind {
    LAUNCH_WORKFLOW,
    SCALE_OUT,
    SCALE_IN,
    INFRA_READINESS,
    DAILY_REPORT;

    /** True for the platform-wide report kinds (no group/workflow/region; carry recipients). */
    public boolean isReport() {
        return this == INFRA_READINESS || this == DAILY_REPORT;
    }

    /** True for the kinds that add or release workers in one (group, region). */
    public boolean isScaling() {
        return this == SCALE_OUT || this == SCALE_IN;
    }
}
