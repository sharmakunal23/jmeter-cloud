package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.MetricsTimeseries.TimeseriesPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only access to {@code metrics."workerMetric"} for the per-run
 * <b>timeseries</b> endpoint (HM-1, served by
 * {@code GET /api/v1/runs/{runId}/timeseries}). Sibling to
 * {@link MetricsRollupRepository}, which serves the per-label rollup
 * for the existing {@code /metrics} endpoint.
 *
 * <p>The repository runs two SQL queries (one for the four numeric
 * series, one for the status-code series) and aggregates them in
 * Java into a single {@link MetricsTimeseries} payload. Splitting the
 * status-code unrolling into its own query keeps both SQL statements
 * grokable; combining them into one would require a UNION-with-different-
 * column-shapes or a JSON build expression — neither pleasant.
 *
 * <h2>Downsampling</h2>
 * Per-second resolution is preserved for short runs; for longer runs
 * the points are bucketed into one of a fixed set of <em>nice</em>
 * widths (1, 2, 5, 10, 15, 30, 60 seconds) so axis ticks land on
 * round boundaries. Bucket width is the smallest value that keeps
 * the rendered point count at or below {@link #BUCKET_TARGET},
 * capped at {@link #MAX_BUCKET_SECONDS} so the operator never sees
 * worse than 1-minute granularity even for multi-day runs.
 *
 * <p>Each bucket aggregates by <b>arithmetic average</b> across the
 * raw per-second points: every series remains in its native
 * per-second unit (req/s, ms, %, count/s) regardless of bucket
 * width. We deliberately do NOT use a TPS-weighted mean here —
 * inflating a noisy 1-RPS second's weight isn't desirable, and the
 * unweighted average is what operators expect when reading "average
 * response time" off the bucketed series.
 *
 * <p>The point's {@code sec} is the first second of the bucket; the
 * response carries {@link MetricsTimeseries#bucketSize()} so the UI
 * can label the time axis correctly.
 */
@Repository
public class MetricsTimeseriesRepository {

    /**
     * Maximum bucketed points returned to the UI. Picked to fit
     * comfortably in a ~720-px-wide chart at 1px/point; the human eye
     * can't distinguish past this density. With the 60-second cap
     * below, a 25-hour run still lands within this budget.
     */
    static final int BUCKET_TARGET = 1500;

    /**
     * Hard ceiling on bucket width: per-minute is the coarsest
     * granularity we ever surface, by product policy. The 7 widths
     * cover the full 1s..60s spectrum at "nice" tick boundaries so
     * the chart's x-axis lands on clean clock marks.
     */
    static final int MAX_BUCKET_SECONDS = 60;
    private static final int[] NICE_BUCKETS = { 1, 2, 5, 10, 15, 30, 60 };

    private final JdbcTemplate jdbc;

    public MetricsTimeseriesRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Default (all-regions total, whole test) — preserves the original payload shape. */
    public MetricsTimeseries timeseries(String runId) {
        return timeseries(runId, false, null);
    }

    /** Whole-test variant with optional region split (no time window). */
    public MetricsTimeseries timeseries(String runId, boolean byRegion) {
        return timeseries(runId, byRegion, null);
    }

    /**
     * Per-second timeseries for a run.
     *
     * @param byRegion when {@code true}, the response additionally carries a
     *        per-region breakdown ({@code regions[region] -> Series}) alongside
     *        the all-regions total in {@code series}. When {@code false} the
     *        cheaper single-GROUP-BY path runs and {@code regions} is empty.
     * @param windowSeconds when non-null, restricts the query to the most recent
     *        {@code windowSeconds} of the run (the "last 5m / 30m / 1h …" UI
     *        selector). The window is anchored at the run's <em>latest</em> data
     *        second (not wall-clock {@code now()}), so it works identically for a
     *        live run and a finished one from days ago. {@code null} = whole test.
     *        This both shrinks the wire payload and — more importantly for a
     *        10-hour run polled every 5 s — prunes the partition scan + aggregate
     *        the orchestrator does on each poll to just the recent window.
     *
     * <p>The region path runs the same two queries but with {@code "region"}
     * added to the GROUP BY, then folds the raw component sums (throughput,
     * throughput-weighted RT, error count, per-code counts) two ways: once per
     * region, and once summed across regions to recover the total. Deriving the
     * ratio metrics (avg RT, error %) only after summing keeps the total exactly
     * consistent with the regions — no double-counting, no weighting drift.
     */
    public MetricsTimeseries timeseries(String runId, boolean byRegion, Long windowSeconds) {
        return timeseries(runId, byRegion, windowSeconds, 0);
    }

    /**
     * As {@link #timeseries(String, boolean, Long)}, but trims the
     * <b>unsettled trailing edge</b> so a <em>live</em> run renders stable data
     * on every poll.
     *
     * @param settleSeconds when {@code > 0}, the newest {@code settleSeconds} of
     *        data are excluded from the response. Those most-recent seconds are
     *        still being produced: each worker holds a per-second window open for
     *        a ~2 s close grace, and the metrics-consumer adds Kafka fetch-wait +
     *        batch-INSERT lag before the rows land in Postgres. So on a run polled
     *        every 5 s the trailing seconds are only partially aggregated and
     *        change shape each poll — a half-counted second shows as a transient
     *        TPS dip / RT spike, and because the chart auto-scales its Y axis that
     *        one outlier rescales the whole line, which is the "smooths then goes
     *        spiky again" wobble. Anchoring the window's UPPER bound at the newest
     *        <em>settled</em> second ({@code maxSec - settleSeconds}) drops the
     *        unstable tail. Pass {@code 0} for a terminal run: its rows are
     *        immutable, so the true final second is shown (and is cacheable).
     */
    public MetricsTimeseries timeseries(String runId, boolean byRegion, Long windowSeconds, int settleSeconds) {
        Bounds bounds = resolveBounds(runId, windowSeconds, settleSeconds);
        if (bounds == null) {
            return emptyTimeseries(runId);  // no rows yet — friendly empty shape
        }
        if (byRegion) {
            return timeseriesByRegion(runId, bounds);
        }
        List<RawSecond> rawNumeric = fetchNumericSeries(runId, bounds);
        // Status codes are independent of the numeric agg — fetched as
        // (sec, code, n) rows and pivoted into (code -> list) below.
        Map<String, List<TimeseriesPoint>> rawStatus = fetchStatusCodeSeries(runId, bounds);

        if (rawNumeric.isEmpty()) {
            // Polling-friendly empty shape — better than 404 during PREPARING
            // (the consumer hasn't written rows yet), or when the entire window
            // is still inside the settle margin (run younger than settleSeconds).
            return emptyTimeseries(runId);
        }

        long fromSec = rawNumeric.get(0).sec;
        long toSec   = rawNumeric.get(rawNumeric.size() - 1).sec;

        // Pick the smallest nice bucket width that keeps the rendered
        // point count <= BUCKET_TARGET. Capped at MAX_BUCKET_SECONDS
        // so the operator never sees worse than 1-minute granularity.
        int bucketSize = chooseBucketSize(rawNumeric.size());
        if (bucketSize == 1) {
            return new MetricsTimeseries(runId, 1, fromSec, toSec,
                    new Series(
                            mapToPoints(rawNumeric, r -> r.tps),
                            mapToPoints(rawNumeric, r -> r.avgRtMs),
                            mapToPoints(rawNumeric, r -> r.errorPct),
                            rawStatus));
        }
        return new MetricsTimeseries(runId, bucketSize, fromSec, toSec,
                new Series(
                        bucketAverage(rawNumeric, bucketSize, r -> r.tps),
                        bucketAverage(rawNumeric, bucketSize, r -> r.avgRtMs),
                        bucketAverage(rawNumeric, bucketSize, r -> r.errorPct),
                        bucketStatusCodes(rawStatus, bucketSize, fromSec)));
    }

    // ── Per-region path ────────────────────────────────────────────────

    /**
     * Region-split build. Fetches region-grained raw component sums,
     * folds them per-region and across-regions (the total), then buckets
     * every series against ONE shared bucket width + anchor so the total
     * and each region line up tick-for-tick on the chart's x-axis.
     */
    private MetricsTimeseries timeseriesByRegion(String runId, Bounds bounds) {
        // region -> (sec -> raw component sums). LinkedHashMap on the
        // outer map preserves the SQL's ORDER BY region so the UI's
        // region order is stable across polls.
        Map<String, Map<Long, RawSums>> numericByRegion = fetchNumericByRegion(runId, bounds);
        Map<String, Map<String, List<TimeseriesPoint>>> statusByRegion = fetchStatusByRegion(runId, bounds);

        if (numericByRegion.isEmpty()) {
            return emptyTimeseries(runId);
        }

        // Fold to the total: per second, sum the component sums across
        // every region, then derive the ratio metrics from those totals.
        Map<Long, RawSums> totalBySec = new TreeMap<>();
        for (Map<Long, RawSums> perSec : numericByRegion.values()) {
            perSec.forEach((sec, s) ->
                    totalBySec.computeIfAbsent(sec, k -> new RawSums()).add(s));
        }
        List<RawSecond> totalRaw = toRawSeconds(totalBySec);

        // Total status codes: sum each (code, sec) across regions.
        Map<String, List<TimeseriesPoint>> totalStatus = foldStatusTotal(statusByRegion);

        // ONE shared bucket width + anchor, sized from the total second
        // count, so the total and all regions share x-axis ticks.
        long fromSec = totalRaw.get(0).sec;
        long toSec   = totalRaw.get(totalRaw.size() - 1).sec;
        int bucketSize = chooseBucketSize(totalRaw.size());

        Series total = buildSeries(totalRaw, totalStatus, bucketSize, fromSec);

        Map<String, Series> regions = new LinkedHashMap<>();
        numericByRegion.forEach((region, perSec) -> {
            List<RawSecond> raw = toRawSeconds(perSec);
            Map<String, List<TimeseriesPoint>> status =
                    statusByRegion.getOrDefault(region, Map.of());
            regions.put(region, buildSeries(raw, status, bucketSize, fromSec));
        });

        return new MetricsTimeseries(runId, bucketSize, fromSec, toSec, total, regions);
    }

    /**
     * Assemble a {@link Series} from already-aggregated per-second rows,
     * applying the shared {@code bucketSize}/{@code fromSec}. Used for the
     * total and for each region so they bucket identically.
     */
    private static Series buildSeries(List<RawSecond> raw,
                                      Map<String, List<TimeseriesPoint>> status,
                                      int bucketSize, long fromSec) {
        if (bucketSize == 1) {
            return new Series(
                    mapToPoints(raw, r -> r.tps),
                    mapToPoints(raw, r -> r.avgRtMs),
                    mapToPoints(raw, r -> r.errorPct),
                    status);
        }
        return new Series(
                bucketByTime(raw, bucketSize, fromSec, r -> r.tps),
                bucketByTime(raw, bucketSize, fromSec, r -> r.avgRtMs),
                bucketByTime(raw, bucketSize, fromSec, r -> r.errorPct),
                bucketStatusCodes(status, bucketSize, fromSec));
    }

    /** Derive per-second {@link RawSecond} (tps + ratio metrics) from component sums. */
    private static List<RawSecond> toRawSeconds(Map<Long, RawSums> bySec) {
        List<RawSecond> out = new ArrayList<>(bySec.size());
        // bySec is a TreeMap (total) or LinkedHashMap built from an
        // ORDER BY windowSecond query (regions) — either way, ascending.
        bySec.forEach((sec, s) -> out.add(new RawSecond(
                sec,
                s.throughput,
                s.throughput > 0 ? s.rtWeighted / s.throughput : 0.0,
                s.throughput > 0 ? 100.0 * s.errorCount / s.throughput : 0.0)));
        return out;
    }

    /** Sum each (code → per-second points) across all regions. */
    private static Map<String, List<TimeseriesPoint>> foldStatusTotal(
            Map<String, Map<String, List<TimeseriesPoint>>> statusByRegion) {
        // code -> (sec -> summed count). TreeMap on sec keeps ascending order.
        Map<String, Map<Long, Long>> agg = new LinkedHashMap<>();
        for (Map<String, List<TimeseriesPoint>> perCode : statusByRegion.values()) {
            perCode.forEach((code, points) -> {
                Map<Long, Long> bySec = agg.computeIfAbsent(code, k -> new TreeMap<>());
                for (TimeseriesPoint p : points) {
                    bySec.merge(p.sec(), (long) p.v(), Long::sum);
                }
            });
        }
        Map<String, List<TimeseriesPoint>> out = new LinkedHashMap<>();
        agg.forEach((code, bySec) -> {
            List<TimeseriesPoint> pts = new ArrayList<>(bySec.size());
            bySec.forEach((sec, n) -> pts.add(new TimeseriesPoint(sec, n)));
            out.put(code, pts);
        });
        return out;
    }

    /**
     * Pick the bucket width for a raw point count.
     * Always one of {@link #NICE_BUCKETS}; never exceeds
     * {@link #MAX_BUCKET_SECONDS} even when the resulting point count
     * would still be above {@link #BUCKET_TARGET} (multi-day runs).
     */
    static int chooseBucketSize(int rawPoints) {
        for (int width : NICE_BUCKETS) {
            int bucketCount = (rawPoints + width - 1) / width;
            if (bucketCount <= BUCKET_TARGET) return width;
        }
        return MAX_BUCKET_SECONDS;
    }

    // ── Raw queries ────────────────────────────────────────────────────

    private List<RawSecond> fetchNumericSeries(String runId, Bounds bounds) {
        // Per-second aggregate across every (worker, label) row for the
        // run. avg_rt_ms is a TPS-weighted mean of per-(worker, label)
        // avgRespTimeMs — the per-row column itself is a TRUE mean
        // (sum of sample elapsed / sample count), populated by HM-1A
        // across Avro → aggregator → Postgres → consumer. Aggregating
        // those means weighted by throughput recovers the proper
        // cross-fleet mean for the whole second.
        return jdbc.query(
                "SELECT \"windowSecond\" AS sec, "
                + "       sum(\"throughput\")::bigint AS tps, "
                + "       CASE WHEN sum(\"throughput\") > 0 "
                + "            THEN sum(\"avgRespTimeMs\" * \"throughput\") / sum(\"throughput\") "
                + "            ELSE 0 END AS avg_rt_ms, "
                + "       CASE WHEN sum(\"throughput\") > 0 "
                + "            THEN 100.0 * sum(\"errorCount\")::double precision / sum(\"throughput\") "
                + "            ELSE 0 END AS error_pct "
                + "FROM metrics.\"workerMetric\" "
                + "WHERE \"runId\" = ? " + windowClause(bounds)
                + "GROUP BY \"windowSecond\" "
                + "ORDER BY \"windowSecond\"",
                (rs, i) -> new RawSecond(
                        rs.getLong("sec"),
                        rs.getLong("tps"),
                        rs.getDouble("avg_rt_ms"),
                        rs.getDouble("error_pct")),
                windowArgs(runId, bounds));
    }

    private Map<String, List<TimeseriesPoint>> fetchStatusCodeSeries(String runId, Bounds bounds) {
        // jsonb_each_text expands the statusCodes JSONB into
        // (key,value) text pairs; SUM(value::bigint) totals counts per
        // (windowSecond, code).
        List<StatusCodeRow> rows = jdbc.query(
                "SELECT \"windowSecond\" AS sec, j.key AS code, "
                + "       sum((j.value)::bigint) AS n "
                + "FROM metrics.\"workerMetric\", "
                + "     LATERAL jsonb_each_text(\"statusCodes\") AS j "
                + "WHERE \"runId\" = ? " + windowClause(bounds)
                + "GROUP BY \"windowSecond\", j.key "
                + "ORDER BY j.key, \"windowSecond\"",
                (rs, i) -> new StatusCodeRow(
                        rs.getLong("sec"),
                        rs.getString("code"),
                        rs.getLong("n")),
                windowArgs(runId, bounds));

        // LinkedHashMap preserves the SQL ORDER BY j.key so the UI's
        // legend has a stable order across polls.
        Map<String, List<TimeseriesPoint>> byCode = new LinkedHashMap<>();
        for (StatusCodeRow r : rows) {
            byCode.computeIfAbsent(r.code, k -> new ArrayList<>())
                  .add(new TimeseriesPoint(r.sec, r.n));
        }
        return byCode;
    }

    /**
     * Region-grained numeric raw sums: one row per {@code (windowSecond,
     * region)} carrying the component sums needed to derive tps / avg RT
     * / error % at any aggregation level. We select SUMS (not the derived
     * ratios) so the caller can fold across regions to the total without
     * weighting drift — {@code sum(rtWeighted)/sum(tps)} over all regions
     * is the exact cross-fleet mean.
     */
    private Map<String, Map<Long, RawSums>> fetchNumericByRegion(String runId, Bounds bounds) {
        // LinkedHashMap preserves ORDER BY region for stable UI ordering;
        // the inner per-region map is keyed by second in ascending order
        // (ORDER BY windowSecond).
        Map<String, Map<Long, RawSums>> byRegion = new LinkedHashMap<>();
        jdbc.query(
                "SELECT \"region\" AS region, \"windowSecond\" AS sec, "
                + "       sum(\"throughput\")::bigint AS tps, "
                + "       sum(\"avgRespTimeMs\" * \"throughput\") AS rt_weighted, "
                + "       sum(\"errorCount\")::bigint AS errors "
                + "FROM metrics.\"workerMetric\" "
                + "WHERE \"runId\" = ? " + windowClause(bounds)
                + "GROUP BY \"region\", \"windowSecond\" "
                + "ORDER BY \"region\", \"windowSecond\"",
                (rs, i) -> {
                    String region = rs.getString("region");
                    long sec      = rs.getLong("sec");
                    RawSums s = new RawSums();
                    s.throughput = rs.getLong("tps");
                    s.rtWeighted = rs.getDouble("rt_weighted");
                    s.errorCount = rs.getLong("errors");
                    byRegion.computeIfAbsent(region, k -> new LinkedHashMap<>()).put(sec, s);
                    return null;
                },
                windowArgs(runId, bounds));
        return byRegion;
    }

    /**
     * Region-grained status-code rows: {@code (region, sec, code, n)},
     * pivoted into {@code region -> code -> list of (sec, n)}.
     */
    private Map<String, Map<String, List<TimeseriesPoint>>> fetchStatusByRegion(String runId, Bounds bounds) {
        Map<String, Map<String, List<TimeseriesPoint>>> byRegion = new LinkedHashMap<>();
        jdbc.query(
                "SELECT \"region\" AS region, \"windowSecond\" AS sec, j.key AS code, "
                + "       sum((j.value)::bigint) AS n "
                + "FROM metrics.\"workerMetric\", "
                + "     LATERAL jsonb_each_text(\"statusCodes\") AS j "
                + "WHERE \"runId\" = ? " + windowClause(bounds)
                + "GROUP BY \"region\", \"windowSecond\", j.key "
                + "ORDER BY \"region\", j.key, \"windowSecond\"",
                (rs, i) -> {
                    String region = rs.getString("region");
                    long sec      = rs.getLong("sec");
                    String code   = rs.getString("code");
                    long n        = rs.getLong("n");
                    byRegion
                            .computeIfAbsent(region, k -> new LinkedHashMap<>())
                            .computeIfAbsent(code, k -> new ArrayList<>())
                            .add(new TimeseriesPoint(sec, n));
                    return null;
                },
                windowArgs(runId, bounds));
        return byRegion;
    }

    // ── Window helpers ──────────────────────────────────────────────────

    /**
     * Resolve the {@code windowSeconds}/{@code settleSeconds} selectors into
     * absolute {@code windowSecond} bounds, both anchored at the run's latest
     * data second so "last 30 m" means the last 30 min OF THE TEST (live or long
     * finished). Returns {@code null} when the run has no rows yet (caller emits
     * the empty shape), or {@link Bounds#ALL} for the whole-test / no-settle case
     * so that path skips the extra {@code max()} probe entirely.
     */
    private Bounds resolveBounds(String runId, Long windowSeconds, int settleSeconds) {
        int settle = Math.max(0, settleSeconds);
        if (windowSeconds == null && settle == 0) {
            return Bounds.ALL;  // whole test, terminal / no trim — no max() needed
        }
        Long maxSec = maxWindowSecond(runId);
        if (maxSec == null) {
            return null;  // no rows yet
        }
        // Newest *settled* second: drop the trailing `settle` seconds that are
        // still being aggregated + ingested on a live run (see the 4-arg
        // timeseries javadoc). settle == 0 keeps the true latest second.
        long settledMax = maxSec - settle;
        Long to   = settle > 0 ? settledMax : null;
        Long from = windowSeconds != null ? settledMax - windowSeconds + 1 : null;
        return new Bounds(from, to);
    }

    /** Latest data second for a run, or {@code null} if it has no rows yet. */
    private Long maxWindowSecond(String runId) {
        return jdbc.queryForObject(
                "SELECT max(\"windowSecond\") FROM metrics.\"workerMetric\" WHERE \"runId\" = ?",
                (rs, i) -> { long v = rs.getLong(1); return rs.wasNull() ? null : v; },
                runId);
    }

    /**
     * Resolved absolute bounds on {@code windowSecond} for one query; either end
     * may be {@code null} (unbounded). {@code from} is the "last Nm" lower edge;
     * {@code to} is the settle-margin upper edge that trims the unstable tail.
     */
    private record Bounds(Long from, Long to) {
        static final Bounds ALL = new Bounds(null, null);
    }

    /** SQL fragment adding the windowSecond range filters that are set. */
    private static String windowClause(Bounds bounds) {
        StringBuilder sb = new StringBuilder(48);
        if (bounds.from() != null) sb.append("AND \"windowSecond\" >= ? ");
        if (bounds.to()   != null) sb.append("AND \"windowSecond\" <= ? ");
        return sb.toString();
    }

    /**
     * Positional args matching {@link #windowClause}: {@code runId}, then the
     * lower bound (if set), then the upper bound (if set) — same order the
     * clause appends them.
     */
    private static Object[] windowArgs(String runId, Bounds bounds) {
        List<Object> args = new ArrayList<>(3);
        args.add(runId);
        if (bounds.from() != null) args.add(bounds.from());
        if (bounds.to()   != null) args.add(bounds.to());
        return args.toArray();
    }

    /** Polling-friendly empty payload (200 + empty arrays, not 404). */
    private static MetricsTimeseries emptyTimeseries(String runId) {
        return new MetricsTimeseries(runId, 1, null, null,
                new Series(List.of(), List.of(), List.of(), Map.of()));
    }

    // ── Bucketing ─────────────────────────────────────────────────────

    /**
     * Arithmetic average across the bucket — {@code sum(values) / count}.
     * Used for every series so values stay in per-second units (req/s,
     * ms, %, count/s) regardless of bucket width.
     *
     * <p>Deliberately unweighted: a TPS-weighted mean would suppress
     * outlier seconds with low traffic, but for "what was the typical
     * response time per second in this window?" the operator wants
     * each second to count equally.
     */
    private static List<TimeseriesPoint> bucketAverage(
            List<RawSecond> raw, int bucketSize, java.util.function.ToDoubleFunction<RawSecond> f) {
        List<TimeseriesPoint> out = new ArrayList<>(raw.size() / bucketSize + 1);
        for (int i = 0; i < raw.size(); i += bucketSize) {
            long bucketSec = raw.get(i).sec;
            double sum = 0;
            int count = 0;
            for (int j = i; j < raw.size() && j < i + bucketSize; j++) {
                sum += f.applyAsDouble(raw.get(j));
                count++;
            }
            double v = count > 0 ? sum / count : 0;
            out.add(new TimeseriesPoint(bucketSec, v));
        }
        return out;
    }

    /**
     * Buckets each per-code series independently using the same bucket
     * boundaries (anchored at {@code fromSec}). Aggregation is an
     * <em>arithmetic average</em> over the seconds the code appeared in
     * the bucket — keeps the value in count/s units, consistent with
     * the rest of the series.
     */
    private static Map<String, List<TimeseriesPoint>> bucketStatusCodes(
            Map<String, List<TimeseriesPoint>> raw, int bucketSize, long fromSec) {
        Map<String, List<TimeseriesPoint>> out = new LinkedHashMap<>();
        raw.forEach((code, points) -> {
            // Bucket by absolute time so all codes share the same x-axis
            // ticks even when sparse codes have gaps.
            Map<Long, double[]> agg = new LinkedHashMap<>(); // sec -> {sum, count}
            for (TimeseriesPoint p : points) {
                long bucketIdx = (p.sec() - fromSec) / bucketSize;
                long bucketSec = fromSec + bucketIdx * bucketSize;
                double[] s = agg.computeIfAbsent(bucketSec, k -> new double[2]);
                s[0] += p.v();
                s[1] += 1;
            }
            List<TimeseriesPoint> bucketed = new ArrayList<>(agg.size());
            agg.forEach((sec, s) -> {
                double v = s[1] > 0 ? s[0] / s[1] : 0;
                bucketed.add(new TimeseriesPoint(sec, v));
            });
            out.put(code, bucketed);
        });
        return out;
    }

    /**
     * Bucket a numeric series by ABSOLUTE time (anchored at {@code fromSec}),
     * arithmetic-averaging the per-second values that fall in each bucket.
     * Unlike {@link #bucketAverage} (which buckets by list index over a dense
     * second list), this aligns buckets to wall-clock boundaries shared with
     * {@link #bucketStatusCodes} — required for the region path, where a region
     * may be missing seconds the total has, and every region must still land on
     * the same x-axis ticks.
     */
    private static List<TimeseriesPoint> bucketByTime(
            List<RawSecond> raw, int bucketSize, long fromSec,
            java.util.function.ToDoubleFunction<RawSecond> f) {
        Map<Long, double[]> agg = new LinkedHashMap<>(); // bucketSec -> {sum, count}
        for (RawSecond r : raw) {
            long bucketIdx = (r.sec - fromSec) / bucketSize;
            long bucketSec = fromSec + bucketIdx * bucketSize;
            double[] s = agg.computeIfAbsent(bucketSec, k -> new double[2]);
            s[0] += f.applyAsDouble(r);
            s[1] += 1;
        }
        List<TimeseriesPoint> out = new ArrayList<>(agg.size());
        agg.forEach((sec, s) -> out.add(new TimeseriesPoint(sec, s[1] > 0 ? s[0] / s[1] : 0)));
        return out;
    }

    private static List<TimeseriesPoint> mapToPoints(
            List<RawSecond> raw, java.util.function.ToDoubleFunction<RawSecond> f) {
        List<TimeseriesPoint> out = new ArrayList<>(raw.size());
        for (RawSecond r : raw) out.add(new TimeseriesPoint(r.sec, f.applyAsDouble(r)));
        return out;
    }

    // ── Internal row carriers (kept package-private for tests) ─────────

    record RawSecond(long sec, long tps, double avgRtMs, double errorPct) { }

    record StatusCodeRow(long sec, String code, long n) { }

    /**
     * Mutable per-second component sums used by the region path. Kept as
     * raw sums (not derived ratios) so they can be folded across regions
     * before the avg-RT / error-% division — see {@link #timeseriesByRegion}.
     */
    static final class RawSums {
        long   throughput;
        double rtWeighted;   // sum(avgRespTimeMs * throughput) — numerator of the weighted mean
        long   errorCount;

        void add(RawSums other) {
            this.throughput += other.throughput;
            this.rtWeighted += other.rtWeighted;
            this.errorCount += other.errorCount;
        }
    }
}
