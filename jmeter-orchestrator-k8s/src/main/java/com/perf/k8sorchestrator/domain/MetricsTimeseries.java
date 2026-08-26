package com.perf.k8sorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Per-second timeseries for a single run, served by
 * {@code GET /api/v1/runs/{runId}/timeseries} (HM-1). Drives the four
 * native charts in the run-detail Metrics tab (UI-side: HM-3): TPS,
 * average response time, error percentage, and status-code breakdown.
 *
 * <p><b>Bucketing.</b> When the raw second-by-second point count
 * exceeds {@link com.perf.k8sorchestrator.repo.MetricsTimeseriesRepository#BUCKET_TARGET},
 * the points are aggregated server-side into wider buckets so the wire
 * payload stays bounded for long-running tests. {@link #bucketSize()}
 * reports the number of raw seconds per returned point ({@code 1}
 * means no bucketing). Both {@code fromSecond} and {@code toSecond} are
 * always raw Unix epoch seconds.
 *
 * <p><b>Per-region breakdown.</b> When the caller requests
 * {@code ?byRegion=true}, {@link #regions} carries the same four series
 * split per AWS region ({@code us-east-1}, {@code us-west-2}, …) so the
 * UI's "Split by region" toggle can compare regions side by side. The
 * top-level {@link #series} stays the all-regions total in both modes
 * (it's the exact fold of the per-region rows), so the aggregate view
 * never has to be recomputed when the operator flips the toggle. When
 * the breakdown isn't requested {@code regions} is empty and omitted
 * from the JSON ({@link JsonInclude.Include#NON_EMPTY}), leaving the
 * default payload byte-for-byte unchanged.
 */
public record MetricsTimeseries(
        String runId,
        int    bucketSize,
        Long   fromSecond,
        Long   toSecond,
        Series series,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Series> regions
) {

    /**
     * Normalize a missing {@code regions} to an empty map. The field is
     * {@code @JsonInclude(NON_EMPTY)}, so the default (no-breakdown) payload
     * omits it on the wire; deserializing that payload hands the canonical
     * constructor {@code null}. Without this, a cached value round-tripped
     * through Redis comes back as {@code regions=null} (≠ the original
     * {@code regions={}}) and every reader must null-check. Keep it empty.
     */
    public MetricsTimeseries {
        if (regions == null) regions = Map.of();
    }

    /** Convenience constructor for the default (no region breakdown) path. */
    public MetricsTimeseries(String runId, int bucketSize, Long fromSecond, Long toSecond, Series series) {
        this(runId, bucketSize, fromSecond, toSecond, series, Map.of());
    }

    /**
     * The four series the UI consumes. {@code statusCodes} is a map
     * keyed by HTTP status (or whatever JMeter wrote — could be
     * {@code "Non HTTP response code:..."} for non-HTTP samplers).
     */
    public record Series(
            List<TimeseriesPoint>            tps,
            List<TimeseriesPoint>            avgRtMs,
            List<TimeseriesPoint>            errorPct,
            Map<String, List<TimeseriesPoint>> statusCodes
    ) { }

    /**
     * One sample. {@code sec} is a Unix epoch second; {@code v} is the
     * metric value (TPS, ms, percentage, count). Compact name kept on
     * the wire to keep payloads small for long runs.
     */
    public record TimeseriesPoint(long sec, double v) { }
}
