package com.perf.orchestrator.model;

import java.util.Objects;

/**
 * Immutable representation of a single row from a JMeter JTL result file.
 *
 * <p>Column order matches the default JMeter JTL CSV layout with timestamp
 * format {@code yyyy/MM/dd HH:mm:ss}. This format has second-level precision,
 * making the raw timestamp string the natural window key — no arithmetic
 * needed to bucket rows into one-second aggregation windows.
 *
 * <p>The {@code epochSecond} field is pre-computed by {@code JtlRowParser}
 * from the raw timestamp string so downstream aggregation never parses
 * dates in the hot path.
 *
 * <p>Field order mirrors the JTL column order so the parser can be read
 * alongside this record without cross-referencing documentation.
 */
public record JtlRow(

        /** Raw timestamp string exactly as written by JMeter: {@code yyyy/MM/dd HH:mm:ss}. */
        String rawTimestamp,

        /** Unix epoch second derived from {@code rawTimestamp}. Cached here to avoid repeated parsing. */
        long epochSecond,

        /** Total elapsed time in milliseconds for this request (start to last byte received). */
        long elapsedMs,

        /** JMeter sampler label — the primary grouping key for all aggregation. */
        String label,

        /**
         * HTTP response code as a string. JMeter may write non-numeric values such as
         * {@code "Non HTTP response code: java.net.SocketTimeoutException"} for
         * connection-level failures. All callers must handle the non-numeric case.
         */
        String responseCode,

        String responseMessage,
        String threadName,
        String dataType,

        /**
         * JMeter's authoritative signal for whether the request succeeded.
         * This flag is the primary basis for {@link #isError()}.
         * A row may have {@code success=false} with a 200 response code when
         * a JMeter assertion (e.g. response body check) fails.
         */
        boolean success,

        String failureMessage,
        long bytes,
        long sentBytes,
        int grpThreads,
        int allThreads,
        String url,
        long latencyMs,
        long idleTimeMs,
        long connectMs

) {

    // -----------------------------------------------------------------------
    // Compact constructor — validation only, Java assigns fields after this block
    // -----------------------------------------------------------------------

    public JtlRow {
        Objects.requireNonNull(rawTimestamp, "rawTimestamp cannot be null");
        Objects.requireNonNull(label,        "label cannot be null — it is the primary aggregation key");
        Objects.requireNonNull(responseCode, "responseCode cannot be null");

        if (elapsedMs < 0) {
            throw new IllegalArgumentException(
                    "elapsedMs cannot be negative — a negative elapsed time would corrupt " +
                    "HDRHistogram percentile calculations. Got: " + elapsedMs);
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs cannot be negative. Got: " + latencyMs);
        }
        if (epochSecond <= 0) {
            throw new IllegalArgumentException(
                    "epochSecond must be positive — a zero or negative value indicates a " +
                    "timestamp parse failure and would corrupt window keying. Got: " + epochSecond);
        }
    }

    // -----------------------------------------------------------------------
    // Error classification — behaviour callers depend on
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if JMeter considered this request a failure.
     *
     * <p>This is the authoritative error signal. A request is an error if
     * JMeter's {@code success} flag is {@code false}, which covers both HTTP
     * error codes and failed JMeter assertions (e.g. a 200 response whose body
     * did not match an expected pattern).
     */
    public boolean isError() {
        return !success;
    }

    /**
     * Returns {@code true} if the HTTP response code is in the 4xx range.
     *
     * <p>Safe to call on any row regardless of response code format — returns
     * {@code false} (not an exception) when {@code responseCode} is non-numeric.
     */
    public boolean isHttpClientError() {
        int code = numericCodeOrMinusOne();
        return code >= 400 && code < 500;
    }

    /**
     * Returns {@code true} if the HTTP response code is in the 5xx range.
     *
     * <p>Safe to call on any row regardless of response code format.
     */
    public boolean isHttpServerError() {
        int code = numericCodeOrMinusOne();
        return code >= 500 && code < 600;
    }

    /**
     * Returns {@code true} if the HTTP response code is in the 2xx range.
     *
     * <p>Note: a 2xx response code does not imply {@code !isError()} — a JMeter
     * assertion can mark a 200 as failed. Use {@link #isError()} to determine
     * overall request success; use this method only for HTTP code classification.
     */
    public boolean isHttpSuccess() {
        int code = numericCodeOrMinusOne();
        return code >= 200 && code < 300;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Parses {@code responseCode} as an integer using a character scan.
     * Returns {@code -1} for non-numeric response codes (e.g. JMeter connection errors)
     * so all public classification methods remain exception-free and allocation-free
     * on the non-HTTP code path — important at high error rates.
     */
    private int numericCodeOrMinusOne() {
        String code = responseCode;
        if (code == null || code.isEmpty()) return -1;

        int result = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') return -1;
            result = result * 10 + (c - '0');
        }
        return result;
    }
}
