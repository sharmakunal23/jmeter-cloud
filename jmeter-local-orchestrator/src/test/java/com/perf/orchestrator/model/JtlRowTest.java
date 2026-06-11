package com.perf.orchestrator.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JtlRow")
class JtlRowTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static final String TIMESTAMP  = "2025/04/13 14:32:07";
    /**
     * The exact Unix epoch second for TIMESTAMP interpreted as UTC.
     * Verified: 2025-01-01T00:00:00Z (1_735_689_600) + 102 days (8_812_800) + 14h32m7s (52_327)
     * = 1_744_554_727. Any other value indicates a timezone bug in the parser.
     */
    private static final long   EPOCH_SEC  = 1_744_554_727L;
    private static final String LABEL      = "POST /api/payment";
    private static final String THREAD     = "jmeter-worker-0 1-1";
    private static final String URL        = "https://app/api/payment";

    /**
     * Builds a row where JMeter marked the request as succeeded, with
     * the given HTTP response code. Simulates the common happy-path case.
     */
    private static JtlRow successRow(String responseCode) {
        return new JtlRow(TIMESTAMP, EPOCH_SEC, 187L, LABEL,
                responseCode, "OK", THREAD, "text", true,
                "", 1024L, 512L, 80, 80, URL, 185L, 0L, 12L);
    }

    /**
     * Builds a row where JMeter marked the request as failed, with
     * the given HTTP response code.
     */
    private static JtlRow failedRow(String responseCode) {
        return new JtlRow(TIMESTAMP, EPOCH_SEC, 4200L, LABEL,
                responseCode, "Service Unavailable", THREAD, "text", false,
                "Response code was " + responseCode, 128L, 512L, 80, 80, URL, 4198L, 0L, 9L);
    }

    /**
     * Builds a row representing a JMeter connection-level failure — no HTTP
     * response code exists; JMeter writes a string description instead.
     */
    private static JtlRow connectionFailureRow() {
        return new JtlRow(TIMESTAMP, EPOCH_SEC, 30_000L, LABEL,
                "Non HTTP response code: java.net.SocketTimeoutException",
                "Connection timed out", THREAD, "text", false,
                "java.net.SocketTimeoutException: Read timed out", 0L, 512L, 80, 80, URL, 29_999L, 0L, 0L);
    }

    // -----------------------------------------------------------------------
    // Error detection behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("error detection")
    class ErrorDetection {

        @Test
        @DisplayName("uses the success flag as the authoritative error signal, not the response code")
        void success_flag_determines_error_status() {
            // JMeter assertions (body checks, duration assertions etc.) can mark a
            // 200 response as failed. The success flag reflects the full assertion
            // result; the response code only tells us what the server returned.
            JtlRow assertionFailedOn200 = failedRow("200");

            assertThat(assertionFailedOn200.isError())
                    .as("success=false on a 200 response must still be treated as an error")
                    .isTrue();
        }

        @Test
        @DisplayName("treats a successful 200 response as not an error")
        void successful_200_is_not_an_error() {
            assertThat(successRow("200").isError()).isFalse();
        }

        @Test
        @DisplayName("treats a connection-level failure as an error even without an HTTP code")
        void connection_failure_is_an_error() {
            assertThat(connectionFailureRow().isError()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // HTTP response code classification behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("HTTP response code classification")
    class HttpCodeClassification {

        @ParameterizedTest(name = "{0} is a client error")
        @ValueSource(strings = {"400", "401", "403", "404", "422", "429", "499"})
        void classifies_4xx_as_client_errors(String code) {
            assertThat(successRow(code).isHttpClientError()).isTrue();
        }

        @ParameterizedTest(name = "{0} is a server error")
        @ValueSource(strings = {"500", "502", "503", "504"})
        void classifies_5xx_as_server_errors(String code) {
            assertThat(successRow(code).isHttpServerError()).isTrue();
        }

        @ParameterizedTest(name = "{0} is an HTTP success")
        @ValueSource(strings = {"200", "201", "202", "204"})
        void classifies_2xx_as_http_success(String code) {
            assertThat(successRow(code).isHttpSuccess()).isTrue();
        }

        @Test
        @DisplayName("a 4xx code is not a server error")
        void client_error_is_not_a_server_error() {
            assertThat(successRow("404").isHttpServerError()).isFalse();
        }

        @Test
        @DisplayName("a 5xx code is not a client error")
        void server_error_is_not_a_client_error() {
            assertThat(successRow("503").isHttpClientError()).isFalse();
        }

        @Test
        @DisplayName("a 2xx code is not a client or server error")
        void success_code_is_not_an_error_code() {
            JtlRow row = successRow("200");

            assertThat(row.isHttpClientError()).isFalse();
            assertThat(row.isHttpServerError()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Non-HTTP response code robustness behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when the response code is a non-HTTP JMeter string")
    class WhenResponseCodeIsNonHttp {

        @Test
        @DisplayName("does not throw when classifying — non-HTTP codes are expected in real JTL files")
        void classification_methods_are_safe_on_non_numeric_codes() {
            // JMeter writes strings like "Non HTTP response code: java.net.SocketTimeoutException"
            // for network-level failures. Any classification method that throws here
            // would crash the aggregator on a perfectly valid row.
            JtlRow row = connectionFailureRow();

            assertThatCode(row::isHttpClientError).doesNotThrowAnyException();
            assertThatCode(row::isHttpServerError).doesNotThrowAnyException();
            assertThatCode(row::isHttpSuccess).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("returns false for all HTTP classifications — non-HTTP is its own category")
        void non_http_code_does_not_match_any_http_category() {
            JtlRow row = connectionFailureRow();

            assertThat(row.isHttpClientError()).isFalse();
            assertThat(row.isHttpServerError()).isFalse();
            assertThat(row.isHttpSuccess()).isFalse();
        }

        @Test
        @DisplayName("is still detected as an error via the success flag")
        void non_http_failure_is_detected_as_error() {
            assertThat(connectionFailureRow().isError()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Construction guardrail behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("construction guardrails")
    class ConstructionGuardrails {

        @Test
        @DisplayName("rejects null label because it is the primary aggregation key")
        void rejects_null_label() {
            // A null label would silently group all unlabelled requests together and
            // produce a nonsensical "null" metric in Kafka — reject it immediately.
            assertThatThrownBy(() ->
                    new JtlRow(TIMESTAMP, EPOCH_SEC, 187L, null,
                            "200", "OK", THREAD, "text", true,
                            "", 1024L, 512L, 80, 80, URL, 185L, 0L, 12L)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null rawTimestamp because it is the window key")
        void rejects_null_raw_timestamp() {
            assertThatThrownBy(() ->
                    new JtlRow(null, EPOCH_SEC, 187L, LABEL,
                            "200", "OK", THREAD, "text", true,
                            "", 1024L, 512L, 80, 80, URL, 185L, 0L, 12L)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects negative elapsed time because it corrupts HDRHistogram percentiles")
        void rejects_negative_elapsed_ms() {
            // HDRHistogram requires non-negative values. A -1 elapsed that slips
            // through would either throw during recording or skew all percentiles.
            assertThatThrownBy(() ->
                    new JtlRow(TIMESTAMP, EPOCH_SEC, -1L, LABEL,
                            "200", "OK", THREAD, "text", true,
                            "", 1024L, 512L, 80, 80, URL, 185L, 0L, 12L)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("elapsedMs");
        }

        @Test
        @DisplayName("rejects negative latency for the same reason as elapsed")
        void rejects_negative_latency_ms() {
            assertThatThrownBy(() ->
                    new JtlRow(TIMESTAMP, EPOCH_SEC, 187L, LABEL,
                            "200", "OK", THREAD, "text", true,
                            "", 1024L, 512L, 80, 80, URL, -1L, 0L, 12L)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("latencyMs");
        }

        @Test
        @DisplayName("rejects zero or negative epochSecond because it indicates a timestamp parse failure")
        void rejects_invalid_epoch_second() {
            assertThatThrownBy(() ->
                    new JtlRow(TIMESTAMP, 0L, 187L, LABEL,
                            "200", "OK", THREAD, "text", true,
                            "", 1024L, 512L, 80, 80, URL, 185L, 0L, 12L)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("epochSecond");
        }

        @Test
        @DisplayName("accepts zero elapsed time — a sub-millisecond response rounds to zero")
        void accepts_zero_elapsed_ms() {
            // Cached or in-process responses can return in under 1ms.
            // Zero is legitimate and should not be rejected.
            assertThatCode(() ->
                    new JtlRow(TIMESTAMP, EPOCH_SEC, 0L, LABEL,
                            "200", "OK", THREAD, "text", true,
                            "", 1024L, 512L, 80, 80, URL, 0L, 0L, 0L)
            ).doesNotThrowAnyException();
        }
    }
}
