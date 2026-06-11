package com.perf.orchestrator.lifecycle;

/**
 * Tells callers whether a test is currently in flight.
 *
 * <p>Step 6's controllers need to refuse mutations (POST / DELETE) while a
 * run is RUNNING — but {@code TestRunManager} doesn't exist until step 7.
 * Threading the test-state check through this interface keeps step 6 from
 * reaching forward; step 7 will swap the {@link #ALWAYS_IDLE} default
 * for an implementation backed by {@code CurrentRun}.
 */
@FunctionalInterface
public interface TestRunGate {

    /** {@code true} when a test is in a non-terminal state (PREPARING/STARTING/RUNNING/DRAINING). */
    boolean isRunning();

    /**
     * Step 6 default — there is no test lifecycle yet, so nothing is ever
     * running. Replaced in step 7 by {@code CurrentRun::isActive}.
     */
    TestRunGate ALWAYS_IDLE = () -> false;
}
