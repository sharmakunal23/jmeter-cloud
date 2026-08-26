package com.perf.orchestrator.http;

import com.perf.orchestrator.logs.LogTail;
import com.perf.orchestrator.metrics.CountersSupplier;
import com.perf.orchestrator.metrics.JmeterJvmSnapshot;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.OrchestratorCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link ObservabilityController}.
 *
 * <p>{@link JmxMetricsCollector} and {@link CountersSupplier} are
 * {@code @MockBean}s — both have ample dedicated unit tests
 * ({@code JmxMetricsCollectorTest}, {@code OrchestratorCountersTest}).
 * The slice's job is the HTTP boundary: 503 mapping for missing JMX,
 * 200 JSON shape for healthy snapshot, the {@code text/plain} log tail,
 * and the {@code tail} param clamping.
 *
 * <p>{@link LogTail} is wired as a real instance (size 50) — the tail/
 * clamp behaviour matters for the test, and a real ring buffer is cheaper
 * than stubbing every {@code tailAsText(n)} call.
 */
@WebMvcTest(controllers = ObservabilityController.class)
@ContextConfiguration(classes = {
        ObservabilityController.class,
        ObservabilityControllerTest.PerTestBeans.class
})
@Import(GlobalErrorHandler.class)
@DisplayName("ObservabilityController — Spring MVC slice (4.4g)")
class ObservabilityControllerTest {

    @Autowired MockMvc mvc;
    @Autowired LogTail logTail;
    @MockBean JmxMetricsCollector jmx;
    @MockBean CountersSupplier counters;

    @BeforeEach
    void clear_log_ring_between_tests() throws IOException {
        // LogTail is a singleton @Bean — without an explicit reset the
        // /logs tests would inherit lines pushed by earlier tests.
        logTail.clear();
        // Same for the on-disk fixture used by the stream=jmeter tests:
        // each test starts from a missing file (the PREPARING-state shape).
        Files.deleteIfExists(PerTestBeans.JMETER_LOG_FIXTURE);
    }

    @Nested
    @DisplayName("GET /api/v1/metrics/jmeterJvm")
    class JmeterJvm {

        @Test
        @DisplayName("returns 503 JMETER_NOT_RUNNING when the JMX agent is unreachable")
        void returns_503_when_jmx_unreachable() throws Exception {
            when(jmx.snapshot()).thenReturn(Optional.empty());

            mvc.perform(get("/api/v1/metrics/jmeterJvm"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("JMETER_NOT_RUNNING"));
        }

        @Test
        @DisplayName("returns the documented JSON shape when a snapshot is available — every documented field is present")
        void returns_documented_shape_when_jmx_available() throws Exception {
            when(jmx.snapshot()).thenReturn(Optional.of(new JmeterJvmSnapshot(
                    314_572_800L, 2_147_483_648L, 89_128_960L,
                    12L, 140L, 1L, 200L,
                    87, 34.2, 45_321L, 18_432)));

            mvc.perform(get("/api/v1/metrics/jmeterJvm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.heapUsedBytes").value(314_572_800L))
                    .andExpect(jsonPath("$.heapMaxBytes").value(2_147_483_648L))
                    .andExpect(jsonPath("$.nonHeapUsedBytes").value(89_128_960L))
                    .andExpect(jsonPath("$.threadCount").value(87))
                    .andExpect(jsonPath("$.cpuLoadPercent").value(34.2))
                    .andExpect(jsonPath("$.uptimeMs").value(45_321L))
                    .andExpect(jsonPath("$.loadedClasses").value(18_432));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/metrics/orchestrator")
    class OrchestratorMetrics {

        @Test
        @DisplayName("returns 200 with the documented counter snapshot — keys match OrchestratorMetrics in the OpenAPI spec")
        void returns_counter_snapshot() throws Exception {
            when(counters.snapshot()).thenReturn(new OrchestratorCounters(
                    100L, 4L, 0L, 1700000000000L, 0L, 8_000_000_000L, 0L));

            mvc.perform(get("/api/v1/metrics/orchestrator"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rowsParsedTotal").value(100L))
                    .andExpect(jsonPath("$.windowsPublishedTotal").value(4L))
                    .andExpect(jsonPath("$.publishLastAckEpochMs").value(1700000000000L))
                    .andExpect(jsonPath("$.diskFreeBytes").value(8_000_000_000L));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/logs")
    class Logs {

        @Test
        @DisplayName("returns text/plain newline-joined buffer contents — easy for an operator to tail with curl")
        void returns_text_plain_buffer() throws Exception {
            logTail.append("line-1");
            logTail.append("line-2");
            logTail.append("line-3");

            mvc.perform(get("/api/v1/logs").param("tail", "2"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/plain"))
                    .andExpect(content().string("line-2\nline-3"));
        }

        @Test
        @DisplayName("clamps tail outside [1, 10000] to a sensible default — no DoS via tail=999999999")
        void clamps_tail_param() throws Exception {
            for (int i = 0; i < 5; i++) logTail.append("l-" + i);

            mvc.perform(get("/api/v1/logs").param("tail", "-5"))
                    .andExpect(status().isOk());

            // Buffer holds 5 lines; even tail=99999999 clamps to 10000 max
            // and is then bounded by what's actually in the buffer.
            mvc.perform(get("/api/v1/logs").param("tail", "99999999"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("l-0\nl-1\nl-2\nl-3\nl-4"));
        }

        @Test
        @DisplayName("returns an empty body when the buffer has no lines — no NullPointer, no 500")
        void empty_buffer_returns_empty_body() throws Exception {
            mvc.perform(get("/api/v1/logs").param("tail", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(""));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/logs?stream=… (UI-1)")
    class LogStreamSelector {

        @Test
        @DisplayName("default (no stream param) returns the in-memory ring — backward-compat with pre-UI-1 callers")
        void default_stream_is_console() throws Exception {
            logTail.append("ring-1");
            logTail.append("ring-2");
            // Even with a populated jmeter.log fixture on disk, the default
            // stream must NOT pull from it — that's the contract change in UI-1.
            Files.writeString(PerTestBeans.JMETER_LOG_FIXTURE,
                    "file-1\nfile-2\n", StandardCharsets.UTF_8);

            mvc.perform(get("/api/v1/logs").param("tail", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ring-1\nring-2"));
        }

        @Test
        @DisplayName("stream=console reads only the in-memory ring buffer — never pulls from jmeter.log")
        void stream_console_reads_ring_only() throws Exception {
            logTail.append("ring-1");
            Files.writeString(PerTestBeans.JMETER_LOG_FIXTURE,
                    "file-only-1\nfile-only-2\n", StandardCharsets.UTF_8);

            mvc.perform(get("/api/v1/logs").param("stream", "console").param("tail", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ring-1"));
        }

        @Test
        @DisplayName("stream=jmeter reads only jmeter.log on disk — never pulls from the ring buffer")
        void stream_jmeter_reads_file_only() throws Exception {
            logTail.append("ring-only-line");
            Files.writeString(PerTestBeans.JMETER_LOG_FIXTURE,
                    "jmeter-1\njmeter-2\njmeter-3\n", StandardCharsets.UTF_8);

            mvc.perform(get("/api/v1/logs").param("stream", "jmeter").param("tail", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("jmeter-1\njmeter-2\njmeter-3"));
        }

        @Test
        @DisplayName("stream=jmeter on a missing file returns 200 with empty body — friendly polling shape during PREPARING")
        void stream_jmeter_missing_file_returns_empty_body() throws Exception {
            // PerTestBeans.JMETER_LOG_FIXTURE is deleted in @BeforeEach,
            // so the file doesn't exist at this point.
            logTail.append("ring-line");

            mvc.perform(get("/api/v1/logs").param("stream", "jmeter").param("tail", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(""));
        }

        @Test
        @DisplayName("stream=jmeter respects tail param — only the last N file lines come back")
        void stream_jmeter_respects_tail() throws Exception {
            Files.writeString(PerTestBeans.JMETER_LOG_FIXTURE,
                    "j-1\nj-2\nj-3\nj-4\nj-5\n", StandardCharsets.UTF_8);

            mvc.perform(get("/api/v1/logs").param("stream", "jmeter").param("tail", "2"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("j-4\nj-5"));
        }

        @Test
        @DisplayName("unknown stream value (e.g. stderr) returns 400 BAD_REQUEST — no silent fall-through to console")
        void stream_unknown_returns_400() throws Exception {
            logTail.append("would-be-leaked-if-fall-through");

            mvc.perform(get("/api/v1/logs").param("stream", "stderr").param("tail", "10"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("stream must be one of")));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class PerTestBeans {
        /**
         * Stable on-disk path the LogTail bean is pointed at for the
         * stream=jmeter slice tests. Lives in the JVM temp dir so the OS
         * cleans it up; @BeforeEach deletes it so each test method starts
         * from the "no file yet" shape.
         */
        static final Path JMETER_LOG_FIXTURE =
                Path.of(System.getProperty("java.io.tmpdir"), "ObservabilityControllerTest-jmeter.log");

        @org.springframework.context.annotation.Bean
        LogTail logTail() {
            return new LogTail(50, JMETER_LOG_FIXTURE);
        }
    }
}
