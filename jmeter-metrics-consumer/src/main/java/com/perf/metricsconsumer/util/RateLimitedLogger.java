package com.perf.metricsconsumer.util;

import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key rate-limited WARN/ERROR sink. Wraps an SLF4J {@link Logger}; a
 * given {@code key} is allowed at most one log line per
 * {@code minIntervalMs}. Suppressed lines are counted; on the next allowed
 * emission, the prefix carries "(N suppressed in last X ms)" so the
 * operator can see the true error rate without paying its log volume.
 *
 * <p>Why this exists: a downstream client that misbehaves (sends bad Avro,
 * hammers a 4xx endpoint, etc) can produce thousands of identical WARN
 * lines per second. Each line is ~0.5 KB in Logback's async queue. With a
 * bounded heap, that's a fast OOM vector — the consumer dies not because
 * the writer is slow but because the log buffer ate the heap.
 *
 * <p>This class trades log fidelity for liveness: in steady-state, the
 * one-in-N-windows sampling preserves the diagnostic message + the count;
 * the operator still sees what's failing without the consumer crashing.
 *
 * <p><b>Thread-safety:</b> the per-key state is held in a
 * {@link ConcurrentHashMap}; {@link AtomicLong} CAS handles the
 * "first-in-the-window-wins" race. No external locking needed.
 *
 * <p><b>Memory:</b> one entry per distinct key. Use stable keys (a small
 * fixed set: "INGEST_BAD_AVRO", "KAFKA_DESER_FAIL", "DLT_RECEIVED") — do
 * NOT key on per-message values like runId or workerId, or the map will
 * grow unbounded.
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
