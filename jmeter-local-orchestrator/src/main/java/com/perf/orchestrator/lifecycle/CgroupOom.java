package com.perf.orchestrator.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Linux-cgroup OOM affordances for the JMeter child.
 *
 * <p>WORKER-OOM (2026-06-01). The orchestrator and its JMeter child share one
 * container cgroup with no memory isolation. Two distinct, easily-confused
 * kernel mechanisms matter here, and this class addresses both:
 *
 * <ol>
 *   <li><b>Who gets killed</b> — when the cgroup hits {@code memory.max}, the
 *       kernel OOM killer picks a victim by {@code oom_badness}. Left to chance
 *       it can pick PID 1 (the orchestrator), which kills the whole pod and
 *       loses the run's FAILED bookkeeping + preserved artifacts.
 *       {@link #preferAsOomVictim(long, int)} raises the child's
 *       {@code /proc/<pid>/oom_score_adj} so the child is always sacrificed
 *       first and the orchestrator survives to report cleanly.</li>
 *   <li><b>Whether it was an OOM</b> — a cgroup OOM is a {@code SIGKILL}, so the
 *       child just exits 137; that is indistinguishable from a PodRecycler drain
 *       or an operator kill by exit code alone. {@link #oomKillCount()} reads the
 *       cgroup's cumulative {@code oom_kill} counter; comparing a before/after
 *       snapshot tells the lifecycle whether the exit was a genuine OOM
 *       ({@code jmeter_oom}) or just a SIGKILL ({@code jmeter_exit_137}).</li>
 * </ol>
 *
 * <p>All methods are best-effort and Linux-only. On a non-Linux dev box (macOS),
 * or under an unexpected cgroup layout, the cgroup files are absent: detection
 * returns {@link #UNAVAILABLE} and the nudge is a silent no-op. Nothing here ever
 * throws into the run path.
 */
final class CgroupOom {

    private static final Logger LOG = LoggerFactory.getLogger(CgroupOom.class);

    /** Sentinel returned by {@link #oomKillCount()} when the counter can't be read. */
    static final long UNAVAILABLE = -1L;

    /** cgroup v2 unified hierarchy. */
    private static final Path V2_EVENTS = Path.of("/sys/fs/cgroup/memory.events");
    /** cgroup v1 memory controller (kernels >= 4.13 expose oom_kill here). */
    private static final Path V1_OOM_CONTROL = Path.of("/sys/fs/cgroup/memory/memory.oom_control");

    private CgroupOom() {}

    /**
     * Cumulative count of OOM kills in this container's memory cgroup, or
     * {@link #UNAVAILABLE} if the counter can't be read (non-Linux, missing file,
     * unparseable). The value is monotonic for the cgroup's lifetime, so callers
     * must snapshot it before the run and compare after the child exits — a delta
     * of >= 1 means the kernel OOM-killed something in this cgroup during the run.
     */
    static long oomKillCount() {
        long v2 = readOomKill(V2_EVENTS);
        if (v2 != UNAVAILABLE) return v2;
        return readOomKill(V1_OOM_CONTROL);
    }

    /**
     * Raise (or lower) the {@code oom_score_adj} of {@code pid} to make it the
     * preferred — or de-prioritised — OOM victim. Valid range is [-1000, 1000];
     * 1000 means "kill me first". Raising the value is unprivileged; lowering it
     * below the current value needs {@code CAP_SYS_RESOURCE}, so a failure here is
     * logged at DEBUG and ignored rather than failing the run.
     *
     * @param scoreAdj 0 (or any out-of-range value) skips the nudge entirely.
     */
    static void preferAsOomVictim(long pid, int scoreAdj) {
        if (scoreAdj == 0 || scoreAdj < -1000 || scoreAdj > 1000) {
            return; // opt-out / invalid → leave the kernel default in place
        }
        Path target = Path.of("/proc/" + pid + "/oom_score_adj");
        try {
            Files.writeString(target, Integer.toString(scoreAdj), StandardCharsets.UTF_8);
            LOG.info("Set oom_score_adj={} on JMeter child pid={} "
                    + "(child preferred as OOM victim; orchestrator protected)", scoreAdj, pid);
        } catch (IOException | RuntimeException e) {
            // Non-Linux, /proc absent, or insufficient privilege. Best-effort:
            // the orchestrator still detects+reports an OOM, it just loses the
            // guarantee that PID 1 is spared. One DEBUG line, no run impact.
            LOG.debug("Could not set oom_score_adj on pid={}: {}", pid, e.toString());
        }
    }

    /** Read + parse the {@code oom_kill N} line from a cgroup stats file; {@link #UNAVAILABLE} on any miss. */
    private static long readOomKill(Path file) {
        try {
            if (!Files.isReadable(file)) return UNAVAILABLE;
            return parseOomKill(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not read oom_kill from {}: {}", file, e.toString());
            return UNAVAILABLE;
        }
    }

    /**
     * Extract the {@code oom_kill} counter from the lines of a cgroup
     * {@code memory.events} (v2) or {@code memory.oom_control} (v1) file.
     * Both expose a {@code "oom_kill N"} line. Returns {@link #UNAVAILABLE} when
     * the line is absent or its value is unparseable. Package-private for testing.
     */
    static long parseOomKill(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("oom_kill ")) {
                try {
                    return Long.parseLong(line.substring("oom_kill ".length()).trim());
                } catch (NumberFormatException e) {
                    return UNAVAILABLE;
                }
            }
        }
        return UNAVAILABLE;
    }
}
