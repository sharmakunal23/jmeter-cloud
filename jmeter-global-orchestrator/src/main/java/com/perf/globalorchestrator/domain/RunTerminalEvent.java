package com.perf.globalorchestrator.domain;

import java.time.Instant;

/**
 * A run reached a terminal state. Published once per run, by the single caller
 * that wins the terminal-transition claim, so a listener never sees the same
 * run finish twice.
 *
 * <p>Listeners are <b>accelerators, never the mechanism of record</b>: the run's
 * state is already committed when this fires, and anything derived from it must
 * still be reachable by whatever polls or sweeps for it. Losing this event may
 * cost latency; it may never cost correctness.
 */
public record RunTerminalEvent(String runId, RunState state, Instant at) {}
