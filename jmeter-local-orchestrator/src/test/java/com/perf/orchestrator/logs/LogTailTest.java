package com.perf.orchestrator.logs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("LogTail — bounded ring buffer + tail-of-file fallback")
class LogTailTest {

    @TempDir Path tempDir;

    @Nested
    @DisplayName("ring buffer eviction")
    class RingBufferEviction {

        @Test
        @DisplayName("retains only the most recent maxLines — older lines are dropped silently")
        void evicts_oldest_when_capacity_reached() {
            LogTail tail = new LogTail(3);
            tail.append("a"); tail.append("b"); tail.append("c"); tail.append("d");

            assertSoftly(softly -> {
                softly.assertThat(tail.tail(10)).containsExactly("b", "c", "d");
                softly.assertThat(tail.bufferedSize()).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("tail(n) returns at most n lines, oldest-first within the window")
        void respects_requested_window() {
            LogTail tail = new LogTail(5);
            for (int i = 0; i < 5; i++) tail.append("line-" + i);

            assertSoftly(softly -> {
                softly.assertThat(tail.tail(2)).containsExactly("line-3", "line-4");
                softly.assertThat(tail.tail(0)).isEmpty();
                softly.assertThat(tail.tail(100)).hasSize(5);
            });
        }
    }

    @Nested
    @DisplayName("file fall-back")
    class FileFallBack {

        @Test
        @DisplayName("when the buffer is empty, returns the last n lines straight from the file")
        void reads_file_tail_when_buffer_empty() throws IOException {
            Path log = tempDir.resolve("jmeter.log");
            Files.writeString(log, "old-1\nold-2\nold-3\nold-4\nold-5\n", StandardCharsets.UTF_8);
            LogTail tail = new LogTail(10, log);

            List<String> result = tail.tail(3);
            assertThat(result).containsExactly("old-3", "old-4", "old-5");
        }

        @Test
        @DisplayName("merges file (older) + buffer (newer) without duplicating lines that the buffer already saw")
        void merges_file_then_buffer() throws IOException {
            Path log = tempDir.resolve("jmeter.log");
            Files.writeString(log, "old-1\nold-2\nold-3\nrecent-1\nrecent-2\n", StandardCharsets.UTF_8);
            LogTail tail = new LogTail(10, log);

            // The buffer mirrors the two newest lines from the file.
            tail.append("recent-1");
            tail.append("recent-2");

            // Asking for 5 lines should yield old-1..old-3 + recent-1..recent-2,
            // not duplicate "recent-*" twice.
            List<String> result = tail.tail(5);
            assertThat(result).containsExactly("old-1", "old-2", "old-3", "recent-1", "recent-2");
        }

        @Test
        @DisplayName("survives a multi-megabyte file — reads in 8 KB chunks, never loads the whole log")
        void reads_large_file_without_oom() throws IOException {
            Path log = tempDir.resolve("jmeter.log");
            // 200 000 lines × ~12 bytes = ~2.4 MB of log content.
            StringBuilder sb = new StringBuilder(2_500_000);
            for (int i = 0; i < 200_000; i++) sb.append("line-").append(i).append('\n');
            Files.writeString(log, sb, StandardCharsets.UTF_8);
            LogTail tail = new LogTail(10, log);

            List<String> last10 = tail.tail(10);
            assertSoftly(softly -> {
                softly.assertThat(last10).hasSize(10);
                softly.assertThat(last10.get(9)).isEqualTo("line-199999");
                softly.assertThat(last10.get(0)).isEqualTo("line-199990");
            });
        }

        @Test
        @DisplayName("returns the buffer alone when no log file is configured — pure in-memory mode")
        void no_file_means_buffer_only() {
            LogTail tail = new LogTail(3);
            tail.append("a"); tail.append("b");

            assertThat(tail.tail(5)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("missing log file is not an error — returns the buffer alone")
        void missing_file_falls_through_to_buffer() {
            LogTail tail = new LogTail(3, tempDir.resolve("does-not-exist.log"));
            tail.append("only-line");

            assertThat(tail.tail(5)).containsExactly("only-line");
        }

        @Test
        @DisplayName("decodes UTF-8 multi-byte characters correctly even when chunk boundaries split a codepoint")
        void utf8_multibyte_round_trips_across_chunk_boundary() throws IOException {
            // Build a log that GUARANTEES a multi-byte character straddles
            // an 8 KB chunk boundary. Pad with 8190 ASCII bytes + one '\n'
            // (8191 bytes), then a UTF-8 line containing 中文 (each char =
            // 3 bytes). The first line ends at byte 8191 (chunk read starts
            // at file end, walks back; the chunk boundary lands inside the
            // multi-byte UTF-8 sequence of the second line).
            StringBuilder padding = new StringBuilder();
            for (int i = 0; i < 8190; i++) padding.append('a');
            String padLine = padding.toString();           // 8190 bytes ASCII
            String unicodeLine = "ラベル: 中文-éèçñü-✓"; // mixed UTF-8 multi-byte
            String content = padLine + "\n" + unicodeLine + "\n";

            Path log = tempDir.resolve("jmeter-utf8.log");
            Files.write(log, content.getBytes(StandardCharsets.UTF_8));

            LogTail tail = new LogTail(10, log);
            List<String> result = tail.tail(2);

            assertSoftly(softly -> {
                softly.assertThat(result).hasSize(2);
                softly.assertThat(result.get(0)).isEqualTo(padLine);
                softly.assertThat(result.get(1))
                        .as("multi-byte UTF-8 must round-trip — no mojibake from per-byte decoding")
                        .isEqualTo(unicodeLine);
            });
        }
    }

    @Nested
    @DisplayName("source-isolated reads (UI-1)")
    class SourceIsolatedReads {

        @Test
        @DisplayName("tailRingOnly returns ring contents and ignores the file even if it has older lines")
        void ring_only_ignores_file() throws IOException {
            Path log = tempDir.resolve("jmeter.log");
            Files.writeString(log, "file-1\nfile-2\nfile-3\n", StandardCharsets.UTF_8);
            LogTail tail = new LogTail(10, log);
            tail.append("ring-1");
            tail.append("ring-2");

            assertThat(tail.tailRingOnly(10)).containsExactly("ring-1", "ring-2");
        }

        @Test
        @DisplayName("tailRingOnly windows from the end like tail() — same oldest-first slice")
        void ring_only_window_semantics() {
            LogTail tail = new LogTail(5);
            for (int i = 0; i < 5; i++) tail.append("line-" + i);

            assertSoftly(softly -> {
                softly.assertThat(tail.tailRingOnly(2)).containsExactly("line-3", "line-4");
                softly.assertThat(tail.tailRingOnly(0)).isEmpty();
                softly.assertThat(tail.tailRingOnly(100)).hasSize(5);
            });
        }

        @Test
        @DisplayName("tailRingOnly on an empty buffer returns an empty list (no NPE)")
        void ring_only_empty_buffer() {
            LogTail tail = new LogTail(5);
            assertThat(tail.tailRingOnly(10)).isEmpty();
        }

        @Test
        @DisplayName("tailFileOnly returns file contents and ignores the ring")
        void file_only_ignores_ring() throws IOException {
            Path log = tempDir.resolve("jmeter.log");
            Files.writeString(log, "file-1\nfile-2\nfile-3\n", StandardCharsets.UTF_8);
            LogTail tail = new LogTail(10, log);
            tail.append("ring-1");
            tail.append("ring-2");

            assertThat(tail.tailFileOnly(10)).containsExactly("file-1", "file-2", "file-3");
        }

        @Test
        @DisplayName("tailFileOnly returns empty when no logFile was configured — no fall-back to the ring")
        void file_only_no_file_returns_empty() {
            LogTail tail = new LogTail(5);
            tail.append("ring-1");

            assertThat(tail.tailFileOnly(10)).isEmpty();
        }

        @Test
        @DisplayName("tailFileOnly returns empty when the configured log file has not been created yet (PREPARING state)")
        void file_only_missing_file_returns_empty() {
            LogTail tail = new LogTail(5, tempDir.resolve("not-yet-written.log"));
            tail.append("ring-1");

            assertThat(tail.tailFileOnly(10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent appends + tails do not throw and the final tail is consistent with capacity")
        void concurrent_appends_and_tails() throws Exception {
            LogTail tail = new LogTail(100);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int t = 0; t < 4; t++) {
                    final int id = t;
                    pool.submit(() -> {
                        for (int i = 0; i < 1000; i++) {
                            tail.append("t" + id + "-" + i);
                            if ((i & 0xff) == 0) tail.tail(50);
                        }
                    });
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            List<String> snap = tail.tail(1000);
            assertSoftly(softly -> {
                softly.assertThat(snap).hasSizeLessThanOrEqualTo(100);
                softly.assertThat(snap)
                        .allSatisfy(line -> softly.assertThat(line).matches("t[0-3]-\\d+"));
            });
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {

        @Test
        @DisplayName("rejects maxLines <= 0 — silent zero-buffer would swallow all logs")
        void rejects_non_positive_capacity() {
            assertThatThrownBy(() -> new LogTail(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogTail(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("tailAsText joins with newlines so the controller can stream straight to text/plain")
        void tail_as_text_joins_newlines() {
            LogTail tail = new LogTail(3);
            tail.append("a"); tail.append("b");

            assertThat(tail.tailAsText(10)).isEqualTo("a\nb");
        }
    }
}
