package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Behaviour tests for {@link ArtifactStager}. Real temp filesystem, real
 * {@link java.util.zip.ZipInputStream} — only the input streams are
 * synthetic. Each {@code @Nested} block describes a documented rule from
 * {@code ORCHESTRATOR-PLAN.md} §"Validation rules" or the atomic-swap
 * contract.
 */
@DisplayName("ArtifactStager — streaming uploads, validation, and atomic swap")
class ArtifactStagerTest {

    @TempDir Path baseDir;

    private ArtifactStager stager;
    private Path planDir;
    private Path dataDir;

    @BeforeEach
    void prepare() {
        OrchestratorConfig config = configIn(baseDir);
        stager  = new ArtifactStager(config);
        planDir = baseDir.resolve("testPlan");
        dataDir = baseDir.resolve("dataFiles");
    }

    // -----------------------------------------------------------------------
    // Test plan — happy paths
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("test plan upload — happy path")
    class TestPlanHappyPath {

        @Test
        @DisplayName("stores a raw .jmx, returns metadata with correct size + sha256, and re-reads round-trip")
        void stores_raw_jmx_and_round_trips_metadata() throws Exception {
            byte[] jmx = ("<jmeterTestPlan><thread/></jmeterTestPlan>").getBytes(StandardCharsets.UTF_8);

            PlanMetadata meta = stager.storeTestPlan(new ByteArrayInputStream(jmx), "checkout.jmx");

            assertSoftly(softly -> {
                softly.assertThat(meta.filename()).isEqualTo("checkout.jmx");
                softly.assertThat(meta.sizeBytes()).isEqualTo(jmx.length);
                softly.assertThat(meta.sha256()).isEqualTo(sha256Hex(jmx));
                softly.assertThat(meta.compressed()).isFalse();
                softly.assertThat(planDir.resolve("plan.jmx")).exists();
            });

            // Metadata companion file round-trips.
            Optional<PlanMetadata> reloaded = stager.getPlanMetadata();
            assertThat(reloaded).isPresent();
            assertThat(reloaded.get().sha256()).isEqualTo(meta.sha256());
        }

        @Test
        @DisplayName("unwraps a single .jmx from a zip body and marks compressed=true")
        void extracts_single_jmx_from_zip() throws Exception {
            byte[] jmx = "plan-content".getBytes(StandardCharsets.UTF_8);
            byte[] zip = zipOf(Map.of("checkout.jmx", jmx));

            PlanMetadata meta = stager.storeTestPlan(new ByteArrayInputStream(zip), null);
            byte[] writtenPlan = Files.readAllBytes(planDir.resolve("plan.jmx"));

            assertSoftly(softly -> {
                softly.assertThat(meta.compressed())
                        .as("the upload was a zip — flag must reflect that for diagnostics")
                        .isTrue();
                softly.assertThat(meta.sizeBytes())
                        .as("size is of the unwrapped .jmx, not of the wrapping zip")
                        .isEqualTo(jmx.length);
                softly.assertThat(writtenPlan).containsExactly(jmx);
            });
        }
    }

    @Nested
    @DisplayName("test plan upload — rejects")
    class TestPlanRejects {

        @Test
        @DisplayName("rejects a zip with no .jmx entry — INVALID_ARCHIVE")
        void rejects_zip_without_jmx() {
            byte[] zip = zipOf(Map.of("notes.txt", new byte[]{1, 2, 3}));

            assertThatThrownBy(() -> stager.storeTestPlan(new ByteArrayInputStream(zip), null))
                    .isInstanceOf(ArtifactValidationException.class)
                    .satisfies(e -> assertThat(((ArtifactValidationException) e).code()).isEqualTo("INVALID_ARCHIVE"));
        }

        @Test
        @DisplayName("rejects a zip with multiple .jmx entries — disambiguation belongs to the client")
        void rejects_zip_with_multiple_jmx() {
            byte[] zip = zipOf(Map.of(
                    "a.jmx", "a".getBytes(StandardCharsets.UTF_8),
                    "b.jmx", "b".getBytes(StandardCharsets.UTF_8)));

            assertThatThrownBy(() -> stager.storeTestPlan(new ByteArrayInputStream(zip), null))
                    .isInstanceOf(ArtifactValidationException.class);
        }

        @Test
        @DisplayName("rejects a plan over MAX_PLAN_SIZE_MB — PAYLOAD_TOO_LARGE, not a silent truncation")
        void rejects_oversized_plan() {
            // Configure a tiny cap so we don't have to ship megabytes of bytes
            // through the test JVM. The contract is what matters.
            stager = new ArtifactStager(configIn(baseDir, Map.of("MAX_PLAN_SIZE_MB", "1")));

            byte[] huge = new byte[2 * 1024 * 1024]; // 2 MB > 1 MB cap

            assertThatThrownBy(() -> stager.storeTestPlan(new ByteArrayInputStream(huge), "huge.jmx"))
                    .isInstanceOf(ArtifactValidationException.class)
                    .satisfies(e -> assertThat(((ArtifactValidationException) e).code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        }

        @Test
        @DisplayName("leaves the prior plan intact when a follow-up upload is rejected — atomic swap on failure")
        void preserves_prior_plan_on_failed_upload() throws Exception {
            // Land a valid plan first.
            byte[] firstJmx = "first".getBytes(StandardCharsets.UTF_8);
            stager.storeTestPlan(new ByteArrayInputStream(firstJmx), "first.jmx");

            // Tighten the cap below the size of a second upload, then fail.
            stager = new ArtifactStager(configIn(baseDir, Map.of("MAX_PLAN_SIZE_MB", "1")));
            byte[] huge = new byte[2 * 1024 * 1024];

            assertThatThrownBy(() -> stager.storeTestPlan(new ByteArrayInputStream(huge), "huge.jmx"))
                    .isInstanceOf(ArtifactValidationException.class);

            // Original plan must still be on disk and reachable.
            assertThat(Files.readAllBytes(planDir.resolve("plan.jmx"))).containsExactly(firstJmx);
            // No staging .new file lingers.
            assertThat(planDir.resolve("plan.jmx.new")).doesNotExist();
        }
    }

    // -----------------------------------------------------------------------
    // Data files — happy path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("data files upload — happy path")
    class DataFilesHappyPath {

        @Test
        @DisplayName("extracts a small zip, stores the original for re-download, and produces a manifest")
        void extracts_zip_and_keeps_original() throws Exception {
            byte[] users    = "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8);
            byte[] products = "sku,price\n1,9.99\n".getBytes(StandardCharsets.UTF_8);
            byte[] zip = zipOf(Map.of(
                    "users.csv",    users,
                    "products.csv", products));

            DataFilesManifest m = stager.storeDataFiles(new ByteArrayInputStream(zip));

            assertSoftly(softly -> {
                softly.assertThat(m.fileCount()).isEqualTo(2);
                softly.assertThat(m.files())
                        .as("manifest lists files in deterministic sorted order")
                        .containsExactly("products.csv", "users.csv");
                softly.assertThat(m.zipSizeBytes()).isEqualTo(zip.length);
                softly.assertThat(m.extractedBytes()).isEqualTo(users.length + products.length);
                softly.assertThat(m.sha256()).isEqualTo(sha256Hex(zip));
                softly.assertThat(dataDir.resolve("users.csv")).exists();
                softly.assertThat(dataDir.resolve("products.csv")).exists();
                // The original zip is kept so GET .../file can re-serve it.
                softly.assertThat(stager.getDataFilesZip()).isPresent();
            });

            // Round-trip the manifest read.
            assertThat(stager.getDataFilesManifest()).isPresent();
            assertThat(stager.getDataFilesManifest().get().sha256()).isEqualTo(m.sha256());
        }
    }

    // -----------------------------------------------------------------------
    // Data files — validation rejects (one rule per test, scenario-named)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("data files upload — rejects")
    class DataFilesRejects {

        @Test
        @DisplayName("rejects path-traversal entries (..) — primary defense against arbitrary file write")
        void rejects_path_traversal() {
            byte[] zip = zipOf(Map.of("../../etc/passwd.csv", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("escapes the archive root");
        }

        @Test
        @DisplayName("rejects an absolute path entry (/etc/...)")
        void rejects_absolute_path() {
            byte[] zip = zipOf(Map.of("/etc/foo.csv", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("absolute");
        }

        @Test
        @DisplayName("rejects a Windows drive letter entry (C:foo.csv)")
        void rejects_windows_drive_letter() {
            byte[] zip = zipOf(Map.of("C:foo.csv", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("Windows drive letter");
        }

        @Test
        @DisplayName("rejects a backslash-encoded traversal entry (..\\evil.csv) — Windows-packaged zips")
        void rejects_backslash_traversal() {
            byte[] zip = zipOf(Map.of("..\\evil.csv", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("escapes the archive root");
        }

        @Test
        @DisplayName("rejects a disallowed extension (.exe) — defense in depth even on path-clean entries")
        void rejects_disallowed_extension() {
            byte[] zip = zipOf(Map.of("payload.exe", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("disallowed extension");
        }

        @Test
        @DisplayName("rejects an entry larger than MAX_ENTRY_SIZE_MB — zip-bomb defense")
        void rejects_oversized_entry() {
            stager = new ArtifactStager(configIn(baseDir, Map.of(
                    "MAX_ENTRY_SIZE_MB",     "1",
                    "MAX_EXTRACTED_SIZE_MB", "10")));
            byte[] zip = zipOf(Map.of("big.csv", new byte[2 * 1024 * 1024]));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("big.csv")
                    .hasMessageContaining("1 MB");
        }

        @Test
        @DisplayName("rejects when total extracted size exceeds MAX_EXTRACTED_SIZE_MB")
        void rejects_oversized_total() {
            stager = new ArtifactStager(configIn(baseDir, Map.of(
                    "MAX_ENTRY_SIZE_MB",     "1",
                    "MAX_EXTRACTED_SIZE_MB", "1")));
            byte[] zip = zipOf(Map.of(
                    "a.csv", new byte[700 * 1024],
                    "b.csv", new byte[700 * 1024]));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("total");
        }

        @Test
        @DisplayName("rejects when file count exceeds MAX_FILE_COUNT — DoS via tiny files")
        void rejects_too_many_files() {
            stager = new ArtifactStager(configIn(baseDir, Map.of("MAX_FILE_COUNT", "2")));
            byte[] zip = zipOf(Map.of(
                    "a.csv", new byte[]{1},
                    "b.csv", new byte[]{1},
                    "c.csv", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .hasMessageContaining("file count");
        }

        @Test
        @DisplayName("rejects a non-zip body — INVALID_ARCHIVE, not a confusing IOException")
        void rejects_non_zip_body() {
            byte[] junk = "this is not a zip".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(junk)))
                    .isInstanceOf(ArtifactValidationException.class)
                    .satisfies(e -> assertThat(((ArtifactValidationException) e).code()).isEqualTo("INVALID_ARCHIVE"));
        }

        @Test
        @DisplayName("leaves the prior dataFiles set intact when the next upload is rejected")
        void preserves_prior_data_on_failed_upload() throws Exception {
            // First upload — valid 2-file zip.
            byte[] zipOk = zipOf(Map.of(
                    "users.csv",    "first".getBytes(StandardCharsets.UTF_8),
                    "products.csv", "first".getBytes(StandardCharsets.UTF_8)));
            stager.storeDataFiles(new ByteArrayInputStream(zipOk));

            // Second upload — invalid (path traversal).
            byte[] zipBad = zipOf(Map.of("../../escape.csv", new byte[]{1}));

            assertThatThrownBy(() -> stager.storeDataFiles(new ByteArrayInputStream(zipBad)))
                    .isInstanceOf(ArtifactValidationException.class);

            assertSoftly(softly -> {
                // Original files still in place.
                softly.assertThat(dataDir.resolve("users.csv")).exists();
                softly.assertThat(dataDir.resolve("products.csv")).exists();
                // No tmp staging files left around.
                softly.assertThat(baseDir.resolve("dataFiles.tmp")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip.tmp")).doesNotExist();
                // No "escape.csv" leaked anywhere — the rejection happened
                // before any user content was written outside the tmp dir.
                softly.assertThat(dataDir.resolve("escape.csv")).doesNotExist();
            });
        }
    }

    // -----------------------------------------------------------------------
    // Memory-bounded streaming
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("streaming memory contract")
    class StreamingMemoryContract {

        @Test
        @DisplayName("never reads the upload body fully into memory — proven by a stream that throws on bulk read")
        void rejects_full_buffering_attempt() throws Exception {
            // Construct a body that fails immediately if a caller asks for
            // all bytes at once. The stager must walk it in chunks ≤ 16 KB.
            InputStream guarded = guardedStream(zipOf(Map.of(
                    "users.csv", "id,name\n".getBytes(StandardCharsets.UTF_8))), 16 * 1024);

            DataFilesManifest m = stager.storeDataFiles(guarded);
            assertThat(m.fileCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("data files upload — reversible swap (atomic from the client's view)")
    class DataFilesReversibleSwap {

        @Test
        @DisplayName("a successful upload leaves no .bak / .staging / .tmp siblings — clean disk after Phase 4")
        void successful_upload_cleans_up_all_siblings() throws Exception {
            byte[] zip = zipOf(Map.of("users.csv", "id,name\n".getBytes(StandardCharsets.UTF_8)));

            stager.storeDataFiles(new ByteArrayInputStream(zip));

            assertSoftly(softly -> {
                softly.assertThat(baseDir.resolve("dataFiles.bak")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip.bak")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.manifest.json.bak")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.manifest.json.staging")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.tmp")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip.tmp")).doesNotExist();
            });
        }

        @Test
        @DisplayName("a follow-up upload that fails the manifest-write step leaves the previous upload fully intact — atomic from the client's view")
        void manifest_write_failure_preserves_prior_state() throws Exception {
            // Land a successful first upload.
            byte[] firstZip = zipOf(Map.of("users.csv", "alice,1\n".getBytes(StandardCharsets.UTF_8)));
            DataFilesManifest firstManifest = stager.storeDataFiles(new ByteArrayInputStream(firstZip));

            // Swap in a stub codec that lets the first upload settle but
            // throws on the next manifest write — simulating ENOSPC at Phase 3a.
            ArtifactStager failing = new ArtifactStager(
                    configIn(baseDir),
                    Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC),
                    new ManifestWriteFailingCodec("simulated ENOSPC"));

            byte[] secondZip = zipOf(Map.of("products.csv", "x,1\n".getBytes(StandardCharsets.UTF_8)));

            assertThatThrownBy(() -> failing.storeDataFiles(new ByteArrayInputStream(secondZip)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("simulated ENOSPC");

            // Previous state must be intact byte-for-byte: same files in
            // dataDir, same zip on disk, same manifest sha256.
            Optional<DataFilesManifest> reloaded = stager.getDataFilesManifest();
            assertSoftly(softly -> {
                softly.assertThat(reloaded).isPresent();
                softly.assertThat(reloaded.get().sha256()).isEqualTo(firstManifest.sha256());
                softly.assertThat(reloaded.get().files()).containsExactly("users.csv");
                softly.assertThat(dataDir.resolve("users.csv")).exists();
                softly.assertThat(dataDir.resolve("products.csv"))
                        .as("the second upload must not have leaked a single file into dataDir")
                        .doesNotExist();
            });
        }

        @Test
        @DisplayName("a manifest-write failure on a fresh orchestrator (no prior state) leaves the dataDir empty — no orphan tmps or staging files")
        void manifest_write_failure_on_fresh_state_leaves_clean_disk() {
            ArtifactStager failing = new ArtifactStager(
                    configIn(baseDir),
                    Clock.systemUTC(),
                    new ManifestWriteFailingCodec("ENOSPC on fresh upload"));

            byte[] zip = zipOf(Map.of("users.csv", "id\n".getBytes(StandardCharsets.UTF_8)));

            assertThatThrownBy(() -> failing.storeDataFiles(new ByteArrayInputStream(zip)))
                    .isInstanceOf(IOException.class);

            assertSoftly(softly -> {
                softly.assertThat(dataDir).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.manifest.json")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.bak")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip.bak")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.manifest.json.bak")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.manifest.json.staging")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.tmp")).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip.tmp")).doesNotExist();
            });
        }

        @Test
        @DisplayName("a second successful upload fully replaces the first — no leftover files from the prior set")
        void second_upload_replaces_first_completely() throws Exception {
            byte[] first  = zipOf(Map.of("users.csv",    "alice\n".getBytes(StandardCharsets.UTF_8)));
            byte[] second = zipOf(Map.of("products.csv", "widget\n".getBytes(StandardCharsets.UTF_8)));

            stager.storeDataFiles(new ByteArrayInputStream(first));
            stager.storeDataFiles(new ByteArrayInputStream(second));

            // Pull the manifest outside the soft-assert lambda since
            // getDataFilesManifest() declares IOException.
            Optional<DataFilesManifest> manifest = stager.getDataFilesManifest();

            assertSoftly(softly -> {
                softly.assertThat(dataDir.resolve("products.csv")).exists();
                softly.assertThat(dataDir.resolve("users.csv"))
                        .as("the first upload's files must not survive the swap")
                        .doesNotExist();
                softly.assertThat(manifest)
                        .map(DataFilesManifest::files)
                        .hasValue(List.of("products.csv"));
            });
        }
    }

    /**
     * Test-only codec that lets plan metadata writes through but always
     * throws on the dataFiles manifest-staging path. Drives the "Phase 3a
     * fails" code path that simulates ENOSPC at the manifest write.
     */
    private static final class ManifestWriteFailingCodec extends MetadataCodec {
        private final String reason;
        ManifestWriteFailingCodec(String reason) { this.reason = reason; }
        @Override
        void writeDataFilesManifestRaw(Path path, DataFilesManifest manifest) throws IOException {
            throw new IOException(reason);
        }
    }

    @Nested
    @DisplayName("upload-inflight gauge")
    class UploadInflightGauge {

        @Test
        @DisplayName("starts at 0 — fresh stager has no upload in flight")
        void initial_value_is_zero() {
            assertThat(stager.getUploadInflightBytes()).isZero();
        }

        @Test
        @DisplayName("returns to 0 after a successful test plan upload — increments are unwound on completion")
        void unwinds_after_successful_plan_upload() throws Exception {
            byte[] jmx = ("<jmeterTestPlan/>").getBytes(StandardCharsets.UTF_8);

            stager.storeTestPlan(new ByteArrayInputStream(jmx), "checkout.jmx");

            assertThat(stager.getUploadInflightBytes())
                    .as("the gauge must report 0 once the upload finishes")
                    .isZero();
        }

        @Test
        @DisplayName("returns to 0 after a successful dataFiles upload — covers the larger streaming path")
        void unwinds_after_successful_data_files_upload() throws Exception {
            byte[] zip = zipOf(Map.of("users.csv", "id,name\n".getBytes(StandardCharsets.UTF_8)));

            stager.storeDataFiles(new ByteArrayInputStream(zip));

            assertThat(stager.getUploadInflightBytes()).isZero();
        }

        @Test
        @DisplayName("returns to 0 after a failed (oversize) plan upload — the finally block unwinds even on rejection")
        void unwinds_after_failed_upload() {
            // Tighten the cap below the body so streamToFile throws PAYLOAD_TOO_LARGE
            // mid-stream — the gauge must still settle to zero.
            stager = new ArtifactStager(configIn(baseDir, Map.of("MAX_PLAN_SIZE_MB", "1")));
            byte[] huge = new byte[2 * 1024 * 1024];

            assertThatThrownBy(() -> stager.storeTestPlan(new ByteArrayInputStream(huge), "huge.jmx"))
                    .isInstanceOf(ArtifactValidationException.class);

            assertThat(stager.getUploadInflightBytes())
                    .as("a failed upload must not leak inflight bytes")
                    .isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Clear / round-trip helpers
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("clearTestPlan removes both the file and the metadata companion")
        void clear_test_plan_removes_file_and_metadata() throws Exception {
            byte[] jmx = "x".getBytes(StandardCharsets.UTF_8);
            stager.storeTestPlan(new ByteArrayInputStream(jmx), "x.jmx");
            assertThat(planDir.resolve("plan.jmx")).exists();

            stager.clearTestPlan();
            Optional<PlanMetadata> reloaded = stager.getPlanMetadata();

            assertSoftly(softly -> {
                softly.assertThat(planDir.resolve("plan.jmx")).doesNotExist();
                softly.assertThat(reloaded).isEmpty();
            });
        }

        @Test
        @DisplayName("clearDataFiles removes extracted contents, the original zip, and the manifest")
        void clear_data_files_removes_everything() throws Exception {
            byte[] zip = zipOf(Map.of("users.csv", "x".getBytes(StandardCharsets.UTF_8)));
            stager.storeDataFiles(new ByteArrayInputStream(zip));

            stager.clearDataFiles();
            Optional<DataFilesManifest> reloaded = stager.getDataFilesManifest();

            assertSoftly(softly -> {
                softly.assertThat(dataDir).doesNotExist();
                softly.assertThat(baseDir.resolve("dataFiles.zip")).doesNotExist();
                softly.assertThat(reloaded).isEmpty();
            });
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static OrchestratorConfig configIn(Path base) {
        return configIn(base, Map.of());
    }

    static OrchestratorConfig configIn(Path base, Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "stager-test",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done",
                "KAFKA_BROKERS",       "kafka:9092",
                "SCHEMA_REGISTRY_URL", "http://schema-registry:8081",
                "KAFKA_TOPIC",         "jmeter.metrics.perSecond"
        ));
        env.put("BASE_DIR",       base.toString());
        env.put("TEST_PLAN_DIR",  base.resolve("testPlan").toString());
        env.put("DATA_FILES_DIR", base.resolve("dataFiles").toString());
        env.putAll(overrides);
        return OrchestratorConfig.from(env);
    }

    static byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
        return baos.toByteArray();
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Wraps {@code source} so that any single read of more than {@code maxChunk}
     * bytes throws — a hard contract check that the caller streams in chunks
     * rather than slurping the whole upload into memory.
     */
    static InputStream guardedStream(byte[] source, int maxChunk) {
        return new InputStream() {
            int pos = 0;

            @Override
            public int read() {
                if (pos >= source.length) return -1;
                return source[pos++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (len > maxChunk) {
                    throw new AssertionError("Caller asked for " + len +
                            " bytes in one read; contract caps at " + maxChunk);
                }
                if (pos >= source.length) return -1;
                int n = Math.min(len, source.length - pos);
                System.arraycopy(source, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }
}
