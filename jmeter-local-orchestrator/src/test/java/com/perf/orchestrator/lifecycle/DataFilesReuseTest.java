package com.perf.orchestrator.lifecycle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.FetchSpec;
import com.perf.orchestrator.storage.HttpResultSink;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-DYNAMICS T4 — data files are reused across runs on a long-lived worker:
 * the manifest anchors the check by {@code blobId}, an intact staged copy
 * skips the download, tampering falls back to a fresh download (never fails
 * the run), and {@code refreshDataFiles} bypasses the cache on demand.
 */
@DisplayName("dataFiles reuse across runs (UX-DYNAMICS T4)")
class DataFilesReuseTest {

    private static final String BLOB = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @TempDir Path baseDir;

    private TestRunManager manager;
    private CurrentRun currentRun;

    @AfterEach
    void cleanup() {
        if (manager != null) {
            if (currentRun != null && currentRun.isActive()) {
                manager.abort();
                Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !currentRun.isActive());
            }
            manager.shutdown();
        }
    }

    private static byte[] dataZip() {
        return ArtifactStagerTest.zipOf(Map.of(
                "users.csv", "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8)));
    }

    // ── stager level ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the manifest records the source blobId and round-trips it")
    void manifestRecordsBlobId() throws Exception {
        ArtifactStager stager = new ArtifactStager(ArtifactStagerTest.configIn(baseDir));
        DataFilesManifest m = stager.storeDataFiles(new ByteArrayInputStream(dataZip()), BLOB);
        assertThat(m.blobId()).isEqualTo(BLOB);
        assertThat(stager.getDataFilesManifest()).isPresent()
                .get().extracting(DataFilesManifest::blobId).isEqualTo(BLOB);
        assertThat(stager.dataFilesIntact(m)).isTrue();
    }

    @Test
    @DisplayName("a direct upload (no blobId) reads back null — never matches, never reused")
    void directUploadHasNullBlobId() throws Exception {
        ArtifactStager stager = new ArtifactStager(ArtifactStagerTest.configIn(baseDir));
        stager.storeDataFiles(new ByteArrayInputStream(dataZip()));
        assertThat(stager.getDataFilesManifest()).isPresent()
                .get().extracting(DataFilesManifest::blobId).isNull();
    }

    @Test
    @DisplayName("intact tolerates extra files (plan.jmx is copied in) but not missing ones")
    void intactSemantics() throws Exception {
        ArtifactStager stager = new ArtifactStager(ArtifactStagerTest.configIn(baseDir));
        DataFilesManifest m = stager.storeDataFiles(new ByteArrayInputStream(dataZip()), BLOB);
        Path dataDir = baseDir.resolve("dataFiles");
        Files.writeString(dataDir.resolve("plan.jmx"), "<jmeterTestPlan/>");
        assertThat(stager.dataFilesIntact(m)).as("extra files are expected").isTrue();
        Files.delete(dataDir.resolve("users.csv"));
        assertThat(stager.dataFilesIntact(m)).as("a missing manifest entry breaks reuse").isFalse();
    }

    // ── manager level ────────────────────────────────────────────────────

    /** Counts dataFiles fetches; serves a fresh zip each time. */
    private static final class CountingSource implements ArtifactSource {
        final AtomicInteger dataFetches = new AtomicInteger();

        @Override
        public Optional<InputStream> fetch(String kind, FetchSpec spec) throws IOException {
            if (ArtifactSource.KIND_DATA_FILES.equals(kind)) {
                dataFetches.incrementAndGet();
                return Optional.of(new ByteArrayInputStream(dataZip()));
            }
            return Optional.empty();
        }
    }

    private static StartTestRequest req(String runId, Boolean refresh) {
        return new StartTestRequest(runId, "us-east-1", null,
                null, BLOB,           // dataFiles come from the counting source
                List.of(), List.of(), Map.of(),
                null, null, null, null, null, null, null, null,
                List.of(), refresh);
    }

    @Test
    @DisplayName("second run reuses the staged copy; tamper falls back; refreshDataFiles forces a download")
    void reuseTamperAndRefresh() throws Exception {
        OrchestratorConfig config = ArtifactStagerTest.configIn(baseDir);
        ArtifactStager stager = new ArtifactStager(config);
        stager.storeTestPlan(new ByteArrayInputStream(
                "<jmeterTestPlan><thread/></jmeterTestPlan>".getBytes(StandardCharsets.UTF_8)), "plan.jmx");
        currentRun = CurrentRun.load(Path.of(config.getRunStateFile()), Clock.systemUTC());
        TestRunManagerTest.FakeLauncher launcher = new TestRunManagerTest.FakeLauncher();
        CountingSource source = new CountingSource();
        manager = new TestRunManager(
                config, stager, currentRun, launcher,
                new TestRunManagerTest.FakePipelineFactory(),
                new HttpResultSink(), source, Clock.systemUTC());

        startAndAwait("r1", null);
        assertThat(source.dataFetches).as("first run downloads").hasValue(1);
        assertThat(manager.lastDataFilesReused()).as("202 provenance: downloaded").isFalse();
        assertThat(stager.getDataFilesManifest()).isPresent()
                .get().extracting(DataFilesManifest::blobId).isEqualTo(BLOB);

        startAndAwait("r2", null);
        assertThat(source.dataFetches).as("same blob, intact copy — download skipped").hasValue(1);
        assertThat(manager.lastDataFilesReused()).as("202 provenance: reused").isTrue();

        Files.delete(baseDir.resolve("dataFiles").resolve("users.csv"));
        startAndAwait("r3", null);
        assertThat(source.dataFetches).as("tampered copy falls back to a download").hasValue(2);

        startAndAwait("r4", Boolean.TRUE);
        assertThat(source.dataFetches).as("refreshDataFiles bypasses the intact cache").hasValue(3);
        assertThat(manager.lastDataFilesReused()).as("202 provenance: forced download").isFalse();
    }

    private void startAndAwait(String runId, Boolean refresh) {
        manager.start(req(runId, refresh));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> currentRun.isTerminal());
    }
}
