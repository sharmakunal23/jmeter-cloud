package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.model.WorkerMetricEntry;
import com.perf.orchestrator.model.JtlRow;
import org.HdrHistogram.Histogram;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates {@link JtlRow} data for one {@code (label, windowSecond)} pair and
 * flushes it as a {@link WorkerMetricEntry}. Package-private: callers reach the
 * aggregation layer only through {@link TumblingWindowAggregator}, which supplies
 * the envelope-level identity this bucket deliberately does not carry.
 *
 * <p>Percentiles come from an HDR {@link Histogram} tracking up to 3,600,000 ms
 * — enough for JMeter's multi-minute connection-timeout rows — at <b>2</b>
 * significant digits, which is a deliberate memory-over-precision trade. Two
 * digits pre-allocate a ~16 KB counts array against ~104 KB at three; with a
 * fresh bucket per label per second, 100 active labels cut allocation churn from
 * ~10 MB/s to ~1.6 MB/s and live footprint from ~31 MB to ~5 MB, which is what
 * makes a 12-hour run viable. The resulting ≤1% error is invisible on a
 * dashboard; a caller needing the true extreme reads
 * {@link WorkerMetricEntry#rawMaxMs()}, which is tracked raw.
 *
 * <p>Counters are plain {@code long} — this class is single-threaded.
 */
final class SecondBucket {

    /**
     * Maximum elapsed time the histogram will track without clamping.
     * 1 hour covers all realistic JMeter elapsed values including timeout rows.
     */
    private static final long HIGHEST_TRACKABLE_VALUE_MS = 3_600_000L;

    /**
     * Significant decimal digits in histogram values.
     * 2 digits gives ≤ 1% error at any recorded value and keeps the
     * pre-allocated counts array ~6.5× smaller than 3 digits (~16 KB vs ~104 KB).
     * See the class-level "Percentile computation" note for the full rationale.
     * Values ≤ {@code 2 * 10^2 = 200} ms are still recorded at exact unit
     * resolution; above that, percentiles/maxMs may over-report by up to 1%
     * (rawMaxMs stays exact).
     */
    private static final int SIGNIFICANT_DIGITS = 2;

    // -----------------------------------------------------------------------
    // Window identity
    // -----------------------------------------------------------------------

    private final long   windowSecond;
    private final String windowTimestamp; // raw string from JTL, e.g. "2025/04/13 14:32:07"
    private final String label;

    // -----------------------------------------------------------------------
    // Counters — plain longs, single-threaded access only
    // -----------------------------------------------------------------------

    private long requestCount;
    private long errorCount;
    private long bytesReceived;
    private long bytesSent;
    private long lastActiveThreads;

    /**
     * Running sum of elapsed times (ms) across all rows in this window.
     * Tracked alongside the HDRHistogram so the flush can emit a TRUE
     * mean ({@code sumElapsedMs / requestCount}). The histogram's
     * percentiles cannot reconstruct the mean — they're a different
     * statistical surface. Sum is in raw (unclamped) elapsed
     * milliseconds; for outlier rows the histogram clamps to its
     * ceiling but the sum still reflects the true total. This
     * matches operator intuition that a single 5-second outlier in an
     * otherwise 10-ms second should pull the average up.
     *
     * <p>Field added 2026-05-10 (HM-1A) for the Avg Response Time chart.
     * Since SCHEMA-OPT Phase 2 (2026-07-30) it is also emitted on the wire and
     * stored verbatim — it stopped being a private intermediate and became the
     * response-time fact, with {@code avgRespTimeMs} demoted to a derived
     * convenience. Sums fold across workers/labels/time exactly; means do not.
     */
    private long sumElapsedMs;

    /**
     * True maximum elapsed time across all rows in this window, without clamping.
     * Tracked separately from {@link #histogram} because HDRHistogram clamps values
     * that exceed {@code highestTrackableValue} — the histogram's {@code maxValue}
     * would silently under-report very long timeout rows.
     */
    private long rawMaxMs;

    /** Frequency of each response code string seen in this window. */
    private final Map<String, Long> statusCodes;

    /** Records elapsed times for percentile computation. */
    private final Histogram histogram;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    SecondBucket(long windowSecond, String windowTimestamp, String label) {
        this.windowSecond    = windowSecond;
        this.windowTimestamp = windowTimestamp;
        this.label           = label;
        this.statusCodes     = new HashMap<>();
        this.histogram       = new Histogram(HIGHEST_TRACKABLE_VALUE_MS, SIGNIFICANT_DIGITS);
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    /**
     * Incorporates one {@link JtlRow} into this bucket's accumulated state.
     *
     * <p>The elapsed time is clamped to {@link #HIGHEST_TRACKABLE_VALUE_MS}
     * before recording to the histogram — HDRHistogram throws if a value
     * exceeds {@code highestTrackableValue}, which would crash the poll loop.
     * Clamping is the correct response: the value is still counted in
     * {@link #requestCount} and the true value is preserved in {@link #rawMaxMs}.
     *
     * @param row a row whose {@code epochSecond} matches this bucket's
     *            {@code windowSecond} and whose {@code label} matches this
     *            bucket's {@code label}
     */
    void record(JtlRow row) {
        requestCount++;
        if (row.isError()) errorCount++;

        long elapsed = row.elapsedMs();

        // Track true max before clamping — HDRHistogram.getMaxValue() would return
        // the clamped ceiling (3,600,000ms) for timeout rows, losing the real value
        if (elapsed > rawMaxMs) rawMaxMs = elapsed;

        // Sum the raw (unclamped) elapsed so the eventual avgRespTimeMs
        // reflects outliers. Worth noting: at our worst-case row rate
        // (333 rps × 1 hour timeout per sample) sum stays well within
        // long range — no overflow risk in practice.
        sumElapsedMs += elapsed;

        // Clamp before recording to avoid IllegalArgumentException from HDRHistogram
        histogram.recordValue(Math.min(elapsed, HIGHEST_TRACKABLE_VALUE_MS));

        statusCodes.merge(row.responseCode(), 1L, Long::sum);

        bytesReceived     += row.bytes();
        bytesSent         += row.sentBytes();
        lastActiveThreads  = row.allThreads();
    }

    // -----------------------------------------------------------------------
    // Flush
    // -----------------------------------------------------------------------

    /**
     * Produces a {@link WorkerMetricEntry} wire record from this bucket's accumulated
     * state. Only label-scoped fields are populated — the envelope-level identity
     * (workerId, region, runId, windowSecond, windowTimestamp) is supplied by
     * {@link TumblingWindowAggregator} when it groups entries into a
     * {@link com.perf.orchestrator.model.WorkerMetricBatch} envelope.
     *
     * <p>This method may be called at most once per bucket. After calling it the
     * bucket's internal state is no longer meaningful — the histogram is not
     * reset, and counters are not cleared.
     *
     * @return complete per-label metric snapshot for this window
     */
    WorkerMetricEntry toMetricEntry() {
        double errorRate = requestCount > 0
                ? (double) errorCount / requestCount
                : 0.0;

        // True mean elapsed: sum / count. Distinct from p50 — moves with
        // outliers, where percentiles do not.
        double avgRespTimeMs = requestCount > 0
                ? (double) sumElapsedMs / requestCount
                : 0.0;

        return new WorkerMetricEntry(
                label,
                requestCount,
                errorCount,
                errorRate,
                avgRespTimeMs,
                sumElapsedMs,                // exact total — what the consumer stores (SCHEMA-OPT Phase 2)
                histogram.getValueAtPercentile(50.0),
                histogram.getValueAtPercentile(90.0),
                histogram.getValueAtPercentile(95.0),
                histogram.getValueAtPercentile(99.0),
                histogram.getMinValue(),
                clampedMaxMs(),              // see method doc — guards against bucket overshoot
                rawMaxMs,                    // unclamped true maximum
                bytesReceived,
                bytesSent,
                Map.copyOf(statusCodes),
                lastActiveThreads);
    }

    /**
     * Returns the histogram's max value, guaranteed not to exceed
     * {@link #HIGHEST_TRACKABLE_VALUE_MS}.
     *
     * <p>HDRHistogram's {@code getMaxValue()} returns the highest-equivalent
     * value of the <em>bucket</em> containing the recorded maximum, not the
     * recorded value itself. At 3 significant digits, the bucket containing
     * 3,600,000ms has an upper bound of roughly 3,600,383ms — so
     * {@code getMaxValue()} can legitimately return a value slightly above
     * our configured ceiling.
     *
     * <p>Two-part defence:
     * <ol>
     *   <li>{@code getMaxValueAsDouble()} returns the recorded integer value
     *       (as a double) rather than the bucket upper bound — this alone
     *       fixes the common case.</li>
     *   <li>{@code Math.min(..., HIGHEST_TRACKABLE_VALUE_MS)} caps the result
     *       at the ceiling regardless of how HdrHistogram implements its
     *       bucket math, making the post-condition
     *       {@code maxMs ≤ HIGHEST_TRACKABLE_VALUE_MS} explicit and
     *       implementation-independent.</li>
     * </ol>
     *
     * <p>Consumers needing the true unclamped maximum (e.g. for outlier
     * detection) should use {@link WorkerMetricEntry#rawMaxMs()}.
     */
    private double clampedMaxMs() {
        if (requestCount == 0) return 0.0;
        return Math.min(histogram.getMaxValueAsDouble(), (double) HIGHEST_TRACKABLE_VALUE_MS);
    }

    // -----------------------------------------------------------------------
    // Accessors used by TumblingWindowAggregator
    // -----------------------------------------------------------------------

    long windowSecond()    { return windowSecond; }
    String windowTimestamp(){ return windowTimestamp; }
    long requestCount()    { return requestCount; }
}