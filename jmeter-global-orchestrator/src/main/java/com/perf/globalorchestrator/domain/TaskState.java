package com.perf.globalorchestrator.domain;

/** Lifecycle of one workflow task. */
public enum TaskState {
    /** Waiting for its join to be satisfied. */
    PENDING,
    RUNNING,
    /** An APPROVAL node parked for an operator; still RUNNING as far as the execution is concerned. */
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    /** Its join can no longer be satisfied — an upstream branch went the other way. */
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
