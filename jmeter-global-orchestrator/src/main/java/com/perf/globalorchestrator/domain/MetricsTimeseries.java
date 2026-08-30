package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Bucketed timeseries for one run, served by
 * {@code GET /api/v1/runs/{runId}/timeseries} and read straight from the
 * run's group fact table ({@code <GROUP_ID>_METRICS}, plus the archived-day
 * table for older data): TPS, response time (mean, p95, p99), error
 * percentage and the HTTP class buckets, every point a throughput-weighted
 * fold of the 15-second worker rows inside a {@link #bucketSize}-second bucket
 * (15, 30 or 60 — the Grafana granularity picker's values).
 *
 * <p>{@link #regions} (on {@code byRegion=true}) splits the same series per
 * {@code WORKER.REGION} — hot rows only, since the archived-day table has no
 * worker dimension; {@link #applications} (on {@code byApplication=true})
 * splits them per {@code LABEL.APPLICATION}, the group classifier's value.
 * Both are omitted from the JSON when empty, so the default payload shape is
 * unchanged. {@code fromSecond} / {@code toSecond} are the first and last bucket
 * starts (epoch seconds).
 */
public record MetricsTimeseries(
        String runId,
        int    bucketSize,
        Long   fromSecond,
        Long   toSecond,
        Series series,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Series> regions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Series> applications
) {

    /** Missing maps come back from the cache as null — keep them empty. */
    public MetricsTimeseries {
        if (regions == null) regions = Map.of();
        if (applications == null) applications = Map.of();
    }

    public MetricsTimeseries(String runId, int bucketSize, Long fromSecond, Long toSecond, Series series) {
        this(runId, bucketSize, fromSecond, toSecond, series, Map.of(), Map.of());
    }

    public MetricsTimeseries(String runId, int bucketSize, Long fromSecond, Long toSecond, Series series,
                             Map<String, Series> regions) {
        this(runId, bucketSize, fromSecond, toSecond, series, regions, Map.of());
    }

    /**
     * The series the UI charts. {@code statusCodes} is keyed by HTTP class —
     * {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx}, {@code other} — as
     * counts per second; the schema keeps no per-code detail.
     */
    public record Series(
            List<TimeseriesPoint>              tps,
            List<TimeseriesPoint>              avgRtMs,
            List<TimeseriesPoint>              errorPct,
            Map<String, List<TimeseriesPoint>> statusCodes,
            List<TimeseriesPoint>              p95Ms,
            List<TimeseriesPoint>              p99Ms
    ) {
        public Series {
            if (p95Ms == null) p95Ms = List.of();
            if (p99Ms == null) p99Ms = List.of();
            if (statusCodes == null) statusCodes = Map.of();
        }

        public Series(List<TimeseriesPoint> tps, List<TimeseriesPoint> avgRtMs,
                      List<TimeseriesPoint> errorPct, Map<String, List<TimeseriesPoint>> statusCodes) {
            this(tps, avgRtMs, errorPct, statusCodes, List.of(), List.of());
        }

        public static Series empty() {
            return new Series(List.of(), List.of(), List.of(), Map.of(), List.of(), List.of());
        }
    }

    /** One sample: {@code sec} is the bucket's start (epoch second), {@code v} the value. */
    public record TimeseriesPoint(long sec, double v) { }
}
