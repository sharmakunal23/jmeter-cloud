package com.perf.orchestrator.io;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.model.JtlRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("FilePoller")
class FilePollerTest {

    // -----------------------------------------------------------------------
    // JTL fixture content
    // -----------------------------------------------------------------------

    static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName," +
            "dataType,success,failureMessage,bytes,sentBytes,grpThreads," +
            "allThreads,URL,Latency,IdleTime,Connect\n";

    static final String ROW_1 =
            "2025/04/13 14:32:07,187,POST /api/payment,200,OK," +
            "jmeter-worker-0 1-1,text,true,,1024,512,80,80," +
            "https://app/api/payment,185,0,12\n";

    static final String ROW_2 =
            "2025/04/13 14:32:08,203,GET /api/account,200,OK," +
            "jmeter-worker-0 1-2,text,true,,2048,256,80,80," +
            "https://app/api/account,200,0,8\n";

    static final String ROW_ERROR =
            "2025/04/13 14:32:09,4200,POST /api/payment,503,Service Unavailable," +
            "jmeter-worker-0 1-1,text,false,Response code was 503,128,512,80,80," +
            "https://app/api/payment,4198,0,9\n";

    @TempDir
    Path tempDir;

    private Path jtlFile;
    private Path stateFile;
    private JtlOffsetStore stateStore;

    @BeforeEach
    void setUp() {
        jtlFile   = tempDir.resolve("results.jtl");
        stateFile = tempDir.resolve(".jtlOffset");
        stateStore = new JtlOffsetStore(stateFile);
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private OrchestratorConfig configFor(Path jtl) {
        Map<String, String> env = new HashMap<>();
        env.put("POD_NAME",            "jmeter-worker-0");
        env.put("TEST_REGION",         "us-east-1");
        env.put("RUN_ID",              "test-20250413");
        env.put("JTL_PATH",            jtl.toString());
        env.put("SENTINEL_PATH",       tempDir.resolve(".done").toString());
        env.put("KAFKA_BROKERS",       "kafka:9092");
        env.put("SCHEMA_REGISTRY_URL", "http://schema-registry:8081");
        env.put("KAFKA_TOPIC",         "jmeter.metrics.perSecond");
        env.put("STATE_FILE_PATH",     stateFile.toString());
        // Large interval so offset is not auto-flushed during tests (controlled manually)
        env.put("STATE_FLUSH_INTERVAL_MS", "3600000");
        return OrchestratorConfig.from(env);
    }

    private void write(String content) throws IOException {
        Files.writeString(jtlFile, content,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private long byteLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // -----------------------------------------------------------------------
    // tryOpen behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("tryOpen")
    class TryOpen {

        @Test
        @DisplayName("returns empty when the JTL file does not exist yet")
        void returns_empty_when_file_absent() throws IOException {
            Optional<FilePoller> result = FilePoller.tryOpen(configFor(jtlFile), stateStore);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when the file exists but is completely empty — JMeter hasn't written yet")
        void returns_empty_when_file_is_empty() throws IOException {
            Files.createFile(jtlFile);

            Optional<FilePoller> result = FilePoller.tryOpen(configFor(jtlFile), stateStore);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when header has been partially written with no terminating newline")
        void returns_empty_when_header_has_no_newline() throws IOException {
            // Write header without the trailing \n — JMeter is mid-flush
            Files.writeString(jtlFile, HEADER.stripTrailing());

            Optional<FilePoller> result = FilePoller.tryOpen(configFor(jtlFile), stateStore);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("succeeds when a complete header is present — returns a ready FilePoller")
        void succeeds_when_header_is_complete() throws IOException {
            write(HEADER);

            Optional<FilePoller> result = FilePoller.tryOpen(configFor(jtlFile), stateStore);

            assertThat(result).isPresent();
            result.get().close();
        }

        @Test
        @DisplayName("propagates a malformed-header RuntimeException unwrapped — callers can distinguish it from generic I/O failure")
        void propagates_column_index_exception_unwrapped() throws IOException {
            // A header missing the required `timeStamp` column — ColumnIndex.parse
            // throws ColumnIndexException (a RuntimeException). The previous
            // catch-Exception block wrapped this in a generic IOException,
            // collapsing the type at the state machine. The fix preserves it.
            Files.writeString(jtlFile, "elapsed,label,success\n");

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> FilePoller.tryOpen(configFor(jtlFile), stateStore))
                    .isInstanceOf(com.perf.orchestrator.parser.ColumnIndexException.class)
                    .hasMessageContaining("timeStamp");
        }
    }

    // -----------------------------------------------------------------------
    // poll behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("poll behaviour")
    class PollBehaviour {

        @Test
        @DisplayName("returns no-data immediately after open when no rows have been written")
        void returns_no_data_when_no_rows_written() throws IOException {
            write(HEADER);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                PollResult result = poller.poll();

                assertThat(result.hadNewData()).isFalse();
                assertThat(result.rows()).isEmpty();
            }
        }

        @Test
        @DisplayName("returns parsed rows after they are appended to the file")
        void returns_rows_as_they_are_appended() throws IOException {
            write(HEADER);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                write(ROW_1);
                write(ROW_2);

                PollResult result = poller.poll();

                assertThat(result.hadNewData()).isTrue();
                assertThat(result.rows()).hasSize(2);
            }
        }

        @Test
        @DisplayName("maps each row to the correct label and response code")
        void maps_row_fields_correctly() throws IOException {
            write(HEADER);
            write(ROW_1);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                PollResult result = poller.poll();

                JtlRow row = result.rows().get(0);
                assertSoftly(softly -> {
                    softly.assertThat(row.label()).isEqualTo("POST /api/payment");
                    softly.assertThat(row.responseCode()).isEqualTo("200");
                    softly.assertThat(row.success()).isTrue();
                    softly.assertThat(row.elapsedMs()).isEqualTo(187L);
                });
            }
        }

        @Test
        @DisplayName("accumulates rows across multiple polls — each poll returns only new rows")
        void accumulates_rows_across_polls() throws IOException {
            write(HEADER);
            write(ROW_1);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                PollResult first = poller.poll();
                assertThat(first.rows()).hasSize(1)
                        .extracting(JtlRow::label).containsExactly("POST /api/payment");

                // New row written between polls
                write(ROW_2);

                PollResult second = poller.poll();
                assertThat(second.rows()).hasSize(1)
                        .extracting(JtlRow::label).containsExactly("GET /api/account");
            }
        }

        @Test
        @DisplayName("correctly parses both successful and error rows")
        void parses_error_rows_correctly() throws IOException {
            write(HEADER);
            write(ROW_ERROR);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                PollResult result = poller.poll();

                JtlRow errorRow = result.rows().get(0);
                assertThat(errorRow.isError()).isTrue();
                assertThat(errorRow.responseCode()).isEqualTo("503");
            }
        }

        @Test
        @DisplayName("assembles a row split across two writes — simulates JMeter flushing mid-row")
        void assembles_row_split_across_writes() throws IOException {
            write(HEADER);

            // Simulate JMeter flushing the file mid-row (no newline on first write)
            String firstHalf  = "2025/04/13 14:32:07,187,POST /api";
            String secondHalf = "/payment,200,OK,jmeter-worker-0 1-1,text,true,,1024,512,80,80," +
                                "https://app/api/payment,185,0,12\n";

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                Files.writeString(jtlFile, firstHalf, StandardOpenOption.APPEND);
                PollResult partial = poller.poll();
                // Bytes arrived but no complete line yet
                assertThat(partial.hadNewData()).isTrue();
                assertThat(partial.rows()).isEmpty();

                Files.writeString(jtlFile, secondHalf, StandardOpenOption.APPEND);
                PollResult complete = poller.poll();
                assertThat(complete.rows()).hasSize(1);
                assertThat(complete.rows().get(0).label()).isEqualTo("POST /api/payment");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Crash recovery behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("crash recovery")
    class CrashRecovery {

        @Test
        @DisplayName("resumes from saved offset — skips rows already processed before the crash")
        void resumes_from_saved_offset_skipping_processed_rows() throws IOException {
            write(HEADER);
            write(ROW_1);

            // Simulate: ROW_1 was processed and offset was saved before crash
            long offsetAfterRow1 = byteLength(HEADER) + byteLength(ROW_1);
            stateStore.saveOffset(offsetAfterRow1);

            // ROW_2 was written before the crash but not processed
            write(ROW_2);

            // Re-open — should start from saved offset, not from after the header
            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                PollResult result = poller.poll();

                // Must see only ROW_2, not ROW_1 (which was already processed)
                assertThat(result.rows()).hasSize(1);
                assertThat(result.rows().get(0).label()).isEqualTo("GET /api/account");
            }
        }

        @Test
        @DisplayName("resets to post-header when saved offset exceeds current file length")
        void resets_to_post_header_when_saved_offset_is_stale() throws IOException {
            write(HEADER);
            write(ROW_1);

            // Saved offset beyond end of file — stale state from a different run
            long staleOffset = byteLength(HEADER) + byteLength(ROW_1) + 99_999L;
            stateStore.saveOffset(staleOffset);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                // After stale offset is discarded, poller starts from after header
                // ROW_1 was already in the file so it should now be visible
                PollResult result = poller.poll();

                assertThat(result.rows())
                        .as("stale offset discarded — should read from post-header position")
                        .hasSize(1);
                assertThat(result.rows().get(0).label()).isEqualTo("POST /api/payment");
            }
        }
    }

    // -----------------------------------------------------------------------
    // pollFinal behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("pollFinal")
    class PollFinal {

        @Test
        @DisplayName("flushes the last row even when it has no trailing newline")
        void flushes_partial_last_row_without_newline() throws IOException {
            write(HEADER);

            // Write last row without trailing newline — simulates JMeter exiting cleanly
            String lastRow = ROW_1.stripTrailing(); // removes the \n
            Files.writeString(jtlFile, lastRow, StandardOpenOption.APPEND);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                // Regular poll — row is incomplete (no \n), so LineBuffer holds it
                PollResult regularPoll = poller.poll();
                assertThat(regularPoll.rows())
                        .as("row has no newline yet — should be held by LineBuffer")
                        .isEmpty();

                // pollFinal flushes the LineBuffer
                List<JtlRow> finalRows = poller.pollFinal();

                assertThat(finalRows).hasSize(1);
                assertThat(finalRows.get(0).label()).isEqualTo("POST /api/payment");
            }
        }

        @Test
        @DisplayName("returns empty list when all rows ended with newlines — nothing to flush")
        void returns_empty_when_file_ends_cleanly() throws IOException {
            write(HEADER);
            write(ROW_1); // ends with \n

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                poller.poll(); // consume ROW_1

                List<JtlRow> finalRows = poller.pollFinal();

                assertThat(finalRows).isEmpty();
            }
        }

        @Test
        @DisplayName("persists the final byte offset unconditionally — ensures crash recovery is current on exit")
        void persists_final_offset_unconditionally() throws IOException {
            write(HEADER);
            write(ROW_1);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                poller.poll();
                poller.pollFinal();
            }

            long persistedOffset = stateStore.loadOffset();
            long expectedOffset  = byteLength(HEADER) + byteLength(ROW_1);

            assertThat(persistedOffset)
                    .as("pollFinal must persist the offset so crash recovery is accurate")
                    .isEqualTo(expectedOffset);
        }
    }

    // -----------------------------------------------------------------------
    // Result immutability
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("result immutability")
    class ResultImmutability {

        @Test
        @DisplayName("PollResult rows list is unmodifiable — callers cannot corrupt the result")
        void poll_result_rows_are_unmodifiable() throws IOException {
            write(HEADER);
            write(ROW_1);

            try (FilePoller poller = FilePoller.tryOpen(configFor(jtlFile), stateStore).orElseThrow()) {
                PollResult result = poller.poll();
                List<JtlRow> rows = result.rows();

                org.assertj.core.api.Assertions.assertThatThrownBy(() -> rows.add(null))
                        .as("rows list must be unmodifiable to prevent caller corruption")
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }
}
