package com.perf.globalorchestrator.domain;

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
}
