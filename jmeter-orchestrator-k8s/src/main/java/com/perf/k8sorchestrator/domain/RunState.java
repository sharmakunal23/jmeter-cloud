package com.perf.k8sorchestrator.domain;

/**
 * Aggregate state of a fleet-wide run, rolled up across all
 * {@link RunFleetMember}s. See the migration's schema comment for the
 * intended lifecycle.
 */
public enum RunState {
    PREPARING,    // row written; fan-out hasn't started yet
    STARTING,     // fan-out POST /api/v1/test in progress
    RUNNING,      // at least one member RUNNING
    DRAINING,     // at least one member DRAINING
    COMPLETED,    // every member terminal
    FAILED,
    ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABORTED;
    }
}
