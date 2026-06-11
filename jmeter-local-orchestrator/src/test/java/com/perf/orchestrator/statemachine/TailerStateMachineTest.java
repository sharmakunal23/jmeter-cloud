package com.perf.orchestrator.statemachine;

import com.perf.orchestrator.aggregator.TumblingWindowAggregator;
import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.testsupport.WorkerMetricRow;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.io.SentinelWatcher;
import com.perf.orchestrator.io.JtlOffsetStore;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Integration test for {@link TailerStateMachine}.
 *
 * <p>Runs the full pipeline end-to-end: real filesystem I/O, real aggregation,
 * and a {@link RecordingMetricPublisher} that captures what would be sent to Kafka.
 * No Kafka broker, no mocking, no fakes beyond the publisher.
 *
 * <p>Each test runs the state machine in a background thread and controls it
 * by writing rows and the sentinel file from the test thread — exactly what
 * the JMeter wrapper script does in production.
 *
 * <p>All tests are annotated {@code @Timeout} so a stuck state machine
 * (e.g. never reaching DONE) fails fast rather than hanging CI.
 */
@DisplayName("TailerStateMachine integration")
class TailerStateMachineTest {

    static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName," +
            "dataType,success,failureMessage,bytes,sentBytes,grpThreads," +
            "allThreads,URL,Latency,IdleTime,Connect\n";

    static final String ROW_TEMPLATE =
            "%s,200,POST /api/payment,200,OK,jmeter-worker-0 1-1," +
            "text,true,,1024,512,80,80,https://app/api/payment,198,0,2\n";

    static final String ROW_ACCOUNT =
            "%s,150,GET /api/account,200,OK,jmeter-worker-0 1-2," +
            "text,true,,512,256,80,80,https://app/api/account,148,0,2\n";

    @TempDir
    Path tempDir;

    private Path jtlFile;
    private Path sentinelFile;
    private Path stateFile;
    private RecordingMetricPublisher publisher;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jtlFile      = tempDir.resolve("results.jtl");
        sentinelFile = tempDir.resolve(".done");
        stateFile    = tempDir.resolve(".jtlOffset");
        publisher    = new RecordingMetricPublisher();
        executor     = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "state-machine-test-thread");
            t.setDaemon(true); // daemon so it doesn't block JVM exit on test failure
            return t;
        });
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                // Test thread did not stop — this will be visible as a test timeout failure
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private OrchestratorConfig config() {
        Map<String, String> env = new HashMap<>();
        env.put("POD_NAME",            "jmeter-worker-0");
        env.put("TEST_REGION",         "us-east-1");
        env.put("RUN_ID",              "test-run");
        env.put("JTL_PATH",            jtlFile.toString());
        env.put("SENTINEL_PATH",       sentinelFile.toString());
        env.put("KAFKA_BROKERS",       "kafka:9092");
        env.put("SCHEMA_REGISTRY_URL", "http://schema-registry:8081");
        env.put("KAFKA_TOPIC",         "jmeter.metrics.perSecond");
        env.put("STATE_FILE_PATH",     stateFile.toString());
        env.put("POLL_INTERVAL_MS",         "20");
        env.put("FILE_WAIT_POLL_INTERVAL_MS","20");
        env.put("STATE_FLUSH_INTERVAL_MS",  "60000");
        env.put("DRAIN_EMPTY_POLLS_THRESHOLD", "3");
        env.put("GRACE_PERIOD_SECONDS",     "2");
        return OrchestratorConfig.from(env);
    }

    private TailerStateMachine buildMachine(OrchestratorConfig config) {
        return new TailerStateMachine(
                config,
                new JtlOffsetStore(stateFile),
                new SentinelWatcher(sentinelFile),
                new TumblingWindowAggregator(
                        config.getPodName(), config.getTestRegion(),
                        config.getRunId(), config.getGracePeriodSeconds()),
                publisher,
                new com.perf.orchestrator.buffer.SynchronousMetricsDispatcher(publisher),
                config.getKafkaTopic());
    }

    private Future<?> runInBackground(TailerStateMachine machine) {
        return executor.submit(machine::run);
    }

    private void writeRows(String... rows) throws IOException {
        for (String row : rows) {
            Files.writeString(jtlFile, row, StandardOpenOption.APPEND);
        }
    }

    private void writeSentinel() throws IOException {
        Files.writeString(sentinelFile, "0");
    }

    private void awaitCompletion(Future<?> f) throws Exception {
        f.get(10, TimeUnit.SECONDS);
    }

    private static String row(String timestamp) {
        return String.format(ROW_TEMPLATE, timestamp);
    }

    private static String accountRow(String timestamp) {
        return String.format(ROW_ACCOUNT, timestamp);
    }

    // -----------------------------------------------------------------------
    // Full lifecycle behaviour
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("full lifecycle")
    class FullLifecycle {

        @Test
        @Timeout(15)
        @DisplayName("waits for the JTL file to appear then processes rows to completion")
        void processes_rows_end_to_end() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            // Simulate JMeter: write file after a short delay
            Thread.sleep(50);
            Files.writeString(jtlFile, HEADER);
            writeRows(row("2025/04/13 14:32:07"));
            writeRows(row("2025/04/13 14:32:07"));
            writeRows(row("2025/04/13 14:32:08"));
            Thread.sleep(50); // let machine poll at least once
            writeSentinel();

            awaitCompletion(f);

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(publisher.snapshot());
            assertThat(metrics).isNotEmpty();

            // Two distinct seconds produced two windows
            assertThat(metrics)
                    .extracting(WorkerMetricRow::windowSecond)
                    .containsExactlyInAnyOrder(
                            metrics.stream().mapToLong(WorkerMetricRow::windowSecond)
                                   .distinct().boxed().toArray(Long[]::new));
        }

        @Test
        @Timeout(15)
        @DisplayName("throughput per second matches row count written for that second")
        void throughput_matches_row_count_per_second() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            Thread.sleep(50);
            Files.writeString(jtlFile, HEADER);
            // 3 rows at second 07, 2 rows at second 08
            writeRows(row("2025/04/13 14:32:07"));
            writeRows(row("2025/04/13 14:32:07"));
            writeRows(row("2025/04/13 14:32:07"));
            writeRows(row("2025/04/13 14:32:08"));
            writeRows(row("2025/04/13 14:32:08"));
            Thread.sleep(50);
            writeSentinel();

            awaitCompletion(f);

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(publisher.snapshot());

            // Find the metric for second 07 (1_744_554_727L)
            long sec07 = 1_744_554_727L;
            long sec08 = 1_744_554_728L;

            WorkerMetricRow at07 = metrics.stream()
                    .filter(m -> m.windowSecond() == sec07).findFirst().orElseThrow(
                    () -> new AssertionError("No metric for second 14:32:07"));
            WorkerMetricRow at08 = metrics.stream()
                    .filter(m -> m.windowSecond() == sec08).findFirst().orElseThrow(
                    () -> new AssertionError("No metric for second 14:32:08"));

            assertSoftly(softly -> {
                softly.assertThat(at07.throughput()).isEqualTo(3L);
                softly.assertThat(at08.throughput()).isEqualTo(2L);
            });
        }

        @Test
        @Timeout(15)
        @DisplayName("produces separate metrics for distinct labels within the same second")
        void separate_metrics_per_label_per_second() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            Thread.sleep(50);
            Files.writeString(jtlFile, HEADER);
            writeRows(row("2025/04/13 14:32:07"));        // POST /api/payment
            writeRows(accountRow("2025/04/13 14:32:07")); // GET /api/account
            Thread.sleep(50);
            writeSentinel();

            awaitCompletion(f);

            List<WorkerMetricRow> metrics = WorkerMetricRow.flatten(publisher.snapshot());
            List<String> labels = metrics.stream()
                    .map(WorkerMetricRow::label).distinct().toList();

            assertThat(labels).containsExactlyInAnyOrder("POST /api/payment", "GET /api/account");
        }

        @Test
        @Timeout(15)
        @DisplayName("all published metrics carry the correct worker identity from config")
        void all_metrics_carry_correct_worker_identity() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            Thread.sleep(50);
            Files.writeString(jtlFile, HEADER);
            writeRows(row("2025/04/13 14:32:07"));
            Thread.sleep(50);
            writeSentinel();

            awaitCompletion(f);

            for (WorkerMetricRow m : WorkerMetricRow.flatten(publisher.snapshot())) {
                assertSoftly(softly -> {
                    softly.assertThat(m.workerId()).isEqualTo("jmeter-worker-0");
                    softly.assertThat(m.region()).isEqualTo("us-east-1");
                    softly.assertThat(m.runId()).isEqualTo("test-run");
                });
            }
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @Timeout(15)
        @DisplayName("handles a JTL file whose final row has no trailing newline")
        void handles_final_row_without_trailing_newline() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            Thread.sleep(50);
            Files.writeString(jtlFile, HEADER);
            // Write final row without \n — JMeter may exit mid-flush
            String lastRow = row("2025/04/13 14:32:07").stripTrailing();
            Files.writeString(jtlFile, lastRow, StandardOpenOption.APPEND);
            Thread.sleep(50);
            writeSentinel();

            awaitCompletion(f);

            assertThat(WorkerMetricRow.flatten(publisher.snapshot()))
                    .as("final row without newline must be captured by pollFinal()'s LineBuffer flush")
                    .isNotEmpty();
        }

        @Test
        @Timeout(15)
        @DisplayName("exits cleanly when sentinel appears before any rows are written")
        void exits_cleanly_when_sentinel_appears_with_empty_file() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            Thread.sleep(50);
            Files.writeString(jtlFile, HEADER); // header only, no rows
            Thread.sleep(50);
            writeSentinel();

            awaitCompletion(f);

            // No rows means no metrics — but the machine must not hang or crash
            assertThat(WorkerMetricRow.flatten(publisher.snapshot())).isEmpty();
        }

        @Test
        @Timeout(15)
        @DisplayName("handles the JTL file appearing after the machine starts polling")
        void handles_delayed_file_appearance() throws Exception {
            OrchestratorConfig config = config();
            Future<?> f = runInBackground(buildMachine(config));

            // Machine starts, polls for file, finds nothing
            Thread.sleep(100);

            // File appears 100ms later
            Files.writeString(jtlFile, HEADER);
            writeRows(row("2025/04/13 14:32:07"));
            Thread.sleep(50);
            writeSentinel();

            awaitCompletion(f);

            assertThat(WorkerMetricRow.flatten(publisher.snapshot())).isNotEmpty();
        }
    }
}
