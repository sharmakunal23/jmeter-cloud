package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.storage.ResultSink;
import com.perf.orchestrator.storage.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("ResultUploader — gzip + upload + retry + state transitions")
class ResultUploaderTest {

    @TempDir Path baseDir;

    private OrchestratorConfig config;
    private CurrentRun currentRun;
    private Path jtl;

    @BeforeEach
    void prepare() throws IOException {
        config = configIn(baseDir);
        currentRun = CurrentRun.load(Path.of(config.getRunStateFile()), Clock.systemUTC());
        currentRun.beginRun(config.getRunId(), "us-east-1");

        // WORKER-HYGIENE Phase A — the uploader resolves the JTL under
        // results/{runId}/results.jtl. Mirror that layout in the test.
        Path runDir = Path.of(config.getResultsDir()).resolve(config.getRunId());
        Files.createDirectories(runDir);
        jtl = runDir.resolve("results.jtl");
        Files.writeString(jtl, "row1\nrow2\nrow3\n");
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("walks PENDING → UPLOADING → UPLOADED, gzips the JTL, and reports the sink target")
        void uploads_and_records_uploaded() throws Exception {
            RecordingSink sink = new RecordingSink(0); // never fails
            new ResultUploader(sink, 0, ms -> { }).upload(config, currentRun, "worker-0", "demo-app");

            CurrentRun.Snapshot snap = currentRun.snapshot();
            assertSoftly(softly -> {
                softly.assertThat(snap.uploadState()).isEqualTo("UPLOADED");
                softly.assertThat(snap.uploadTarget()).isEqualTo("doc-service://test");
                softly.assertThat(sink.attempts.get())
                        .as("succeeded on first attempt")
                        .isEqualTo(1);

                // Gzip artifact exists and round-trips.
                Path gz = jtl.resolveSibling("results.jtl.gz");
                softly.assertThat(gz).exists();
                softly.assertThatCode(() -> {
                    byte[] decompressed;
                    try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
                        decompressed = in.readAllBytes();
                    }
                    softly.assertThat(new String(decompressed, StandardCharsets.UTF_8))
                            .isEqualTo("row1\nrow2\nrow3\n");
                }).doesNotThrowAnyException();
            });

            // The .gz.tmp staging file is gone whether we succeeded or failed.
            assertThat(jtl.resolveSibling("results.jtl.gz.tmp")).doesNotExist();
        }
    }

    // -----------------------------------------------------------------------
    // Retry
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("succeeds on the third attempt — retries with exponential backoff while transient")
        void succeeds_after_retries() {
            RecordingSink sink = new RecordingSink(2); // fail twice, then succeed
            List<Long> sleeps = new java.util.ArrayList<>();

            new ResultUploader(sink, 5, sleeps::add).upload(config, currentRun, "worker-0", "demo-app");

            assertSoftly(softly -> {
                softly.assertThat(currentRun.snapshot().uploadState()).isEqualTo("UPLOADED");
                softly.assertThat(sink.attempts.get()).isEqualTo(3);
                softly.assertThat(sleeps)
                        .as("backoff schedule = 1 s, 2 s — exponential before the success")
                        .containsExactly(1_000L, 2_000L);
            });
        }

        @Test
        @DisplayName("gives up after retries exhausted — uploadState=FAILED carries the last error reason")
        void gives_up_after_retries_exhausted() {
            RecordingSink sink = new RecordingSink(Integer.MAX_VALUE); // always fails
            new ResultUploader(sink, 2, ms -> { }).upload(config, currentRun, "worker-0", "demo-app");

            CurrentRun.Snapshot snap = currentRun.snapshot();
            assertSoftly(softly -> {
                softly.assertThat(snap.uploadState()).isEqualTo("FAILED");
                softly.assertThat(snap.uploadFailureReason())
                        .as("the failure reason mentions the underlying I/O cause")
                        .contains("transient sink error");
                softly.assertThat(sink.attempts.get())
                        .as("3 attempts = initial + 2 retries")
                        .isEqualTo(3);
            });
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("missing JTL → uploadState=FAILED with reason=missing_jtl, no exception thrown")
        void missing_jtl_lands_failed() throws IOException {
            Files.delete(jtl);
            RecordingSink sink = new RecordingSink(0);

            new ResultUploader(sink, 3, ms -> { }).upload(config, currentRun, "worker-0", "demo-app");

            assertSoftly(softly -> {
                softly.assertThat(currentRun.snapshot().uploadState()).isEqualTo("FAILED");
                softly.assertThat(currentRun.snapshot().uploadFailureReason()).isEqualTo("missing_jtl");
                softly.assertThat(sink.attempts.get())
                        .as("sink not even called when the JTL is missing")
                        .isEqualTo(0);
            });
        }

        @Test
        @DisplayName("a sink that returns noUpload() lands FAILED with sink_returned_no_upload — gating should have prevented this call")
        void no_upload_sink_lands_failed() {
            ResultSink noOp = (application, runId, workerId, file) -> UploadResult.noUpload();

            new ResultUploader(noOp, 0, ms -> { }).upload(config, currentRun, "worker-0", "demo-app");

            CurrentRun.Snapshot snap = currentRun.snapshot();
            assertSoftly(softly -> {
                softly.assertThat(snap.uploadState()).isEqualTo("FAILED");
                softly.assertThat(snap.uploadFailureReason()).isEqualTo("sink_returned_no_upload");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static OrchestratorConfig configIn(Path base) {
        Map<String, String> env = new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "uploader-test",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done",
                "KAFKA_BROKERS",       "kafka:9092",
                "SCHEMA_REGISTRY_URL", "http://schema-registry:8081",
                "KAFKA_TOPIC",         "jmeter.metrics.perSecond"
        ));
        env.put("BASE_DIR",       base.toString());
        env.put("RESULTS_DIR",    base.resolve("results").toString());
        env.put("LOGS_DIR",       base.resolve("logs").toString());
        env.put("TEST_PLAN_DIR",  base.resolve("testPlan").toString());
        env.put("DATA_FILES_DIR", base.resolve("dataFiles").toString());
        env.put("RUN_STATE_FILE", base.resolve("state/currentRun.json").toString());
        return OrchestratorConfig.from(env);
    }

    /** Sink that fails the first {@code failuresBeforeSuccess} calls then returns success. */
    static final class RecordingSink implements ResultSink {
        final AtomicInteger attempts = new AtomicInteger();
        final int failuresBeforeSuccess;

        RecordingSink(int failuresBeforeSuccess) { this.failuresBeforeSuccess = failuresBeforeSuccess; }

        @Override
        public UploadResult upload(String application, String runId, String workerId, Path file) throws IOException {
            int n = attempts.incrementAndGet();
            if (n <= failuresBeforeSuccess) {
                throw new IOException("transient sink error #" + n);
            }
            return UploadResult.uploaded("doc-service://test", java.nio.file.Files.size(file), 5L);
        }
    }
}
