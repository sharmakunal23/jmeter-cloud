package com.perf.orchestrator.statemachine;

/**
 * States of the JTL file tailing lifecycle.
 *
 * <pre>
 * WAITING_FOR_FILE ──► RUNNING ──► DRAINING ──► DONE
 *       │                                         ▲
 *       └─────────── (SIGTERM) ───────────────────┘
 * </pre>
 *
 * <p>{@code WAITING_FOR_FILE}: The JTL file does not yet exist or its header
 * has not been fully written. {@link com.perf.orchestrator.io.FilePoller#tryOpen}
 * is retried at {@code fileWaitPollIntervalMs} intervals.
 *
 * <p>{@code RUNNING}: The file is open and rows are being read, aggregated,
 * and published. The state machine polls for new bytes at {@code pollIntervalMs}
 * intervals and checks the sentinel on every iteration.
 *
 * <p>{@code DRAINING}: The sentinel file has appeared (or SIGTERM received),
 * signalling that JMeter has finished. The poller continues reading until
 * {@code drainEmptyPollsThreshold} consecutive empty reads confirm the file
 * is fully consumed.
 *
 * <p>{@code DONE}: All rows have been published and the producer has been
 * flushed. The process exits cleanly.
 */
public enum TailerState {
    WAITING_FOR_FILE,
    RUNNING,
    DRAINING,
    DONE
}
