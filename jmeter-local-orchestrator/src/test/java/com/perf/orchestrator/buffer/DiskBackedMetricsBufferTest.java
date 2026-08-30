package com.perf.orchestrator.buffer;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.model.WorkerMetricEntry;
import com.perf.orchestrator.buffer.DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig;
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

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-05-11T12:00:00Z"));
    }

    private DiskBackedMetricsBuffer newBuffer(DiskBackedMetricsBufferConfig cfg) {
        return new DiskBackedMetricsBuffer(bufferDir, cfg, clock);
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

            BufferedEnvelope handle = buf.enqueue(original).orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(handle.envelope().windowSecond()).isEqualTo(1_700_000_000L);
                softly.assertThat(handle.envelope().workerId().toString()).isEqualTo("worker-1");
                softly.assertThat(handle.envelope().entries()).hasSize(1);
                softly.assertThat(handle.file()).isNotNull();
                softly.assertThat(handle.sizeBytes()).isGreaterThan(0);
            });

            // The on-disk file exists with the .envelope.gz suffix
            assertThat(handle.file().getFileName().toString()).endsWith(".envelope.gz");
            assertThat(Files.exists(handle.file())).isTrue();

            BufferedEnvelope peeked = buf.peekOldest().orElseThrow();
            assertThat(peeked.id()).isEqualTo(handle.id());
            assertThat(peeked.envelope().windowSecond()).isEqualTo(1_700_000_000L);
        }

        @Test
        @DisplayName("delete removes the file from disk and clears the index entry")
        void delete_removes_from_disk_and_index() throws IOException {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope handle = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a"))).orElseThrow();

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
            BufferedEnvelope handle = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a"))).orElseThrow();

            buf.delete(handle);
            buf.delete(handle); // must not throw

            assertThat(buf.depthEnvelopes()).isZero();
        }

        @Test
        @DisplayName("multiple envelopes preserve chronological order via peekOldest")
        void multiple_envelopes_chronological_order() {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope first  = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a"))).orElseThrow();
            clock.advance(Duration.ofSeconds(1));
            BufferedEnvelope second = buf.enqueue(envelope(1_700_000_001L, "w-1", List.of("b"))).orElseThrow();
            clock.advance(Duration.ofSeconds(1));
            BufferedEnvelope third  = buf.enqueue(envelope(1_700_000_002L, "w-1", List.of("c"))).orElseThrow();

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
                    clock);
            long oneEntryBytes = probe.enqueue(envelope(1L, "w-probe", List.of("a")))
                    .orElseThrow().sizeBytes();
            // Now size the cap so 2 envelopes fit but a 3rd evicts the oldest.
            // Pad the per-file cap to comfortably exceed oneEntryBytes.
            long cap = 2L * oneEntryBytes + (oneEntryBytes / 2);

            DiskBackedMetricsBuffer buf = newBuffer(new DiskBackedMetricsBufferConfig(
                    cap,
                    oneEntryBytes * 2,    // generous per-file
                    0L,                   // no free-disk reserve for this test
                    Duration.ofHours(6)));

            BufferedEnvelope first  = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a"))).orElseThrow();
            clock.advance(Duration.ofMillis(1));
            BufferedEnvelope second = buf.enqueue(envelope(1_700_000_001L, "w-1", List.of("b"))).orElseThrow();
            // Adding a third pushes past cap (3 × oneEntryBytes > 2.5 × oneEntryBytes); eviction kicks in.
            clock.advance(Duration.ofMillis(1));
            BufferedEnvelope third  = buf.enqueue(envelope(1_700_000_002L, "w-1", List.of("c"))).orElseThrow();

            // The first envelope (oldest) should have been evicted.
            assertThat(Files.exists(first.file())).isFalse();
            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(second.id());
            assertThat(buf.depthEnvelopes()).isEqualTo(2L);
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

            BufferedEnvelope ancient = buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a"))).orElseThrow();

            // Age the clock past the TTL.
            clock.advance(Duration.ofHours(2));

            // The next enqueue triggers TTL sweep — the ancient envelope drops.
            BufferedEnvelope fresh = buf.enqueue(envelope(1_700_001_000L, "w-1", List.of("b"))).orElseThrow();

            assertThat(Files.exists(ancient.file())).isFalse();
            assertThat(buf.peekOldest().orElseThrow().id()).isEqualTo(fresh.id());
            assertThat(buf.depthEnvelopes()).isEqualTo(1L);
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
                    buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("a")));

            assertThat(result).as("enqueue must refuse when free disk < threshold").isEmpty();
            assertThat(buf.depthEnvelopes()).isZero();
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
                    buf.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /api/big")));

            assertThat(result).as("oversize envelope must be refused").isEmpty();
            assertThat(buf.depthEnvelopes()).isZero();
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
            assertThat(buf.depthEnvelopes()).isZero();
        }

        @Test
        @DisplayName("an undecodable buffered file is dropped at boot, valid ones survive")
        void drops_undecodable_envelope_keeps_json() throws IOException {
            // A valid (JSON-era) envelope persisted by a prior process...
            DiskBackedMetricsBuffer first = newBuffer();
            first.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a"))).orElseThrow();

            // ...plus a file this build cannot decode: gzipped bytes that are
            // not JSON (a corrupt file, or one written by an older encoding).
            Path legacy = bufferDir.resolve("0000000000001-000001.envelope.gz");
            try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(legacy))) {
                out.write(new byte[] {0x02, 0x30, 0x14, 0x00, 0x7f, (byte) 0xC3, 0x01});
            }

            DiskBackedMetricsBuffer rebooted = new DiskBackedMetricsBuffer(
                    bufferDir, DiskBackedMetricsBufferConfig.defaults(), clock);

            assertThat(rebooted.depthEnvelopes())
                    .as("only the decodable JSON envelope survives the boot scrub")
                    .isEqualTo(1);
            assertThat(Files.exists(legacy))
                    .as("the undecodable legacy file must be deleted")
                    .isFalse();
        }

        @Test
        @DisplayName("re-indexes .envelope.gz files left by a prior process")
        void re_indexes_existing_envelopes() throws IOException {
            // Pre-seed: enqueue with one buffer instance, drop the instance,
            // construct a new one against the same dir — the second instance
            // must rediscover the persisted envelopes.
            DiskBackedMetricsBuffer first = newBuffer();
            BufferedEnvelope original =
                    first.enqueue(envelope(1_700_000_000L, "w-1", List.of("GET /a"))).orElseThrow();

            // New buffer instance against the same dir
            DiskBackedMetricsBuffer second = new DiskBackedMetricsBuffer(
                    bufferDir, DiskBackedMetricsBufferConfig.defaults(), clock);

            assertThat(second.depthEnvelopes()).isEqualTo(1L);
            BufferedEnvelope recovered = second.peekOldest().orElseThrow();
            assertThat(recovered.id()).isEqualTo(original.id());
            assertThat(recovered.envelope().windowSecond()).isEqualTo(1_700_000_000L);
        }

        @Test
        @DisplayName("malformed .envelope.gz is deleted rather than crashing the buffer")
        void deletes_malformed_envelope_files() throws IOException {
            // Pre-seed a .envelope.gz file that contains malformed gzip bytes.
            Path bad = bufferDir.resolve("9999999999999-000001.envelope.gz");
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(bad))) {
                os.write("not json {".getBytes());
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

            BufferedEnvelope first  = buf.enqueue(envelope(1L, "w-1", List.of("a"))).orElseThrow();
            BufferedEnvelope second = buf.enqueue(envelope(2L, "w-1", List.of("b"))).orElseThrow();
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
            entries.add(new WorkerMetricEntry(
                label,
                1L,
                0L,
                0.0,
                10.0,
                10L,   // sumElapsedMs — 1 sample × 10 ms
                10.0,
                10.0,
                10.0,
                10.0,
                10.0,
                10.0,
                10L,
                100L,
                50L,
                statusCodes,
                1L));
        }
        return new WorkerMetricBatch(
                sec,
                "2026/05/11 12:00:00",
                "us-east-1",
                workerId,
                "test-run",
                0L,
                entries);
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

    @Nested
    @DisplayName("group routing (PRIVATE-CLOUD-ALIGNMENT Track 5)")
    class GroupRouting {

        @Test
        @DisplayName("the group is part of the filename and survives a restart's boot scrub")
        void group_survives_restart() {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope handle = buf.enqueue(envelope(1_700_000_000L, "worker-1", List.of("GET /a")), "cps").orElseThrow();
            assertThat(handle.groupId()).isEqualTo("cps");
            assertThat(handle.file().getFileName().toString()).endsWith("~cps.envelope.gz");
            BufferedEnvelope plain = buf.enqueue(envelope(1_700_000_015L, "worker-1", List.of("GET /a"))).orElseThrow();
            assertThat(plain.groupId()).isNull();
            assertThat(plain.file().getFileName().toString()).doesNotContain("~");
            buf.close();

            DiskBackedMetricsBuffer reopened = newBuffer();
            assertThat(reopened.depthEnvelopes()).isEqualTo(2L);
            BufferedEnvelope oldest = reopened.peekOldest().orElseThrow();
            assertThat(oldest.groupId()).isEqualTo("cps");
            assertThat(oldest.id()).isEqualTo(handle.id());
            reopened.delete(oldest);
            assertThat(reopened.peekOldest().orElseThrow().groupId()).isNull();
        }

        @Test
        @DisplayName("an invalid group never reaches a filename — the envelope is buffered without one")
        void invalid_group_is_dropped_to_none() {
            DiskBackedMetricsBuffer buf = newBuffer();
            BufferedEnvelope handle = buf.enqueue(envelope(1L, "worker-1", List.of("GET /a")), "CPS;DROP").orElseThrow();
            assertThat(handle.groupId()).isNull();
        }
    }

}
