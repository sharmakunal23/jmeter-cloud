package com.perf.orchestrator.http;

import com.perf.orchestrator.config.OrchestratorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("PlatformController")
class PlatformControllerTest {

    // -----------------------------------------------------------------------
    // Pure-Java tests of the redaction logic — no HTTP layer involved.
    // These survived the Step 4.4b Javalin → Spring migration because the
    // redactedConfig() / redactUrlUserInfo() methods are still package-
    // private and the contract (every key present, secrets redacted) is
    // load-bearing for /api/v1/config.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("/config redaction logic")
    class ConfigRedaction {

        @Test
        @DisplayName("renders the AUTH_TOKEN value as *** when set so the raw secret never reaches the JSON serializer")
        void redacts_auth_token_when_set() {
            Map<String, Object> body = controllerWith(Map.of("AUTH_TOKEN", "real-secret"))
                    .redactedConfig();

            assertSoftly(softly -> {
                softly.assertThat(body).containsEntry("AUTH_TOKEN", "***");
                softly.assertThat(body.toString())
                        .as("the raw secret must not appear even via toString")
                        .doesNotContain("real-secret");
            });
        }

        @Test
        @DisplayName("renders empty string for unset AUTH_TOKEN — distinguishable from set-but-hidden")
        void empty_auth_token_renders_as_empty_string() {
            Map<String, Object> body = controllerWith(Map.of()).redactedConfig();

            assertThat(body).containsEntry("AUTH_TOKEN", "");
        }

        @Test
        @DisplayName("redacts DOCUMENT_SERVICE_AUTH_HEADER on the same rule as AUTH_TOKEN")
        void redacts_doc_service_auth_header_when_set() {
            Map<String, Object> body = controllerWith(
                    Map.of("DOCUMENT_SERVICE_AUTH_HEADER", "Authorization: Bearer xyz"))
                    .redactedConfig();

            assertSoftly(softly -> {
                softly.assertThat(body).containsEntry("DOCUMENT_SERVICE_AUTH_HEADER", "***");
                softly.assertThat(body.toString())
                        .doesNotContain("Bearer xyz");
            });
        }
    }

    @Nested
    @DisplayName("/config — URL userinfo redaction")
    class UrlUserInfoRedaction {

        @Test
        @DisplayName("rewrites user:password embedded in DOCUMENT_SERVICE_URL to ***:*** so basic-auth credentials never leak")
        void redacts_user_and_password_when_present() {
            Map<String, Object> body = controllerWith(Map.of(
                    "RESULT_SINK", "DOCUMENT_SERVICE",
                    "AUTO_UPLOAD_RESULTS", "true",
                    "DOCUMENT_SERVICE_URL", "https://alice:s3cret@docs.internal:8443/api/v1"))
                    .redactedConfig();

            assertSoftly(softly -> {
                softly.assertThat(body)
                        .containsEntry("DOCUMENT_SERVICE_URL", "https://***:***@docs.internal:8443/api/v1");
                softly.assertThat(body.toString())
                        .as("neither the username nor the password may appear anywhere in the rendered config")
                        .doesNotContain("alice")
                        .doesNotContain("s3cret");
            });
        }

        @Test
        @DisplayName("rewrites a username-only userinfo segment to *** — covers token-style URLs")
        void redacts_user_only_when_no_password() {
            Map<String, Object> body = controllerWith(Map.of(
                    "RESULT_SINK", "DOCUMENT_SERVICE",
                    "AUTO_UPLOAD_RESULTS", "true",
                    "DOCUMENT_SERVICE_URL", "https://api-token@docs.internal/api"))
                    .redactedConfig();

            assertSoftly(softly -> {
                softly.assertThat(body)
                        .containsEntry("DOCUMENT_SERVICE_URL", "https://***@docs.internal/api");
                softly.assertThat(body.toString()).doesNotContain("api-token");
            });
        }

        @Test
        @DisplayName("leaves a credential-free DOCUMENT_SERVICE_URL untouched — operators see what they configured")
        void passes_through_url_without_userinfo() {
            Map<String, Object> body = controllerWith(Map.of(
                    "RESULT_SINK", "DOCUMENT_SERVICE",
                    "AUTO_UPLOAD_RESULTS", "true",
                    "DOCUMENT_SERVICE_URL", "https://docs.internal/api"))
                    .redactedConfig();

            assertThat(body).containsEntry("DOCUMENT_SERVICE_URL", "https://docs.internal/api");
        }

        @Test
        @DisplayName("renders empty string for unset DOCUMENT_SERVICE_URL — distinguishable from set-but-redacted")
        void unset_url_renders_as_empty_string() {
            Map<String, Object> body = controllerWith(Map.of()).redactedConfig();

            assertThat(body).containsEntry("DOCUMENT_SERVICE_URL", "");
        }

        @Test
        @DisplayName("returns the input verbatim when the URL is malformed — better than silently mangling operator input")
        void passes_malformed_url_through_unchanged() {
            String malformed = "not a real :: url";
            String result = PlatformController.redactUrlUserInfo(malformed);

            assertThat(result).isEqualTo(malformed);
        }
    }

    @Nested
    @DisplayName("/config completeness")
    class ConfigCompleteness {

        @Test
        @DisplayName("includes every orchestrator-era key declared in step 2 — operators can verify the full effective config")
        void includes_every_orchestrator_era_key() {
            Map<String, Object> body = controllerWith(Map.of()).redactedConfig();

            assertThat(body).containsKeys(
                    "HTTP_PORT", "HTTP_BIND_ADDRESS", "HTTP_MIN_THREADS", "HTTP_MAX_THREADS",
                    "HTTP_REQUEST_TIMEOUT_S", "AUTH_TOKEN",
                    "BASE_DIR", "TEST_PLAN_DIR", "DATA_FILES_DIR", "RESULTS_DIR", "LOGS_DIR",
                    "RUN_STATE_FILE",
                    "MAX_PLAN_SIZE_MB", "MAX_DATA_ZIP_SIZE_MB", "MAX_EXTRACTED_SIZE_MB",
                    "MAX_ENTRY_SIZE_MB", "MAX_FILE_COUNT",
                    "JMETER_HOME", "JMETER_BIN", "JMETER_JVM_ARGS", "JMETER_OOM_SCORE_ADJ", "JMX_PORT",
                    "ARTIFACT_SOURCE", "RESULT_SINK", "AUTO_UPLOAD_RESULTS",
                    "DOCUMENT_SERVICE_URL", "DOCUMENT_SERVICE_AUTH_HEADER",
                    "DOCUMENT_SERVICE_TIMEOUT_S", "DOCUMENT_SERVICE_RETRY_COUNT",
                    "S3_REGION",
                    "LOG_BUFFER_LINES", "KAFKA_HEALTH_CHECK_INTERVAL_MS",
                    "KAFKA_HEALTH_CHECK_TIMEOUT_MS", "MIN_FREE_DISK_MB",
                    "ORCHESTRATOR_SHUTDOWN_GRACE_S",
                    "KAFKA_BROKERS", "SCHEMA_REGISTRY_URL", "KAFKA_TOPIC",
                    "TEST_REGION", "POD_NAME", "WORKER_ID_SOURCE");
        }

        @Test
        @DisplayName("renders WORKER_ID_SOURCE back as POD_NAME / THREAD_NAME — round-trips the operator's input form")
        void renders_worker_id_source_back_as_input_form() {
            Map<String, Object> defaults = controllerWith(Map.of()).redactedConfig();
            Map<String, Object> threadName = controllerWith(Map.of("WORKER_ID_SOURCE", "THREAD_NAME"))
                    .redactedConfig();

            assertSoftly(softly -> {
                softly.assertThat(defaults).containsEntry("WORKER_ID_SOURCE", "POD_NAME");
                softly.assertThat(threadName).containsEntry("WORKER_ID_SOURCE", "THREAD_NAME");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Spring MVC slice tests — verify the @GetMapping routes are wired
    // correctly and the JSON shape matches the previous Javalin behavior.
    // @WebMvcTest brings up just the web layer (no full context, no Tomcat,
    // no autoconfiguration of unrelated beans). MockMvc serves requests
    // through the DispatcherServlet directly.
    // -----------------------------------------------------------------------

    @Nested
    @WebMvcTest(controllers = PlatformController.class)
    @ContextConfiguration(classes = {
            PlatformController.class,
            ReadyEndpointMvc.PlatformControllerTestBeans.class
    })
    @DisplayName("GET /api/v1/ready — Spring MVC slice")
    class ReadyEndpointMvc {

        @Autowired MockMvc mvc;
        @MockBean ReadinessProbe readinessProbe;

        @Test
        @DisplayName("returns 200 with status=UP when readiness reports UP")
        void up_returns_200() throws Exception {
            when(readinessProbe.snapshot()).thenReturn(
                    ReadinessProbe.Snapshot.up(5L * 1024 * 1024 * 1024, "IDLE"));

            mvc.perform(get("/api/v1/ready"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.kafkaReachable").value(true))
                    .andExpect(jsonPath("$.diskFreeBytes").value(5L * 1024 * 1024 * 1024))
                    .andExpect(jsonPath("$.testState").value("IDLE"))
                    .andExpect(jsonPath("$.reason").doesNotExist());
        }

        @Test
        @DisplayName("returns 503 with reason and kafkaReachable=false when Kafka is unreachable")
        void kafka_down_returns_503() throws Exception {
            when(readinessProbe.snapshot()).thenReturn(
                    ReadinessProbe.Snapshot.down("kafka_unreachable", 1024L, "RUNNING"));

            mvc.perform(get("/api/v1/ready"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.kafkaReachable").value(false))
                    .andExpect(jsonPath("$.reason").value("kafka_unreachable"));
        }

        @Test
        @DisplayName("returns 503 with reason=disk_pressure but kafkaReachable=true — disk failure does not contradict the live Kafka signal")
        void disk_pressure_returns_503_with_kafka_up() throws Exception {
            when(readinessProbe.snapshot()).thenReturn(
                    ReadinessProbe.Snapshot.downKafkaUp("disk_pressure", 100L, "IDLE"));

            mvc.perform(get("/api/v1/ready"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.kafkaReachable")
                            .value(true)) // sibling failure mode does not flip the Kafka flag
                    .andExpect(jsonPath("$.reason").value("disk_pressure"));
        }

        @TestConfiguration
        static class PlatformControllerTestBeans {
            // The slice runs without the pre-publish bridge, so OrchestratorConfig
            // is built directly here from the same env fixture the pure-Java
            // tests use. PlatformController's ctor takes (config, readinessProbe);
            // readinessProbe is a @MockBean so each test stubs it.
            @Bean
            OrchestratorConfig orchestratorConfig() {
                return OrchestratorConfig.from(validEnv());
            }
        }
    }

    @Nested
    @WebMvcTest(controllers = PlatformController.class)
    @ContextConfiguration(classes = {
            PlatformController.class,
            ConfigEndpointMvc.PlatformControllerTestBeans.class
    })
    @DisplayName("GET /api/v1/config — Spring MVC slice")
    class ConfigEndpointMvc {

        @Autowired MockMvc mvc;
        @MockBean ReadinessProbe readinessProbe;

        @Test
        @DisplayName("returns 200 with the full redacted env snapshot — secrets replaced with ***, every documented key present")
        void returns_redacted_config() throws Exception {
            mvc.perform(get("/api/v1/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.AUTH_TOKEN").value("***"))
                    .andExpect(jsonPath("$.HTTP_PORT").value(8080))
                    .andExpect(jsonPath("$.KAFKA_BROKERS").value("kafka:9092"))
                    .andExpect(jsonPath("$.WORKER_ID_SOURCE").value("POD_NAME"));
        }

        @TestConfiguration
        static class PlatformControllerTestBeans {
            @Bean
            OrchestratorConfig orchestratorConfig() {
                Map<String, String> env = validEnv();
                env.put("AUTH_TOKEN", "live-secret");
                return OrchestratorConfig.from(env);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static PlatformController controllerWith(Map<String, String> envOverrides) {
        Map<String, String> env = validEnv();
        env.putAll(envOverrides);
        OrchestratorConfig config = OrchestratorConfig.from(env);
        ReadinessProbe probe = ReadinessProbe.alwaysHealthy(0L, "IDLE");
        return new PlatformController(config, probe);
    }

    private static HashMap<String, String> validEnv() {
        return new HashMap<>(Map.of(
                "POD_NAME",            "jmeter-worker-0",
                "TEST_REGION",         "us-east-1",
                "RUN_ID",              "test-run",
                "JTL_PATH",            "/results/results.jtl",
                "SENTINEL_PATH",       "/results/.done",
                "KAFKA_BROKERS",       "kafka:9092",
                "SCHEMA_REGISTRY_URL", "http://schema-registry:8081",
                "KAFKA_TOPIC",         "jmeter.metrics.perSecond"
        ));
    }
}
