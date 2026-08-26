package com.perf.orchestrator.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("CurrentRun — state holder + persistence")
class CurrentRunTest {

    @TempDir Path tempDir;

    @Nested
    @DisplayName("on a fresh process")
    class OnFreshProcess {

        @Test
        @DisplayName("starts IDLE with no snapshot — distinguishes 'never run' from 'finished a run'")
        void starts_idle_with_no_snapshot() {
            CurrentRun run = CurrentRun.load(tempDir.resolve("state.json"), Clock.systemUTC());

            assertSoftly(softly -> {
                softly.assertThat(run.state()).isEqualTo(TestState.IDLE);
                softly.assertThat(run.isActive()).isFalse();
                softly.assertThat(run.snapshotIfPresent()).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("beginRun → STARTING → RUNNING → COMPLETED records timestamps and persists each step")
        void normal_lifecycle() throws Exception {
            Path file = tempDir.resolve("state.json");
            CurrentRun run = CurrentRun.load(file, fixedClock("2026-05-03T10:00:00Z"));

            run.beginRun("run-42", "us-east-1");
            run.transitionTo(TestState.STARTING);
            run.recordJmeterPid(12345L);
            run.transitionTo(TestState.RUNNING);
            run.transitionTo(TestState.COMPLETED);

            CurrentRun.Snapshot snap = run.snapshot();
            assertSoftly(softly -> {
                softly.assertThat(snap.state()).isEqualTo(TestState.COMPLETED);
                softly.assertThat(snap.runId()).isEqualTo("run-42");
                softly.assertThat(snap.region()).isEqualTo("us-east-1");
                softly.assertThat(snap.jmeterPid()).isEqualTo(12345L);
                softly.assertThat(snap.startedAt()).isEqualTo(Instant.parse("2026-05-03T10:00:00Z"));
                softly.assertThat(snap.completedAt()).isEqualTo(Instant.parse("2026-05-03T10:00:00Z"));
            });

            // Disk reflects the final state — no .tmp left around.
            assertThat(Files.exists(file)).isTrue();
            assertThat(Files.exists(tempDir.resolve("state.json.tmp"))).isFalse();
        }

        @Test
        @DisplayName("recordFailure / recordAborted set the terminal state with a reason and a completedAt")
        void terminal_failure_and_abort_record_reason() {
            CurrentRun fail = CurrentRun.load(tempDir.resolve("fail.json"), Clock.systemUTC());
            fail.beginRun("r1", "us-east-1");
            fail.recordFailure("ingest_unreachable");

            CurrentRun abort = CurrentRun.load(tempDir.resolve("abort.json"), Clock.systemUTC());
            abort.beginRun("r2", "us-east-1");
            abort.recordAborted("aborted_by_request");

            assertSoftly(softly -> {
                softly.assertThat(fail.state()).isEqualTo(TestState.FAILED);
                softly.assertThat(fail.snapshot().failureReason()).isEqualTo("ingest_unreachable");
                softly.assertThat(fail.snapshot().completedAt()).isNotNull();
                softly.assertThat(abort.state()).isEqualTo(TestState.ABORTED);
                softly.assertThat(abort.snapshot().failureReason()).isEqualTo("aborted_by_request");
            });
        }
    }

    @Nested
    @DisplayName("restart recovery")
    class RestartRecovery {

        @Test
        @DisplayName("a fresh process re-loads the last snapshot — runId, state, counters all survive")
        void state_round_trips_across_a_simulated_restart() {
            Path file = tempDir.resolve("state.json");
            CurrentRun before = CurrentRun.load(file, fixedClock("2026-05-03T10:00:00Z"));
            before.beginRun("run-99", "eu-west-1");
            before.recordJmeterPid(7777L);
            before.transitionTo(TestState.RUNNING);
            before.updateMetrics(1234, 56, 0, 1700000000000L);
            before.flushMetrics();

            // Simulate a process restart — discard `before` and load fresh.
            CurrentRun reloaded = CurrentRun.load(file, Clock.systemUTC());
            CurrentRun.Snapshot snap = reloaded.snapshot();

            assertSoftly(softly -> {
                softly.assertThat(snap.runId()).isEqualTo("run-99");
                softly.assertThat(snap.state())
                        .as("the loaded state preserves what was on disk — caller decides how to handle non-terminal load")
                        .isEqualTo(TestState.RUNNING);
                softly.assertThat(snap.region()).isEqualTo("eu-west-1");
                softly.assertThat(snap.jmeterPid()).isEqualTo(7777L);
                softly.assertThat(snap.rowsIngested()).isEqualTo(1234L);
                softly.assertThat(snap.windowsPublished()).isEqualTo(56L);
                softly.assertThat(snap.lastPublishAckMs()).isEqualTo(1700000000000L);
            });
        }

        @Test
        @DisplayName("a corrupt snapshot file is discarded — process boots clean instead of refusing to start")
        void corrupt_snapshot_is_discarded_quietly() throws Exception {
            Path file = tempDir.resolve("state.json");
            Files.writeString(file, "{not valid json");

            CurrentRun run = CurrentRun.load(file, Clock.systemUTC());

            // Falls back to IDLE — distinguishable from a clean boot only by
            // the WARN log, but the contract is "the orchestrator still starts".
            assertThat(run.state()).isEqualTo(TestState.IDLE);
            assertThat(run.snapshotIfPresent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("write atomicity")
    class WriteAtomicity {

        @Test
        @DisplayName("creates the parent directory if it does not exist — orchestrator first-boot path")
        void persists_when_parent_dir_missing() {
            Path nested = tempDir.resolve("nested/state.json");
            CurrentRun run = CurrentRun.load(nested, Clock.systemUTC());
            run.beginRun("r", "us-east-1");

            assertThat(Files.exists(nested)).isTrue();
        }

        @Test
        @DisplayName("never leaves a .tmp file behind on a normal write")
        void no_tmp_artifact_after_successful_write() {
            Path file = tempDir.resolve("state.json");
            CurrentRun run = CurrentRun.load(file, Clock.systemUTC());
            run.beginRun("r", "us-east-1");
            run.transitionTo(TestState.RUNNING);
            run.transitionTo(TestState.COMPLETED);

            assertThat(Files.exists(tempDir.resolve("state.json.tmp"))).isFalse();
        }
    }

    private static Clock fixedClock(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
    }
}
