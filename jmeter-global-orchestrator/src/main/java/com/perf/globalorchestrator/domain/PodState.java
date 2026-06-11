package com.perf.globalorchestrator.domain;

/**
 * Lifecycle of a registered local-orchestrator pod from the global's
 * perspective. BUSY is intentionally not modeled here — a pod's busyness
 * is derived from {@code runFleetMember} reservations at claim time,
 * which keeps heartbeats and run-coordination decoupled.
 */
public enum PodState {
    /** Registered, recent heartbeat, eligible to be claimed for a run. */
    IDLE,
    /** Heartbeat older than the sweeper's threshold; treat as gone. */
    LOST,
    /**
     * WORKER-HYGIENE Phase D — pod has tripped a recycle threshold
     * (or image-mismatch) and is being drain-and-replaced. The
     * claim path filters this out the same way it filters LOST;
     * heartbeat does NOT flip this back to IDLE.
     */
    DRAINING_FOR_RECYCLE;
}
