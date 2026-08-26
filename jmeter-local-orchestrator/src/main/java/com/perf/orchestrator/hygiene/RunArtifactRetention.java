package com.perf.orchestrator.hygiene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Bounds what previous runs leave on disk.
 *
 * <h2>Why this is needed now</h2>
 * Post-run cleanup deliberately <em>preserves</em> {@code results/{runId}/}
 * and {@code logs/{runId}/} for FAILED / ABORTED runs and for runs whose
 * upload failed — that preservation is a documented contract (an operator
 * replays a failed upload from disk). On a control-plane-provisioned worker
 * that is bounded by the recycle policy: the container goes away and takes
 * the directories with it. An operator-declared worker is never recycled,
 * so the same contract becomes unbounded growth, and the failure mode is
 * ugly — the volume fills and every subsequent run dies at first write,
 * long after the runs that caused it are forgotten.
 *
 * <h2>The trade-off, stated</h2>
 * This DELETES forensics. The defaults favour keeping the worker usable
 * over keeping every old failure: the most recent {@code retainCount} runs
 * survive, and anything older than {@code retainAge} goes regardless of
 * count. Either bound can be disabled independently (0 / ZERO), and setting
 * both to 0 disables the sweep entirely for a debugging session.
 */
public final class RunArtifactRetention {

    private static final Logger LOG = LoggerFactory.getLogger(RunArtifactRetention.class);

    private final Path resultsDir;
    private final Path logsDir;
    private final int retainCount;
    private final Duration retainAge;
    private final Clock clock;

    public RunArtifactRetention(Path resultsDir, Path logsDir, int retainCount, Duration retainAge) {
        this(resultsDir, logsDir, retainCount, retainAge, Clock.systemUTC());
    }

    /** Test seam — deterministic age evaluation. */
    public RunArtifactRetention(Path resultsDir, Path logsDir, int retainCount,
                                Duration retainAge, Clock clock) {
        this.resultsDir = resultsDir;
        this.logsDir = logsDir;
        this.retainCount = Math.max(0, retainCount);
        this.retainAge = retainAge == null ? Duration.ZERO : retainAge;
        this.clock = clock;
    }

    /** True when both bounds are off — the sweep is a no-op. */
    public boolean isDisabled() {
        return retainCount == 0 && retainAge.isZero();
    }

    /**
     * Sweeps both trees.
     *
     * @param protectedRunIds run directories that must never be deleted —
     *                        the in-flight run, whose artifacts are being
     *                        written as we walk
     * @return what was removed
     */
    public Sweep sweep(Set<String> protectedRunIds) {
        if (isDisabled()) {
            return Sweep.NOTHING;
        }
        List<String> removed = new ArrayList<>();
        long bytes = 0;
        for (Path root : List.of(resultsDir, logsDir)) {
            for (Path dir : expired(root, protectedRunIds)) {
                long size = sizeOf(dir);
                if (deleteTree(dir)) {
                    removed.add(root.getFileName() + "/" + dir.getFileName());
                    bytes += size;
                }
            }
        }
        if (!removed.isEmpty()) {
            LOG.info("Run-artifact retention removed {} directory/ies ({} KiB): {}",
                    removed.size(), bytes / 1024, removed);
        }
        return new Sweep(removed.size(), bytes, removed);
    }

    /**
     * Per-run directories under {@code root} that fall outside both bounds.
     * Newest-first ordering by last-modified time: the first
     * {@code retainCount} survive the count bound, and everything is then
     * checked against the age bound.
     */
    private List<Path> expired(Path root, Set<String> protectedRunIds) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> runDirs;
        try (Stream<Path> children = Files.list(root)) {
            runDirs = children
                    .filter(Files::isDirectory)
                    .filter(p -> !protectedRunIds.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(RunArtifactRetention::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            LOG.warn("Retention could not list {}: {}", root, e.toString());
            return List.of();
        }

        Instant cutoff = retainAge.isZero() ? null : clock.instant().minus(retainAge);
        List<Path> doomed = new ArrayList<>();
        for (int i = 0; i < runDirs.size(); i++) {
            Path dir = runDirs.get(i);
            boolean overCount = retainCount > 0 && i >= retainCount;
            boolean overAge = cutoff != null
                    && Instant.ofEpochMilli(lastModified(dir)).isBefore(cutoff);
            // retainCount == 0 means "no count bound", not "keep nothing" —
            // otherwise a deployment that only wants an age cap would silently
            // delete everything on the first sweep.
            if (overCount || overAge) {
                doomed.add(dir);
            }
        }
        return doomed;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            // Unreadable timestamp — treat as ancient so it is a deletion
            // candidate rather than an immortal directory.
            return 0L;
        }
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException io) { LOG.warn("retention could not delete {}: {}", p, io.toString()); }
            });
            return !Files.exists(dir);
        } catch (IOException e) {
            LOG.warn("retention could not sweep {}: {}", dir, e.toString());
            return false;
        }
    }

    /** @param removedDirs relative names, for logging and assertions */
    public record Sweep(int removed, long bytesFreed, List<String> removedDirs) {
        static final Sweep NOTHING = new Sweep(0, 0L, List.of());
    }
}
