package com.perf.globalorchestrator.domain;

/**
 * The outcome of a single CRON fire attempt. Recorded on the
 * {@code cronJob.lastFireStatus} column and as one row in
 * {@code cronJobFireHistory}.
 *
 * <ul>
 *   <li>{@link #LAUNCHED} — the run started ({@code RunService.startRun}
 *       returned a run); {@code lastFiredRunId} is set.</li>
 *   <li>{@link #SKIPPED} — the fire was intentionally not launched: the
 *       application had no free capacity (409/503) or the previous fire's run
 *       is still active (overlap guard). Operator action, not a bug — try
 *       again next window.</li>
 *   <li>{@link #FAILED} — the fire could not complete: the template blob was
 *       unavailable / malformed, or the launch failed with an unexpected
 *       error. Needs operator attention.</li>
 *   <li>{@link #DISABLED} — the schedule was disabled between claim and fire;
 *       the attempt is a no-op.</li>
 * </ul>
 */
public enum CronJobFireOutcome {
    LAUNCHED,
    SKIPPED,
    FAILED,
    DISABLED
}
