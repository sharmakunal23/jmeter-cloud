package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.MetricsTimeseries;
import com.perf.globalorchestrator.domain.MetricsTimeseries.Series;
import com.perf.globalorchestrator.domain.MetricsTimeseries.TimeseriesPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The timeseries behind {@code GET /api/v1/runs/{runId}/timeseries}, read
 * from the run's group fact table the way the hosted Grafana panels read it:
 * one statement per shape, every one carrying {@code RUN_ID = ?} and a
 * {@code WINDOW_SECOND BETWEEN ? AND ?} range (the tables are partitioned by
 * day, and {@code (RUN_ID, LABEL_ID, WINDOW_SECOND)} is the local index),
 * grouped by {@code FLOOR(WINDOW_SECOND / g) * g} for a bucket width
 * {@code g ∈ {15, 30, 60}} seconds, and folding component sums only — the
 * division happens in Java at the end.
 *
 * <p>Rows older than the group's hot days live in the archived-day table
 * (one row per label per window, workers collapsed) and are read through a
 * {@code UNION ALL}; the archive drops a day from the hot table only after
 * publishing it, so nothing is counted twice. The per-region split reads hot
 * rows only — the archive has no worker dimension.
 *
 * <p>The per-label split ({@code byLabel}) is bounded twice: an optional
 * {@code LABEL_KEY LIKE 'prefix%'} (a leading literal, so the index is used)
 * and a cap ({@link #LABELS_SHOWN} at the API edge, at most {@link #LABELS_MAX},
 * or every label on {@code all}), the busiest by samples.
 */
@Repository
public class MetricsTimeseriesRepository {

    /** A response never carries more points than this; the bucket width grows to keep under it. */
    static final int BUCKET_TARGET = 1500;
    /** The window width the workers publish at; also the finest bucket. */
    public static final int WINDOW_SECONDS = 15;
    /** The Grafana granularity picker's values. */
    static final int[] NICE_BUCKETS = { 15, 30, 60 };
    /** The per-label split's default cap at the API edge — the busiest by samples. */
    public static final int LABELS_SHOWN = 10;
    /** The most labels a numeric cap may ask for; {@link #LABELS_ALL} lifts the cap. */
    public static final int LABELS_MAX = 50;
    /** A {@code labelLimit} of zero: every label. */
    public static final int LABELS_ALL = 0;

    private static final String COLS =
            "WINDOW_SECOND, LABEL_ID, THROUGHPUT, ERROR_COUNT, AVG_MS, P90_MS, P95_MS, P99_MS, "
            + "HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX, HTTP_OTHER";
    private static final String SUMS =
            "SUM(b.THROUGHPUT) AS samples, SUM(b.ERROR_COUNT) AS errors, "
            + "SUM(b.AVG_MS * b.THROUGHPUT) AS w_avg, SUM(b.P90_MS * b.THROUGHPUT) AS w_p90, "
            + "SUM(b.P95_MS * b.THROUGHPUT) AS w_p95, SUM(b.P99_MS * b.THROUGHPUT) AS w_p99, "
            + "SUM(b.HTTP_2XX) AS h2, SUM(b.HTTP_3XX) AS h3, "
            + "SUM(b.HTTP_4XX) AS h4, SUM(b.HTTP_5XX) AS h5, SUM(b.HTTP_OTHER) AS ho";

    /**
     * What one read asks for. {@code granularity} null = the smallest nice bucket
     * under {@link #BUCKET_TARGET}; {@code labelPrefix} narrows the per-label split
     * (exact prefix on {@code LABEL_KEY}, null = every label).
     */
    public record Query(boolean byRegion, boolean byApplication, boolean byLabel, String labelPrefix,
                        Integer labelLimit, Integer granularity, Long windowSeconds) {
        public static final Query AGGREGATE = new Query(false, false, false, null, null, null, null);

        public Query(boolean byRegion, boolean byApplication, Integer granularity, Long windowSeconds) {
            this(byRegion, byApplication, false, null, null, granularity, windowSeconds);
        }

        public Query(boolean byRegion, boolean byApplication, boolean byLabel, String labelPrefix,
                     Integer granularity, Long windowSeconds) {
            this(byRegion, byApplication, byLabel, labelPrefix, null, granularity, windowSeconds);
        }

        /** The label cap: the caller's, bounded to {@link #LABELS_MAX}; unset or {@link #LABELS_ALL} = every label. */
        public int labelsShown() {
            return labelLimit == null || labelLimit <= LABELS_ALL ? Integer.MAX_VALUE : Math.min(LABELS_MAX, labelLimit);
        }
    }

    private final JdbcTemplate jdbc;

    public MetricsTimeseriesRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param settleSeconds trailing seconds to drop on a live run — the newest
     *                      window is still being published by the workers
     */
    public MetricsTimeseries timeseries(String runKey, MetricsTarget t, RunWindow run, Query q, int settleSeconds) {
        RunWindow w = resolveRange(t, run, q.windowSeconds(), settleSeconds);
        if (w == null) {
            return new MetricsTimeseries(runKey, WINDOW_SECONDS, null, null, Series.empty());
        }
        int g = q.granularity() != null ? q.granularity() : bucketSecondsFor(w.hi() - w.lo() + 1);

        boolean history = t.historyTable() != null;
        Series total = series(rows(aggregateSql(t), args(t, w, g, history)), g);
        // The region split reads hot rows only (the archive has no worker dimension).
        Map<String, Series> regions = q.byRegion() ? split(rows(byRegionSql(t), args(t, w, g, false)), g) : Map.of();
        Map<String, Series> applications = q.byApplication() ? split(rows(byApplicationSql(t), args(t, w, g, history)), g) : Map.of();
        Map<String, Series> labels = Map.of();
        Integer labelsTotal = null;
        if (q.byLabel()) {
            String prefix = likePrefix(q.labelPrefix());
            Map<String, Series> all = split(rows(byLabelSql(t, prefix != null), args(t, w, g, history, prefix)), g);
            labelsTotal = all.size();
            labels = busiest(all, q.labelsShown());
        }

        Long fromSecond = total.tps().isEmpty() ? null : total.tps().get(0).sec();
        Long toSecond = total.tps().isEmpty() ? null : total.tps().get(total.tps().size() - 1).sec();
        return new MetricsTimeseries(runKey, g, fromSecond, toSecond, total, regions, applications, labels, labelsTotal);
    }

    /**
     * The range a read covers: the run's rows narrowed to the trailing
     * {@code windowSeconds} (null = everything), minus the newest
     * {@code settleSeconds} on a live run. Null when the run has no rows in its
     * window yet. Shared with the summary and the aggregate report so every
     * panel of the Metrics tab reads the same slice.
     */
    public RunWindow resolveRange(MetricsTarget t, RunWindow run, Long windowSeconds, int settleSeconds) {
        long[] minMax = minMax(t, run);
        if (minMax == null) {
            return null;
        }
        long settledMax = minMax[1] - Math.max(0, settleSeconds);
        long to = Math.max(minMax[0], settledMax);
        long from = windowSeconds != null ? Math.max(minMax[0], to - windowSeconds + 1) : minMax[0];
        return run.narrowTo(from, to);
    }

    /**
     * A {@code LIKE} pattern for an exact prefix: the caller's text with its
     * {@code \}, {@code %} and {@code _} escaped, then {@code %}. Null for blank.
     */
    public static String likePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        return prefix.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** The {@code n} busiest series by total samples, in that order. */
    static Map<String, Series> busiest(Map<String, Series> all, int n) {
        if (all.size() <= n) {
            return all;
        }
        Map<String, Series> out = new LinkedHashMap<>();
        all.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Series> e) -> -samples(e.getValue()))
                        .thenComparing(Map.Entry::getKey))
                .limit(n)
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static double samples(Series s) {
        double sum = 0;
        for (TimeseriesPoint p : s.tps()) sum += p.v();
        return sum;
    }

    /** The smallest nice bucket that keeps a span under {@link #BUCKET_TARGET} points; 60 s at most. */
    static int bucketSecondsFor(long spanSeconds) {
        for (int b : NICE_BUCKETS) {
            if ((spanSeconds + b - 1) / b <= BUCKET_TARGET) {   // ceil: a partial bucket is still a point
                return b;
            }
        }
        return NICE_BUCKETS[NICE_BUCKETS.length - 1];
    }

    // ── SQL ────────────────────────────────────────────────────────────

    /** Hot rows, plus the archived-day rows when the group keeps them. */
    static String factRows(MetricsTarget t, boolean withWorker) {
        String hot = "SELECT " + COLS + (withWorker ? ", WORKER_ID" : "") + " FROM " + t.metricsTable()
                + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?";
        if (withWorker || t.historyTable() == null) {
            return hot;
        }
        return hot + " UNION ALL SELECT " + COLS + " FROM " + t.historyTable()
                + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?";
    }

    /**
     * The bucket is computed once, in an inline view, and grouped by its alias:
     * Oracle treats two {@code ?} placeholders as two different expressions, so
     * {@code SELECT FLOOR(c / ?) * ? … GROUP BY FLOOR(c / ?) * ?} is ORA-00979.
     * The view merges; the range predicate still prunes.
     */
    static String bucketed(MetricsTarget t, boolean withWorker) {
        return "(SELECT FLOOR(x.WINDOW_SECOND / ?) * ? AS sec, x.* FROM (" + factRows(t, withWorker) + ") x) b";
    }

    static String aggregateSql(MetricsTarget t) {
        return "SELECT b.sec, " + SUMS + " FROM " + bucketed(t, false) + " GROUP BY b.sec ORDER BY 1";
    }

    static String byRegionSql(MetricsTarget t) {
        return "SELECT b.sec, w.REGION AS grp, " + SUMS
                + " FROM " + bucketed(t, true) + " JOIN WORKER w ON w.WORKER_ID = b.WORKER_ID"
                + " GROUP BY b.sec, w.REGION ORDER BY 2, 1";
    }

    static String byApplicationSql(MetricsTarget t) {
        return "SELECT b.sec, l.APPLICATION AS grp, " + SUMS
                + " FROM " + bucketed(t, false) + " JOIN LABEL l ON l.LABEL_ID = b.LABEL_ID"
                + " GROUP BY b.sec, l.APPLICATION ORDER BY 2, 1";
    }

    /** One series per label; the prefix predicate is a leading literal on the indexed {@code LABEL_KEY}. */
    static String byLabelSql(MetricsTarget t, boolean withPrefix) {
        return "SELECT b.sec, l.LABEL_KEY AS grp, " + SUMS
                + " FROM " + bucketed(t, false) + " JOIN LABEL l ON l.LABEL_ID = b.LABEL_ID"
                + (withPrefix ? " WHERE l.LABEL_KEY LIKE ? ESCAPE '\\'" : "")
                + " GROUP BY b.sec, l.LABEL_KEY ORDER BY 2, 1";
    }

    /** Binds in statement order: the bucket width twice, the hot branch, the history branch when present. */
    static Object[] args(MetricsTarget t, RunWindow w, int g, boolean withHistory) {
        return args(t, w, g, withHistory, null);
    }

    /** As {@link #args(MetricsTarget, RunWindow, int, boolean)}, plus the label pattern last when set. */
    static Object[] args(MetricsTarget t, RunWindow w, int g, boolean withHistory, String likePattern) {
        List<Object> a = new ArrayList<>();
        a.add(g); a.add(g);
        a.add(t.runId()); a.add(w.lo()); a.add(w.hi());
        if (withHistory) {
            a.add(t.runId()); a.add(w.lo()); a.add(w.hi());
        }
        if (likePattern != null) {
            a.add(likePattern);
        }
        return a.toArray();
    }

    private long[] minMax(MetricsTarget t, RunWindow w) {
        String sql = "SELECT MIN(x.WINDOW_SECOND), MAX(x.WINDOW_SECOND) FROM (" + factRows(t, false) + ") x";
        List<Object> a = new ArrayList<>(List.of(t.runId(), w.lo(), w.hi()));
        if (t.historyTable() != null) {
            a.addAll(List.of(t.runId(), w.lo(), w.hi()));
        }
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return null;
            long min = rs.getLong(1);
            if (rs.wasNull()) return null;
            return new long[] {min, rs.getLong(2)};
        }, a.toArray());
    }

    private List<Row> rows(String sql, Object[] args) {
        return jdbc.query(sql, (rs, n) -> Row.of(rs), args);
    }

    // ── Folding ────────────────────────────────────────────────────────

    /** The component-sum columns after {@code sec} (and the split key when grouped). */
    private static final int SUM_COLUMNS = 12;

    record Row(long sec, String group, long samples, long errors, double wAvg, double wP90, double wP95, double wP99,
               long h2, long h3, long h4, long h5, long ho) {
        static Row of(ResultSet rs) throws SQLException {
            boolean grouped = rs.getMetaData().getColumnCount() > SUM_COLUMNS;
            int c = 1;
            long sec = rs.getLong(c++);
            String group = grouped ? rs.getString(c++) : null;
            return new Row(sec, group, rs.getLong(c++), rs.getLong(c++), rs.getDouble(c++), rs.getDouble(c++),
                    rs.getDouble(c++), rs.getDouble(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c++),
                    rs.getLong(c++), rs.getLong(c));
        }
    }

    /**
     * Per-bucket ratios from the component sums; rates per second so every series
     * keeps its native unit. {@code errorPct} is 4xx + 5xx over samples (the
     * dashboard's definition), not JMeter's success flag.
     */
    static Series series(List<Row> rows, int g) {
        List<TimeseriesPoint> tps = new ArrayList<>(rows.size());
        List<TimeseriesPoint> avg = new ArrayList<>(rows.size());
        List<TimeseriesPoint> err = new ArrayList<>(rows.size());
        List<TimeseriesPoint> p90 = new ArrayList<>(rows.size());
        List<TimeseriesPoint> p95 = new ArrayList<>(rows.size());
        List<TimeseriesPoint> p99 = new ArrayList<>(rows.size());
        Map<String, List<TimeseriesPoint>> codes = new LinkedHashMap<>();
        for (String k : List.of("2xx", "3xx", "4xx", "5xx", "other")) codes.put(k, new ArrayList<>(rows.size()));
        for (Row r : rows) {
            double samples = r.samples();
            tps.add(new TimeseriesPoint(r.sec(), samples / g));
            avg.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wAvg() / samples : 0));
            // Error % is HTTP 4xx + 5xx over samples — the hosted "Error %" panels' definition.
            err.add(new TimeseriesPoint(r.sec(), samples > 0 ? 100.0 * (r.h4() + r.h5()) / samples : 0));
            p90.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wP90() / samples : 0));
            p95.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wP95() / samples : 0));
            p99.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wP99() / samples : 0));
            codes.get("2xx").add(new TimeseriesPoint(r.sec(), (double) r.h2() / g));
            codes.get("3xx").add(new TimeseriesPoint(r.sec(), (double) r.h3() / g));
            codes.get("4xx").add(new TimeseriesPoint(r.sec(), (double) r.h4() / g));
            codes.get("5xx").add(new TimeseriesPoint(r.sec(), (double) r.h5() / g));
            codes.get("other").add(new TimeseriesPoint(r.sec(), (double) r.ho() / g));
        }
        codes.values().removeIf(pts -> pts.stream().allMatch(p -> p.v() == 0));
        return new Series(tps, avg, err, codes, p90, p95, p99);
    }

    static Map<String, Series> split(List<Row> rows, int g) {
        Map<String, List<Row>> byGroup = new TreeMap<>();
        for (Row r : rows) {
            byGroup.computeIfAbsent(r.group() == null ? "(none)" : r.group(), k -> new ArrayList<>()).add(r);
        }
        Map<String, Series> out = new LinkedHashMap<>();
        byGroup.forEach((k, v) -> out.put(k, series(v, g)));
        return out;
    }
}
