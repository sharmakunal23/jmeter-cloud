package com.perf.metricsconsumer.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.metricsconsumer.health.ConsumerHeartbeat;
import com.perf.metricsconsumer.jdbc.GroupRegistry;
import com.perf.metricsconsumer.jdbc.GroupTarget;
import com.perf.metricsconsumer.jdbc.UnknownGroupException;
import com.perf.metricsconsumer.jdbc.WorkerMetricWriter;
import com.perf.metricsconsumer.model.WireBounds;
import com.perf.metricsconsumer.model.WorkerMetricBatch;
import com.perf.metricsconsumer.model.WorkerMetricEntry;
import com.perf.metricsconsumer.util.RateLimitedLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform's only metrics-ingest endpoint: a worker POSTs one JSON
 * {@link WorkerMetricBatch} per window to {@code /api/v1/ingest?groupId=<g>};
 * {@link GroupRegistry} routes the group to its fact table and
 * {@link WorkerMetricWriter} lands the rows first-write-wins.
 *
 * <p>The status codes are a cross-component contract the worker's dispatcher
 * branches on: {@code 202 ACCEPTED} (the worker drops the envelope from its
 * buffer, {@code rowsInserted} 0 on a replay is success); {@code 400}
 * {@code UNKNOWN_GROUP} / {@code BAD_REQUEST} and {@code 413 PAYLOAD_TOO_LARGE}
 * are terminal; {@code 401 UNAUTHORIZED} keeps the envelope buffered;
 * {@code 503 ORACLE_UNAVAILABLE} and {@code 500 INTERNAL_ERROR} are retried
 * — ingest is idempotent, so a replay is always safe. Everything not thrown
 * from here ({@code 415}, {@code 405}, {@code 503}, {@code 500}) is shaped by
 * {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private static final Logger LOG = LoggerFactory.getLogger(IngestController.class);
    /** The rejection paths are rate-limited: a misbehaving producer can hit them thousands of times a second. */
    private static final RateLimitedLogger RL_LOG = new RateLimitedLogger(LOG, 1000L);

    private final GroupRegistry groups;
    private final WorkerMetricWriter writer;
    private final ConsumerHeartbeat heartbeat;
    private final ObjectMapper mapper;
    private final long maxBodyBytes;

    public IngestController(GroupRegistry groups,
                            WorkerMetricWriter writer,
                            ConsumerHeartbeat heartbeat,
                            ObjectMapper mapper,
                            @Value("${metricsConsumer.ingest.maxBodyBytes:2097152}") long maxBodyBytes) {
        this.groups = groups;
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
    public ResponseEntity<IngestResponse> ingest(
            @RequestParam(name = "groupId", required = false) String groupId,
            @RequestBody byte[] body) {
        // Size before parse, so an oversize garbage payload cannot pin parser memory.
        if (body.length > maxBodyBytes) {
            RL_LOG.warn("INGEST_TOO_LARGE", "Rejected oversize /ingest body: {} bytes exceeds maxBodyBytes {}",
                    body.length, maxBodyBytes);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new IngestResponse(0, "PAYLOAD_TOO_LARGE",
                            "body " + body.length + " bytes exceeds maxBodyBytes " + maxBodyBytes));
        }
        WorkerMetricBatch envelope;
        try {
            envelope = mapper.readValue(body, WorkerMetricBatch.class);
        } catch (Exception e) {
            RL_LOG.warn("INGEST_BAD_JSON", "Rejected malformed /ingest body ({} bytes): {}", body.length, e.toString());
            return ResponseEntity.badRequest().body(new IngestResponse(0, "BAD_REQUEST", e.getMessage()));
        }
        String violation = firstViolation(envelope);
        if (violation != null) {
            RL_LOG.warn("INGEST_BAD_JSON", "Rejected invalid /ingest envelope: {}", violation);
            return ResponseEntity.badRequest().body(new IngestResponse(0, "BAD_REQUEST", violation));
        }
        GroupTarget target;
        try {
            target = groups.resolve(groupId);
        } catch (UnknownGroupException e) {
            RL_LOG.warn("INGEST_UNKNOWN_GROUP", "Rejected /ingest envelope: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new IngestResponse(0, "UNKNOWN_GROUP", e.getMessage()));
        }
        // DataAccessException → 503, anything else → 500, both via GlobalExceptionHandler.
        int rowsInserted = writer.write(target, envelope);
        heartbeat.markBatchProcessed(rowsInserted);
        return ResponseEntity.accepted().body(new IngestResponse(rowsInserted, "ACCEPTED", null));
    }

    /**
     * The first reason the envelope could never land, or null. Mirrors the
     * schema: identity fields present and bounded, {@code windowSecond} a
     * positive epoch second, counts non-negative with
     * {@code errorCount <= throughput}, finite latencies. A violation that
     * reached the database would be a 503 the worker replays forever. An empty
     * {@code entries} list is valid — 202 with {@code rowsInserted: 0}.
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
        if (env.joinedAtSecond() < 0)        return "joinedAtSecond must be >= 0";
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
            // Jackson decodes "Infinity"/"NaN"/1e999 into non-finite doubles.
            if (!Double.isFinite(entry.p50Ms()) || !Double.isFinite(entry.p90Ms())
                    || !Double.isFinite(entry.p95Ms()) || !Double.isFinite(entry.p99Ms())
                    || !Double.isFinite(entry.avgRespTimeMs()) || !Double.isFinite(entry.minMs())
                    || !Double.isFinite(entry.maxMs())) {
                return at + " has a non-finite latency";
            }
            if (entry.throughput() > WireBounds.MAX_COUNT || entry.rawMaxMs() > WireBounds.MAX_COUNT
                    || entry.activeThreads() > WireBounds.MAX_COUNT || entry.p99Ms() > WireBounds.MAX_COUNT) {
                return at + " has a value beyond the column range (" + WireBounds.MAX_COUNT + ")";
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
}
