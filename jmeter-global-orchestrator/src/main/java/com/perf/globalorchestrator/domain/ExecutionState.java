package com.perf.globalorchestrator.domain;

import java.util.List;

/**
 * Aggregate state of one workflow execution.
 *
 * <p>A RUNNING row always carries a {@code nextTickAt} and a terminal one never
 * does — the database CHECK enforces it, so "running, but nothing will ever
 * touch it again" cannot be stored.
 */
public enum ExecutionState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }

    /**
     * The verdict for a finished execution: CANCELLED if any task was, else
     * FAILED if any task failed, else SUCCEEDED. A skipped task is not a
     * failure — nothing ran, so nothing failed.
     *
     * <p>An {@code ON_FAILURE} branch deliberately does <em>not</em> forgive
     * the failure it handles: forgiving made a run whose load test failed read
     * SUCCEEDED in the history because an alert email was wired up, while the
     * email that same branch sent said FAILED. It lives here rather than in the
     * engine because the run's chip and its email both need it, and the rule
     * itself needs nothing but the tasks.
     */
    public static ExecutionState verdictOf(List<WorkflowTask> tasks) {
        for (WorkflowTask t : tasks) {
            if (t.state() == TaskState.CANCELLED) return CANCELLED;
        }
        for (WorkflowTask t : tasks) {
            if (t.state() == TaskState.FAILED) return FAILED;
        }
        return SUCCEEDED;
    }
}
