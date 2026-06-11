package com.perf.orchestrator.io;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("JtlOffsetStore")
class JtlOffsetStoreTest {

    @TempDir
    Path tempDir;

    private Path stateFile;
    private JtlOffsetStore store;

    @BeforeEach
    void setUp() {
        stateFile = tempDir.resolve(".jtlOffset");
        store = new JtlOffsetStore(stateFile);
    }

    // -----------------------------------------------------------------------
    // Fresh start behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("on fresh start (no state file)")
    class OnFreshStart {

        @Test
        @DisplayName("returns 0 when no state file exists — beginning of file")
        void returns_zero_when_no_state_file() {
            assertThat(store.loadOffset()).isZero();
        }

        @Test
        @DisplayName("does not create the state file by merely loading")
        void load_does_not_create_state_file() {
            store.loadOffset();

            assertThat(Files.exists(stateFile))
                    .as("loadOffset should not create the state file as a side effect")
                    .isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Save and load behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("save and load round-trip")
    class SaveAndLoad {

        @Test
        @DisplayName("loads exactly the value that was saved")
        void loads_what_was_saved() {
            store.saveOffset(5_242_880L); // 5 MB into the file

            assertThat(store.loadOffset()).isEqualTo(5_242_880L);
        }

        @Test
        @DisplayName("each save overwrites the previous value — always returns the latest offset")
        void successive_saves_produce_latest_value() {
            store.saveOffset(1_000L);
            store.saveOffset(50_000L);
            store.saveOffset(999_999L);

            assertThat(store.loadOffset()).isEqualTo(999_999L);
        }

        @Test
        @DisplayName("saves and loads large offsets correctly — 8-10 hour JTL files can exceed 1 GB")
        void handles_large_offsets() {
            long largeOffset = 3L * 1024 * 1024 * 1024; // 3 GB

            store.saveOffset(largeOffset);

            assertThat(store.loadOffset()).isEqualTo(largeOffset);
        }

        @Test
        @DisplayName("correctly handles offset of zero — valid after file truncation or fresh run marker")
        void saves_and_loads_zero() {
            store.saveOffset(100_000L);
            store.saveOffset(0L);

            assertThat(store.loadOffset()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Atomicity behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("atomic write behaviour")
    class AtomicWrite {

        @Test
        @DisplayName("does not leave a tmp file behind after a successful save")
        void no_tmp_file_remains_after_successful_save() throws IOException {
            store.saveOffset(12_345L);

            Path tmpFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            assertThat(Files.exists(tmpFile))
                    .as("tmp file must be renamed away — a leftover tmp indicates a partial write")
                    .isFalse();
        }

        @Test
        @DisplayName("state file exists and is readable immediately after save")
        void state_file_exists_after_save() {
            store.saveOffset(42L);

            assertThat(Files.exists(stateFile)).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Corrupt state file resilience
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when the state file is corrupt or invalid")
    class WhenStateFileIsCorrupt {

        @Test
        @DisplayName("returns 0 rather than throwing when file contains non-numeric text")
        void returns_zero_for_non_numeric_content() throws IOException {
            Files.writeString(stateFile, "not-a-number");

            assertThat(store.loadOffset())
                    .as("corrupt state file should produce a safe fallback, not a crash")
                    .isZero();
        }

        @Test
        @DisplayName("returns 0 for a negative saved offset — negative byte offsets are invalid")
        void returns_zero_for_negative_offset() throws IOException {
            Files.writeString(stateFile, "-1");

            assertThat(store.loadOffset()).isZero();
        }

        @Test
        @DisplayName("returns 0 when state file exists but is empty")
        void returns_zero_for_empty_file() throws IOException {
            Files.writeString(stateFile, "");

            assertThat(store.loadOffset()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Clear behaviour
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Save-failure observability
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("save failures (Prometheus counter)")
    class SaveFailureCounter {

        @Test
        @DisplayName("when the parent directory is missing, saveOffset increments the counter and does not throw")
        void counter_increments_on_save_failure() {
            // Point at a nonexistent subdir so writeString fails with IOException.
            // The store must swallow that (per-flush-interval reprocessing is
            // documented as acceptable) but increment the counter.
            Path stateInMissingDir = tempDir.resolve("no-such-subdir/.jtlOffset");
            LongAdder failures = new LongAdder();
            JtlOffsetStore observed = new JtlOffsetStore(stateInMissingDir, failures);

            assertThatCode(() -> observed.saveOffset(123L)).doesNotThrowAnyException();

            assertSoftly(softly -> {
                softly.assertThat(failures.sum())
                        .as("supplied counter sees one failure")
                        .isEqualTo(1L);
                softly.assertThat(observed.getSaveFailureCount())
                        .as("getter mirrors the supplied adder")
                        .isEqualTo(1L);
            });
        }

        @Test
        @DisplayName("a successful save does not increment the counter")
        void counter_does_not_advance_on_success() {
            LongAdder failures = new LongAdder();
            JtlOffsetStore observed = new JtlOffsetStore(stateFile, failures);

            observed.saveOffset(42L);

            assertThat(failures.sum()).isZero();
        }

        @Test
        @DisplayName("each failed save increments the counter — repeated failures are observable")
        void counter_advances_per_failure() {
            Path stateInMissingDir = tempDir.resolve("still-no-such-subdir/.jtlOffset");
            LongAdder failures = new LongAdder();
            JtlOffsetStore observed = new JtlOffsetStore(stateInMissingDir, failures);

            observed.saveOffset(1L);
            observed.saveOffset(2L);
            observed.saveOffset(3L);

            assertThat(failures.sum()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("removes the state file so the next load returns 0")
        void clear_causes_next_load_to_return_zero() {
            store.saveOffset(500_000L);
            store.clear();

            assertThat(store.loadOffset()).isZero();
        }

        @Test
        @DisplayName("can be called safely when no state file exists")
        void clear_is_safe_when_no_state_file_exists() {
            // Must not throw
            store.clear();

            assertThat(store.loadOffset()).isZero();
        }
    }
}
