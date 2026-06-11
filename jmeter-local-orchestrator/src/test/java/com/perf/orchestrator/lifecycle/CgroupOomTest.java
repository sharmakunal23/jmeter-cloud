package com.perf.orchestrator.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WORKER-OOM — robustness + parsing tests for {@link CgroupOom}. The class is
 * Linux-only and best-effort, so the invariants under test are: it never throws
 * into the run path, and it parses the cgroup {@code oom_kill} counter correctly
 * from both v1 and v2 stat-file layouts.
 */
class CgroupOomTest {

    @Test
    void parsesOomKillFromCgroupV2Events() {
        // cgroup v2 /sys/fs/cgroup/memory.events
        List<String> v2 = List.of("low 0", "high 0", "max 0", "oom 1", "oom_kill 3");
        assertThat(CgroupOom.parseOomKill(v2)).isEqualTo(3L);
    }

    @Test
    void parsesOomKillFromCgroupV1OomControl() {
        // cgroup v1 /sys/fs/cgroup/memory/memory.oom_control
        List<String> v1 = List.of("oom_kill_disable 0", "under_oom 0", "oom_kill 7");
        assertThat(CgroupOom.parseOomKill(v1)).isEqualTo(7L);
    }

    @Test
    void returnsUnavailableWhenLineAbsentOrUnparseable() {
        assertThat(CgroupOom.parseOomKill(List.of("low 0", "high 0"))).isEqualTo(CgroupOom.UNAVAILABLE);
        assertThat(CgroupOom.parseOomKill(List.of("oom_kill notANumber"))).isEqualTo(CgroupOom.UNAVAILABLE);
        assertThat(CgroupOom.parseOomKill(List.of())).isEqualTo(CgroupOom.UNAVAILABLE);
    }

    @Test
    void oomKillCountNeverThrows() {
        // Returns a real count on Linux CI, UNAVAILABLE on a non-Linux dev box —
        // either way it must not throw and must be >= UNAVAILABLE.
        assertThatCode(() -> {
            long n = CgroupOom.oomKillCount();
            assertThat(n).isGreaterThanOrEqualTo(CgroupOom.UNAVAILABLE);
        }).doesNotThrowAnyException();
    }

    @Test
    void preferAsOomVictimIsBestEffortAndNeverThrows() {
        // A bogus pid + an opted-out / out-of-range score must all be safe no-ops.
        assertThatCode(() -> {
            CgroupOom.preferAsOomVictim(2_147_483_647L, 1000); // pid won't exist
            CgroupOom.preferAsOomVictim(1L, 0);                // 0 = opt out
            CgroupOom.preferAsOomVictim(1L, 9999);             // out of range = skip
        }).doesNotThrowAnyException();
    }
}
