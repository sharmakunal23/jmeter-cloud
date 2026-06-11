package com.perf.orchestrator.buffer;

/**
 * Outcome of a {@link HttpFallbackClient#send} call. Maps the K-4 response
 * codes onto three buffer-state transitions.
 *
 * @param outcome    the dispatcher's next action
 * @param statusCode HTTP status code (or {@code 0} for network errors / timeouts)
 * @param detail     short human-readable note for logs / counters
 */
public record HttpFallbackResult(Outcome outcome, int statusCode, String detail) {

    public enum Outcome {
        /** 202 — envelope accepted; dispatcher should delete from buffer. */
        ACCEPTED,
        /**
         * 400 / 413 — consumer says the payload is malformed for this endpoint.
         * Retry won't help. Dispatcher should delete from buffer (data is lost,
         * but keeping it on disk wastes space) and bump a counter so operators
         * see the rejection.
         */
        TERMINAL_REJECT,
        /**
         * Any other HTTP status (5xx, 429, network failure, timeout).
         * Dispatcher should leave the envelope on disk so the K-3 retry
         * sweeper picks it up later.
         */
        RETRY
    }

    /** Convenience for tests / clear call sites. */
    public static HttpFallbackResult accepted() {
        return new HttpFallbackResult(Outcome.ACCEPTED, 202, "ACCEPTED");
    }

    public static HttpFallbackResult terminalReject(int statusCode, String detail) {
        return new HttpFallbackResult(Outcome.TERMINAL_REJECT, statusCode, detail);
    }

    public static HttpFallbackResult retry(int statusCode, String detail) {
        return new HttpFallbackResult(Outcome.RETRY, statusCode, detail);
    }
}
