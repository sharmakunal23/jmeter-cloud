package com.perf.orchestrator.buffer;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import com.perf.orchestrator.buffer.DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("DiskBackedMetricsBuffer")
class DiskBackedMetricsBufferTest {

    @TempDir Path bufferDir;

    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-05-11T12:00:00Z"));
    }

    private DiskBackedMetricsBuffer newBuffer(DiskBackedMetricsBufferConfig cfg) {
        return new DiskBackedMetricsBuffer(bufferDir, cfg, meterRegistry, clock);
    }

    private DiskBackedMetricsBuffer newBuffer() {
        return newBuffer(DiskBackedMetricsBufferConfig.defaults());
    }

    // -----------------------------------------------------------------------
    // Round-trip
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("enqueue persists envelope to disk; peekOldest returns identical envelope")
        void enqueue_then_peek_roundtrips_envelope() {
            DiskBackedMetricsBuffer buf = newBuffer();
            WorkerMetricBatch original = envelope(1_700_000_000L, "worker-1", List.of("GET /a"));

            BufferedEnvelope handle = buf.enqueue(original, "test.topic").orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(handle.envelope().getWindowSecond()).isEqualTo(1_700_000_000L);
                softly.assertThat(handle.envelope().getWorkerId().toString()).isEqualTo("worker-1");
                softly.assertThat(handle.envelope().getEntries()).hasSize(1);
                softly.assertThat(handle.file()).isNotNull();
                softly.assertThat(handle.sizeBytes()).isGreaterThan(0);
            });

            // The on-disk file exists with the .envelope.gz suffix
            assertThat(handle.file().getFileName().toString()).endsWith(".envelope.gz");
            assertThat(Files.exists(handle.file())).isTrue();

            BufferedEnvelope peeked = buf.peekOldest().orElseThrow();
            assertThat(peeked.id()).isEqualTo(handle.id());
            assertThat(peeked.envelope().getWindowSecond()).isEqualTo(1_700_000_000L);
        }

        @Test
        @DisplayName("delete removes the file from disk and clears the index entry")
        void delete_removes_from_disk_and_index() throws IOException {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope handle = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a")), "test.topic").orElseThrow();

            assertThat(Files.exists(handle.file())).isTrue();

            buf.delete(handle);

            assertThat(Files.exists(handle.file())).isFalse();
            assertThat(buf.peekOldest()).isEmpty();
            assertThat(buf.depthEnvelopes()).isZero();
            assertThat(buf.depthBytes()).isZero();
        }

        @Test
        @DisplayName("delete is idempotent — second delete is a no-op")
        void delete_is_idempotent() {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope handle = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a")), "test.topic").orElseThrow();

            buf.delete(handle);
            buf.delete(handle); // must not throw

            assertThat(buf.depthEnvelopes()).isZero();
        }

        @Test
        @DisplayName("multiple envelopes preserve chronological order via peekOldest")
        void multiple_envelopes_chronological_order() {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope first  = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a")), "test.topic").orElseThrow();
            clock.advance(Duration.ofSeconds(1));
            BufferedEnvelope second = buf.enqueue(envelope(1_700_000_001L, "w-1", List.of("b")), "test.topic").orElseThrow();
            clock.advance(Duration.ofSeconds(1));
            BufferedEnvelope third  = buf.enqueue(envelope(1_700_000_002L, "w-1", List.of("c")), "test.topic").orElseThrow();

            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(first.id());
            buf.delete(first);
            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(second.id());
            buf.delete(second);
            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(third.id());
        }
    }

    // -----------------------------------------------------------------------
    // Capacity-cap eviction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("capacity-cap eviction (drop-oldest-first)")
    class CapEviction {

        @Test
        @DisplayName("when total bytes exceed cap, oldest envelopes are dropped to make room")
        void evicts_oldest_to_make_room() {
            // First measure the actual gzipped envelope size against an unbounded
            // buffer so the cap-test isn't sensitive to gzip implementation choices.
            DiskBackedMetricsBuffer probe = new DiskBackedMetricsBuffer(
                    bufferDir.resolveSibling(bufferDir.getFileName() + "-probe"),
                    DiskBackedMetricsBufferConfig.defaults(),
                    new SimpleMeterRegistry(),
                    clock);
            long oneEntryBytes = probe.enqueue(envelope(1L, "w-probe", List.of("a")), "test.topic")
                    .orElseThrow().sizeBytes();
            // Now size the cap so 2 envelopes fit but a 3rd evicts the oldest.
            // Pad the per-file cap to comfortably exceed oneEntryBytes.
            long cap = 2L * oneEntryBytes + (oneEntryBytes / 2);

            DiskBackedMetricsBuffer buf = newBuffer(new DiskBackedMetricsBufferConfig(
                    cap,
                    oneEntryBytes * 2,    // generous per-file
                    0L,                   // no free-disk reserve for this test
                    Duration.ofHours(6)));

            BufferedEnvelope first  = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a")), "test.topic").orElseThrow();
            clock.advance(Duration.ofMillis(1));
            BufferedEnvelope second = buf.enqueue(envelope(1_700_000_001L, "w-1", List.of("b")), "test.topic").orElseThrow();
            // Adding a third pushes past cap (3 × oneEntryBytes > 2.5 × oneEntryBytes); eviction kicks in.
            clock.advance(Duration.ofMillis(1));
            BufferedEnvelope third  = buf.enqueue(envelope(1_700_000_002L, "w-1", List.of("c")), "test.topic").orElseThrow();

            // The first envelope (oldest) should have been evicted.
            assertThat(Files.exists(first.file())).isFalse();
            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(second.id());
            assertThat(buf.depthEnvelopes()).isEqualTo(2L);
            assertThat(meterRegistry.counter("metricsBuffer.dropsForCap").count()).isEqualTo(1.0);
        }
    }

    // -----------------------------------------------------------------------
    // TTL sweep
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TTL sweep")
    class TtlSweep {

        @Test
        @DisplayName("envelopes older than maxAge are evicted on the next enqueue (before drop-oldest)")
        void ttl_sweep_drops_stale_before_fresh() {
            DiskBackedMetricsBuffer buf = newBuffer(new DiskBackedMetricsBufferConfig(
                    1024L * 1024L,        // generous cap so cap-eviction can't be the cause
                    200L * 1024L,
                    0L,
                    Duration.ofHours(1)));

            BufferedEnvelope ancient = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a")), "test.topic").orElseThrow();

            // Age the clock past the TTL.
            clock.advance(Duration.ofHours(2));

            // The next enqueue triggers TTL sweep — the ancient envelope drops.
            BufferedEnvelope fresh = buf.enqueue(envelope(1_700_001_000L, "w-1", List.of("b")), "test.topic").orElseThrow();

            assertThat(Files.exists(ancient.file())).isFalse();
            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(fresh.id());
            assertThat(meterRegistry.counter("metricsBuffer.dropsForAge").count()).isEqualTo(1.0);
            assertThat(meterRegistry.counter("metricsBuffer.dropsForCap").count()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Free-disk reservation (JMeter wins)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("free-disk reservation")
    class FreeDiskReservation {

        @Test
        @DisplayName("enqueue refuses when free disk is below the reservation — JMeter wins")
        void refuses_below_free_disk_threshold() {
            // Set the free-disk threshold above any plausible host disk so the
            // guard always trips. Real free disk on a developer laptop is at most
            // a few TB; Long.MAX_VALUE is unreachable.
            DiskBackedMetricsBuffer buf = newBuffer(new DiskBackedMetricsBufferConfig(
                    1024L * 1024L,
                    200L * 1024L,
                    Long.MAX_VALUE,        // impossible threshold → every enqueue refused
                    Duration.ofHours(6)));

            Optional<BufferedEnvelope> result =
                    buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a")), "test.topic");

            assertThat(result).as("enqueue must refuse when free disk < threshold").isEmpty();
            assertThat(buf.depthEnvelopes()).isZero();
            assertThat(meterRegistry.counter("metricsBuffer.dropsForLowDisk").count()).isEqualTo(1.0);
        }
    }

    // -----------------------------------------------------------------------
    // Oversize envelope rejection
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("oversize envelope rejection")
    class OversizeRejection {

        @Test
        @DisplayName("envelope larger than maxFileBytes is refused at enqueue")
        void rejects_oversize_envelope() {
            // Tiny per-file cap so even a 1-entry envelope exceeds it.
            DiskBackedMetricsBuffer buf = newBuffer(new DiskBackedMetricsBufferConfig(
                    1024L * 1024L,
                    50L,                  // 50 B per-file — even the gzip header exceeds this
                    0L,
                    Duration.ofHours(6)));

            Optional<BufferedEnvelope> result =
                    buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /api/big")), "test.topic");

            assertThat(result).as("oversize envelope must be refused").isEmpty();
            assertThat(buf.depthEnvelopes()).isZero();
            assertThat(meterRegistry.counter("metricsBuffer.dropsForOversize").count()).isEqualTo(1.0);
        }
    }

    // -----------------------------------------------------------------------
    // Boot scrubber
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("boot scrubber")
    class BootScrubber {

        @Test
        @DisplayName("removes orphan .tmp files left by a prior crash mid-write")
        void removes_orphan_tmp_files() throws IOException {
            // Pre-seed an orphan .tmp file (simulates a crash mid-write).
            Path orphan = bufferDir.resolve("9999999999999-000001.envelope.gz.tmp");
            Files.writeString(orphan, "garbage from a crashed write");

            DiskBackedMetricsBuffer buf = newBuffer();

            assertThat(Files.exists(orphan)).isFalse();
            assertThat(meterRegistry.counter("metricsBuffer.bootOrphansRemoved").count()).isEqualTo(1.0);
            assertThat(buf.depthEnvelopes()).isZero();
        }

        @Test
        @DisplayName("re-indexes .envelope.gz files left by a prior process")
        void re_indexes_existing_envelopes() throws IOException {
            // Pre-seed: enqueue with one buffer instance, drop the instance,
            // construct a new one against the same dir — the second instance
            // must rediscover the persisted envelopes.
            DiskBackedMetricsBuffer first = newBuffer();
            BufferedEnvelope original =
                    first.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a")), "test.topic").orElseThrow();

            // New buffer instance against the same dir
            SimpleMeterRegistry registry2 = new SimpleMeterRegistry();
            DiskBackedMetricsBuffer second = new DiskBackedMetricsBuffer(
                    bufferDir, DiskBackedMetricsBufferConfig.defaults(), registry2, clock);

            assertThat(second.depthEnvelopes()).isEqualTo(1L);
            BufferedEnvelope recovered = second.peekOldest().orElseThrow();
            assertThat(recovered.id()).isEqualTo(original.id());
            assertThat(recovered.envelope().getWindowSecond()).isEqualTo(1_700_000_000L);
            assertThat(registry2.counter("metricsBuffer.bootRecovered").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("malformed .envelope.gz is deleted rather than crashing the buffer")
        void deletes_malformed_envelope_files() throws IOException {
            // Pre-seed a .envelope.gz file that contains malformed gzip bytes.
            Path bad = bufferDir.resolve("9999999999999-000001.envelope.gz");
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(bad))) {
                os.write("not avro".getBytes());
            }

            DiskBackedMetricsBuffer buf = newBuffer();

            // Bad file is deleted; nothing in the index.
            assertThat(Files.exists(bad)).isFalse();
            assertThat(buf.depthEnvelopes()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // depth gauges
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("depth gauges")
    class DepthGauges {

        @Test
        @DisplayName("depthBytes + depthEnvelopes track enqueue + delete in lockstep")
        void depth_gauges_track_lifecycle() {
            DiskBackedMetricsBuffer buf = newBuffer();
            assertThat(buf.depthEnvelopes()).isZero();
            assertThat(buf.depthBytes()).isZero();

            BufferedEnvelope first  = buf.enqueue(envelope(1L, "w-1", List.of("a")), "test.topic").orElseThrow();
            BufferedEnvelope second = buf.enqueue(envelope(2L, "w-1", List.of("b")), "test.topic").orElseThrow();
            assertThat(buf.depthEnvelopes()).isEqualTo(2L);
            assertThat(buf.depthBytes()).isEqualTo(first.sizeBytes() + second.sizeBytes());

            buf.delete(first);
            assertThat(buf.depthEnvelopes()).isEqualTo(1L);
            assertThat(buf.depthBytes()).isEqualTo(second.sizeBytes());
        }
    }

    // -----------------------------------------------------------------------
    // Config validation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("config validation")
    class ConfigValidation {

        @Test
        @DisplayName("rejects maxBytes <= 0")
        void rejects_non_positive_max_bytes() {
            assertThatThrownBy(() -> new DiskBackedMetricsBufferConfig(
                    0, 200, 0, Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects maxFileBytes > maxBytes — would never store anything")
        void rejects_max_file_bigger_than_total_cap() {
            assertThatThrownBy(() -> new DiskBackedMetricsBufferConfig(
                    1024, 2048, 0, Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxFileBytes");
        }

        @Test
        @DisplayName("rejects non-positive maxAge")
        void rejects_non_positive_max_age() {
            assertThatThrownBy(() -> new DiskBackedMetricsBufferConfig(
                    1024, 200, 0, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static WorkerMetricBatch envelope(long sec, String workerId, List<String> labels) {
        Map<String, Long> statusCodes = new HashMap<>();
        statusCodes.put("200", 1L);
        List<WorkerMetricEntry> entries = new ArrayList<>();
        for (String label : labels) {
            entries.add(WorkerMetricEntry.newBuilder()
                    .setLabel(label)
                    .setThroughput(1L).setErrorCount(0L).setErrorRate(0.0)
                    .setAvgRespTimeMs(10.0)
                    .setP50Ms(10.0).setP90Ms(10.0).setP95Ms(10.0).setP99Ms(10.0)
                    .setMinMs(10.0).setMaxMs(10.0).setRawMaxMs(10L)
                    .setBytesReceived(100L).setBytesSent(50L)
                    .setStatusCodes(statusCodes)
                    .setActiveThreads(1L)
                    .build());
        }
        return WorkerMetricBatch.newBuilder()
                .setWindowSecond(sec)
                .setWindowTimestamp("2026/05/11 12:00:00")
                .setRegion("us-east-1")
                .setWorkerId(workerId)
                .setRunId("test-run")
                .setEntries(entries)
                .build();
    }

    /** Minimal mutable Clock for advancing time in tests. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
