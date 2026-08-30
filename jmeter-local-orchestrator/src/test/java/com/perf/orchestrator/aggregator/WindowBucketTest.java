package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.model.WorkerMetricEntry;
import com.perf.orchestrator.model.JtlRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("SecondBucket")
class SecondBucketTest {

    private static final long   WINDOW_SECOND    = 1_744_554_727L;
    private static final String WINDOW_TIMESTAMP = "2025/04/13 14:32:07";
    private static final String LABEL            = "POST /api/payment";
    private static final String THREAD           = "jmeter-worker-0 1-1";
    private static final String URL              = "https://app/api/payment";

    private SecondBucket bucket;

    @BeforeEach
    void setUp() {
        bucket = new SecondBucket(WINDOW_SECOND, WINDOW_TIMESTAMP, LABEL);
    }

    // -----------------------------------------------------------------------
    // Row fixtures
    // -----------------------------------------------------------------------

    private static JtlRow row(long elapsedMs, String responseCode, boolean success) {
        return new JtlRow(WINDOW_TIMESTAMP, WINDOW_SECOND, elapsedMs, LABEL,
                responseCode, "OK", THREAD, "text", success,
                success ? "" : "assertion failed",
                1024L, 512L, 80, 80, URL, elapsedMs - 2, 0L, 12L);
    }

    private static JtlRow successRow(long elapsedMs) {
        return row(elapsedMs, "200", true);
    }

    private static JtlRow errorRow(long elapsedMs, String code) {
        return row(elapsedMs, code, false);
    }

    // -----------------------------------------------------------------------
    // Accumulation behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("accumulation")
    class Accumulation {

        @Test
        @DisplayName("throughput equals the number of rows recorded")
        void throughput_equals_row_count() {
            bucket.record(successRow(100));
            bucket.record(successRow(200));
            bucket.record(successRow(300));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertThat(entry.throughput()).isEqualTo(3L);
        }

        @Test
        @DisplayName("accumulates bytes received and bytes sent across all rows")
        void accumulates_bytes_across_rows() {
            // Each row: bytes=1024, sentBytes=512
            bucket.record(successRow(100));
            bucket.record(successRow(200));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertSoftly(softly -> {
                softly.assertThat(entry.bytesReceived()).isEqualTo(2048L);
                softly.assertThat(entry.bytesSent()).isEqualTo(1024L);
            });
        }

        @Test
        @DisplayName("activeThreads reflects the allThreads value from the last recorded row")
        void active_threads_reflects_last_row() {
            // First row: 40 threads; last row: 80 threads
            JtlRow first = new JtlRow(WINDOW_TIMESTAMP, WINDOW_SECOND, 100L, LABEL,
                    "200", "OK", THREAD, "text", true, "", 1024L, 512L, 40, 40, URL, 98L, 0L, 2L);
            JtlRow last = new JtlRow(WINDOW_TIMESTAMP, WINDOW_SECOND, 200L, LABEL,
                    "200", "OK", THREAD, "text", true, "", 1024L, 512L, 80, 80, URL, 198L, 0L, 2L);

            bucket.record(first);
            bucket.record(last);

            assertThat(bucket.toMetricEntry().activeThreads())
                    .as("activeThreads must reflect the last row, not the first or an average")
                    .isEqualTo(80L);
        }
    }

    // -----------------------------------------------------------------------
    // Error rate behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("error rate")
    class ErrorRate {

        @Test
        @DisplayName("errorRate is 0.0 when all rows succeed")
        void error_rate_is_zero_when_no_errors() {
            bucket.record(successRow(100));
            bucket.record(successRow(200));

            assertThat(bucket.toMetricEntry().errorRate())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("errorRate is 1.0 when all rows fail")
        void error_rate_is_one_when_all_fail() {
            bucket.record(errorRow(4000, "503"));
            bucket.record(errorRow(5000, "503"));

            assertThat(bucket.toMetricEntry().errorRate())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("errorRate is computed correctly for a mixed window")
        void error_rate_computed_correctly_for_mixed_window() {
            // 1 error in 10 requests = 0.1
            for (int i = 0; i < 9; i++) bucket.record(successRow(100));
            bucket.record(errorRow(503, "503"));

            assertThat(bucket.toMetricEntry().errorRate())
                    .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("errorCount tracks JMeter success=false rows independently of HTTP code")
        void error_count_uses_success_flag_not_response_code() {
            // success=false on a 200 (assertion failure) must count as an error
            bucket.record(row(200, "200", false));
            // success=true on a non-2xx would not count (unusual but possible with custom assertions)
            bucket.record(row(150, "404", true));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertThat(entry.errorCount())
                    .as("errorCount must reflect success=false rows, not HTTP code range")
                    .isEqualTo(1L);
        }
    }

    // -----------------------------------------------------------------------
    // Average response time (HM-1A) — TRUE mean, not P50
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("avgRespTimeMs (HM-1A)")
    class AverageResponseTime {

        @Test
        @DisplayName("zero throughput → 0.0 (no division by zero)")
        void empty_bucket_avg_is_zero() {
            assertThat(bucket.toMetricEntry().avgRespTimeMs())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("JI-7 — every double field is FINITE even on a zero-sample bucket (JSON cannot carry NaN/Infinity)")
        void all_doubles_finite_on_empty_bucket() {
            WorkerMetricEntry e = bucket.toMetricEntry();
            assertSoftly(softly -> {
                softly.assertThat(Double.isFinite(e.errorRate())).as("errorRate").isTrue();
                softly.assertThat(Double.isFinite(e.avgRespTimeMs())).as("avgRespTimeMs").isTrue();
                softly.assertThat(Double.isFinite(e.p50Ms())).as("p50Ms").isTrue();
                softly.assertThat(Double.isFinite(e.p90Ms())).as("p90Ms").isTrue();
                softly.assertThat(Double.isFinite(e.p95Ms())).as("p95Ms").isTrue();
                softly.assertThat(Double.isFinite(e.p99Ms())).as("p99Ms").isTrue();
                softly.assertThat(Double.isFinite(e.minMs())).as("minMs").isTrue();
                softly.assertThat(Double.isFinite(e.maxMs())).as("maxMs").isTrue();
            });
        }

        @Test
        @DisplayName("uniform window → mean equals the per-row elapsed")
        void uniform_window_mean_equals_row_value() {
            for (int i = 0; i < 5; i++) bucket.record(successRow(100));
            assertThat(bucket.toMetricEntry().avgRespTimeMs())
                    .isEqualTo(100.0);
        }

        @Test
        @DisplayName("SCHEMA-OPT Phase 2 — sumElapsedMs is the exact total, where the mean is not (F11)")
        void sum_is_exact_where_the_mean_rounds() {
            // 3 samples of 10 ms: the mean is 10/3 ms, which no double can hold
            // exactly, so the old schema's avgRespTimeMs × throughput could not
            // round-trip. The sum can, and is what the consumer now stores.
            bucket.record(successRow(3));
            bucket.record(successRow(3));
            bucket.record(successRow(4));

            WorkerMetricEntry e = bucket.toMetricEntry();
            assertThat(e.sumElapsedMs()).as("exact, integral").isEqualTo(10L);
            // The consumer's backward-compat fallback still recovers the total
            // here — at these magnitudes the double error is far below 0.5 — but
            // it is a reconstruction, and the wire now carries the real thing.
            assertThat(Math.round(e.avgRespTimeMs() * e.throughput())).isEqualTo(e.sumElapsedMs());
        }

        @Test
        @DisplayName("sumElapsedMs counts unclamped elapsed, so a timeout outlier is not lost")
        void sum_uses_unclamped_elapsed() {
            bucket.record(successRow(10));
            bucket.record(successRow(4_000_000));   // beyond the histogram ceiling

            // The histogram clamps to 3,600,000 for percentiles; the sum does not.
            assertThat(bucket.toMetricEntry().sumElapsedMs()).isEqualTo(4_000_010L);
        }

        @Test
        @DisplayName("mixed window → mean is sum / count, NOT the median")
        void mean_uses_sum_over_count_not_p50() {
            // 4 fast requests + 1 outlier. sum = 5*4 + 1000 = 1020; count = 5;
            // mean = 204. P50 (median) of {5, 5, 5, 5, 1000} would be 5 — very
            // different. The whole point of HM-1A is that the chart should
            // reflect the outlier, not stay flat at the median.
            for (int i = 0; i < 4; i++) bucket.record(successRow(5));
            bucket.record(successRow(1000));

            WorkerMetricEntry entry = bucket.toMetricEntry();
            assertThat(entry.avgRespTimeMs())
                    .as("a single 1000 ms outlier in a 4×5ms window should pull the mean to 204")
                    .isEqualTo(204.0);
            // Sanity: P50 on the same data is the median (5), demonstrating
            // the divergence the user called out.
            assertThat(entry.p50Ms())
                    .as("P50 stays flat at the median; that's why we needed avgRespTimeMs")
                    .isLessThan(entry.avgRespTimeMs());
        }

        @Test
        @DisplayName("outlier beyond the histogram ceiling still contributes to the mean (sum is unclamped)")
        void mean_reflects_unclamped_outlier() {
            // One row well above the 3.6e6 ms histogram ceiling. Histogram
            // would clamp to 3.6e6, but the SUM is taken from the raw
            // elapsed — so the mean reflects the true outlier.
            bucket.record(successRow(100));
            bucket.record(successRow(5_000_000)); // exceeds ceiling

            // mean = (100 + 5_000_000) / 2 = 2_500_050
            assertThat(bucket.toMetricEntry().avgRespTimeMs())
                    .isEqualTo(2_500_050.0);
        }
    }

    // -----------------------------------------------------------------------
    // Status code accumulation behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("status code accumulation")
    class StatusCodeAccumulation {

        @Test
        @DisplayName("counts each distinct response code separately")
        void counts_distinct_response_codes_separately() {
            bucket.record(row(100, "200", true));
            bucket.record(row(100, "200", true));
            bucket.record(row(500, "503", false));

            Map<String, Long> codes = bucket.toMetricEntry().statusCodes();

            assertThat(codes)
                    .containsEntry("200", 2L)
                    .containsEntry("503", 1L);
        }

        @Test
        @DisplayName("accumulates non-HTTP JMeter response code strings without throwing")
        void accumulates_non_http_codes() {
            bucket.record(row(30_000, "Non HTTP response code: java.net.SocketTimeoutException", false));
            bucket.record(row(30_000, "Non HTTP response code: java.net.SocketTimeoutException", false));

            Map<String, Long> codes = bucket.toMetricEntry().statusCodes();

            assertThat(codes)
                    .containsEntry("Non HTTP response code: java.net.SocketTimeoutException", 2L);
        }

        @Test
        @DisplayName("statusCodes map in the entry is unmodifiable — guards against in-flight mutation post-flush")
        void status_codes_map_is_unmodifiable() {
            bucket.record(successRow(100));
            Map<String, Long> codes = bucket.toMetricEntry().statusCodes();

            assertThatThrownBy(() -> codes.put("999", 1L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // -----------------------------------------------------------------------
    // Percentile accuracy behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("percentile accuracy")
    class PercentileAccuracy {

        @Test
        @DisplayName("p50 of a single-value histogram equals that value exactly")
        void p50_of_single_value_is_exact() {
            bucket.record(successRow(187));

            double p50 = bucket.toMetricEntry().p50Ms();

            // HDRHistogram stores integer ms values ≤ 200 exactly at 2 significant
            // digits (unit resolution below 2×10^2); 187 < 200 so p50 is exact.
            assertThat(p50).isEqualTo(187.0);
        }

        @Test
        @DisplayName("min and max reflect the actual extreme values recorded")
        void min_and_max_reflect_extremes() {
            bucket.record(successRow(50));
            bucket.record(successRow(200));
            bucket.record(successRow(1_000));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertSoftly(softly -> {
                // 50 ≤ 200 ms is recorded at exact unit resolution (2 sig digits) → exact.
                softly.assertThat(entry.minMs()).isEqualTo(50.0);
                // 1000 > 200 ms: at 2 significant digits getMaxMs() returns the bucket
                // upper bound, which may over-report by up to 1% (~10 ms). rawMaxMs stays
                // exact; maxMs is allowed this bounded over-report (RELIABILITY Round 6).
                softly.assertThat(entry.maxMs()).isCloseTo(1_000.0,
                        org.assertj.core.data.Offset.offset(12.0));
            });
        }

        @Test
        @DisplayName("very long elapsed times are clamped and counted rather than causing an error")
        void very_long_elapsed_time_is_clamped_not_rejected() {
            // JMeter timeout rows can have elapsed > 60 seconds
            long timeoutMs = 3_700_000L; // beyond HIGHEST_TRACKABLE_VALUE_MS

            bucket.record(successRow(timeoutMs));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            // Must be counted in throughput even if clamped in histogram
            assertThat(entry.throughput()).isEqualTo(1L);
            // maxMs reflects clamped histogram ceiling, not the raw value
            assertThat(entry.maxMs()).isLessThanOrEqualTo(3_600_000.0);
        }

        @Test
        @DisplayName("rawMaxMs is the true unclamped maximum — differs from maxMs when elapsed exceeds histogram ceiling")
        void raw_max_ms_preserves_unclamped_value() {
            long timeoutMs = 3_700_000L; // exceeds 3,600,000ms ceiling

            bucket.record(successRow(timeoutMs));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertSoftly(softly -> {
                // maxMs is clamped — consumers relying on it would miss the true spike
                softly.assertThat(entry.maxMs())
                        .as("maxMs must be clamped to the histogram ceiling")
                        .isLessThanOrEqualTo(3_600_000.0);
                // rawMaxMs retains the true value — use this to detect extreme outliers
                softly.assertThat(entry.rawMaxMs())
                        .as("rawMaxMs must be the true unclamped elapsed value")
                        .isEqualTo(timeoutMs);
            });
        }

        @Test
        @DisplayName("rawMaxMs equals maxMs for normal elapsed times that do not exceed the ceiling")
        void raw_max_ms_equals_max_ms_for_normal_responses() {
            bucket.record(successRow(200));
            bucket.record(successRow(350));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertThat(entry.rawMaxMs())
                    .as("rawMaxMs should equal the normal max when no clamping occurs")
                    .isEqualTo(350L);
        }
    }

    // -----------------------------------------------------------------------
    // Label propagation — only label-scoped fields live on the entry now;
    // the envelope-level identity (workerId, region, runId, windowSecond,
    // windowTimestamp) is supplied by TumblingWindowAggregator.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("label on the entry")
    class LabelPropagation {

        @Test
        @DisplayName("entry carries the bucket's label — the only identity field on the entry")
        void entry_carries_label() {
            bucket.record(successRow(100));

            WorkerMetricEntry entry = bucket.toMetricEntry();

            assertThat(entry.label().toString()).isEqualTo(LABEL);
        }
    }
}
