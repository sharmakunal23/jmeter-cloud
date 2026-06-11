package com.perf.orchestrator.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LineBuffer")
class LineBufferTest {

    private LineBuffer buffer;

    @BeforeEach
    void fresh() {
        buffer = new LineBuffer();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<String> feed(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return buffer.feed(bytes, bytes.length);
    }

    // -----------------------------------------------------------------------
    // Basic line emission behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("basic line emission")
    class BasicLineEmission {

        @Test
        @DisplayName("emits a complete line when it arrives with a trailing newline")
        void emits_complete_line_on_newline() {
            List<String> lines = feed("2025/04/13 14:32:07,187,POST /api/payment\n");

            assertThat(lines).containsExactly("2025/04/13 14:32:07,187,POST /api/payment");
        }

        @Test
        @DisplayName("emits nothing when the read contains no newline — partial line is held")
        void holds_partial_line_when_no_newline_received() {
            List<String> lines = feed("2025/04/13 14:32:07,187");

            assertThat(lines).isEmpty();
        }

        @Test
        @DisplayName("emits multiple lines when several arrive in a single read")
        void emits_all_lines_from_a_multi_line_read() {
            // JMeter flushes in blocks — a single read will typically carry
            // dozens of complete rows at once
            List<String> lines = feed("row-one\nrow-two\nrow-three\n");

            assertThat(lines).containsExactly("row-one", "row-two", "row-three");
        }

        @Test
        @DisplayName("does not emit the trailing partial when a read ends mid-line")
        void retains_trailing_partial_after_multi_line_read() {
            List<String> lines = feed("row-one\nrow-two\npartia");

            assertThat(lines).containsExactly("row-one", "row-two");
            // "partia" is held — assert by completing it in next feed
            List<String> completed = feed("l\n");
            assertThat(completed).containsExactly("partial");
        }
    }

    // -----------------------------------------------------------------------
    // Cross-feed line assembly — the core correctness guarantee
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("cross-feed line assembly")
    class CrossFeedLineAssembly {

        @Test
        @DisplayName("assembles a line correctly when it is split across exactly two reads")
        void assembles_line_split_across_two_reads() {
            // Simulates JMeter flushing mid-row — the most common real-world scenario
            feed("2025/04/13 14:32:07,187,POST /api");    // no newline — held
            List<String> lines = feed("/payment,200,OK\n");    // completes the line

            assertThat(lines).containsExactly(
                    "2025/04/13 14:32:07,187,POST /api/payment,200,OK");
        }

        @Test
        @DisplayName("assembles a line correctly when it is split across many small reads")
        void assembles_line_split_across_many_reads() {
            // Extreme case: one byte per read
            String fullLine = "timestamp,elapsed,label\n";
            for (int i = 0; i < fullLine.length() - 1; i++) {
                List<String> partial = feed(String.valueOf(fullLine.charAt(i)));
                assertThat(partial).as("no line yet after byte %d", i).isEmpty();
            }
            // Feed the final newline
            List<String> lines = feed("\n");
            assertThat(lines).containsExactly("timestamp,elapsed,label");
        }

        @Test
        @DisplayName("correctly emits the first line when a read starts with a newline that completes it")
        void newline_at_start_of_read_completes_previous_partial() {
            feed("first-line-partial");
            List<String> lines = feed("\nsecond-line-start");

            assertThat(lines).containsExactly("first-line-partial");
            // "second-line-start" must be retained — if it were silently dropped
            // the next poll would produce a row missing its leading bytes
            assertThat(buffer.hasPendingContent())
                    .as("second-line-start should be held as pending partial content")
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Line ending handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("line ending handling")
    class LineEndingHandling {

        @Test
        @DisplayName("strips the carriage return from Windows CRLF line endings")
        void strips_carriage_return_from_crlf() {
            List<String> lines = feed("row-content\r\n");

            assertThat(lines)
                    .containsExactly("row-content")
                    .doesNotContain("row-content\r");
        }

        @Test
        @DisplayName("does not strip a lone carriage return in the middle of a field — it is data")
        void preserves_carriage_return_not_at_line_end() {
            // Unusual but valid: a failureMessage might contain \r in the middle
            List<String> lines = feed("part-a\r,part-b\n");

            assertThat(lines).containsExactly("part-a\r,part-b");
        }

        @Test
        @DisplayName("handles mixed LF and CRLF within the same read block")
        void handles_mixed_line_endings_in_same_read() {
            List<String> lines = feed("unix-line\nwindows-line\r\nanother-unix\n");

            assertThat(lines).containsExactly("unix-line", "windows-line", "another-unix");
        }
    }

    // -----------------------------------------------------------------------
    // Empty and blank line handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("empty line handling")
    class EmptyLineHandling {

        @Test
        @DisplayName("silently discards blank lines — JMeter does not produce them but robustness matters")
        void discards_blank_lines() {
            List<String> lines = feed("row-one\n\nrow-two\n");

            assertThat(lines).containsExactly("row-one", "row-two");
        }

        @Test
        @DisplayName("returns empty list when fed zero bytes — this is the idle-poll case")
        void returns_empty_on_zero_byte_feed() {
            List<String> lines = buffer.feed(new byte[0], 0);

            assertThat(lines).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when fed a negative length — defensive against bad callers")
        void returns_empty_on_negative_length_feed() {
            List<String> lines = buffer.feed(new byte[10], -1);

            assertThat(lines).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Flush behaviour — used during DRAINING state
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("flush behaviour")
    class FlushBehaviour {

        @Test
        @DisplayName("flush emits the partial line even without a trailing newline")
        void flush_emits_partial_without_newline() {
            // The very last line of a JTL file has no trailing newline.
            // Without flush(), this row would be silently lost.
            feed("last-row-of-the-test-run");
            Optional<String> flushed = buffer.flush();

            assertThat(flushed).contains("last-row-of-the-test-run");
        }

        @Test
        @DisplayName("flush returns empty when there is no pending content")
        void flush_returns_empty_when_buffer_is_clean() {
            feed("complete-line\n");                  // buffer is now empty
            Optional<String> flushed = buffer.flush();

            assertThat(flushed).isEmpty();
        }

        @Test
        @DisplayName("buffer is empty after flush — a second flush produces nothing")
        void buffer_is_empty_after_flush() {
            feed("pending");
            buffer.flush();

            assertThat(buffer.flush()).isEmpty();
            assertThat(buffer.hasPendingContent()).isFalse();
        }

        @Test
        @DisplayName("strips CRLF during flush just as it does during normal line emission")
        void flush_strips_carriage_return() {
            feed("last-line\r");      // partial with stray \r (no \n follows)
            Optional<String> flushed = buffer.flush();

            assertThat(flushed).contains("last-line");
        }
    }

    // -----------------------------------------------------------------------
    // Buffer growth behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("buffer growth")
    class BufferGrowth {

        @Test
        @DisplayName("handles a line longer than the 4 KB initial capacity without data loss")
        void handles_line_longer_than_initial_buffer_capacity() {
            // A failureMessage containing a full response body can be very long.
            // The buffer must grow to accommodate it.
            String longValue  = "x".repeat(8_192);   // 8 KB — double the initial 4 KB
            String longLine   = "col1," + longValue + ",col3\n";

            List<String> lines = feed(longLine);

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains(longValue);
        }
    }
}
