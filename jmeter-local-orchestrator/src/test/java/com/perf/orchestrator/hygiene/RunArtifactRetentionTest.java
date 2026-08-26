package com.perf.orchestrator.hygiene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunArtifactRetention — bounds what preserved runs cost on a never-recycled worker")
class RunArtifactRetentionTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir Path base;

    private Path results;
    private Path logs;

    private RunArtifactRetention retention(int count, Duration age) throws IOException {
        results = Files.createDirectories(base.resolve("results"));
        logs = Files.createDirectories(base.resolve("logs"));
        return new RunArtifactRetention(results, logs, count, age, CLOCK);
    }

    /** Creates results/{runId}/results.jtl + logs/{runId}/jmeter.log aged {@code ageDays}. */
    private void seedRun(String runId, int ageDays) throws IOException {
        FileTime stamp = FileTime.from(NOW.minus(Duration.ofDays(ageDays)));
        for (Path root : new Path[]{results, logs}) {
            Path dir = Files.createDirectories(root.resolve(runId));
            Files.writeString(dir.resolve("artifact.txt"), "x".repeat(128));
            Files.setLastModifiedTime(dir, stamp);
        }
    }

    private boolean exists(String runId) {
        return Files.exists(results.resolve(runId)) || Files.exists(logs.resolve(runId));
    }

    @Test
    @DisplayName("keeps the newest N and deletes the rest")
    void countBoundKeepsNewest() throws IOException {
        RunArtifactRetention r = retention(2, Duration.ZERO);
        seedRun("run-oldest", 5);
        seedRun("run-middle", 3);
        seedRun("run-newest", 1);

        RunArtifactRetention.Sweep sweep = r.sweep(Set.of());

        assertThat(exists("run-newest")).isTrue();
        assertThat(exists("run-middle")).isTrue();
        assertThat(exists("run-oldest")).isFalse();
        assertThat(sweep.removed()).isEqualTo(2); // one dir under results/, one under logs/
        assertThat(sweep.bytesFreed()).isPositive();
    }

    @Test
    @DisplayName("the age bound bites even when the count bound would have kept it")
    void ageBoundOverridesCount() throws IOException {
        RunArtifactRetention r = retention(10, Duration.ofDays(7));
        seedRun("run-ancient", 30);
        seedRun("run-recent", 1);

        r.sweep(Set.of());

        assertThat(exists("run-recent")).isTrue();
        assertThat(exists("run-ancient")).isFalse();
    }

    @Test
    @DisplayName("retainCount=0 means 'no count bound', NOT 'keep nothing' — a deployment that "
            + "only wants an age cap must not lose everything on the first sweep")
    void zeroCountIsNoBoundNotDeleteAll() throws IOException {
        RunArtifactRetention r = retention(0, Duration.ofDays(7));
        seedRun("run-recent-a", 1);
        seedRun("run-recent-b", 2);
        seedRun("run-ancient", 30);

        r.sweep(Set.of());

        assertThat(exists("run-recent-a")).isTrue();
        assertThat(exists("run-recent-b")).isTrue();
        assertThat(exists("run-ancient")).isFalse();
    }

    @Test
    @DisplayName("both bounds at 0 disables the sweep entirely — the documented debugging escape")
    void bothBoundsZeroDisables() throws IOException {
        RunArtifactRetention r = retention(0, Duration.ZERO);
        seedRun("run-ancient", 365);

        assertThat(r.isDisabled()).isTrue();
        assertThat(r.sweep(Set.of()).removed()).isZero();
        assertThat(exists("run-ancient")).isTrue();
    }

    @Test
    @DisplayName("the in-flight run is never a candidate — its artifacts are being written as we walk")
    void protectedRunSurvivesEvenWhenOldest() throws IOException {
        RunArtifactRetention r = retention(1, Duration.ofDays(1));
        seedRun("run-live", 10);   // old enough to be doomed by BOTH bounds
        seedRun("run-newer", 0);

        r.sweep(Set.of("run-live"));

        assertThat(exists("run-live")).isTrue();
        assertThat(exists("run-newer")).isTrue();
    }

    @Test
    @DisplayName("an empty or missing tree is a no-op, not a failure")
    void emptyTreeIsFine() throws IOException {
        RunArtifactRetention r = retention(2, Duration.ofDays(7));
        assertThat(r.sweep(Set.of()).removed()).isZero();

        RunArtifactRetention missing = new RunArtifactRetention(
                base.resolve("nope"), base.resolve("alsoNope"), 2, Duration.ofDays(7), CLOCK);
        assertThat(missing.sweep(Set.of()).removed()).isZero();
    }

    @Test
    @DisplayName("loose files beside the run directories are left alone")
    void ignoresNonDirectoryEntries() throws IOException {
        RunArtifactRetention r = retention(0, Duration.ofDays(1));
        Files.writeString(results.resolve("currentRun.json"), "{}");
        Files.setLastModifiedTime(results.resolve("currentRun.json"),
                FileTime.from(NOW.minus(Duration.ofDays(30))));

        r.sweep(Set.of());

        assertThat(Files.exists(results.resolve("currentRun.json"))).isTrue();
    }
}
