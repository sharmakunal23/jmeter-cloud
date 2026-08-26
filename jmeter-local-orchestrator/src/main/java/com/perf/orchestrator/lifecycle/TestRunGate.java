package com.perf.orchestrator.lifecycle;

/**
 * Tells callers whether a test is in flight, so the artifact controllers can
 * refuse mutations mid-run without depending on the lifecycle package.
 * {@code TestRunManager} is the production implementation.
 */
@FunctionalInterface
public interface TestRunGate {

    /** {@code true} when a test is in a non-terminal state (PREPARING/STARTING/RUNNING/DRAINING). */
    boolean isRunning();

    /** Never-running default, for contexts wired without a lifecycle. */
    TestRunGate ALWAYS_IDLE = () -> false;
}
