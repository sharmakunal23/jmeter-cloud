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
 * {@code @WebMvcTest} slice for {@link TestPlanController}.
 *
 * <p>The test bakes a real {@link ArtifactStager} over a JUnit {@link TempDir}
 * — the streaming + validation logic is the load-bearing part of the upload
 * path, and substituting mocks would lose coverage of the very behaviour
 * 4.4c is supposed to preserve. {@link TestRunGate} is mocked so each
 * test can flip the running flag without spinning up a real
 * {@link com.perf.orchestrator.lifecycle.TestRunManager}.
 *
 * <p>{@link GlobalErrorHandler} is imported so {@link
 * com.perf.orchestrator.lifecycle.ArtifactValidationException}s thrown by
 * the stager are mapped to the documented {@code { error, message }} envelope.
 */
@WebMvcTest(controllers = TestPlanController.class)
@ContextConfiguration(classes = {
        TestPlanController.class,
        TestPlanControllerTest.PerTestArtifactStager.class
})
@Import(GlobalErrorHandler.class)
@DisplayName("TestPlanController — Spring MVC slice (4.4c)")
class TestPlanControllerTest {

    /**
     * JUnit @TempDir wired into a static reference so the
     * {@link Configuration} class can read it when constructing
     * {@link OrchestratorConfig}. The slice context is built before the
     * test method (and its instance @TempDir) exists, so a static is the
     * cleanest bridge.
     */
    @TempDir
    static Path baseDir;

    @Autowired MockMvc mvc;
    @MockBean TestRunGate gate;

    @BeforeEach
    void default_gate_idle() {
        when(gate.isRunning()).thenReturn(false);
    }

    @Nested
    @DisplayName("POST /api/v1/testPlan")
    class Post {

        @Test
        @DisplayName("accepts a raw .jmx octet-stream and returns 201 with metadata matching the openapi schema")
        void accepts_raw_jmx() throws Exception {
            byte[] body = "<jmeterTestPlan/>".getBytes(StandardCharsets.UTF_8);

            mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/octet-stream")
                            .header("X-Filename", "checkout.jmx")
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.filename").value("checkout.jmx"))
                    .andExpect(jsonPath("$.sizeBytes").value(body.length))
                    .andExpect(jsonPath("$.sha256").isString())
                    .andExpect(jsonPath("$.compressed").value(false))
                    .andExpect(jsonPath("$.uploadedAt").exists());
        }

        @Test
        @DisplayName("accepts a .zip wrapping exactly one .jmx and reports compressed=true")
        void accepts_zip_wrapping_single_jmx() throws Exception {
            byte[] zip = singleEntryZip("checkout.jmx", "<plan/>".getBytes(StandardCharsets.UTF_8));

            mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/zip")
                            .content(zip))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.compressed").value(true));
        }

        @Test
        @DisplayName("returns 400 INVALID_ARCHIVE for a malformed zip — JSON envelope, never a stack trace")
        void rejects_bad_zip() throws Exception {
            byte[] notAZip = "PK".getBytes(StandardCharsets.UTF_8); // looks zippy, isn't

            MvcResult result = mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/zip")
                            .content(notAZip))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_ARCHIVE"))
                    .andExpect(jsonPath("$.message").exists())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .as("error envelope must not leak stack traces")
                    .doesNotContain("Exception");
        }

        @Test
        @DisplayName("returns 409 TEST_RUNNING when the gate reports a test in flight — does not mutate disk")
        void rejects_post_while_test_running() throws Exception {
            when(gate.isRunning()).thenReturn(true);

            mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/octet-stream")
                            .content("x"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TEST_RUNNING"));

            // Subsequent GET still 404 — nothing was written.
            mvc.perform(get("/api/v1/testPlan"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET / GET-file / DELETE")
    class GetAndDelete {

        @Test
        @DisplayName("GET returns 404 NO_FILE_EXISTS before any upload — the documented empty state")
        void get_metadata_returns_404_when_empty() throws Exception {
            mvc.perform(get("/api/v1/testPlan"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NO_FILE_EXISTS"));
        }

        @Test
        @DisplayName("GET .../file streams the bytes back exactly as uploaded — round-trip preserves content")
        void get_file_round_trips_bytes() throws Exception {
            byte[] body = "<jmeterTestPlan>1234567890</jmeterTestPlan>".getBytes(StandardCharsets.UTF_8);
            mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/octet-stream")
                            .content(body))
                    .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/testPlan/file"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/octet-stream"))
                    .andExpect(content().bytes(body))
                    .andExpect(header().exists("Content-Disposition"));
        }

        @Test
        @DisplayName("DELETE returns 204 and subsequent GET reports the empty state again")
        void delete_clears_and_subsequent_get_404s() throws Exception {
            mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/octet-stream")
                            .content("x"))
                    .andExpect(status().isCreated());

            mvc.perform(delete("/api/v1/testPlan"))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/testPlan"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("DELETE returns 409 TEST_RUNNING when a test is in flight — plan stays on disk")
        void delete_rejects_while_test_running() throws Exception {
            mvc.perform(post("/api/v1/testPlan")
                            .contentType("application/octet-stream")
                            .content("x"))
                    .andExpect(status().isCreated());

            when(gate.isRunning()).thenReturn(true);

            mvc.perform(delete("/api/v1/testPlan"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("TEST_RUNNING"));

            when(gate.isRunning()).thenReturn(false);
            mvc.perform(get("/api/v1/testPlan"))
                    .andExpect(status().isOk());
        }
    }

    /**
     * Spring MVC slice's `Multipart-Filter` doesn't fire on
     * {@code application/octet-stream} or {@code application/zip} content
     * types, so the controller can read the raw stream via
     * {@link jakarta.servlet.http.HttpServletRequest#getInputStream()}.
     * The slice's default `MaxFileSize` does still apply to actual
     * multipart parts; lifting it here matches what the orchestrator
     * sets in production via {@code MultipartConfigElement} so a 512 MB
     * data-files zip would not be pre-rejected before it reaches the
     * controller. (Test-side parity matters because Spring's MockMvc
     * routes through the servlet container's filter chain.)
     */
    @DynamicPropertySource
    static void multipart_size(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.max-file-size", () -> "-1");
        registry.add("spring.servlet.multipart.max-request-size", () -> "-1");
    }

    /**
     * Builds the real {@link ArtifactStager} the slice exercises. Wired
     * here (not as a @MockBean) because the upload tests need the actual
     * streaming + validation behaviour — mocks would lose the coverage
     * 4.4c is supposed to preserve.
     */
    // @TestConfiguration (NOT plain @Configuration) so Spring Boot's
    // TypeExcludeFilter keeps this class out of the production scan
    // started by OrchestratorBeans / OrchestratorMainTest.BootSpringContext.
    // Plain @Configuration would get auto-detected and trip
    // BeanDefinitionOverrideException on `orchestratorConfig`.
    @TestConfiguration
    static class PerTestArtifactStager {
        @Bean
        OrchestratorConfig orchestratorConfig() {
            Map<String, String> env = new HashMap<>(Map.of(
                    "POD_NAME",            "jmeter-worker-0",
                    "TEST_REGION",         "us-east-1",
                    "RUN_ID",              "controller-test",
                    "JTL_PATH",            "/results/results.jtl",
                    "SENTINEL_PATH",       "/results/.done"
            ));
            env.put("BASE_DIR",       baseDir.toString());
            env.put("TEST_PLAN_DIR",  baseDir.resolve("testPlan").toString());
            env.put("DATA_FILES_DIR", baseDir.resolve("dataFiles").toString());
            return OrchestratorConfig.from(env);
        }

        @Bean
        ArtifactStager artifactStager(OrchestratorConfig config) throws IOException {
            // Each test class gets its own @TempDir, but @Nested classes
            // share it. Reset the testPlan dir between tests so prior
            // uploads don't leak across method boundaries.
            Path planDir = baseDir.resolve("testPlan");
            if (Files.exists(planDir)) {
                deleteRecursively(planDir);
            }
            return new ArtifactStager(config);
        }
    }

    private static byte[] singleEntryZip(String name, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
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
