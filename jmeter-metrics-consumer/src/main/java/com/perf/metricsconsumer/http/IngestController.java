package com.perf.metricsconsumer.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.health.ConsumerHeartbeat;
import com.perf.metricsconsumer.jdbc.WorkerMetricWriter;
import com.perf.metricsconsumer.model.WireBounds;
import com.perf.metricsconsumer.model.WorkerMetricBatch;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import com.perf.metricsconsumer.util.RateLimitedLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The platform's only metrics-ingest endpoint: every worker POSTs its
 * per-second {@link WorkerMetricBatch} here as JSON, and
 * {@link WorkerMetricWriter#writeBatch} lands it idempotently.
 *
 * <p>Identity fields, size bounds and the numeric invariants the schema's
 * CHECK constraints enforce are validated here, so a structurally valid but
 * semantically broken envelope is rejected terminally instead of looping
 * forever through the worker's retry path as a 503.
 *
 * <p><b>The status codes are a cross-component contract</b> — the worker's
 * dispatcher branches on them, so changing one changes the worker:
 *
 * <table>
 *   <caption>Response contract</caption>
 *   <tr><td>{@code 202}</td><td>Accepted, {@code {rowsInserted: N}}. N is below
 *       the entry count when duplicates collapsed on the PK. The worker drops
 *       the envelope from its disk buffer.</td></tr>
 *   <tr><td>{@code 400}</td><td>Not valid JSON, or a required field is missing.
 *       Terminal — the worker must not retry.</td></tr>
 *   <tr><td>{@code 413}</td><td>Body over {@code maxBodyBytes}. Terminal.</td></tr>
 *   <tr><td>{@code 415}</td><td>Non-JSON Content-Type. A worker predating the
 *       JSON wire lands here and retries from its buffer until rebuilt.</td></tr>
 *   <tr><td>{@code 503}</td><td>The database is unreachable or rejected the
 *       chunk. The worker retries — ingest is idempotent, so replay is safe.</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private static final Logger LOG = LoggerFactory.getLogger(IngestController.class);

    /**
     * Per-class rate-limited logger for the high-volume "rejected"
     * WARN paths. A misbehaving producer can hit these thousands of
     * times per second; without rate-limiting the Logback async queue
     * fills the heap. 1 line per second per error key + a "(N suppressed)"
     * roll-up preserves the diagnostic value without the volume.
     */
    private static final RateLimitedLogger RL_LOG =
            new RateLimitedLogger(LOG, /* minIntervalMs */ 1000L);

    private final WorkerMetricWriter writer;
    private final ConsumerHeartbeat heartbeat;
    private final ObjectMapper mapper;
    private final long maxBodyBytes;

    public IngestController(WorkerMetricWriter writer,
                            ConsumerHeartbeat heartbeat,
                            ObjectMapper mapper,
                            @Value("${metricsConsumer.ingest.maxBodyBytes:2097152}") long maxBodyBytes) {
        this.writer = writer;
        this.heartbeat = heartbeat;
        this.mapper = mapper;
        if (maxBodyBytes < 1024) {
            throw new IllegalArgumentException("maxBodyBytes too small (< 1 KB): " + maxBodyBytes);
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingest(@RequestBody byte[] body) {
        // Step 1 — body size guard. Reject oversize before parse so an
        // attacker can't pin parser memory with a huge invalid payload.
        if (body.length > maxBodyBytes) {
            // This WARN is the only signal a dropped envelope leaves.
            RL_LOG.warn("INGEST_TOO_LARGE",
                    "Rejected oversize /ingest body: {} bytes exceeds maxBodyBytes {}",
                    body.length, maxBodyBytes);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new IngestResponse(0, "PAYLOAD_TOO_LARGE",
                            "body " + body.length + " bytes exceeds maxBodyBytes " + maxBodyBytes));
        }

        // Both failure classes are terminal 400s — the worker must drop the
        // envelope, not retry. Rate-limited because a stale-image producer can
        // hit this thousands of times a second and has OOM-killed the consumer
        // through this exact path before.
        WorkerMetricBatch envelope;
        try {
            envelope = mapper.readValue(body, WorkerMetricBatch.class);
        } catch (Exception e) {
            RL_LOG.warn("INGEST_BAD_JSON",
                    "Rejected malformed /ingest body ({} bytes): {}", body.length, e.toString());
            return ResponseEntity.badRequest()
                    .body(new IngestResponse(0, "INVALID_JSON", e.getMessage()));
        }
        String violation = firstViolation(envelope);
        if (violation != null) {
            RL_LOG.warn("INGEST_BAD_JSON",
                    "Rejected invalid /ingest envelope: {}", violation);
            return ResponseEntity.badRequest()
                    .body(new IngestResponse(0, "INVALID_JSON", violation));
        }

        // Step 3 — write.
        try {
            int rowsInserted = writer.writeBatch(List.of(envelope));
            heartbeat.markBatchProcessed(rowsInserted);
            return ResponseEntity.accepted()
                    .body(new IngestResponse(rowsInserted, "ACCEPTED", null));
        } catch (DataAccessException | TransactionException e) {
            // TransactionException covers a database that is down at
            // transaction start (CannotCreateTransactionException) — not a
            // DataAccessException, but the same retryable outcome.
            RL_LOG.warn("INGEST_DB_DOWN",
                    "Rejected /ingest envelope: database unavailable: {}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new IngestResponse(0, "DATABASE_UNAVAILABLE", e.getMessage()));
        }
    }

    /**
     * Returns the first reason the envelope could never land, or null when it
     * is insertable. Mirrors the schema: identity fields present and within
     * {@link WireBounds#ID_CHARS}, {@code windowSecond} a positive epoch second,
     * counts non-negative with {@code errorCount <= throughput}. JSON decode is
     * lenient (a missing field arrives as null/0), and a violation that reached
     * the database would be a retryable 503 poisoning the worker's disk buffer.
     * An EMPTY entries list is valid — 202 with {@code rowsInserted: 0}.
     */
    static String firstViolation(WorkerMetricBatch env) {
        String v;
        if ((v = idViolation("runId", env.runId())) != null)       return v;
        if ((v = idViolation("workerId", env.workerId())) != null) return v;
        if ((v = idViolation("region", env.region())) != null)     return v;
        if (isBlank(env.windowTimestamp()))  return "windowTimestamp is required";
        if (env.windowSecond() < 1 || env.windowSecond() > WireBounds.MAX_WINDOW_SECOND) {
            return "windowSecond must be an epoch second in 1.." + WireBounds.MAX_WINDOW_SECOND
                    + ", got " + env.windowSecond();
        }
        if (env.entries() == null)           return "entries is required (may be empty, not absent)";
        for (int i = 0; i < env.entries().size(); i++) {
            WorkerMetricEntry entry = env.entries().get(i);
            String at = "entries[" + i + "]";
            if (entry == null)               return at + " is null";
            if (isBlank(entry.label()))      return at + ".label is required";
            if (entry.throughput() < 0)      return at + ".throughput must be >= 0";
            if (entry.errorCount() < 0)      return at + ".errorCount must be >= 0";
            if (entry.errorCount() > entry.throughput()) {
                return at + ".errorCount (" + entry.errorCount() + ") exceeds throughput ("
                        + entry.throughput() + ")";
            }
            if (entry.resolvedSumElapsedMs() < 0) return at + ".sumElapsedMs must be >= 0";
            if (entry.rawMaxMs() < 0)        return at + ".rawMaxMs must be >= 0";
            if (entry.activeThreads() < 0)   return at + ".activeThreads must be >= 0";
            if (entry.bytesReceived() < 0)   return at + ".bytesReceived must be >= 0";
            if (entry.bytesSent() < 0)       return at + ".bytesSent must be >= 0";
            if (entry.p50Ms() < 0 || entry.p90Ms() < 0 || entry.p95Ms() < 0 || entry.p99Ms() < 0) {
                return at + " has a negative percentile";
            }
            // Jackson decodes "Infinity"/"NaN"/1e999 into non-finite doubles, and
            // neither is caught by a "< 0" check; a NUMBER(10) column also caps
            // every count at 2^31-1. Reject here rather than clamp silently.
            if (!Double.isFinite(entry.p50Ms()) || !Double.isFinite(entry.p90Ms())
                    || !Double.isFinite(entry.p95Ms()) || !Double.isFinite(entry.p99Ms())) {
                return at + " has a non-finite percentile";
            }
            if (entry.throughput() > Integer.MAX_VALUE || entry.rawMaxMs() > Integer.MAX_VALUE
                    || entry.activeThreads() > Integer.MAX_VALUE || entry.p99Ms() > Integer.MAX_VALUE) {
                return at + " has a value beyond the column range (2147483647)";
            }
        }
        return null;
    }

    private static String idViolation(String field, String value) {
        if (isBlank(value)) return field + " is required";
        if (value.length() > WireBounds.ID_CHARS) {
            return field + " exceeds " + WireBounds.ID_CHARS + " chars (" + value.length() + ")";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Response shape for {@code POST /api/v1/ingest}.
     *
     * @param rowsInserted number of rows actually written; may be less than
     *                     envelope's entry count if duplicates collapsed
     * @param code         short status code: {@code ACCEPTED}, {@code INVALID_JSON},
     *                     {@code PAYLOAD_TOO_LARGE}, {@code DATABASE_UNAVAILABLE}
     * @param message      human-readable detail; null on success
     */
    public record IngestResponse(int rowsInserted, String code, String message) { }
}
