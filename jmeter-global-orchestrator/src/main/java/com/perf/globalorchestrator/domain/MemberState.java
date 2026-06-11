package com.perf.globalorchestrator.domain;

/**
 * Per-pod lifecycle within a fleet run. Independent of the parent
 * {@link RunState} — the parent rolls up across all members.
 *
 * <p><b>MID-TEST-SCALING Phase B</b> added {@link #DRAINING} and
 * {@link #DRAINED}. {@code DRAINING} marks a worker the operator
 * asked to drain (via {@code POST /runs/{runId}/scaleDown}); the run
 * stays RUNNING while drain is in flight. {@code DRAINED} is the
 * successful terminal — distinct from {@link #COMPLETED} (natural end)
 * and {@link #ABORTED} (forced stop). {@code ABORTED} catches drain
 * timeouts as well, with reason {@code drainTimeoutExpired}.
 */
public enum MemberState {
    PENDING,      // member row inserted; fan-out call not yet made
    REQUESTED,    // POST /api/v1/test in flight
    ACCEPTED,     // local-orchestrator returned 2xx on /test
    RUNNING,      // local-orchestrator reports RUNNING via /test status
    DRAINING,     // operator drained via scaleDown; in-flight samplers completing
    COMPLETED,
    FAILED,
    ABORTED,
    DRAINED;      // graceful drain completed; clean JMeter exit

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABORTED || this == DRAINED;
    }

    /**
     * MID-TEST-SCALING Phase B — true for states that count toward the
     * per-(app, region) capacity gate (a draining worker still occupies
     * its pod) AND should NOT be re-claimed by another run. See
     * {@code PodRepository.claimIdleByRegionAndApp} active filter +
     * {@code ApplicationCapacityRepository.countActivePodsForAppRegion}.
     */
    public boolean isActiveForCapacity() {
        return this == PENDING || this == REQUESTED || this == ACCEPTED
                || this == RUNNING || this == DRAINING;
    }
}
