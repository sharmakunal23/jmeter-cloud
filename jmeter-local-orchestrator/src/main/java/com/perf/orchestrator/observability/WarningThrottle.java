package com.perf.orchestrator.observability;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bounds the volume of a repetitive warning so a systematic per-row problem
 * (e.g. a JTL whose columns drifted from the orchestrator's expected layout, or
 * clock skew dropping every row as "late") cannot flood the logs.
 *
 * <h2>Policy</h2>
 * Per rolling {@code windowMs} window, the first {@code burst} occurrences are
 * emitted in full; the rest are counted and suppressed. When a window rolls over
 * (the first occurrence after it expires), a single summary line reports how many
 * were suppressed in the window that just ended. Output is therefore bounded to
 * at most {@code burst + 1} lines per window — but the signal is never fully
 * lost, unlike demoting the warning to a level that's filtered out entirely.
 *
 * <p>Why this matters: the per-row WARNING sites in {@code JtlRowParser} and
 * {@code TumblingWindowAggregator} never fire on a healthy run, but a single
 * format mismatch would otherwise emit one line per row (~250/s/worker) — which
 * wastes CPU/I/O and, worse, churns the container's capped log-rotation ring so
 * fast it evicts every useful diagnostic line.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Designed for a single caller — the poll-loop thread — matching
 * the {@code JtlRowParser} / {@code TumblingWindowAggregator} contract. Each is
 * constructed per run, so throttle state resets each run.
 */
public final class WarningThrottle {

    /** Default: 5 full warnings then a summary, per 60-second window. */
    public static final int  DEFAULT_BURST     = 5;
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    private final int  burst;
    private final long windowMs;
    private final LongSupplier clockMs;

    private long windowStartMs = Long.MIN_VALUE;
    private int  emittedInWindow;
    private long suppressedInWindow;

    /** Production constructor — wall-clock driven, default burst/window. */
    public WarningThrottle() {
        this(DEFAULT_BURST, DEFAULT_WINDOW_MS, System::currentTimeMillis);
    }

    public WarningThrottle(int burst, long windowMs) {
        this(burst, windowMs, System::currentTimeMillis);
    }

    /** Test seam — inject a fake clock so window roll-over is deterministic. */
    WarningThrottle(int burst, long windowMs, LongSupplier clockMs) {
        if (burst < 0) throw new IllegalArgumentException("burst must be >= 0, got: " + burst);
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be > 0, got: " + windowMs);
        this.burst    = burst;
        this.windowMs = windowMs;
        this.clockMs  = clockMs;
    }

    /**
     * Records one occurrence of the throttled event.
     *
     * @param emitFull    invoked for each of the first {@code burst} occurrences
     *                    in the current window — should log the full warning
     * @param emitSummary invoked once when a window rolls over and the prior
     *                    window suppressed at least one occurrence — receives the
     *                    suppressed count so the caller can log a one-line summary
     */
    public void record(Runnable emitFull, Consumer<Long> emitSummary) {
        long now = clockMs.getAsLong();
        if (windowStartMs == Long.MIN_VALUE) {
            windowStartMs = now;
        } else if (now - windowStartMs >= windowMs) {
            if (suppressedInWindow > 0) {
                emitSummary.accept(suppressedInWindow);
            }
            windowStartMs      = now;
            emittedInWindow    = 0;
            suppressedInWindow = 0;
        }
        if (emittedInWindow < burst) {
            emittedInWindow++;
            emitFull.run();
        } else {
            suppressedInWindow++;
        }
    }
}
