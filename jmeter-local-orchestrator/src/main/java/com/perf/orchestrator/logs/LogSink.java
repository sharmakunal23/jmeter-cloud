package com.perf.orchestrator.logs;

/**
 * Single-method seam for "give me one line at a time".
 *
 * <p>Used by {@code JmeterProcessManager}'s stdout/stderr drainer to feed
 * the orchestrator's {@link LogTail} ring buffer in addition to SLF4J
 * and the on-disk JMeter log. Keeping it a {@link FunctionalInterface}
 * means tests can substitute a recording lambda without subclassing.
 */
@FunctionalInterface
public interface LogSink {

    void append(String line);

    /** No-op sink — used as the default when the orchestrator is not attached. */
    LogSink NULL = line -> { };
}
