package com.perf.metricsconsumer.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.observability.ErrorContext;
import com.perf.metricsconsumer.observability.SpanAttributes;
import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk-inserts {@link WorkerMetricBatch} envelopes into {@code metrics."workerMetric"}
 * by exploding each envelope's {@code entries[]} into per-row INSERTs.
 *
 * <p>One multi-row {@code INSERT … VALUES (?,…), (?,…) … ON CONFLICT DO NOTHING}
 * per chunk — single round-trip per chunk keeps the consumer ahead of the
 * producer at fleet scale (target: 20k+ rows/s on a single consumer instance).
 *
 * <p>Idempotency comes from the {@code (runId, workerId, label, windowSecond)}
 * primary key — duplicate Kafka deliveries (Spring Kafka redelivers on retry,
 * brokers can re-deliver on consumer rebalance) collapse to no-ops.
 *
 * <p><b>Chunking:</b> at envelope grain, a single Kafka poll can deliver
 * tens of thousands of rows post-explode (e.g. 50 envelopes × 500 entries =
 * 25k rows). The single-statement INSERT path bounds the bind buffer and
 * Postgres parse time per call to {@link #maxRowsPerInsert} (default 5000).
 */
@Component
public class WorkerMetricWriter {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricWriter.class);

    /** Columns inserted, in the same order each row binds. */
    private static final String COLUMNS =
            "\"runId\",\"workerId\",\"label\",\"windowSecond\",\"windowTimestamp\","
            + "\"region\",\"throughput\",\"errorCount\",\"errorRate\","
            + "\"avgRespTimeMs\","
            + "\"p50Ms\",\"p90Ms\",\"p95Ms\",\"p99Ms\","
            + "\"minMs\",\"maxMs\",\"rawMaxMs\","
            + "\"bytesReceived\",\"bytesSent\",\"statusCodes\",\"activeThreads\","
            + "\"joinedAtSecond\"";

    /** Number of bind parameters per row — must match COLUMNS. */
    private static final int PARAMS_PER_ROW = 22;

    private static final String ROW_PLACEHOLDER =
            "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)";

    private static final String ON_CONFLICT =
            " ON CONFLICT (\"runId\",\"workerId\",\"label\",\"windowSecond\") DO NOTHING";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int maxRowsPerInsert;

    public WorkerMetricWriter(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${metricsConsumer.maxRowsPerInsert:5000}") int maxRowsPerInsert) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        if (maxRowsPerInsert < 1) {
            throw new IllegalArgumentException(
                    "maxRowsPerInsert must be >= 1, got: " + maxRowsPerInsert);
        }
        this.maxRowsPerInsert = maxRowsPerInsert;
    }

    /**
     * Insert all rows from a list of envelopes. Each envelope's per-label
     * entries are projected onto the envelope's identity fields to form
     * per-row INSERT bindings. Chunked at {@link #maxRowsPerInsert} rows per
     * statement so a single oversized poll can't blow Postgres parse time.
     *
     * @return number of rows actually written across all chunks; may be less
     *         than total entry count if duplicates conflicted on the PK.
     */
    @Observed(name = "metricsConsumer.writeBatch",
              contextualName = "writeBatch",
              lowCardinalityKeyValues = {"action", "writeBatch"})
    public int writeBatch(List<WorkerMetricBatch> envelopes) {
        SpanAttributes.tag("envelopeCount", String.valueOf(envelopes.size()));
        if (envelopes.isEmpty()) {
            return 0;
        }

        List<Row> rows = explode(envelopes);
        if (rows.isEmpty()) {
            return 0;
        }

        int totalWritten = 0;
        for (int from = 0; from < rows.size(); from += maxRowsPerInsert) {
            int to = Math.min(from + maxRowsPerInsert, rows.size());
            List<Row> chunk = rows.subList(from, to);
            totalWritten += insertChunk(chunk);
        }
        return totalWritten;
    }

    private int insertChunk(List<Row> chunk) {
        String sql = buildInsertSql(chunk.size());
        try {
            return jdbc.update(sql, ps -> bindChunk(ps, chunk));
        } catch (Exception e) {
            // Build a low-cardinality context — the per-chunk row count
            // tells the operator how big the batch was when it failed,
            // which often hints at whether Postgres is saturated vs a
            // schema/binding bug. Sample the first row's runId so the
            // operator can grep for the specific test's lines.
            String firstRunId = chunk.isEmpty() ? "(empty)" : String.valueOf(chunk.get(0).envelope().getRunId());
            ErrorContext.logError(LOG,
                    "writeBatch rows=" + chunk.size() + " firstRunId=" + firstRunId,
                    "Failed to insert chunk of " + chunk.size() + " rows",
                    e);
            // Re-throw so the listener's error handler routes the batch to the DLQ.
            throw e;
        }
    }

    /** Explodes envelopes into a flat row list, projecting envelope identity onto each entry. */
    static List<Row> explode(List<WorkerMetricBatch> envelopes) {
        int total = 0;
        for (WorkerMetricBatch env : envelopes) {
            total += env.getEntries().size();
        }
        List<Row> rows = new ArrayList<>(total);
        for (WorkerMetricBatch env : envelopes) {
            for (WorkerMetricEntry entry : env.getEntries()) {
                rows.add(new Row(env, entry));
            }
        }
        return rows;
    }

    private static String buildInsertSql(int rowCount) {
        StringBuilder sb = new StringBuilder(64 + rowCount * (ROW_PLACEHOLDER.length() + 1));
        sb.append("INSERT INTO metrics.\"workerMetric\" (").append(COLUMNS).append(") VALUES ");
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) sb.append(',');
            sb.append(ROW_PLACEHOLDER);
        }
        sb.append(ON_CONFLICT);
        return sb.toString();
    }

    private void bindChunk(PreparedStatement ps, List<Row> chunk) throws SQLException {
        int p = 1;
        for (Row r : chunk) {
            WorkerMetricBatch env = r.envelope;
            WorkerMetricEntry entry = r.entry;
            ps.setString(p++, env.getRunId().toString());
            ps.setString(p++, env.getWorkerId().toString());
            ps.setString(p++, entry.getLabel().toString());
            ps.setLong  (p++, env.getWindowSecond());
            ps.setString(p++, env.getWindowTimestamp().toString());
            ps.setString(p++, env.getRegion().toString());
            ps.setLong  (p++, entry.getThroughput());
            ps.setLong  (p++, entry.getErrorCount());
            ps.setDouble(p++, entry.getErrorRate());
            ps.setDouble(p++, entry.getAvgRespTimeMs());
            ps.setDouble(p++, entry.getP50Ms());
            ps.setDouble(p++, entry.getP90Ms());
            ps.setDouble(p++, entry.getP95Ms());
            ps.setDouble(p++, entry.getP99Ms());
            ps.setDouble(p++, entry.getMinMs());
            ps.setDouble(p++, entry.getMaxMs());
            ps.setLong  (p++, entry.getRawMaxMs());
            ps.setLong  (p++, entry.getBytesReceived());
            ps.setLong  (p++, entry.getBytesSent());
            ps.setString(p++, toJsonString(entry.getStatusCodes()));
            ps.setLong  (p++, entry.getActiveThreads());
            // MID-TEST-SCALING Phase D — envelope-level field projected
            // onto every row for this (worker, windowSecond). 0 for
            // original-fleet workers; > 0 for mid-test scale-up joiners.
            ps.setLong  (p++, env.getJoinedAtSecond());
        }
        if ((p - 1) != chunk.size() * PARAMS_PER_ROW) {
            throw new IllegalStateException(
                    "Bind count mismatch: bound " + (p - 1) + ", expected "
                            + chunk.size() * PARAMS_PER_ROW);
        }
    }

    /**
     * Encodes Avro's {@code Map<String, Long>} as a JSON string.
     * The matching SQL placeholder casts the string via {@code ?::jsonb};
     * binding a String avoids a hard compile-time dependency on the
     * Postgres-specific {@code PGobject} type.
     */
    private String toJsonString(java.util.Map<? extends CharSequence, Long> statusCodes) {
        try {
            java.util.Map<String, Long> jsonMap;
            if (statusCodes == null || statusCodes.isEmpty()) {
                jsonMap = java.util.Collections.emptyMap();
            } else {
                jsonMap = new java.util.LinkedHashMap<>(statusCodes.size());
                for (var e : statusCodes.entrySet()) {
                    jsonMap.put(e.getKey().toString(), e.getValue());
                }
            }
            return mapper.writeValueAsString(jsonMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode statusCodes as JSON", e);
        }
    }

    /** Internal flat row pairing one entry with its parent envelope's identity fields. */
    record Row(WorkerMetricBatch envelope, WorkerMetricEntry entry) { }
}
