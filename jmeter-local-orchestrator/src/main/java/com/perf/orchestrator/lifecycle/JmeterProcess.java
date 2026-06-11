package com.perf.orchestrator.lifecycle;

import java.time.Duration;
import java.util.Optional;

/**
 * Handle on a running JMeter child process.
 *
 * <p>Decoupled from {@link java.lang.Process} so {@link TestRunManager}
 * tests can substitute a fake without spawning a real subprocess. The
 * production implementation lives in {@link JmeterProcessManager}; the
 * test impl is a small in-memory state machine.
 */
public interface JmeterProcess {

    /** OS-level process id. Surfaced via {@code GET /api/v1/test}. */
    long pid();

    boolean isAlive();

    /** Sends SIGTERM (or the platform equivalent). Idempotent. */
    void sigterm();

    /** Sends SIGKILL (or the platform equivalent). Idempotent. */
    void sigkill();

    /**
     * Blocks for up to {@code timeout} for the process to exit.
     *
     * @return the exit code if the process terminated within the window,
     *         empty if it is still alive
     */
    Optional<Integer> awaitExit(Duration timeout) throws InterruptedException;
}
