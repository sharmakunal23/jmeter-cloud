package com.perf.orchestrator.http;

import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.lifecycle.ArtifactStager;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link DataFilesController}.
 *
 * <p>Same harness shape as {@link TestPlanControllerTest}: a real
 * {@link ArtifactStager} over a {@link TempDir} so the streaming +
 * validation path is actually exercised, with {@link TestRunGate} mocked
 * for cheap gate-flag flips. {@link GlobalErrorHandler} is imported so
 * {@code ArtifactValidationException}s map to the documented 400 / 413
 * envelope.
 */
@WebMvcTest(controllers = DataFilesController.class)
@ContextConfiguration(classes = {
        DataFilesController.class,
        DataFilesControllerTest.PerTestArtifactStager.class
})
@Import(GlobalErrorHandler.class)
@DisplayName("DataFilesController — Spring MVC slice (4.4d)")
class DataFilesControllerTest {

    @TempDir
    static Path baseDir;

    @Autowired MockMvc mvc;
    @Autowired ArtifactStager stager;
    @MockBean TestRunGate gate;

    @BeforeEach
    void reset_state_between_tests() throws IOException {
        // ArtifactStager is a context-scoped singleton — without an explicit
        // reset between tests its in-memory + on-disk manifests would leak
        // across method boundaries (the prior @Bean-level "delete dataDir"
        // hook only fires at context boot, not per test).
        stager.clearDataFiles();
        when(gate.isRunning()).thenReturn(false);
    }

    @Nested
    @DisplayName("POST /api/v1/dataFiles")
    class Post {

        @Test
        @DisplayName("accepts a small zip and returns 201 with the documented manifest fields")
        void accepts_small_zip() throws Exception {
            byte[] zip = zipOf(Map.of(
                    "users.csv",    "id,name\n1,a\n".getBytes(StandardCharsets.UTF_8),
                    "products.csv", "sku\n1\n".getBytes(StandardCharsets.UTF_8)));

            mvc.perform(post("/api/v1/dataFiles")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fileCount").value(2))
                    .andExpect(jsonPath("$.zipSizeBytes").value(zip.length))
                    .andExpect(jsonPath("$.sha256").isString())
                    .andExpect(jsonPath("$.files").isArray())
                    .andExpect(jsonPath("$.files.length()").value(2));
        }

        @Test
        @DisplayName("returns 400 INVALID_ARCHIVE on a path-traversal entry — never writes the malicious file")
        void rejects_path_traversal_zip() throws Exception {
            byte[] zip = zipOf(Map.of("../escape.csv", new byte[]{1}));

            MvcResult result = mvc.perform(post("/api/v1/dataFiles")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_ARCHIVE"))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as("error envelope must not leak stack traces")
                    .doesNotContain("Exception");

            // No data files manifest exists.
            mvc.perform(get("/api/v1/dataFiles"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 409 TEST_RUNNING when a test is in flight — POST is a write, not allowed during a run")
        void rejects_post_while_test_running() throws Exception {
            when(gate.isRunning()).thenReturn(true);
            byte[] zip = zipOf(Map.of("users.csv", new byte[]{1}));

            mvc.perform(post("/api/v1/dataFiles")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TEST_RUNNING"));
        }
    }

    @Nested
    @DisplayName("GET / GET-file / DELETE")
    class Lifecycle {

        @Test
        @DisplayName("GET returns 404 NO_FILE_EXISTS before any upload")
        void get_returns_404_when_empty() throws Exception {
            mvc.perform(get("/api/v1/dataFiles"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_FILE_EXISTS"));
        }

        @Test
        @DisplayName("GET .../file streams the original zip back exactly as uploaded — round-trip preserves bytes")
        void get_file_round_trips_zip() throws Exception {
            byte[] zip = zipOf(Map.of("users.csv", "x".getBytes(StandardCharsets.UTF_8)));
            mvc.perform(post("/api/v1/dataFiles")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/dataFiles/file"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/zip"))
                    .andExpect(content().bytes(zip))
                    .andExpect(header().exists("Content-Disposition"));
        }

        @Test
        @DisplayName("DELETE returns 204 and clears manifest + extracted contents")
        void delete_clears_state() throws Exception {
            byte[] zip = zipOf(Map.of("users.csv", "x".getBytes(StandardCharsets.UTF_8)));
            mvc.perform(post("/api/v1/dataFiles")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isCreated());

            mvc.perform(delete("/api/v1/dataFiles"))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/dataFiles"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("DELETE returns 409 TEST_RUNNING while a test is in flight")
        void delete_rejects_while_test_running() throws Exception {
            byte[] zip = zipOf(Map.of("users.csv", "x".getBytes(StandardCharsets.UTF_8)));
            mvc.perform(post("/api/v1/dataFiles")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isCreated());

            when(gate.isRunning()).thenReturn(true);

            mvc.perform(delete("/api/v1/dataFiles"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TEST_RUNNING"));
        }
    }

    /**
     * Lift Spring's multipart-size cap so a 512 MB zip would not be
     * pre-rejected by the filter. Same rationale as
     * {@link TestPlanControllerTest}.
     */
    @DynamicPropertySource
    static void multipart_size(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.max-file-size", () -> "-1");
        registry.add("spring.servlet.multipart.max-request-size", () -> "-1");
    }

    /**
     * @TestConfiguration (NOT plain @Configuration) so Spring Boot's
     * TypeExcludeFilter keeps this class out of the production scan
     * started by OrchestratorBeans / OrchestratorMainTest.BootSpringContext.
     */
    @TestConfiguration
    static class PerTestArtifactStager {
        @Bean
        OrchestratorConfig orchestratorConfig() {
            Map<String, String> env = new HashMap<>(Map.of(
                    "POD_NAME",            "jmeter-worker-0",
                    "TEST_REGION",         "us-east-1",
                    "RUN_ID",              "controller-test",
                    "JTL_PATH",            "/results/results.jtl",
                    "SENTINEL_PATH",       "/results/.done",
                    "KAFKA_BROKERS",       "kafka:9092",
                    "SCHEMA_REGISTRY_URL", "http://schema-registry:8081",
                    "KAFKA_TOPIC",         "jmeter.metrics.perSecond"
            ));
            env.put("BASE_DIR",       baseDir.toString());
            env.put("TEST_PLAN_DIR",  baseDir.resolve("testPlan").toString());
            env.put("DATA_FILES_DIR", baseDir.resolve("dataFiles").toString());
            return OrchestratorConfig.from(env);
        }

        @Bean
        ArtifactStager artifactStager(OrchestratorConfig config) throws IOException {
            // Reset the dataFiles dir between tests so prior uploads don't
            // leak across method boundaries (see TestPlanControllerTest
            // for the identical pattern on testPlan).
            Path dataDir = baseDir.resolve("dataFiles");
            if (Files.exists(dataDir)) {
                deleteRecursively(dataDir);
            }
            Path zip = baseDir.resolve("dataFiles.zip");
            Files.deleteIfExists(zip);
            Path manifest = baseDir.resolve("dataFiles.manifest.json");
            Files.deleteIfExists(manifest);
            return new ArtifactStager(config);
        }
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
        }
    }
}
