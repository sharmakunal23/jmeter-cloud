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
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Lands {@link WorkerMetricBatch} envelopes in the Oracle {@code metrics}
 * schema: each chunk is batch-inserted into the two staging tables and
 * {@code metrics."metricsIngest"."ingestStaged"} does the rest in the same
 * transaction — prunes rows already present, inserts the remainder, and
 * merges the rollup deltas from exactly those rows. Two round-trips per
 * chunk regardless of size.
 *
 * <p>Replays are safe by construction: the package drops any staged row
 * whose key is already in {@code "workerMetric"} before it inserts, and a
 * duplicate that a concurrent replica lands in between raises ORA-00001,
 * which rolls the whole chunk back into a 503 and a worker retry. The
 * returned count is the rows that actually landed, which is also exactly
 * what the rollups absorbed.
 *
 * <p>Rows are de-duplicated by primary key before staging (first wins) —
 * the package relies on it: a duplicate key in the stage would fail the raw
 * insert and become a 503 the worker replays. Labels longer than
 * {@link WireBounds#LABEL_CHARS} and codes longer than
 * {@link WireBounds#CODE_CHARS} are truncated with a WARN rather than
 * rejected, so one oversized sampler name cannot blank out a run.
 */
@Component
public class WorkerMetricWriter {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricWriter.class);

    /** One line per 10 s per key. Keys are a small fixed set — never per-row values. */
    private static final RateLimitedLogger RL_LOG =
            new RateLimitedLogger(LOG, /* minIntervalMs */ 10_000L);

    /** What a blank JMeter {@code responseCode} (non-HTTP samplers) is stored as. */
    static final String BLANK_CODE = "(none)";

    private static final String STAGE_SQL =
            "INSERT INTO metrics.\"workerMetricStage\" ("
            + "\"runId\",\"workerId\",\"label\",\"windowSecond\",\"region\","
            + "\"throughput\",\"errorCount\",\"sumElapsedMs\","
            + "\"p50Ms\",\"p90Ms\",\"p95Ms\",\"p99Ms\",\"maxMs\",\"activeThreads\","
            + "\"bytesReceived\",\"bytesSent\") "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String STAGE_STATUS_SQL =
            "INSERT INTO metrics.\"workerMetricStatusStage\" ("
            + "\"runId\",\"workerId\",\"label\",\"windowSecond\",\"region\",\"code\",\"n\") "
            + "VALUES (?,?,?,?,?,?,?)";

    /** A procedure with an OUT count: a function doing DML cannot be called from SQL (ORA-14551). */
    private static final String INGEST_CALL =
            "BEGIN metrics.\"metricsIngest\".\"ingestStaged\"(?); END;";

    /**
     * Primary-key order of the raw table. Chunks are sorted by it so equal
     * keys are adjacent for the dedupe pass and the insert walks each
     * partition's index forward.
     */
    private static final Comparator<Row> BY_PRIMARY_KEY =
            Comparator.<Row, String>comparing(Row::runId)
                    .thenComparing(Row::workerId)
                    .thenComparing(Row::label)
                    .thenComparingLong(Row::windowSecond);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxRowsPerChunk;

    public WorkerMetricWriter(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            @Value("${metricsConsumer.maxRowsPerChunk:5000}") int maxRowsPerChunk) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        if (maxRowsPerChunk < 1) {
            throw new IllegalArgumentException(
                    "maxRowsPerChunk must be >= 1, got: " + maxRowsPerChunk);
        }
        this.maxRowsPerChunk = maxRowsPerChunk;
    }

    /**
     * Writes every entry of every envelope, one transaction per chunk of
     * {@code maxRowsPerChunk} rows, each chunk holding one row per key.
     *
     * @return rows that actually landed across all chunks — below the entry
     *         count when replays or in-batch duplicates collapsed on the key
     */
    public int writeBatch(List<WorkerMetricBatch> envelopes) {
        if (envelopes.isEmpty()) {
            return 0;
        }
        List<Row> rows = dedupe(explode(envelopes));
        if (rows.isEmpty()) {
            return 0;
        }
        int totalLanded = 0;
        for (int from = 0; from < rows.size(); from += maxRowsPerChunk) {
            List<Row> chunk = rows.subList(from, Math.min(from + maxRowsPerChunk, rows.size()));
            Integer landed = tx.execute(status -> stageAndIngest(chunk));
            totalLanded += landed == null ? 0 : landed;
        }
        return totalLanded;
    }

    private int stageAndIngest(List<Row> chunk) {
        try {
            jdbc.batchUpdate(STAGE_SQL, new BatchPreparedStatementSetter() {
                @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                    bindRow(ps, chunk.get(i));
                }
                @Override public int getBatchSize() {
                    return chunk.size();
                }
            });
            List<StatusRow> statuses = statusRows(chunk);
            if (!statuses.isEmpty()) {
                jdbc.batchUpdate(STAGE_STATUS_SQL, new BatchPreparedStatementSetter() {
                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                        bindStatus(ps, statuses.get(i));
                    }
                    @Override public int getBatchSize() {
                        return statuses.size();
                    }
                });
            }
            Integer landed = jdbc.execute(INGEST_CALL, (CallableStatementCallback<Integer>) cs -> {
                cs.registerOutParameter(1, Types.NUMERIC);
                cs.execute();
                return cs.getInt(1);
            });
            return landed == null ? 0 : landed;
        } catch (RuntimeException e) {
            // Low-cardinality context: the chunk size hints at saturation vs a
            // binding bug; the first runId lets the operator grep the run.
            ErrorContext.logError(LOG,
                    "writeBatch rows=" + chunk.size() + " firstRunId=" + chunk.get(0).runId(),
                    "Failed to land chunk of " + chunk.size() + " rows",
                    e);
            // The TransactionTemplate rolls the chunk back (which also clears
            // the staging tables); IngestController maps the failure to 503.
            throw e;
        }
    }

    // ── Row shaping (pure; unit-tested) ─────────────────────────────────

    /** Projects each entry onto its envelope's identity, applying the length bounds. */
    static List<Row> explode(List<WorkerMetricBatch> envelopes) {
        int total = 0;
        for (WorkerMetricBatch env : envelopes) {
            total += env.entries().size();
        }
        List<Row> rows = new ArrayList<>(total);
        for (WorkerMetricBatch env : envelopes) {
            for (WorkerMetricEntry entry : env.entries()) {
                rows.add(new Row(
                        env.runId(), env.workerId(), boundedLabel(entry.label()),
                        env.windowSecond(), env.region(), entry));
            }
        }
        return rows;
    }

    /** Sorts by primary key and keeps the first row of each key. */
    static List<Row> dedupe(List<Row> rows) {
        rows.sort(BY_PRIMARY_KEY);
        List<Row> unique = new ArrayList<>(rows.size());
        Row previous = null;
        for (Row r : rows) {
            if (previous != null && BY_PRIMARY_KEY.compare(previous, r) == 0) {
                RL_LOG.warn("INGEST_DUPLICATE_KEY",
                        "Dropped an in-batch duplicate for runId={} workerId={} windowSecond={}",
                        r.runId(), r.workerId(), r.windowSecond());
                continue;
            }
            unique.add(r);
            previous = r;
        }
        return unique;
    }

    /** Unrolls each row's status-code map; blank codes become {@link #BLANK_CODE}, counts ≤ 0 are skipped. */
    static List<StatusRow> statusRows(List<Row> chunk) {
        List<StatusRow> out = new ArrayList<>();
        for (Row r : chunk) {
            Map<String, Long> codes = r.entry().statusCodes();
            if (codes == null) continue;
            for (Map.Entry<String, Long> c : codes.entrySet()) {
                if (c.getValue() == null || c.getValue() <= 0) continue;
                out.add(new StatusRow(r, boundedCode(c.getKey()), c.getValue()));
            }
        }
        return out;
    }

    static String boundedLabel(String label) {
        if (label.length() <= WireBounds.LABEL_CHARS) return label;
        RL_LOG.warn("INGEST_LABEL_TRUNCATED",
                "Label of {} chars truncated to {}: {}", label.length(), WireBounds.LABEL_CHARS,
                label.substring(0, 40) + "…");
        return label.substring(0, WireBounds.LABEL_CHARS);
    }

    static String boundedCode(String code) {
        if (code == null || code.isBlank()) return BLANK_CODE;
        if (code.length() <= WireBounds.CODE_CHARS) return code;
        RL_LOG.warn("INGEST_CODE_TRUNCATED",
                "Status code of {} chars truncated to {}", code.length(), WireBounds.CODE_CHARS);
        return code.substring(0, WireBounds.CODE_CHARS);
    }

    // ── Binding ─────────────────────────────────────────────────────────

    private static void bindRow(PreparedStatement ps, Row r) throws SQLException {
        WorkerMetricEntry entry = r.entry();
        int p = 1;
        ps.setString(p++, r.runId());
        ps.setString(p++, r.workerId());
        ps.setString(p++, r.label());
        ps.setLong(p++, r.windowSecond());
        ps.setString(p++, r.region());
        // NUMBER(10) columns: clamp rather than fail — an out-of-range bind
        // would 503 and the worker would replay the envelope forever.
        ps.setInt(p++, toInt(entry.throughput(),    "throughput",    r.label()));
        ps.setInt(p++, toInt(entry.errorCount(),    "errorCount",    r.label()));
        // The exact total, or — for a worker predating SCHEMA-OPT Phase 2 —
        // the best reconstruction of it (WorkerMetricEntry#resolvedSumElapsedMs).
        ps.setLong(p++, entry.resolvedSumElapsedMs());
        ps.setInt(p++, toInt(Math.round(entry.p50Ms()), "p50Ms", r.label()));
        ps.setInt(p++, toInt(Math.round(entry.p90Ms()), "p90Ms", r.label()));
        ps.setInt(p++, toInt(Math.round(entry.p95Ms()), "p95Ms", r.label()));
        ps.setInt(p++, toInt(Math.round(entry.p99Ms()), "p99Ms", r.label()));
        // "maxMs" takes the wire's rawMaxMs — the exact, unclamped maximum. The
        // entry's own maxMs is an HDRHistogram bucket edge, quantised to 2
        // significant digits and capped at 3,600,000 ms; storing it misreported
        // every timeout row. Don't "simplify" this back.
        ps.setInt(p++, toInt(entry.rawMaxMs(),      "maxMs",         r.label()));
        ps.setInt(p++, toInt(entry.activeThreads(), "activeThreads", r.label()));
        ps.setLong(p++, entry.bytesReceived());
        ps.setLong(p,   entry.bytesSent());
    }

    private static void bindStatus(PreparedStatement ps, StatusRow s) throws SQLException {
        Row r = s.row();
        ps.setString(1, r.runId());
        ps.setString(2, r.workerId());
        ps.setString(3, r.label());
        ps.setLong(4, r.windowSecond());
        ps.setString(5, r.region());
        ps.setString(6, s.code());
        ps.setInt(7, toInt(s.n(), "n", r.label()));
    }

    /**
     * Narrows a wire {@code long} into a {@code NUMBER(10)} column, clamping
     * with a WARN. It should never fire — every value here is bounded far
     * below 2^31 — but an abort here would poison the worker's buffer.
     */
    private static int toInt(long value, String column, String label) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            RL_LOG.warn("METRIC_VALUE_CLAMPED",
                    "Value {} for column \"{}\" (label={}) exceeds its NUMBER(10) column "
                    + "— clamping. The metric is wrong for this row; ingest continues.",
                    value, column, label);
            return value > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /** One raw row: the envelope's identity projected onto one entry, label already bounded. */
    record Row(String runId, String workerId, String label, long windowSecond, String region,
               WorkerMetricEntry entry) { }

    /** One status-code row of a raw row. */
    record StatusRow(Row row, String code, long n) { }
}
