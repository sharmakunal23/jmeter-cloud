package com.perf.metricsconsumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One per-label aggregate inside a {@link WorkerMetricBatch}, carrying only the
 * numbers — envelope-level identity lives on the batch and is projected onto
 * each row at insert time.
 *
 * <p>{@code statusCodes} may be null on the wire and the writer reads null as an
 * empty map. {@code sumElapsedMs} is boxed on purpose — see
 * {@link #resolvedSumElapsedMs()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerMetricEntry(
        String label,
        long throughput,
        long errorCount,
        double errorRate,
        double avgRespTimeMs,
        Long sumElapsedMs,
        double p50Ms,
        double p90Ms,
        double p95Ms,
        double p99Ms,
        double minMs,
        double maxMs,
        long rawMaxMs,
        long bytesReceived,
        long bytesSent,
        Map<String, Long> statusCodes,
        long activeThreads) {

    /**
     * The exact total elapsed milliseconds for this window, falling back to
     * {@code round(avgRespTimeMs × throughput)} when the producer predates the
     * field — so a rolling upgrade degrades to the old schema's precision
     * rather than to zeroes.
     *
     * <p>That fallback is why the component is {@code Long} and not
     * {@code long}. Zero is a legitimate sum, so a primitive would make
     * "absent" and "genuinely zero" indistinguishable and silently zero out the
     * response time of every row from an older worker.
     */
    public long resolvedSumElapsedMs() {
        if (sumElapsedMs != null) return sumElapsedMs;
        return Math.round(avgRespTimeMs * throughput);
    }
}
