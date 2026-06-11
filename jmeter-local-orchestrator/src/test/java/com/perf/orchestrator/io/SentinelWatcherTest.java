package com.perf.orchestrator.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SentinelWatcher")
class SentinelWatcherTest {

    @TempDir
    Path tempDir;

    private Path sentinelPath;
    private SentinelWatcher watcher;

    @BeforeEach
    void setUp() {
        sentinelPath = tempDir.resolve(".done");
        watcher = new SentinelWatcher(sentinelPath);
    }

    // -----------------------------------------------------------------------
    // Completion detection behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("test completion detection")
    class CompletionDetection {

        @Test
        @DisplayName("reports not done when sentinel file has not been written")
        void reports_not_done_before_sentinel_exists() {
            assertThat(watcher.isDone()).isFalse();
        }

        @Test
        @DisplayName("reports done as soon as the sentinel file appears")
        void reports_done_once_sentinel_file_appears() throws IOException {
            Files.writeString(sentinelPath, "0");

            assertThat(watcher.isDone()).isTrue();
        }

        @Test
        @DisplayName("continues reporting done even after the sentinel file is removed — result is cached")
        void caches_done_result_after_first_detection() throws IOException {
            Files.writeString(sentinelPath, "0");
            watcher.isDone(); // first call — caches true

            Files.deleteIfExists(sentinelPath); // remove the file
            // Watcher must still report true — it should not re-check the filesystem
            assertThat(watcher.isDone())
                    .as("isDone must return true from cache, not re-check the filesystem")
                    .isTrue();
        }

        @Test
        @DisplayName("reports not done for an empty sentinel directory — file must actually exist")
        void reports_not_done_when_only_directory_exists() {
            // tempDir exists but sentinelPath does not
            assertThat(watcher.isDone()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Exit code reading behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("exit code reading")
    class ExitCodeReading {

        @Test
        @DisplayName("returns the integer written in the sentinel file by the wrapper script")
        void reads_exit_code_from_sentinel_content() throws IOException {
            Files.writeString(sentinelPath, "0");

            assertThat(watcher.readExitCode()).contains(0);
        }

        @Test
        @DisplayName("reads a non-zero exit code — indicating JMeter reported failures")
        void reads_non_zero_exit_code() throws IOException {
            Files.writeString(sentinelPath, "1");

            assertThat(watcher.readExitCode()).contains(1);
        }

        @Test
        @DisplayName("handles trailing whitespace and newlines in sentinel content")
        void handles_trailing_whitespace_in_sentinel() throws IOException {
            // Shell scripts typically produce "0\n" not "0"
            Files.writeString(sentinelPath, "0\n");

            assertThat(watcher.readExitCode()).contains(0);
        }

        @Test
        @DisplayName("returns empty when sentinel file does not exist")
        void returns_empty_when_file_absent() {
            assertThat(watcher.readExitCode()).isEmpty();
        }

        @Test
        @DisplayName("returns empty when sentinel file exists but is empty — wrapper script edge case")
        void returns_empty_when_file_is_empty() throws IOException {
            Files.writeString(sentinelPath, "");

            assertThat(watcher.readExitCode()).isEmpty();
        }

        @Test
        @DisplayName("returns empty rather than throwing when content is not a valid integer")
        void returns_empty_for_non_numeric_content() throws IOException {
            Files.writeString(sentinelPath, "ERROR");

            assertThat(watcher.readExitCode()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // testFailed convenience behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("testFailed convenience")
    class TestFailed {

        @Test
        @DisplayName("returns true when JMeter exited with a non-zero code")
        void returns_true_for_non_zero_exit() throws IOException {
            Files.writeString(sentinelPath, "1");

            assertThat(watcher.testFailed()).isTrue();
        }

        @Test
        @DisplayName("returns false when JMeter exited cleanly with code 0")
        void returns_false_for_zero_exit() throws IOException {
            Files.writeString(sentinelPath, "0");

            assertThat(watcher.testFailed()).isFalse();
        }

        @Test
        @DisplayName("returns false when sentinel is absent — cannot determine failure without the file")
        void returns_false_when_sentinel_absent() {
            assertThat(watcher.testFailed()).isFalse();
        }
    }
}
