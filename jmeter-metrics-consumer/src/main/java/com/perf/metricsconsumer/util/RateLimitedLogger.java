package com.perf.metricsconsumer.util;

import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key rate-limited WARN/ERROR sink allowing one line per {@code key} per
 * {@code minIntervalMs}, with suppressed lines counted and reported as
 * "(N suppressed in last X ms)" on the next emission — so the true error rate
 * stays visible without its log volume.
 *
 * <p>This exists because a misbehaving worker can emit thousands of identical
 * WARNs per second, and at ~0.5 KB each in Logback's async queue that is a fast
 * OOM: the consumer would die of its own logging rather than of the fault.
 *
 * <p><b>Keys must come from a small fixed set</b> — currently
 * {@code METRIC_VALUE_CLAMPED}, {@code INGEST_TOO_LARGE},
 * {@code INGEST_BAD_JSON}, {@code INGEST_DB_DOWN}. Keying on a per-message
 * value such as runId or workerId grows the map without bound. Thread-safety
 * needs no external locking: state lives in a {@link ConcurrentHashMap} and an
 * {@link AtomicLong} CAS settles the first-in-the-window race.
 */
public final class RateLimitedLogger {

    private final Logger delegate;
    private final long minIntervalMs;
    private final ConcurrentHashMap<String, KeyState> stateByKey = new ConcurrentHashMap<>();

    public RateLimitedLogger(Logger delegate, long minIntervalMs) {
        if (minIntervalMs < 0) {
            throw new IllegalArgumentException("minIntervalMs must be >= 0, got: " + minIntervalMs);
        }
        this.delegate = delegate;
        this.minIntervalMs = minIntervalMs;
    }

    /** Logs at WARN if the rate-window has expired for {@code key}; otherwise
     *  bumps the per-key suppression counter for next-emission roll-up. */
    public void warn(String key, String format, Object... args) {
        log(key, /* error */ false, format, args);
    }

    /** Same as {@link #warn} but at ERROR. */
    public void error(String key, String format, Object... args) {
        log(key, /* error */ true, format, args);
    }

    private void log(String key, boolean error, String format, Object[] args) {
        long now = System.currentTimeMillis();
        KeyState state = stateByKey.computeIfAbsent(key, k -> new KeyState(now));
        long lastEmitted = state.lastEmittedMs.get();
        if (now - lastEmitted < minIntervalMs) {
            // Inside the suppression window — bump counter + drop.
            state.suppressed.incrementAndGet();
            return;
        }
        // CAS: only one thread wins the window; losers fall back to suppress-counter.
        if (!state.lastEmittedMs.compareAndSet(lastEmitted, now)) {
            state.suppressed.incrementAndGet();
            return;
        }
        long suppressed = state.suppressed.getAndSet(0);
        long elapsed = now - lastEmitted;
        // Format the user's message first, then prefix the rollup counter
        // (so the original line stays readable in its usual shape).
        if (suppressed > 0) {
            String prefix = "[" + key + "; suppressed " + suppressed + " similar in last "
                    + elapsed + " ms] ";
            if (error) {
                delegate.error(prefix + format, args);
            } else {
                delegate.warn(prefix + format, args);
            }
        } else {
            String prefix = "[" + key + "] ";
            if (error) {
                delegate.error(prefix + format, args);
            } else {
                delegate.warn(prefix + format, args);
            }
        }
    }

    private static final class KeyState {
        final AtomicLong lastEmittedMs;
        final AtomicLong suppressed = new AtomicLong(0);

        KeyState(long now) {
            // Initialise to (now - minIntervalMs - 1) would let the first
            // call always pass; instead initialise to 0 so the first call
            // emits immediately (any wall-clock now > 0 + minIntervalMs).
            this.lastEmittedMs = new AtomicLong(0);
        }
    }
}
