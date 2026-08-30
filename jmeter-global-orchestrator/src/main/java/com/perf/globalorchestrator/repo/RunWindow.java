package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.Run;

import java.time.Instant;

/**
 * The inclusive {@code WINDOW_SECOND} range a run can have rows in, derived
 * from the control plane's own timestamps so every fact-table statement
 * carries a partition-key range (the tables are partitioned by day). One
 * minute of slack on each side covers clock skew and the last window's flush.
 */
public record RunWindow(long lo, long hi) {

    private static final long SLACK_SECONDS = 60;

    public static RunWindow of(Run run) {
        return of(run, Instant.now());
    }

    static RunWindow of(Run run, Instant now) {
        Instant start = run.startedAt() != null ? run.startedAt() : run.createdAt();
        Instant end = run.completedAt() != null ? run.completedAt() : now;
        long lo = Math.max(0, start.getEpochSecond() - SLACK_SECONDS);
        long hi = Math.max(lo, end.getEpochSecond() + SLACK_SECONDS);
        return new RunWindow(lo, hi);
    }

    public RunWindow narrowTo(long from, long to) {
        return new RunWindow(Math.max(lo, from), Math.min(hi, to));
    }
}
