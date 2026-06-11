package com.perf.orchestrator.http;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.TestRunGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ResultsController}.
 *
 * <p>Real {@link OrchestratorConfig} + real {@link CurrentRun} backed by a
 * {@link TempDir}, so the on-disk {@code results.jtl} / {@code results.jtl.gz}
 * paths and timestamps are exercised honestly. {@link TestRunGate} is
 * mocked so the running-flag flips are cheap.
 */
@WebMvcTest(controllers = ResultsController.class)
@ContextConfiguration(classes = {
        ResultsController.class,
        ResultsControllerTest.PerTestBeans.class
})
@Import(GlobalErrorHandler.class)
@DisplayName("ResultsController — Spring MVC slice (4.4f)")
class ResultsControllerTest {

    @TempDir
    static Path baseDir;

    @Autowired MockMvc mvc;
    @Autowired OrchestratorConfig config;
    @Autowired CurrentRun currentRun;
    @MockBean TestRunGate gate;

    private Path jtl;

    @BeforeEach
    void reset_state() throws IOException {
        when(gate.isRunning()).thenReturn(false);
        // WORKER-HYGIENE Phase A — JTL lives at results/{runId}/results.jtl
        // when a run has started, and the legacy flat path otherwise. The
        // controller's fallback handles both, so we sweep both locations
        // between tests.
        Path baseResults = Path.of(config.getResultsDir());
        Files.createDirectories(baseResults);
        try (var stream = Files.walk(baseResults)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(baseResults))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        }
        // Default jtl path tracks the runId on currentRun — writeJtl()
        // re-evaluates at write time so a test that calls beginRun()
        // first lands the JTL in the right subdir.
        jtl = baseResults.resolve("results.jtl");
    }

    @Nested
    @DisplayName("GET /api/v1/results — metadata")
    class Metadata {

        @Test
        @DisplayName("returns 404 NO_FILE_EXISTS before any test has run")
        void returns_404_when_no_jtl() throws Exception {
            mvc.perform(get("/api/v1/results"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_FILE_EXISTS"));
        }

        @Test
        @DisplayName("returns 200 with the documented metadata fields when a JTL exists")
        void returns_documented_metadata() throws Exception {
            currentRun.beginRun("run-1", "us-east-1");
            writeJtl("row1\nrow2\n");
            long size = Files.size(jtl);

            mvc.perform(get("/api/v1/results"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value("run-1"))
                    .andExpect(jsonPath("$.filename").value("results.jtl"))
                    .andExpect(jsonPath("$.sizeBytes").value(size))
                    .andExpect(jsonPath("$.uploadState").value("SKIPPED"))
                    .andExpect(jsonPath("$.createdAt").exists());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/results/file — streaming")
    class Download {

        @Test
        @DisplayName("returns 404 when no JTL exists")
        void returns_404_when_no_jtl() throws Exception {
            mvc.perform(get("/api/v1/results/file"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("streams text/csv with byte-exact JTL content for ?format=raw (default)")
        void returns_text_csv_for_raw() throws Exception {
            byte[] body = "row-a\nrow-b\nrow-c\n".getBytes(StandardCharsets.UTF_8);
            writeJtl(new String(body, StandardCharsets.UTF_8));

            mvc.perform(get("/api/v1/results/file"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/csv"))
                    .andExpect(content().bytes(body));
        }

        @Test
        @DisplayName("?format=zip returns 404 when no .gz has been produced — prevents silent empty downloads")
        void format_zip_404s_without_gz() throws Exception {
            writeJtl("data\n");

            mvc.perform(get("/api/v1/results/file").param("format", "zip"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_FILE_EXISTS"));
        }

        @Test
        @DisplayName("?format=zip streams application/gzip when results.jtl.gz is present")
        void format_zip_returns_gzip_when_present() throws Exception {
            writeJtl("data\n");
            Path gz = jtl.resolveSibling("results.jtl.gz");
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
                out.write("data\n".getBytes(StandardCharsets.UTF_8));
            }

            mvc.perform(get("/api/v1/results/file").param("format", "zip"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/gzip"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/results")
    class Delete {

        @Test
        @DisplayName("returns 204 and clears both .jtl and .gz")
        void deletes_jtl_and_gz() throws Exception {
            writeJtl("data\n");
            Path gz = jtl.resolveSibling("results.jtl.gz");
            Files.writeString(gz, "fake-gz");

            mvc.perform(delete("/api/v1/results"))
                    .andExpect(status().isNoContent());

            assertThat(jtl).doesNotExist();
            assertThat(gz).doesNotExist();
        }

        @Test
        @DisplayName("returns 409 TEST_RUNNING while a test is in progress — JTL is being written to")
        void rejects_delete_while_test_running() throws Exception {
            writeJtl("data\n");
            when(gate.isRunning()).thenReturn(true);

            mvc.perform(delete("/api/v1/results"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TEST_RUNNING"));

            assertThat(jtl).as("file untouched").exists();
        }

        @Test
        @DisplayName("returns 204 even when no JTL exists — idempotent")
        void delete_is_idempotent() throws Exception {
            mvc.perform(delete("/api/v1/results"))
                    .andExpect(status().isNoContent());
        }
    }

    private void writeJtl(String body) throws Exception {
        // Resolve under the runId subdir if a run has been begun in this
        // test — mirrors the production layout from WORKER-HYGIENE Phase A.
        String runId = currentRun.snapshot().runId();
        Path base = Path.of(config.getResultsDir());
        jtl = (runId == null || runId.isBlank())
                ? base.resolve("results.jtl")
                : base.resolve(runId).resolve("results.jtl");
        Files.createDirectories(jtl.getParent());
        Files.writeString(jtl, body);
    }

    /**
     * @TestConfiguration so Spring Boot's TypeExcludeFilter keeps these
     * test-only beans out of the production component scan.
     */
    @TestConfiguration
    static class PerTestBeans {
        @Bean
        OrchestratorConfig orchestratorConfig() {
            Map<String, String> env = new HashMap<>(Map.of(
                    "POD_NAME",            "jmeter-worker-0",
                    "TEST_REGION",         "us-east-1",
                    "RUN_ID",              "results-test",
                    "JTL_PATH",            "/results/results.jtl",
                    "SENTINEL_PATH",       "/results/.done",
                    "KAFKA_BROKERS",       "kafka:9092",
                    "SCHEMA_REGISTRY_URL", "http://schema-registry:8081",
                    "KAFKA_TOPIC",         "jmeter.metrics.perSecond"
            ));
            env.put("BASE_DIR",       baseDir.toString());
            env.put("RESULTS_DIR",    baseDir.resolve("results").toString());
            env.put("LOGS_DIR",       baseDir.resolve("logs").toString());
            env.put("RUN_STATE_FILE", baseDir.resolve("state/currentRun.json").toString());
            return OrchestratorConfig.from(env);
        }

        @Bean
        CurrentRun currentRun(OrchestratorConfig config) {
            return CurrentRun.load(Path.of(config.getRunStateFile()), Clock.systemUTC());
        }
    }
}
