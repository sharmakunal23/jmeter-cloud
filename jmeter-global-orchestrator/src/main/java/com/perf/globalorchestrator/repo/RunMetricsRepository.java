package com.perf.globalorchestrator.repo;

import com.perf.globalorchestrator.domain.RunSummary;
import com.perf.globalorchestrator.domain.RunSummary.Stats;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Range aggregates from the run's group fact table — the headline stats behind
 * {@code GET /api/v1/runs/{runId}/summary}, the per-label table behind
 * {@code GET /api/v1/runs/{runId}/metrics} and the one-row aggregate the
 * {@code runTrend} snapshot records at completion. Same shape as
 * {@link MetricsTimeseriesRepository}: {@code RUN_ID} + a {@code WINDOW_SECOND}
 * range, hot rows {@code UNION ALL} the archived day's, throughput-weighted
 * percentiles (a sample-count-aware approximation — the histograms are not
 * persisted), division at the end. The formulas are the hosted Grafana
 * dashboard's: {@code tps} is samples over the span the rows cover
 * ({@code MAX - MIN + 15}), never over the wall clock, and "errors" in the
 * summary and {@code httpErrors} in the report are HTTP 4xx + 5xx
 * ({@code totalErrors}/{@code errorRate} keep JMeter's success flag for the AI digest).
 */
@Repository
public class RunMetricsRepository {

    /** Run-level aggregate for the {@code runTrend} snapshot; {@code rowCount} 0 ⇒ nothing landed yet. */
    public record RunAggregate(long rowCount, double p50Ms, double p95Ms, double p99Ms,
                               double errorRate, double throughputRps) { }

    private static final String COLS =
            "WINDOW_SECOND, LABEL_ID, THROUGHPUT, ERROR_COUNT, HTTP_4XX, HTTP_5XX, AVG_MS, P50_MS, P90_MS, P95_MS, P99_MS, "
            + "MAX_MS, ACTIVE_THREADS";

    /** The stat columns every aggregate here selects, in {@link #readStats} order. */
    private static final String STATS =
            "SUM(x.THROUGHPUT) AS samples, SUM(x.HTTP_4XX + x.HTTP_5XX) AS errors, "
            + "SUM(x.THROUGHPUT) / NULLIF(MAX(x.WINDOW_SECOND) - MIN(x.WINDOW_SECOND) + " + MetricsTimeseriesRepository.WINDOW_SECONDS + ", 0) AS tps, "
            + "SUM(x.AVG_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS avg_ms, "
            + "SUM(x.P90_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS p90_ms, "
            + "SUM(x.P95_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS p95_ms, "
            + "SUM(x.P99_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS p99_ms, "
            + "MAX(x.MAX_MS) AS max_ms, MAX(x.ACTIVE_THREADS) AS max_threads, "
            + "MIN(x.WINDOW_SECOND) AS first_sec, MAX(x.WINDOW_SECOND) AS last_sec";

    private final JdbcTemplate jdbc;

    public RunMetricsRepository(@Qualifier("metricsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per label with the keys the UI's aggregate report and the AI digest read. */
    public List<Map<String, Object>> rollupByLabel(MetricsTarget t, RunWindow w) {
        return rollupByLabel(t, w, null);
    }

    /**
     * As {@link #rollupByLabel(MetricsTarget, RunWindow)}, narrowed to labels
     * starting with {@code labelPrefix} (a {@link MetricsTimeseriesRepository#likePrefix}
     * pattern; null = every label). Busiest label first, like the hosted report.
     */
    public List<Map<String, Object>> rollupByLabel(MetricsTarget t, RunWindow w, String likePattern) {
        return rollupByLabel(t, w, likePattern, MetricsTimeseriesRepository.LABELS_ALL);
    }

    /**
     * As above, keeping only the {@code limit} busiest labels
     * ({@link MetricsTimeseriesRepository#LABELS_ALL} = every label) — a bounded
     * listing, {@code FETCH FIRST} after the ordered aggregate.
     */
    public List<Map<String, Object>> rollupByLabel(MetricsTarget t, RunWindow w, String likePattern, int limit) {
        boolean bounded = limit > MetricsTimeseriesRepository.LABELS_ALL;
        List<Object> a = new ArrayList<>(List.of(args(t, w, likePattern)));
        if (bounded) {
            a.add(Math.min(limit, MetricsTimeseriesRepository.LABELS_MAX));
        }
        return jdbc.query(rollupByLabelSql(t, likePattern != null, bounded), CAMEL_KEYS, a.toArray());
    }

    /**
     * Column labels are UPPER_SNAKE like every identifier; the rollup's keys are
     * the API's camelCase ({@code TOTAL_THROUGHPUT} → {@code totalThroughput}).
     */
    static final ColumnMapRowMapper CAMEL_KEYS = new ColumnMapRowMapper() {
        @Override
        protected String getColumnKey(String columnName) {
            return OracleBind.camel(columnName);
        }
    };

    static String rollupByLabelSql(MetricsTarget t, boolean withPrefix) {
        return rollupByLabelSql(t, withPrefix, false);
    }

    static String rollupByLabelSql(MetricsTarget t, boolean withPrefix, boolean bounded) {
        return "SELECT l.LABEL_KEY AS LABEL, l.APPLICATION AS APPLICATION, "
                + "       SUM(x.THROUGHPUT) AS TOTAL_THROUGHPUT, SUM(x.ERROR_COUNT) AS TOTAL_ERRORS, "
                + "       CASE WHEN SUM(x.THROUGHPUT) > 0 THEN SUM(x.ERROR_COUNT) / SUM(x.THROUGHPUT) ELSE 0 END AS ERROR_RATE, "
                + "       SUM(x.HTTP_4XX + x.HTTP_5XX) AS HTTP_ERRORS, "
                + "       CASE WHEN SUM(x.THROUGHPUT) > 0 THEN SUM(x.HTTP_4XX + x.HTTP_5XX) / SUM(x.THROUGHPUT) ELSE 0 END AS HTTP_ERROR_RATE, "
                + "       SUM(x.THROUGHPUT) / NULLIF(MAX(x.WINDOW_SECOND) - MIN(x.WINDOW_SECOND) + "
                + MetricsTimeseriesRepository.WINDOW_SECONDS + ", 0) AS THROUGHPUT_RPS, "
                + "       SUM(x.AVG_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS AVG_MS, "
                + "       SUM(x.P50_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS AVG_P50_MS, "
                + "       SUM(x.P90_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS AVG_P90_MS, "
                + "       SUM(x.P95_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS AVG_P95_MS, "
                + "       SUM(x.P99_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0) AS AVG_P99_MS, "
                + "       MAX(x.MAX_MS) AS MAX_MS, MAX(x.ACTIVE_THREADS) AS MAX_ACTIVE_THREADS, "
                + "       MIN(x.WINDOW_SECOND) AS FIRST_SECOND, MAX(x.WINDOW_SECOND) AS LAST_SECOND, "
                + "       COUNT(*) AS ROW_COUNT "
                + "FROM (" + factRows(t) + ") x JOIN LABEL l ON l.LABEL_ID = x.LABEL_ID "
                + (withPrefix ? "WHERE l.LABEL_KEY LIKE ? ESCAPE '\\' " : "")
                + "GROUP BY l.LABEL_KEY, l.APPLICATION ORDER BY TOTAL_THROUGHPUT DESC, l.LABEL_KEY"
                + (bounded ? " FETCH FIRST ? ROWS ONLY" : "");
    }

    /**
     * The headline stats over {@code w} — the total and one row per
     * {@code LABEL.APPLICATION} from one {@code GROUP BY ROLLUP} statement. An
     * empty range answers {@link RunSummary#empty}.
     */
    public RunSummary summary(String runKey, MetricsTarget t, RunWindow w) {
        List<SummaryRow> rows = jdbc.query(summarySql(t), (rs, n) -> SummaryRow.of(rs), args(t, w, null));
        SummaryRow total = null;
        List<Stats> byApplication = new ArrayList<>();
        for (SummaryRow r : rows) {
            if (r.total()) {
                total = r;
            } else {
                byApplication.add(r.stats());
            }
        }
        if (total == null || total.stats().samples() == 0) {
            return RunSummary.empty(runKey);
        }
        return new RunSummary(runKey, total.firstSecond(), total.lastSecond(), total.stats(), byApplication);
    }

    static String summarySql(MetricsTarget t) {
        return "SELECT l.APPLICATION AS application, GROUPING(l.APPLICATION) AS is_total, " + STATS + " "
                + "FROM (" + factRows(t) + ") x JOIN LABEL l ON l.LABEL_ID = x.LABEL_ID "
                + "GROUP BY ROLLUP(l.APPLICATION) ORDER BY is_total DESC, samples DESC";
    }

    /** One {@code ROLLUP} row: the grand total ({@code GROUPING = 1}) or one application. */
    record SummaryRow(boolean total, Stats stats, long firstSecond, long lastSecond) {
        static SummaryRow of(ResultSet rs) throws SQLException {
            boolean total = rs.getInt("IS_TOTAL") == 1;
            Stats stats = readStats(rs, total ? null : rs.getString("APPLICATION"));
            return new SummaryRow(total, stats, rs.getLong("FIRST_SEC"), rs.getLong("LAST_SEC"));
        }
    }

    /** Reads the {@link #STATS} columns; {@code NUMBER} nulls (an empty group) read as zero. */
    static Stats readStats(ResultSet rs, String application) throws SQLException {
        long samples = rs.getLong("SAMPLES");
        long errors = rs.getLong("ERRORS");
        return new Stats(application, samples, errors, rs.getDouble("TPS"),
                samples > 0 ? 100.0 * errors / samples : 0.0,
                rs.getDouble("AVG_MS"), rs.getDouble("P90_MS"), rs.getDouble("P95_MS"), rs.getDouble("P99_MS"),
                rs.getDouble("MAX_MS"), rs.getLong("MAX_THREADS"));
    }

    /** Always one row; {@code rowCount} 0 when the run has no rows in its range yet. */
    public RunAggregate runAggregate(MetricsTarget t, RunWindow w) {
        RunAggregate agg = jdbc.queryForObject(
                "SELECT COUNT(*) AS n, COALESCE(SUM(x.THROUGHPUT), 0) AS samples, COALESCE(SUM(x.ERROR_COUNT), 0) AS errors, "
                + "       COALESCE(MIN(x.WINDOW_SECOND), 0) AS first_sec, COALESCE(MAX(x.WINDOW_SECOND), 0) AS last_sec, "
                + "       COALESCE(SUM(x.P50_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0), 0) AS p50, "
                + "       COALESCE(SUM(x.P95_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0), 0) AS p95, "
                + "       COALESCE(SUM(x.P99_MS * x.THROUGHPUT) / NULLIF(SUM(x.THROUGHPUT), 0), 0) AS p99 "
                + "FROM (" + factRows(t) + ") x",
                (rs, n) -> {
                    long rowCount = rs.getLong("N");
                    long samples = rs.getLong("SAMPLES");
                    long errors = rs.getLong("ERRORS");
                    long span = Math.max(1, rs.getLong("LAST_SEC") - rs.getLong("FIRST_SEC") + MetricsTimeseriesRepository.WINDOW_SECONDS);
                    return new RunAggregate(rowCount, rs.getDouble("P50"), rs.getDouble("P95"), rs.getDouble("P99"),
                            samples > 0 ? (double) errors / samples : 0.0, (double) samples / span);
                },
                args(t, w));
        return agg == null ? new RunAggregate(0, 0, 0, 0, 0, 0) : agg;
    }

    static String factRows(MetricsTarget t) {
        String hot = "SELECT " + COLS + " FROM " + t.metricsTable() + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?";
        if (t.historyTable() == null) {
            return hot;
        }
        return hot + " UNION ALL SELECT " + COLS + " FROM " + t.historyTable()
                + " WHERE RUN_ID = ? AND WINDOW_SECOND BETWEEN ? AND ?";
    }

    private static Object[] args(MetricsTarget t, RunWindow w) {
        return args(t, w, null);
    }

    /** The hot branch, the history branch when present, then the label pattern when set. */
    static Object[] args(MetricsTarget t, RunWindow w, String likePattern) {
        List<Object> a = new ArrayList<>(List.of(t.runId(), w.lo(), w.hi()));
        if (t.historyTable() != null) {
            a.addAll(List.of(t.runId(), w.lo(), w.hi()));
        }
        if (likePattern != null) {
            a.add(likePattern);
        }
        return a.toArray();
    }
}
