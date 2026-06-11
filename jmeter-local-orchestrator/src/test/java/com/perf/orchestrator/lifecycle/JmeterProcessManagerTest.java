package com.perf.orchestrator.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Verifies the real {@link JmeterProcessManager} via a tiny shell stub
 * (no real JMeter required). Skipped on Windows where {@code /bin/sh}
 * does not exist.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("JmeterProcessManager — real subprocess via /bin/sh stub")
class JmeterProcessManagerTest {

    @TempDir Path tempDir;

    private final JmeterProcessManager mgr = new JmeterProcessManager();

    @Nested
    @DisplayName("spawn + clean exit")
    class SpawnAndExit {

        @Test
        @DisplayName("returns an alive process with a real PID, then reports exit code 0 after the stub finishes")
        void spawn_then_exit_zero() throws Exception {
            JmeterProcess p = mgr.launch(spec(List.of("/bin/sh", "-c", "echo hello; exit 0")));

            assertSoftly(softly -> {
                softly.assertThat(p.pid()).isPositive();
            });

            Optional<Integer> exit = p.awaitExit(Duration.ofSeconds(5));
            assertSoftly(softly -> {
                softly.assertThat(exit).hasValue(0);
                softly.assertThat(p.isAlive()).isFalse();
            });
        }

        @Test
        @DisplayName("propagates a non-zero exit code — operator can distinguish JMeter error from clean finish")
        void spawn_then_exit_non_zero() throws Exception {
            JmeterProcess p = mgr.launch(spec(List.of("/bin/sh", "-c", "exit 7")));

            Optional<Integer> exit = p.awaitExit(Duration.ofSeconds(5));
            assertThat(exit).hasValue(7);
        }
    }

    @Nested
    @DisplayName("signals")
    class Signals {

        @Test
        @DisplayName("sigterm() ends a long-sleeping child within the next awaitExit window")
        void sigterm_terminates_long_running_child() throws Exception {
            // sleep 60 will outlive any reasonable test window — sigterm
            // should bring it down well within a second.
            JmeterProcess p = mgr.launch(spec(List.of("/bin/sh", "-c", "sleep 60")));
            assertThat(p.isAlive()).isTrue();

            p.sigterm();
            Optional<Integer> exit = p.awaitExit(Duration.ofSeconds(5));
            assertSoftly(softly -> {
                softly.assertThat(exit).isPresent();
                softly.assertThat(p.isAlive()).isFalse();
            });
        }

        @Test
        @DisplayName("sigkill() terminates a long-running child — last-resort path")
        void sigkill_terminates_long_running_child() throws Exception {
            // SIGKILL must work regardless of what the child is doing. We
            // can't reliably test "ignores SIGTERM" via a portable shell
            // trap (the shell propagates SIGTERM to the sleep child on
            // some platforms), so this test isolates the SIGKILL path.
            JmeterProcess p = mgr.launch(spec(List.of("/bin/sh", "-c", "sleep 60")));
            assertThat(p.isAlive()).isTrue();

            p.sigkill();
            Optional<Integer> killExit = p.awaitExit(Duration.ofSeconds(5));
            assertSoftly(softly -> {
                softly.assertThat(killExit).isPresent();
                softly.assertThat(p.isAlive()).isFalse();
            });
        }

        @Test
        @DisplayName("sigterm() against an already-exited child is a no-op — idempotent contract")
        void sigterm_after_exit_is_safe() throws Exception {
            JmeterProcess p = mgr.launch(spec(List.of("/bin/sh", "-c", "exit 0")));
            p.awaitExit(Duration.ofSeconds(5));

            // Should not throw.
            p.sigterm();
            p.sigkill();
            assertThat(p.isAlive()).isFalse();
        }
    }

    @Nested
    @DisplayName("awaitExit timeout")
    class AwaitExitTimeout {

        @Test
        @DisplayName("returns empty when the process is still running, leaves it alive")
        void returns_empty_when_still_alive() throws Exception {
            JmeterProcess p = mgr.launch(spec(List.of("/bin/sh", "-c", "sleep 60")));

            Optional<Integer> exit = p.awaitExit(Duration.ofMillis(200));
            assertSoftly(softly -> {
                softly.assertThat(exit).isEmpty();
                softly.assertThat(p.isAlive()).isTrue();
            });

            p.sigkill();
            p.awaitExit(Duration.ofSeconds(5));
        }
    }

    private JmeterLauncher.LaunchSpec spec(List<String> command) {
        return new JmeterLauncher.LaunchSpec(
                command,
                Map.of("PATH", System.getenv("PATH") == null ? "/usr/bin:/bin" : System.getenv("PATH")),
                tempDir,
                tempDir.resolve("jmeter.log"));
    }
}
