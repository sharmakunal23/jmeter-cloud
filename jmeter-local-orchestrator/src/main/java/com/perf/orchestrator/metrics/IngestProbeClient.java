package com.perf.orchestrator.metrics;

import java.io.Closeable;
import java.time.Duration;

/**
 * Test seam for {@link IngestReachabilityProbe}. Production wraps an HTTP
 * {@code OPTIONS} request to the metrics-consumer's ingest URL; tests inject
 * a stub that returns canned responses without a running consumer.
 *
 * <p>{@link #checkReachable(Duration)} must be self-contained: any timeout,
 * exception, or connection failure must be translated into a
 * {@link Result#unreachable(String)}, never propagated to the caller.
 * The probe loop relies on this — an exception escaping here would tear
 * down the daemon thread and silently freeze the readiness signal.
 */
public interface IngestProbeClient extends Closeable {

    Result checkReachable(Duration timeout);

    /** Outcome of a single reachability check. */
    record Result(boolean reachable, String reason) {
        public static Result up()                   { return new Result(true, null); }
        public static Result unreachable(String r)  { return new Result(false, r); }
    }
}
