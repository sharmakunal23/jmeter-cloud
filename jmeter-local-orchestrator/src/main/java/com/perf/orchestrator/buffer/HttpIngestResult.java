package com.perf.orchestrator.buffer;

/**
 * Outcome of a {@link HttpIngestClient#send} call. Maps the ingest response
 * codes onto three buffer-state transitions.
 *
 * @param outcome    the dispatcher's next action
 * @param statusCode HTTP status code (or {@code 0} for network errors / timeouts)
 * @param detail     short human-readable note for logs / counters
 */
public record HttpIngestResult(Outcome outcome, int statusCode, String detail) {

    public enum Outcome {
        /** 202 — envelope accepted; dispatcher should delete from buffer. */
        ACCEPTED,
        /**
         * 400 / 413 / 415 / 405 — the consumer says the request is malformed
         * (unknown group, bad body, wrong media type).
         * Retry won't help. Dispatcher should delete from buffer (data is lost,
         * but keeping it on disk wastes space) and bump a counter so operators
         * see the rejection.
         */
        TERMINAL_REJECT,
        /**
         * 401 / 403 — the consumer refused the {@code Authorization} header.
         * The data is fine; the token is missing or rotated. Dispatcher keeps
         * the envelope on disk and pauses posting for its auth-retry interval.
         */
        AUTH_REJECT,
        /**
         * Any other HTTP status (5xx, 429, network failure, timeout).
         * Dispatcher should leave the envelope on disk so the retry sweeper
         * picks it up later.
         */
        RETRY
    }

    /** Convenience for tests / clear call sites. */
    public static HttpIngestResult accepted() {
        return accepted(202);
    }

    public static HttpIngestResult accepted(int statusCode) {
        return new HttpIngestResult(Outcome.ACCEPTED, statusCode, "ACCEPTED");
    }

    public static HttpIngestResult authReject(int statusCode, String detail) {
        return new HttpIngestResult(Outcome.AUTH_REJECT, statusCode, detail);
    }

    public static HttpIngestResult terminalReject(int statusCode, String detail) {
        return new HttpIngestResult(Outcome.TERMINAL_REJECT, statusCode, detail);
    }

    public static HttpIngestResult retry(int statusCode, String detail) {
        return new HttpIngestResult(Outcome.RETRY, statusCode, detail);
    }
}
