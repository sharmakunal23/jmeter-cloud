package com.perf.metricsconsumer.jdbc;

import com.perf.metricsconsumer.model.WireBounds;
import com.perf.metricsconsumer.model.WorkerMetricBatch;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import com.perf.metricsconsumer.observability.ErrorContext;
import com.perf.metricsconsumer.util.RateLimitedLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lands one {@link WorkerMetricBatch} in its group's fact table: the run and
 * worker are resolved once per envelope and each label once per entry
 * ({@link DimensionResolver}), then the rows are inserted in chunks of
 * {@code metricsConsumer.maxRowsPerInsert} with
 * {@code IGNORE_ROW_ON_DUPKEY_INDEX} on the primary key — first write wins,
 * a replayed row counts 0, and {@code rowsInserted} is the rows that actually
 * landed. Nothing here updates, deletes or holds a transaction.
 *
 * <p>Before a chunk is built, the labels already landed for this
 * {@code (RUN_ID, WORKER_ID, WINDOW_SECOND)} are read with one primary-key-prefix
 * probe and left out: a batch must never carry a known duplicate, because on
 * Oracle Free 26ai a JDBC array insert in which the hint suppresses a row is
 * {@code ORA-00600} and a dead session (see {@code oracle/docs/metricsSchema.md}).
 * A duplicate that still slips in — a concurrent replica — surfaces as a 503 and
 * a replay, which the probe then filters.
 */
@Component
public class WorkerMetricWriter {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricWriter.class);
    private static final RateLimitedLogger RL_LOG = new RateLimitedLogger(LOG, 10_000L);

    static final int PARAMS_PER_ROW = 21;

    private static final String INSERT_TEMPLATE =
            "INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(%1$s(RUN_ID,WORKER_ID,LABEL_ID,WINDOW_SECOND)) */ "
            + "INTO %1$s (RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND, "
            + "THROUGHPUT, ERROR_COUNT, AVG_MS, "
            + "P50_MS, P90_MS, P95_MS, P99_MS, MIN_MS, MAX_MS, "
            + "BYTES_RECV, BYTES_SENT, "
            + "HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX, HTTP_OTHER, "
            + "ACTIVE_THREADS) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String PROBE_TEMPLATE =
            "SELECT LABEL_ID FROM %s WHERE RUN_ID = ? AND WORKER_ID = ? AND WINDOW_SECOND = ?";

    private final JdbcTemplate jdbc;
    private final DimensionResolver dims;
    private final int maxRowsPerInsert;
    /** One entry per fact table — bounded by the number of groups. */
    private final ConcurrentHashMap<String, String> insertSqlByTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> probeSqlByTable = new ConcurrentHashMap<>();

    public WorkerMetricWriter(JdbcTemplate jdbc, DimensionResolver dims,
                              @Value("${metricsConsumer.maxRowsPerInsert:5000}") int maxRowsPerInsert) {
        this.jdbc = jdbc;
        this.dims = dims;
        if (maxRowsPerInsert < 1) {
            throw new IllegalArgumentException("maxRowsPerInsert must be >= 1, got: " + maxRowsPerInsert);
        }
        this.maxRowsPerInsert = maxRowsPerInsert;
    }

    /** @return rows that actually landed — 0 for a full replay, which is success */
    public int write(GroupTarget target, WorkerMetricBatch env) {
        if (env.entries().isEmpty()) {
            return 0;
        }
        long runId = dims.runId(target.prefix(), env.runId(), env.windowSecond());
        long workerId = dims.workerId(runId, target.prefix(), env.workerId(), env.region(), env.joinedAtSecond());

        // One row per label; an in-envelope duplicate keeps its first entry.
        Map<Long, Row> byLabel = new LinkedHashMap<>();
        for (WorkerMetricEntry entry : env.entries()) {
            long labelId = dims.labelId(target.prefix(), boundedLabel(entry.label()), target.classifyFn(), env.windowSecond());
            if (byLabel.putIfAbsent(labelId, new Row(labelId, entry)) != null) {
                RL_LOG.warn("INGEST_DUPLICATE_KEY",
                        "Dropped an in-envelope duplicate label for runId={} workerId={} windowSecond={}",
                        env.runId(), env.workerId(), env.windowSecond());
            }
        }

        String table = target.metricsTable();
        Set<Long> landed = new HashSet<>(jdbc.queryForList(
                probeSqlFor(table), Long.class, runId, workerId, env.windowSecond()));
        List<Row> rows = rowsToInsert(new ArrayList<>(byLabel.values()), landed);

        int inserted = 0;
        for (int from = 0; from < rows.size(); from += maxRowsPerInsert) {
            inserted += insertChunk(table, runId, workerId, env.windowSecond(),
                    rows.subList(from, Math.min(from + maxRowsPerInsert, rows.size())));
        }
        return inserted;
    }

    private int insertChunk(String table, long runId, long workerId, long windowSecond, List<Row> chunk) {
        try {
            int[] counts = jdbc.batchUpdate(insertSqlFor(table), new BatchPreparedStatementSetter() {
                @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                    bindRow(ps, runId, workerId, windowSecond, chunk.get(i));
                }
                @Override public int getBatchSize() {
                    return chunk.size();
                }
            });
            return countInserted(counts);
        } catch (RuntimeException e) {
            // Low-cardinality context only: never labels or values.
            ErrorContext.logError(LOG, "insertChunk table=" + table + " rows=" + chunk.size() + " runId=" + runId,
                    "Failed to insert a chunk of " + chunk.size() + " rows", e);
            throw e;
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────

    /** The rows whose label has not landed for this (run, worker, window). */
    static List<Row> rowsToInsert(List<Row> rows, Set<Long> landedLabelIds) {
        if (landedLabelIds.isEmpty()) {
            return rows;
        }
        List<Row> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (!landedLabelIds.contains(r.labelId())) {
                out.add(r);
            }
        }
        return out;
    }

    /** {@code SUCCESS_NO_INFO} counts as one row; a hint-suppressed duplicate reports 0. */
    static int countInserted(int[] counts) {
        int n = 0;
        for (int c : counts) {
            n += c == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, c);
        }
        return n;
    }

    /** The table name is re-validated here, the second time, before it is spliced. */
    String insertSqlFor(String table) {
        return insertSqlByTable.computeIfAbsent(table, t -> INSERT_TEMPLATE.formatted(requireIdentifier(t)));
    }

    String probeSqlFor(String table) {
        return probeSqlByTable.computeIfAbsent(table, t -> PROBE_TEMPLATE.formatted(requireIdentifier(t)));
    }

    static String requireIdentifier(String table) {
        if (table == null || !GroupRegistry.IDENTIFIER.matcher(table).matches()) {
            throw new IllegalStateException("fact table name is not an identifier: " + table);
        }
        return table;
    }

    /** {@code LABEL_KEY} is {@code VARCHAR2(1000)} in bytes; a longer label is truncated with a WARN, not rejected. */
    static String boundedLabel(String label) {
        if (label.getBytes(StandardCharsets.UTF_8).length <= WireBounds.LABEL_BYTES) {
            return label;
        }
        String cut = label;
        while (cut.getBytes(StandardCharsets.UTF_8).length > WireBounds.LABEL_BYTES) {
            cut = cut.substring(0, cut.length() - 1);
        }
        RL_LOG.warn("INGEST_LABEL_TRUNCATED", "Label of {} chars truncated to {} bytes: {}",
                label.length(), WireBounds.LABEL_BYTES, label.substring(0, Math.min(40, label.length())) + "…");
        return cut;
    }

    // ── Binding — the hosted consumer's 21 positions ────────────────────

    static void bindRow(PreparedStatement ps, long runId, long workerId, long windowSecond, Row r) throws SQLException {
        WorkerMetricEntry e = r.entry();
        HttpBuckets h = HttpBuckets.fold(e.statusCodes());
        int p = 1;
        ps.setLong(p++, runId);
        ps.setLong(p++, workerId);
        ps.setLong(p++, r.labelId());
        ps.setLong(p++, windowSecond);
        ps.setLong(p++, clampCount(e.throughput(), "THROUGHPUT"));
        ps.setLong(p++, clampCount(e.errorCount(), "ERROR_COUNT"));
        ps.setDouble(p++, clampLatency(averageMs(e), "AVG_MS"));
        ps.setDouble(p++, clampLatency(e.p50Ms(), "P50_MS"));
        ps.setDouble(p++, clampLatency(e.p90Ms(), "P90_MS"));
        ps.setDouble(p++, clampLatency(e.p95Ms(), "P95_MS"));
        ps.setDouble(p++, clampLatency(e.p99Ms(), "P99_MS"));
        ps.setDouble(p++, clampLatency(e.minMs(), "MIN_MS"));
        ps.setDouble(p++, clampLatency(maxMs(e), "MAX_MS"));
        ps.setLong(p++, e.bytesReceived());
        ps.setLong(p++, e.bytesSent());
        ps.setLong(p++, clampCount(h.http2xx(), "HTTP_2XX"));
        ps.setLong(p++, clampCount(h.http3xx(), "HTTP_3XX"));
        ps.setLong(p++, clampCount(h.http4xx(), "HTTP_4XX"));
        ps.setLong(p++, clampCount(h.http5xx(), "HTTP_5XX"));
        ps.setLong(p++, clampCount(h.other(), "HTTP_OTHER"));
        ps.setLong(p, Math.min(e.activeThreads(), MAX_ACTIVE_THREADS));
        if (p != PARAMS_PER_ROW) {
            throw new IllegalStateException("bound " + p + " of " + PARAMS_PER_ROW + " parameters");
        }
    }

    /** The mean from the exact sum when the producer sends one; the wire's mean otherwise. */
    static double averageMs(WorkerMetricEntry e) {
        return e.throughput() > 0 && e.sumElapsedMs() != null
                ? (double) e.sumElapsedMs() / e.throughput()
                : e.avgRespTimeMs();
    }

    /** The exact maximum: {@code rawMaxMs} when the producer sends it, else the wire's {@code maxMs}. */
    static double maxMs(WorkerMetricEntry e) {
        return e.rawMaxMs() > 0 ? e.rawMaxMs() : e.maxMs();
    }

    /** {@code NUMBER(10)} counters. */
    static final long MAX_COUNT = 9_999_999_999L;
    /** {@code NUMBER(8)}. */
    static final long MAX_ACTIVE_THREADS = 99_999_999L;
    /** {@code NUMBER(9,1)} latencies, milliseconds. */
    static final double MAX_LATENCY_MS = 99_999_999.9d;

    /** Out-of-range values are clamped with a WARN — an aborted chunk would be a 503 the worker replays forever. */
    static long clampCount(long value, String column) {
        if (value > MAX_COUNT) {
            RL_LOG.warn("METRIC_VALUE_CLAMPED", "Value {} for {} exceeds its column — clamping", value, column);
            return MAX_COUNT;
        }
        return Math.max(0, value);
    }

    static double clampLatency(double value, String column) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        if (value > MAX_LATENCY_MS) {
            RL_LOG.warn("METRIC_VALUE_CLAMPED", "Value {} for {} exceeds its column — clamping", value, column);
            return MAX_LATENCY_MS;
        }
        return value;
    }

    /** One fact row: the resolved label and the entry that fills its measures. */
    record Row(long labelId, WorkerMetricEntry entry) { }
}
