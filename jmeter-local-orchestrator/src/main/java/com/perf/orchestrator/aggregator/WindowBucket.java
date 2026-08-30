package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.model.WorkerMetricEntry;
import com.perf.orchestrator.model.JtlRow;
import org.HdrHistogram.Histogram;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates {@link JtlRow} data for one {@code (label, window)} pair and
 * flushes it as a {@link WorkerMetricEntry}. Package-private: callers reach the
 * aggregation layer only through {@link TumblingWindowAggregator}, which supplies
 * the envelope-level identity this bucket deliberately does not carry.
 *
 * <p>Percentiles come from an HDR {@link Histogram} tracking up to 3,600,000 ms
 * — enough for JMeter's multi-minute connection-timeout rows — at <b>2</b>
 * significant digits, which is a deliberate memory-over-precision trade. Two
 * digits pre-allocate a ~16 KB counts array against ~104 KB at three; with a
 * fresh bucket per label per window, 200 active labels at a 15 s window keep
 * the live footprint near 6 MB. The resulting ≤1% error is invisible on a
 * dashboard; a caller needing the true extreme reads
 * {@link WorkerMetricEntry#rawMaxMs()}, which is tracked raw.
 *
 * <p>Counters are plain {@code long} — this class is single-threaded.
 */
final class WindowBucket {

    /**
     * Maximum elapsed time the histogram will track without clamping.
     * 1 hour covers all realistic JMeter elapsed values including timeout rows.
     */
    private static final long HIGHEST_TRACKABLE_VALUE_MS = 3_600_000L;

    /**
     * Significant decimal digits in histogram values. 2 digits gives ≤ 1%
     * error at any recorded value and keeps the pre-allocated counts array
     * ~6.5× smaller than 3 digits (~16 KB vs ~104 KB). Values ≤ 200 ms are
     * still recorded at exact unit resolution; above that, percentiles/maxMs
     * may over-report by up to 1% (rawMaxMs stays exact).
     */
    private static final int SIGNIFICANT_DIGITS = 2;

    // -----------------------------------------------------------------------
    // Window identity
    // -----------------------------------------------------------------------

    /** Grid-aligned start of the window (epoch second). */
    private final long   windowSecond;
    private final String label;

    // -----------------------------------------------------------------------
    // Counters — plain longs, single-threaded access only
    // -----------------------------------------------------------------------

    private long requestCount;
    private long errorCount;
    private long bytesReceived;
    private long bytesSent;
    /** Highest {@code allThreads} seen in the window — the hosted flush's semantics. */
    private long maxActiveThreads;

    /**
     * Running sum of elapsed times (ms) across all rows in this window — the
     * response-time fact the consumer stores ({@code sumElapsedMs}); the mean
     * is derived from it. Raw (unclamped), so an outlier pulls the average up
     * the way an operator expects; sums fold across workers/labels/time
     * exactly, means do not.
     */
    private long sumElapsedMs;

    /**
     * True maximum elapsed time across all rows in this window, without clamping.
     * Tracked separately because HDRHistogram clamps values above
     * {@code highestTrackableValue} — its {@code maxValue} would under-report a
     * very long timeout row.
     */
    private long rawMaxMs;

    /** Frequency of each response code string seen in this window. */
    private final Map<String, Long> statusCodes;

    /** Records elapsed times for percentile computation. */
    private final Histogram histogram;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    WindowBucket(long windowSecond, String label) {
        this.windowSecond = windowSecond;
        this.label        = label;
        this.statusCodes  = new HashMap<>();
        this.histogram    = new Histogram(HIGHEST_TRACKABLE_VALUE_MS, SIGNIFICANT_DIGITS);
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    /**
     * Incorporates one {@link JtlRow} into this bucket's accumulated state.
     *
     * <p>The elapsed time is clamped to {@link #HIGHEST_TRACKABLE_VALUE_MS}
     * before recording to the histogram — HDRHistogram throws above its
     * ceiling, which would crash the poll loop. The row is still counted and
     * the true value is preserved in {@link #rawMaxMs} and {@link #sumElapsedMs}.
     *
     * @param row a row whose window and label match this bucket's
     */
    void record(JtlRow row) {
        requestCount++;
        if (row.isError()) errorCount++;

        long elapsed = row.elapsedMs();
        if (elapsed > rawMaxMs) rawMaxMs = elapsed;
        sumElapsedMs += elapsed;
        histogram.recordValue(Math.min(elapsed, HIGHEST_TRACKABLE_VALUE_MS));

        statusCodes.merge(row.responseCode(), 1L, Long::sum);

        bytesReceived += row.bytes();
        bytesSent     += row.sentBytes();
        if (row.allThreads() > maxActiveThreads) maxActiveThreads = row.allThreads();
    }

    // -----------------------------------------------------------------------
    // Flush
    // -----------------------------------------------------------------------

    /**
     * Produces the {@link WorkerMetricEntry} wire record for this bucket. Only
     * label-scoped fields are populated — the envelope-level identity
     * (workerId, region, runId, windowSecond) is supplied by
     * {@link TumblingWindowAggregator}. Call at most once per bucket.
     */
    WorkerMetricEntry toMetricEntry() {
        double errorRate = requestCount > 0
                ? (double) errorCount / requestCount
                : 0.0;
        double avgRespTimeMs = requestCount > 0
                ? (double) sumElapsedMs / requestCount
                : 0.0;

        return new WorkerMetricEntry(
                label,
                requestCount,
                errorCount,
                errorRate,
                avgRespTimeMs,
                sumElapsedMs,
                histogram.getValueAtPercentile(50.0),
                histogram.getValueAtPercentile(90.0),
                histogram.getValueAtPercentile(95.0),
                histogram.getValueAtPercentile(99.0),
                histogram.getMinValue(),
                clampedMaxMs(),
                rawMaxMs,
                bytesReceived,
                bytesSent,
                Map.copyOf(statusCodes),
                maxActiveThreads);
    }

    /**
     * The histogram's max, never above {@link #HIGHEST_TRACKABLE_VALUE_MS}:
     * {@code getMaxValue()} returns the containing bucket's upper bound, which
     * can sit slightly above the configured ceiling, so the recorded value is
     * read as a double and capped explicitly.
     */
    private double clampedMaxMs() {
        if (requestCount == 0) return 0.0;
        return Math.min(histogram.getMaxValueAsDouble(), (double) HIGHEST_TRACKABLE_VALUE_MS);
    }

    // -----------------------------------------------------------------------
    // Accessors used by TumblingWindowAggregator
    // -----------------------------------------------------------------------

    long windowSecond() { return windowSecond; }
    long requestCount() { return requestCount; }
}
