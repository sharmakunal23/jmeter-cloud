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
 */
@Repository
public class MetricsTimeseriesRepository {

    /** A response never carries more points than this; the bucket width grows to keep under it. */
    static final int BUCKET_TARGET = 1500;
    /** The window width the workers publish at; also the finest bucket. */
    public static final int WINDOW_SECONDS = 15;
    /** The Grafana granularity picker's values. */
    static final int[] NICE_BUCKETS = { 15, 30, 60 };

    private static final String COLS =
            "WINDOW_SECOND, LABEL_ID, THROUGHPUT, ERROR_COUNT, AVG_MS, P95_MS, P99_MS, "
            + "HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX, HTTP_OTHER";
    private static final String SUMS =
            "SUM(b.THROUGHPUT) AS samples, SUM(b.ERROR_COUNT) AS errors, "
            + "SUM(b.AVG_MS * b.THROUGHPUT) AS w_avg, SUM(b.P95_MS * b.THROUGHPUT) AS w_p95, "
            + "SUM(b.P99_MS * b.THROUGHPUT) AS w_p99, SUM(b.HTTP_2XX) AS h2, SUM(b.HTTP_3XX) AS h3, "
            + "SUM(b.HTTP_4XX) AS h4, SUM(b.HTTP_5XX) AS h5, SUM(b.HTTP_OTHER) AS ho";

    /** What one read asks for. {@code granularity} null = the smallest nice bucket under {@link #BUCKET_TARGET}. */
    public record Query(boolean byRegion, boolean byApplication, Integer granularity, Long windowSeconds) {
        public static final Query AGGREGATE = new Query(false, false, null, null);
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
        long[] minMax = minMax(t, run);
        if (minMax == null) {
            return new MetricsTimeseries(runKey, WINDOW_SECONDS, null, null, Series.empty());
        }
        long settledMax = minMax[1] - Math.max(0, settleSeconds);
        long to = Math.max(minMax[0], settledMax);
        long from = q.windowSeconds() != null ? Math.max(minMax[0], to - q.windowSeconds() + 1) : minMax[0];
        int g = q.granularity() != null ? q.granularity() : bucketSecondsFor(to - from + 1);
        RunWindow w = run.narrowTo(from, to);

        boolean history = t.historyTable() != null;
        Series total = series(rows(aggregateSql(t), args(t, w, g, history)), g);
        // The region split reads hot rows only (the archive has no worker dimension).
        Map<String, Series> regions = q.byRegion() ? split(rows(byRegionSql(t), args(t, w, g, false)), g) : Map.of();
        Map<String, Series> applications = q.byApplication() ? split(rows(byApplicationSql(t), args(t, w, g, history)), g) : Map.of();

        Long fromSecond = total.tps().isEmpty() ? null : total.tps().get(0).sec();
        Long toSecond = total.tps().isEmpty() ? null : total.tps().get(total.tps().size() - 1).sec();
        return new MetricsTimeseries(runKey, g, fromSecond, toSecond, total, regions, applications);
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

    /** Binds in statement order: the bucket width twice, the hot branch, the history branch when present. */
    static Object[] args(MetricsTarget t, RunWindow w, int g, boolean withHistory) {
        List<Object> a = new ArrayList<>();
        a.add(g); a.add(g);
        a.add(t.runId()); a.add(w.lo()); a.add(w.hi());
        if (withHistory) {
            a.add(t.runId()); a.add(w.lo()); a.add(w.hi());
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

    record Row(long sec, String group, long samples, long errors, double wAvg, double wP95, double wP99,
               long h2, long h3, long h4, long h5, long ho) {
        static Row of(ResultSet rs) throws SQLException {
            boolean grouped = rs.getMetaData().getColumnCount() > 11;
            int c = 1;
            long sec = rs.getLong(c++);
            String group = grouped ? rs.getString(c++) : null;
            return new Row(sec, group, rs.getLong(c++), rs.getLong(c++), rs.getDouble(c++), rs.getDouble(c++),
                    rs.getDouble(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c++), rs.getLong(c));
        }
    }

    /** Per-bucket ratios from the component sums; rates per second so every series keeps its native unit. */
    static Series series(List<Row> rows, int g) {
        List<TimeseriesPoint> tps = new ArrayList<>(rows.size());
        List<TimeseriesPoint> avg = new ArrayList<>(rows.size());
        List<TimeseriesPoint> err = new ArrayList<>(rows.size());
        List<TimeseriesPoint> p95 = new ArrayList<>(rows.size());
        List<TimeseriesPoint> p99 = new ArrayList<>(rows.size());
        Map<String, List<TimeseriesPoint>> codes = new LinkedHashMap<>();
        for (String k : List.of("2xx", "3xx", "4xx", "5xx", "other")) codes.put(k, new ArrayList<>(rows.size()));
        for (Row r : rows) {
            double samples = r.samples();
            tps.add(new TimeseriesPoint(r.sec(), samples / g));
            avg.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wAvg() / samples : 0));
            err.add(new TimeseriesPoint(r.sec(), samples > 0 ? 100.0 * r.errors() / samples : 0));
            p95.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wP95() / samples : 0));
            p99.add(new TimeseriesPoint(r.sec(), samples > 0 ? r.wP99() / samples : 0));
            codes.get("2xx").add(new TimeseriesPoint(r.sec(), (double) r.h2() / g));
            codes.get("3xx").add(new TimeseriesPoint(r.sec(), (double) r.h3() / g));
            codes.get("4xx").add(new TimeseriesPoint(r.sec(), (double) r.h4() / g));
            codes.get("5xx").add(new TimeseriesPoint(r.sec(), (double) r.h5() / g));
            codes.get("other").add(new TimeseriesPoint(r.sec(), (double) r.ho() / g));
        }
        codes.values().removeIf(pts -> pts.stream().allMatch(p -> p.v() == 0));
        return new Series(tps, avg, err, codes, p95, p99);
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
