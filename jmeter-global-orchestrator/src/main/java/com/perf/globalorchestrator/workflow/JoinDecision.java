package com.perf.globalorchestrator.workflow;

/** Whether a pending task may start, must wait, or can never start. */
public enum JoinDecision {
    READY,
    WAIT,
    /** No remaining inbound edge can be satisfied — the branch went the other way. */
    SKIP
}
