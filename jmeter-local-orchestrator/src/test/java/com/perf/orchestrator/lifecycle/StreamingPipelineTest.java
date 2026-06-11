package com.perf.orchestrator.lifecycle;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.testsupport.WorkerMetricRow;
import com.perf.orchestrator.config.OrchestratorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * End-to-end black-box test of the {@code StreamingPipeline} wiring.
 *
 * <p>Drives a synthetic JTL file through the real parser, aggregator, and
 * state machine, with a recording publisher in place of Kafka. The
 * comprehensive lifecycle coverage lives in {@code TailerStateMachineTest};
 * this suite is narrower — its purpose is to prove that constructing a
 * {@link StreamingPipeline} produces a working assembly that the
 * orchestrator can re-use unchanged.
 */
@DisplayName("StreamingPipeline — end-to-end wiring")
class StreamingPipelineTest {

    // Header + row format mirror those in TailerStateMachineTest — same JMeter
    // CSV layout, so the parser exercises the same column-mapping path here
    // as on the well-covered hot path. Timestamps are JMeter's wall-clock
    // string format (yyyy/MM/dd HH:mm:ss), not epoch millis.
    private static final String JTL_HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName," +
            "dataType,success,failureMessage,bytes,sentBytes,grpThreads," +
            "allThreads,URL,Latency,IdleTime,Connect\n";

    private static final String ROW_TEMPLATE =
            "%s,200,POST /api/payment,200,OK,jmeter-worker-0 1-1," +
            "text,true,,1024,512,80,80,https://app/api/payment,198,0,2\n";

    @TempDir Path tempDir;

    private Path jtlFile;
    private Path sentinelFile;
    private Path stateFile;
    private RecordingMetricPublisher publisher;
    private ExecutorService executor;

    @BeforeEach
    void prepare_filesystem() throws IOException {
        jtlFile      = tempDir.resolve("results.jtl");
        sentinelFile = tempDir.resolve(".done");
        stateFile    = tempDir.resolve(".jtlOffset");

        // Header must exist before the pipeline starts so the parser can
        // map column indices on first read.
        Files.writeString(jtlFile, JTL_HEADER, StandardOpenOption.CREATE);

        publisher = new RecordingMetricPublisher();
        executor  = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void shutdown_executor() {
        executor.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Wiring contract — constructing a pipeline produces a working assembly
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("when run end-to-end against a synthetic JTL")
    class WhenRunEndToEnd {

        @Test
        @Timeout(15)
        @DisplayName("publishes one window per second worth of rows — proves parser → aggregator → publisher are wired")
        void publishes_one_window_per_second_of_rows() throws Exception {
            OrchestratorConfig config = configFor(jtlFile, sentinelFile, stateFile);
            StreamingPipeline pipeline = new StreamingPipeline(config, publisher, new com.perf.orchestrator.buffer.SynchronousMetricsDispatcher(publisher));

            Future<?> done = executor.submit(pipeline::run);

            // Two distinct seconds — should produce two windows for the same label.
            writeRows(
                    row("2026/05/03 10:00:00"),
                    row("2026/05/03 10:00:00"),
                    row("2026/05/03 10:00:01"));
            writeSentinel();

            done.get(10, TimeUnit.SECONDS);

            List<WorkerMetricRow> windows = WorkerMetricRow.flatten(publisher.snapshot());
            assertSoftly(softly -> {
                softly.assertThat(windows)
                        .as("two seconds of rows → two windows")
                        .hasSize(2);
                softly.assertThat(windows).extracting("label")
                        .containsOnly("POST /api/payment");
                softly.assertThat(windows).extracting("workerId")
                        .as("workerId derived from POD_NAME — wiring honours config")
                        .containsOnly("jmeter-worker-0");
                softly.assertThat(windows).extracting("region")
                        .containsOnly("us-east-1");
            });
        }

        @Test
        @Timeout(15)
        @DisplayName("returns from run() once the sentinel is observed and rows have drained — pipeline owns the publisher lifecycle")
        void run_returns_after_sentinel_and_drain() throws Exception {
            OrchestratorConfig config = configFor(jtlFile, sentinelFile, stateFile);
            StreamingPipeline pipeline = new StreamingPipeline(config, publisher, new com.perf.orchestrator.buffer.SynchronousMetricsDispatcher(publisher));

            Future<?> done = executor.submit(pipeline::run);

            writeRows(row("2026/05/03 10:00:00"));
            writeSentinel();

            // run() returning is the contract — KafkaMetricPublisher.close()
            // is invoked inside the state machine's finally block, so a
            // non-blocking caller (TestRunManager in step 7) can rely on
            // "future done = pipeline shut down".
            done.get(10, TimeUnit.SECONDS);

            assertThat(publisher.getPublishedCount())
                    .as("at least one window published before drain")
                    .isGreaterThan(0L);
        }
    }

    // -----------------------------------------------------------------------
    // Construction guards
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects a null config — fail loud at wiring time, not at run() time")
        void rejects_null_config() {
            assertThat(catchThrowable(() -> new StreamingPipeline(null, publisher, new com.perf.orchestrator.buffer.SynchronousMetricsDispatcher(publisher))))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("config");
        }

        @Test
        @DisplayName("rejects a null publisher — same fail-fast contract")
        void rejects_null_publisher() {
            OrchestratorConfig config = configFor(jtlFile, sentinelFile, stateFile);

            assertThat(catchThrowable(() -> new StreamingPipeline(config, null, new com.perf.orchestrator.buffer.SynchronousMetricsDispatcher(publisher))))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("publisher");
        }

        private Throwable catchThrowable(Runnable r) {
            try { r.run(); return null; } catch (Throwable t) { return t; }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static OrchestratorConfig configFor(Path jtl, Path sentinel, Path state) {
        Map<String, String> env = new HashMap<>();
        env.put("POD_NAME",            "jmeter-worker-0");
        env.put("TEST_REGION",         "us-east-1");
        env.put("RUN_ID",              "pipeline-test");
        env.put("JTL_PATH",            jtl.toString());
        env.put("SENTINEL_PATH",       sentinel.toString());
        env.put("KAFKA_BROKERS",       "kafka:9092");
        env.put("SCHEMA_REGISTRY_URL", "http://schema-registry:8081");
        env.put("KAFKA_TOPIC",         "jmeter.metrics.perSecond");
        env.put("STATE_FILE_PATH",     state.toString());
        env.put("POLL_INTERVAL_MS",          "20");
        env.put("FILE_WAIT_POLL_INTERVAL_MS", "20");
        env.put("STATE_FLUSH_INTERVAL_MS",   "60000");
        env.put("DRAIN_EMPTY_POLLS_THRESHOLD", "3");
        env.put("GRACE_PERIOD_SECONDS",       "1");
        return OrchestratorConfig.from(env);
    }

    private void writeRows(String... rows) throws IOException {
        for (String row : rows) {
            Files.writeString(jtlFile, row, StandardOpenOption.APPEND);
        }
    }

    private void writeSentinel() throws IOException {
        Files.writeString(sentinelFile, "0");
    }

    private static String row(String timestamp) {
        return String.format(ROW_TEMPLATE, timestamp);
    }
}
