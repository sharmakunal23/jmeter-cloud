package com.perf.orchestrator.lifecycle;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-DYNAMICS events — a kill must take the whole process tree.
 * {@code bin/jmeter} is a shell wrapper: killing only the handle we hold
 * orphans the {@code java} child, which keeps the fixed ports (JMX 9999,
 * shutdown 4445, BeanShell 4446) bound and makes an immediate same-worker
 * restart die with {@code EADDRINUSE}. This drives a REAL shell + child.
 */
@DisplayName("RealJmeterProcess — kills descendants, not just the wrapper")
class RealJmeterProcessTreeKillTest {

    @Test
    void sigkill_takes_the_descendants_down_too() throws Exception {
        // `sleep 30; :` forces sh to keep a `sleep` CHILD (a bare `sleep 30`
        // would let sh exec-replace itself and leave no tree to prove).
        Process shell = new ProcessBuilder("sh", "-c", "sleep 30; :").start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .until(() -> shell.descendants().findAny().isPresent());
            ProcessHandle child = shell.descendants().findFirst().orElseThrow();

            new JmeterProcessManager.RealJmeterProcess(shell, shell.pid()).sigkill();

            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .until(() -> !shell.isAlive() && !child.isAlive());
            assertThat(child.isAlive()).as("the orphan would hold the fixed ports").isFalse();
        } finally {
            shell.descendants().forEach(ProcessHandle::destroyForcibly);
            shell.destroyForcibly();
        }
    }
}
