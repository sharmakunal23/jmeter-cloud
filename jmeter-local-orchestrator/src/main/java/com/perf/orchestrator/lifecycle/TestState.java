package com.perf.orchestrator.lifecycle;

/**
 * Outer test lifecycle owned by {@link TestRunManager}.
 *
 * <p>{@link #RUNNING} is a super-state — the existing
 * {@link com.perf.orchestrator.statemachine.TailerStateMachine} runs its own
 * inner state machine (WAITING_FOR_FILE → RUNNING → DRAINING → DONE)
 * underneath it; both are surfaced via {@code GET /api/v1/test}.
 *
 * <p>The five terminal states are {@link #COMPLETED}, {@link #FAILED},
 * {@link #ABORTED}, {@link #DRAINED}, and {@link #IDLE} (returned to
 * after a terminal run is acknowledged or another test starts).
 */
public enum TestState {

    /** No test has ever run, or the last run is fully cleared. */
    IDLE,

    /** Request accepted; clearing previous results / waiting for scheduled start. */
    PREPARING,

    /** JMeter child has been spawned; awaiting first JTL bytes. */
    STARTING,

    /** JMeter is producing rows and the streaming pipeline is publishing them. */
    RUNNING,

    /** JMeter has exited (or been signalled) and the pipeline is flushing. */
    DRAINING,

    /** Run finished cleanly — JMeter exit code 0 and pipeline drained. */
    COMPLETED,

    /** Run finished abnormally — JMeter non-zero exit, IO failure, or restart-after-crash. */
    FAILED,

    /** Run was killed via {@code DELETE /test} or {@code POST /test/abort}. */
    ABORTED,

    /**
     * Run was gracefully stopped via
     * {@code POST /api/v1/test/drain} (JMeter TCP shutdown port → in-flight
     * samplers complete → clean exit). Distinct from {@link #COMPLETED}
     * (operator chose to stop) and {@link #ABORTED} (forced stop). Counts
     * as a successful terminal state for run-level pass/fail rollups.
     */
    DRAINED;

    /** True for {@link #COMPLETED}, {@link #FAILED}, {@link #ABORTED}, {@link #DRAINED}. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABORTED || this == DRAINED;
    }

    /**
     * True when a test occupies the orchestrator (anything other than IDLE
     * or a terminal state). Drives {@code TestRunGate.isRunning()}, which
     * in turn drives the 409s on the upload + delete endpoints.
     */
    public boolean isActive() {
        return this != IDLE && !isTerminal();
    }
}
