package com.perf.metricsconsumer.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.observability.ErrorContext;
import com.perf.metricsconsumer.model.WorkerMetricBatch;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import com.perf.metricsconsumer.util.RateLimitedLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Explodes {@link WorkerMetricBatch} envelopes into per-row INSERTs against
 * {@code metrics."workerMetric"}, maintaining the three rollup tables in the
 * same statement — one round-trip per chunk, which is what keeps the consumer
 * ahead of the fleet.
 *
 * <p>Duplicate deliveries collapse to no-ops on the
 * {@code (runId, workerId, label, windowSecond)} primary key, so a worker
 * replaying its disk buffer is always safe.
 *
 * <p><b>Do not move the rollups out of this statement.</b>
 * {@code metrics."runSecond"}, {@code "runSecondStatus"} and {@code "runLabel"}
 * are what every orchestrator read actually queries, and they are maintained
 * as deltas. The raw INSERT is idempotent, but {@code += delta} is not — so the
 * insert runs in a CTE whose {@code RETURNING} feeds the rollup upserts
 * <b>only the rows that actually landed</b>. That is the entire correctness
 * argument: a replayed envelope contributes nothing because it inserted
 * nothing. A background aggregator would double-count replays, and a
 * watermark-based one would also miss arbitrarily late arrivals.
 *
 * <p>The deliberate cost: a rollup failure fails the whole statement, so the
 * envelope is rejected 503 and retried. Raw and rollup can then never disagree,
 * and a broken rollup is loud rather than silently drifting.
 * {@code metrics."rebuildRunRollups"(runId)} repairs them if it ever happens.
 *
 * <p>That SQL function duplicates the aggregation arithmetic below.
 * {@code MetricsConsumerWriteIT} pins the two together — ingest, rebuild,
 * assert identical — so changing one fails on the other.
 *
 * <p>A single call can deliver tens of thousands of rows post-explode, so
 * {@link #maxRowsPerInsert} (default 5000) bounds the bind buffer and
 * Postgres parse time per statement.
 */
@Component
public class WorkerMetricWriter {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricWriter.class);

    /**
     * One line per 10 s per key. Keys are a small fixed set (see the class
     * doc on {@link RateLimitedLogger}) — never per-row values, or the map
     * grows without bound.
     */
    private static final RateLimitedLogger RL_LOG =
            new RateLimitedLogger(LOG, /* minIntervalMs */ 10_000L);

    /**
     * Columns inserted, in bind order, which is also the table's physical order
     * — not required, but it keeps this list diffable against the migration.
     *
     * <p>The wire carries more fields than this list persists
     * ({@code windowTimestamp}, {@code errorRate}, {@code joinedAtSecond},
     * {@code rawMaxMs}, {@code minMs}, {@code avgRespTimeMs}). They are still
     * accepted and validated; they simply have no reader.
     */
    private static final String COLUMNS =
            "\"windowSecond\",\"sumElapsedMs\",\"bytesReceived\",\"bytesSent\","
            + "\"throughput\",\"errorCount\","
            + "\"p50Ms\",\"p90Ms\",\"p95Ms\",\"p99Ms\",\"maxMs\",\"activeThreads\","
            + "\"runId\",\"workerId\",\"label\",\"region\",\"statusCodes\"";

    /** Number of bind parameters per row — must match COLUMNS. */
    private static final int PARAMS_PER_ROW = 17;

    private static final String ROW_PLACEHOLDER =
            "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)";

    /**
     * Everything after the VALUES list: the conflict clause, the RETURNING that
     * makes the deltas exactly-once, and the three rollup upserts.
     *
     * <p>Each rollup SELECT is {@code GROUP BY}-ed to its own key before the
     * upsert. That is mandatory, not tidiness — two rows of the same statement
     * hitting one conflict target would raise "cannot affect row a second time".
     * The {@code ORDER BY} on the conflict key makes concurrent consumer
     * connections acquire row locks in a consistent order; Postgres does not
     * *promise* lock order from ORDER BY, but it removes the realistic deadlock
     * shape (two statements touching the same label set from opposite ends).
     *
     * <p><b>Every percentile product needs its explicit {@code ::bigint}.</b>
     * Both operands are {@code INTEGER} columns and Postgres evaluates
     * {@code integer * integer} as {@code integer}, so a row pairing a
     * multi-second p99 with a four-digit throughput overflows int4 and aborts
     * the statement — which the worker sees as 503 and retries forever.
     * Widening one side promotes the product. {@code metrics."rebuildRunRollups"}
     * carries identical casts and the agreement IT fails if they diverge.
     */
    private static final String ROLLUP_SUFFIX = """
             ON CONFLICT ("runId","workerId","label","windowSecond") DO NOTHING
             RETURNING "runId","windowSecond","region","label","throughput","errorCount",\
             "sumElapsedMs","p50Ms","p90Ms","p95Ms","p99Ms","maxMs","activeThreads",\
             "bytesReceived","bytesSent","statusCodes"
            ), "sec" AS (
             INSERT INTO metrics."runSecond" AS t (
               "runId","windowSecond","region","rowCount","samples","errors",
               "sumElapsedMs","sumP50Weighted","sumP90Weighted","sumP95Weighted",
               "sumP99Weighted","maxMs","maxActiveThreads","bytesReceived","bytesSent")
             SELECT "runId","windowSecond","region", count(*),
                    sum("throughput")::bigint, sum("errorCount")::bigint,
                    sum("sumElapsedMs"),
                    sum("p50Ms"::bigint * "throughput"), sum("p90Ms"::bigint * "throughput"),
                    sum("p95Ms"::bigint * "throughput"), sum("p99Ms"::bigint * "throughput"),
                    max("maxMs"), max("activeThreads")::bigint,
                    sum("bytesReceived")::bigint, sum("bytesSent")::bigint
             FROM "ins"
             GROUP BY "runId","windowSecond","region"
             ORDER BY "runId","windowSecond","region"
             ON CONFLICT ("runId","windowSecond","region") DO UPDATE SET
               "rowCount"         = t."rowCount"       + EXCLUDED."rowCount",
               "samples"          = t."samples"        + EXCLUDED."samples",
               "errors"           = t."errors"         + EXCLUDED."errors",
               "sumElapsedMs"     = t."sumElapsedMs"   + EXCLUDED."sumElapsedMs",
               "sumP50Weighted"   = t."sumP50Weighted" + EXCLUDED."sumP50Weighted",
               "sumP90Weighted"   = t."sumP90Weighted" + EXCLUDED."sumP90Weighted",
               "sumP95Weighted"   = t."sumP95Weighted" + EXCLUDED."sumP95Weighted",
               "sumP99Weighted"   = t."sumP99Weighted" + EXCLUDED."sumP99Weighted",
               "maxMs"            = GREATEST(t."maxMs", EXCLUDED."maxMs"),
               "maxActiveThreads" = GREATEST(t."maxActiveThreads", EXCLUDED."maxActiveThreads"),
               "bytesReceived"    = t."bytesReceived"  + EXCLUDED."bytesReceived",
               "bytesSent"        = t."bytesSent"      + EXCLUDED."bytesSent"
            ), "st" AS (
             INSERT INTO metrics."runSecondStatus" AS t ("runId","windowSecond","region","code","n")
             SELECT i."runId", i."windowSecond", i."region", j.key,
                    sum((j.value)::bigint)::bigint
             FROM "ins" i, LATERAL jsonb_each_text(i."statusCodes") AS j
             GROUP BY i."runId", i."windowSecond", i."region", j.key
             ORDER BY i."runId", i."windowSecond", i."region", j.key
             ON CONFLICT ("runId","windowSecond","region","code") DO UPDATE SET
               "n" = t."n" + EXCLUDED."n"
            ), "lbl" AS (
             INSERT INTO metrics."runLabel" AS t (
               "runId","label","rowCount","samples","errors","sumElapsedMs",
               "sumP50","sumP90","sumP95","sumP99",
               "sumP50Weighted","sumP90Weighted","sumP95Weighted","sumP99Weighted",
               "maxMs","maxActiveThreads","bytesReceived","bytesSent",
               "firstSecond","lastSecond")
             SELECT "runId","label", count(*),
                    sum("throughput")::bigint, sum("errorCount")::bigint,
                    sum("sumElapsedMs"),
                    sum("p50Ms"), sum("p90Ms"), sum("p95Ms"), sum("p99Ms"),
                    sum("p50Ms"::bigint * "throughput"), sum("p90Ms"::bigint * "throughput"),
                    sum("p95Ms"::bigint * "throughput"), sum("p99Ms"::bigint * "throughput"),
                    max("maxMs"), max("activeThreads")::bigint,
                    sum("bytesReceived")::bigint, sum("bytesSent")::bigint,
                    min("windowSecond")::bigint, max("windowSecond")::bigint
             FROM "ins"
             GROUP BY "runId","label"
             ORDER BY "runId","label"
             ON CONFLICT ("runId","label") DO UPDATE SET
               "rowCount"         = t."rowCount"       + EXCLUDED."rowCount",
               "samples"          = t."samples"        + EXCLUDED."samples",
               "errors"           = t."errors"         + EXCLUDED."errors",
               "sumElapsedMs"     = t."sumElapsedMs"   + EXCLUDED."sumElapsedMs",
               "sumP50"           = t."sumP50"         + EXCLUDED."sumP50",
               "sumP90"           = t."sumP90"         + EXCLUDED."sumP90",
               "sumP95"           = t."sumP95"         + EXCLUDED."sumP95",
               "sumP99"           = t."sumP99"         + EXCLUDED."sumP99",
               "sumP50Weighted"   = t."sumP50Weighted" + EXCLUDED."sumP50Weighted",
               "sumP90Weighted"   = t."sumP90Weighted" + EXCLUDED."sumP90Weighted",
               "sumP95Weighted"   = t."sumP95Weighted" + EXCLUDED."sumP95Weighted",
               "sumP99Weighted"   = t."sumP99Weighted" + EXCLUDED."sumP99Weighted",
               "maxMs"            = GREATEST(t."maxMs", EXCLUDED."maxMs"),
               "maxActiveThreads" = GREATEST(t."maxActiveThreads", EXCLUDED."maxActiveThreads"),
               "bytesReceived"    = t."bytesReceived"  + EXCLUDED."bytesReceived",
               "bytesSent"        = t."bytesSent"      + EXCLUDED."bytesSent",
               "firstSecond"      = LEAST(t."firstSecond", EXCLUDED."firstSecond"),
               "lastSecond"       = GREATEST(t."lastSecond", EXCLUDED."lastSecond")
            )
            SELECT count(*) FROM "ins"\
            """;

    /**
     * Key order of the raw table's primary key. Chunks are sorted by it before
     * binding so each statement walks the index leaves forward instead of
     * scattering across them — cheap CPU, materially fewer page touches per
     * INSERT once a run's index no longer fits in cache.
     */
    private static final Comparator<Row> BY_PRIMARY_KEY =
            Comparator.<Row, String>comparing(r -> r.envelope().runId())
                    .thenComparing(r -> r.envelope().workerId())
                    .thenComparing(r -> r.entry().label())
                    .thenComparingLong(r -> r.envelope().windowSecond());

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
     *         than total entry count if duplicates conflicted on the PK. This is
     *         also exactly the number of rows that contributed to the rollups.
     */
    public int writeBatch(List<WorkerMetricBatch> envelopes) {
        if (envelopes.isEmpty()) {
            return 0;
        }

        List<Row> rows = explode(envelopes);
        if (rows.isEmpty()) {
            return 0;
        }
        rows.sort(BY_PRIMARY_KEY);

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
            // The statement's final SELECT is the count of rows that actually
            // landed, so this is a query, not an update — JdbcTemplate.update()
            // would have no update count to report. Both functional arguments are
            // typed explicitly: with two bare lambdas the compiler can also see
            // query(String, ResultSetExtractor, Object...) as a candidate.
            PreparedStatementSetter setter = ps -> bindChunk(ps, chunk);
            ResultSetExtractor<Integer> countExtractor = rs -> rs.next() ? rs.getInt(1) : 0;
            Integer written = jdbc.query(sql, setter, countExtractor);
            return written == null ? 0 : written;
        } catch (Exception e) {
            // Build a low-cardinality context — the per-chunk row count
            // tells the operator how big the batch was when it failed,
            // which often hints at whether Postgres is saturated vs a
            // schema/binding bug. Sample the first row's runId so the
            // operator can grep for the specific test's lines.
            String firstRunId = chunk.isEmpty() ? "(empty)" : chunk.get(0).envelope().runId();
            ErrorContext.logError(LOG,
                    "writeBatch rows=" + chunk.size() + " firstRunId=" + firstRunId,
                    "Failed to insert chunk of " + chunk.size() + " rows",
                    e);
            // Re-throw so IngestController maps the failure to 503 and the
            // worker's disk-buffer sweeper retries the envelope.
            throw e;
        }
    }

    /** Explodes envelopes into a flat row list, projecting envelope identity onto each entry. */
    static List<Row> explode(List<WorkerMetricBatch> envelopes) {
        int total = 0;
        for (WorkerMetricBatch env : envelopes) {
            total += env.entries().size();
        }
        List<Row> rows = new ArrayList<>(total);
        for (WorkerMetricBatch env : envelopes) {
            for (WorkerMetricEntry entry : env.entries()) {
                rows.add(new Row(env, entry));
            }
        }
        return rows;
    }

    private static String buildInsertSql(int rowCount) {
        StringBuilder sb = new StringBuilder(
                ROLLUP_SUFFIX.length() + 96 + rowCount * (ROW_PLACEHOLDER.length() + 1));
        sb.append("WITH \"ins\" AS (\n INSERT INTO metrics.\"workerMetric\" (")
          .append(COLUMNS).append(") VALUES ");
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) sb.append(',');
            sb.append(ROW_PLACEHOLDER);
        }
        sb.append('\n').append(ROLLUP_SUFFIX);
        return sb.toString();
    }

    private void bindChunk(PreparedStatement ps, List<Row> chunk) throws SQLException {
        int p = 1;
        for (Row r : chunk) {
            WorkerMetricBatch env = r.envelope;
            WorkerMetricEntry entry = r.entry;
            // Bind order follows COLUMNS, which follows the table's physical
            // physical order: 8-byte fixed, 4-byte fixed, then varlena.
            ps.setLong(p++, env.windowSecond());
            // The exact total, or — for a worker predating Phase 2 — the best
            // reconstruction of it. See WorkerMetricEntry#resolvedSumElapsedMs.
            ps.setLong(p++, entry.resolvedSumElapsedMs());
            ps.setLong(p++, entry.bytesReceived());
            ps.setLong(p++, entry.bytesSent());

            // setInt, not setLong: these columns are INTEGER since Phase 2, and
            // the wire still carries longs. The narrowing is safe by construction
            // — throughput is samples in ONE second for ONE label on ONE worker,
            // and the percentiles come out of a histogram capped at 3,600,000 ms
            // — but it is checked rather than assumed, because a silent overflow
            // here would corrupt a metric instead of failing.
            ps.setInt(p++, toInt(entry.throughput(),    "throughput",    entry.label()));
            ps.setInt(p++, toInt(entry.errorCount(),    "errorCount",    entry.label()));
            ps.setInt(p++, toInt(round(entry.p50Ms()),  "p50Ms",         entry.label()));
            ps.setInt(p++, toInt(round(entry.p90Ms()),  "p90Ms",         entry.label()));
            ps.setInt(p++, toInt(round(entry.p95Ms()),  "p95Ms",         entry.label()));
            ps.setInt(p++, toInt(round(entry.p99Ms()),  "p99Ms",         entry.label()));
            // "maxMs" is fed the wire's rawMaxMs — the EXACT unclamped maximum.
            // The entry's own maxMs is an HDRHistogram bucket bound, quantized to
            // 2 significant digits and capped at 3,600,000 ms, so it misreports
            // every timeout row. Both stay on the wire; only which one
            // is stored changed. rawMaxMs is the one value here with no
            // architectural ceiling, so the range check earns its keep.
            ps.setInt(p++, toInt(entry.rawMaxMs(),      "maxMs",         entry.label()));
            ps.setInt(p++, toInt(entry.activeThreads(), "activeThreads", entry.label()));

            ps.setString(p++, env.runId());
            ps.setString(p++, env.workerId());
            ps.setString(p++, entry.label());
            ps.setString(p++, env.region());
            ps.setString(p++, toJsonString(entry.statusCodes()));
        }
        if ((p - 1) != chunk.size() * PARAMS_PER_ROW) {
            throw new IllegalStateException(
                    "Bind count mismatch: bound " + (p - 1) + ", expected "
                            + chunk.size() * PARAMS_PER_ROW);
        }
    }

    /**
     * Encodes the entry's {@code statusCodes} map as a JSON string (null on
     * the wire → {@code {}}). The matching SQL placeholder casts the string
     * via {@code ?::jsonb}; binding a String avoids a hard compile-time
     * dependency on the Postgres-specific {@code PGobject} type.
     */
    private String toJsonString(java.util.Map<String, Long> statusCodes) {
        try {
            return mapper.writeValueAsString(
                    statusCodes == null ? java.util.Collections.emptyMap() : statusCodes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode statusCodes as JSON", e);
        }
    }

    /** Rounds a wire double to the whole millisecond the column now stores. */
    private static long round(double wireMs) {
        return Math.round(wireMs);
    }

    /**
     * Narrows a wire {@code long} into an {@code INTEGER} column, clamping
     * rather than failing if it somehow does not fit.
     *
     * <p><b>Clamping is deliberate.</b> An out-of-range bind aborts the chunk,
     * which becomes a 503, which the worker retries from its disk buffer
     * forever — the envelope never becomes valid, so one absurd value would
     * stall the whole fleet's ingest. Clamping loses precision on a single
     * already-nonsensical number, keeps the rest of the batch, and WARNs.
     *
     * <p>It should never fire: every value here is bounded far below 2^31 —
     * throughput is one label's sample count in one second, percentiles come
     * from a histogram capped at 3,600,000 ms, and even the un-histogrammed
     * {@code rawMaxMs} would need 24 days on one sample.
     */
    private static int toInt(long value, String column, String label) {
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            RL_LOG.warn("METRIC_VALUE_CLAMPED",
                    "Value {} for column \"{}\" (label={}) exceeds its INTEGER column "
                    + "— clamping. The metric is wrong for this row; ingest continues.",
                    value, column, label);
            return value > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        return (int) value;
    }

    /** Internal flat row pairing one entry with its parent envelope's identity fields. */
    record Row(WorkerMetricBatch envelope, WorkerMetricEntry entry) { }
}
