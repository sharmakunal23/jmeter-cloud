package com.perf.metricsconsumer.http;

import com.perf.metricsconsumer.jdbc.WorkerMetricWriter;
import com.perf.metricsconsumer.util.RateLimitedLogger;
import com.perf.orchestrator.WorkerMetricBatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * HTTP fallback ingest endpoint for {@link WorkerMetricBatch} envelopes
 * (K-4). When Kafka is unreachable from the producer
 * side, the local-orchestrator's {@code MetricsDispatcher} (K-5) falls back
 * to {@code POST /api/v1/ingest} on this service. The internal write path is
 * identical to the Kafka path — same {@link WorkerMetricWriter#writeBatch},
 * same multi-row INSERT, same {@code ON CONFLICT DO NOTHING} idempotency.
 *
 * <h2>Wire format</h2>
 * Request body is raw Avro binary (no Schema Registry consultation — the
 * {@code WorkerMetricBatch} schema is fixed at deploy time per the K-0 hard
 * cutover decision). {@code Content-Type: application/avro}.
 *
 * <h2>Response shape</h2>
 * <ul>
 *   <li>{@code 202 ACCEPTED} + {@code {rowsInserted: N}} — happy path. {@code N}
 *       may be less than the envelope's entry count if duplicates collapsed
 *       on the PK.</li>
 *   <li>{@code 400 BAD_REQUEST} — body could not be deserialised as
 *       {@link WorkerMetricBatch}. The dispatcher should NOT retry — the
 *       payload is malformed.</li>
 *   <li>{@code 413 PAYLOAD_TOO_LARGE} — body exceeded {@code maxBodyBytes}.
 *       Defense in depth against oversize envelopes.</li>
 *   <li>{@code 503 SERVICE_UNAVAILABLE} — Postgres unreachable. The
 *       dispatcher SHOULD retry via the K-5 retry sweeper.</li>
 * </ul>
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

    /** Avro reader is thread-safe — share a singleton. */
    private static final SpecificDatumReader<WorkerMetricBatch> READER =
            new SpecificDatumReader<>(WorkerMetricBatch.class);

    /** Custom MediaType for Avro binary — Spring Boot doesn't ship one. */
    public static final String APPLICATION_AVRO_VALUE = "application/avro";

    private final WorkerMetricWriter writer;
    private final long maxBodyBytes;
    private final Counter cIngested;
    private final Counter cRejectedBadRequest;
    private final Counter cRejectedTooLarge;
    private final Counter cRejectedDbDown;

    public IngestController(WorkerMetricWriter writer,
                            MeterRegistry meterRegistry,
                            @Value("${metricsConsumer.ingest.maxBodyBytes:2097152}") long maxBodyBytes) {
        this.writer = writer;
        if (maxBodyBytes < 1024) {
            throw new IllegalArgumentException("maxBodyBytes too small (< 1 KB): " + maxBodyBytes);
        }
        this.maxBodyBytes = maxBodyBytes;

        this.cIngested = Counter.builder("metricsConsumer.ingest.envelopesAccepted")
                .description("Envelopes accepted via HTTP /ingest (K-4 fallback path).")
                .register(meterRegistry);
        this.cRejectedBadRequest = Counter.builder("metricsConsumer.ingest.rejectedBadRequest")
                .description("Envelopes rejected at /ingest due to malformed Avro body.")
                .register(meterRegistry);
        this.cRejectedTooLarge = Counter.builder("metricsConsumer.ingest.rejectedTooLarge")
                .description("Envelopes rejected at /ingest because body exceeded maxBodyBytes.")
                .register(meterRegistry);
        this.cRejectedDbDown = Counter.builder("metricsConsumer.ingest.rejectedDbDown")
                .description("Envelopes rejected at /ingest because Postgres was unreachable.")
                .register(meterRegistry);
    }

    @PostMapping(value = "/ingest", consumes = APPLICATION_AVRO_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestResponse> ingest(@RequestBody byte[] body) {
        // Step 1 — body size guard. Reject oversize before deserialise so an
        // attacker can't pin Avro reader memory with a huge invalid payload.
        if (body.length > maxBodyBytes) {
            cRejectedTooLarge.increment();
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new IngestResponse(0, "PAYLOAD_TOO_LARGE",
                            "body " + body.length + " bytes exceeds maxBodyBytes " + maxBodyBytes));
        }

        // Step 2 — deserialise.
        WorkerMetricBatch envelope;
        try {
            envelope = decode(body);
        } catch (Exception e) {
            // Avro can throw IOException AND AvroRuntimeException; broad catch.
            // Rate-limited: a stale-image producer can hit this thousands of
            // times per second (incident 2026-05-15 OOM-killed the consumer
            // via this exact path).
            cRejectedBadRequest.increment();
            RL_LOG.warn("INGEST_BAD_AVRO",
                    "Rejected malformed /ingest body ({} bytes): {}", body.length, e.toString());
            return ResponseEntity.badRequest()
                    .body(new IngestResponse(0, "INVALID_AVRO", e.getMessage()));
        }

        // Step 3 — write.
        try {
            int rowsInserted = writer.writeBatch(List.of(envelope));
            cIngested.increment();
            return ResponseEntity.accepted()
                    .body(new IngestResponse(rowsInserted, "ACCEPTED", null));
        } catch (DataAccessException e) {
            cRejectedDbDown.increment();
            RL_LOG.warn("INGEST_DB_DOWN",
                    "Rejected /ingest envelope: Postgres unreachable: {}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new IngestResponse(0, "POSTGRES_UNAVAILABLE", e.getMessage()));
        }
    }

    private static WorkerMetricBatch decode(byte[] body) throws IOException {
        BinaryDecoder dec = DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(body), null);
        return READER.read(null, dec);
    }

    /**
     * Response shape for {@code POST /api/v1/ingest}.
     *
     * @param rowsInserted number of rows actually written; may be less than
     *                     envelope's entry count if duplicates collapsed
     * @param code         short status code: {@code ACCEPTED}, {@code INVALID_AVRO},
     *                     {@code PAYLOAD_TOO_LARGE}, {@code POSTGRES_UNAVAILABLE}
     * @param message      human-readable detail; null on success
     */
    public record IngestResponse(int rowsInserted, String code, String message) { }
}
