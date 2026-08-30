package com.perf.orchestrator;

import com.perf.orchestrator.config.OrchestratorBeans;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.http.BuildInfo;
import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.http.ReadinessProbe;
import com.perf.orchestrator.lifecycle.ArtifactStager;
import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.TestRunGate;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.logs.LogTail;
import com.perf.orchestrator.metrics.CountersSupplier;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.IngestReachabilityProbe;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.ResultSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OrchestratorMain")
class OrchestratorMainTest {

    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final long FIVE_GB = 5L * ONE_GB;

    @Nested
    @DisplayName("composeReadinessSnapshot precedence")
    class ReadinessPrecedence {

        @Test
        @DisplayName("UP when the consumer is reachable and disk is above threshold — the documented happy path")
        void up_when_both_healthy() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(),
                    FIVE_GB, ONE_GB, "RUNNING");

            assertSoftly(softly -> {
                softly.assertThat(s.ingestReachable()).isTrue();
                softly.assertThat(s.diskFreeBytes()).isEqualTo(FIVE_GB);
                softly.assertThat(s.testState())
                        .as("testState surfaced for diagnostics — never gates the verdict")
                        .isEqualTo("RUNNING");
                softly.assertThat(s.reason())
                        .as("UP responses must not include a reason")
                        .isNull();
            });
        }

        @Test
        @DisplayName("DOWN with the ingest reason when the consumer probe is unreachable — ingest beats disk")
        void down_when_ingest_unreachable() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.down("ingest_unreachable"),
                    FIVE_GB, ONE_GB, "IDLE");

            assertSoftly(softly -> {
                softly.assertThat(s.ingestReachable()).isFalse();
                softly.assertThat(s.reason()).isEqualTo("ingest_unreachable");
            });
        }

        @Test
        @DisplayName("DOWN with disk_pressure when the consumer is up but disk is below the configured threshold")
        void down_when_disk_below_threshold() {
            // 800 MB free, threshold 1 GB → DOWN
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(),
                    800L * 1024 * 1024, ONE_GB, "IDLE");

            assertSoftly(softly -> {
                softly.assertThat(s.ingestReachable())
                        .as("ingestReachable still TRUE — disk pressure does not contradict the ingest signal")
                        .isTrue();
                softly.assertThat(s.reason()).isEqualTo("disk_pressure");
            });
        }

        @Test
        @DisplayName("an unresolved orphan JMeter flips the worker DOWN so "
                + "a poisoned host stops being handed runs")
        void down_when_orphan_unresolved() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(),
                    FIVE_GB, ONE_GB, "IDLE",
                    "orphanJmeterProcess: pid(s) [4242] survived SIGTERM and SIGKILL");

            assertSoftly(softly -> {
                softly.assertThat(s.isUp()).isFalse();
                softly.assertThat(s.ingestReachable())
                        .as("the consumer is fine — don't report a false negative for it")
                        .isTrue();
                softly.assertThat(s.reason()).contains("orphanJmeterProcess");
            });
        }

        @Test
        @DisplayName("a cleared orphan leaves the worker UP — the reaper's normal path kills the "
                + "process and clears the signal, so a healthy worker never flaps")
        void up_when_orphan_resolved() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(), FIVE_GB, ONE_GB, "IDLE", null);

            assertThat(s.isUp()).isTrue();
            assertThat(s.reason()).isNull();
        }

        @Test
        @DisplayName("ingest and disk both outrank the orphan signal — the worker being unreachable "
                + "or out of space is the more severe cause")
        void severe_causes_outrank_orphan() {
            ReadinessProbe.Snapshot ingestDown = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.down("ingest_unreachable"),
                    FIVE_GB, ONE_GB, "IDLE", "orphanJmeterProcess: pid(s) [1]");
            assertThat(ingestDown.reason()).isEqualTo("ingest_unreachable");

            ReadinessProbe.Snapshot diskDown = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(),
                    100L * 1024 * 1024, ONE_GB, "IDLE", "orphanJmeterProcess: pid(s) [1]");
            assertThat(diskDown.reason()).isEqualTo("disk_pressure");
        }

        @Test
        @DisplayName("Ingest failure wins over disk failure when both are bad — operators see the more severe cause first")
        void ingest_reason_wins_over_disk() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.down("ingest_unreachable"),
                    100L * 1024 * 1024, ONE_GB, "IDLE");

            assertSoftly(softly -> {
                softly.assertThat(s.ingestReachable()).isFalse();
                softly.assertThat(s.reason())
                        .as("ingest_unreachable beats disk_pressure")
                        .isEqualTo("ingest_unreachable");
            });
        }

        @Test
        @DisplayName("threshold = 0 disables the disk gate — operators with external disk monitoring can opt out")
        void zero_threshold_disables_disk_gate() {
            // 1 byte free, threshold 0 → still UP because the gate is disabled
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(),
                    1L, 0L, "IDLE");

            assertThat(s.ingestReachable()).isTrue();
            assertThat(s.reason()).isNull();
        }

        @Test
        @DisplayName("disk equal to threshold is UP — strict less-than comparison avoids edge-case flapping")
        void disk_equal_threshold_is_up() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.up(),
                    ONE_GB, ONE_GB, "IDLE");

            assertThat(s.reason()).isNull();
        }

        @Test
        @DisplayName("startup_in_progress reason from the ingest probe propagates through to /ready response")
        void startup_in_progress_propagates() {
            ReadinessProbe.Snapshot s = OrchestratorMain.composeReadinessSnapshot(
                    IngestReachabilityProbe.Snapshot.down("startup_in_progress"),
                    FIVE_GB, ONE_GB, "IDLE");

            assertThat(s.reason()).isEqualTo("startup_in_progress");
        }
    }

    /**
     * Verifies the {@link OrchestratorBeans} {@code @Bean}-factory wiring:
     * every singleton the orchestrator needs is constructed by Spring from
     * environment variables and discoverable in the resulting
     * {@link ConfigurableApplicationContext}. Long-lived {@link Closeable}
     * beans use {@code destroyMethod = ""} so context close does not
     * double-close them — the orchestrator's shutdown hook owns that
     * ordering.
     *
     * <p>{@link IngestReachabilityProbe} is mocked via the
     * {@link MockOverrides} {@code @TestConfiguration}: the production
     * {@code @Bean} factory starts a daemon probe thread that OPTIONS-polls
     * an unreachable consumer URL, slowing the test for no observability
     * gain. The mock also lets us assert the close-not-called invariant on
     * a verifiable {@link Closeable} bean.
     */
    @Nested
    @DisplayName("bootSpringContext — @Bean-factory wiring")
    class BootSpringContext {

        private static Map<String, Object> validProps() {
            // Spring Boot reads SPRING_APPLICATION_JSON-style nested keys
            // here. We feed OrchestratorConfig through environment variables
            // (its existing contract); the application.* / server.* keys go
            // through Spring's normal property resolution.
            Map<String, Object> props = new LinkedHashMap<>();
            // OrchestratorConfig env vars — Spring's StandardEnvironment
            // reads the JVM's actual System.getenv(), so we pass these via
            // SpringApplication.setDefaultProperties using their env-var
            // names, but with dots so Spring's relaxed binding works.
            props.put("POD_NAME",            "jmeter-worker-0");
            props.put("TEST_REGION",         "us-east-1");
            props.put("RUN_ID",              "test-run-id");
            props.put("JTL_PATH",            "/results/results.jtl");
            props.put("SENTINEL_PATH",       "/results/.done");
            // Ephemeral port keeps these tests hermetic.
            props.put("HTTP_PORT",           "0");
            // Tomcat property defaults (mirror OrchestratorMain.bootSpringContext).
            props.put("server.port", 0);
            props.put("server.address", "127.0.0.1");
            props.put("management.endpoints.web.exposure.include", "keepalive,health,info");
            props.put("management.endpoint.health.show-details", "always");
            return props;
        }

        /**
         * Boots a fresh Spring context backed by {@link OrchestratorBeans}
         * plus a {@link MockOverrides} that swaps out the probe bean for a
         * mock. The returned context is the caller's responsibility to close.
         */
        private static ConfigurableApplicationContext bootForTest() {
            // MockOverrides is supplied as a SOURCE after OrchestratorBeans;
            // combined with spring.main.allow-bean-definition-overriding=true,
            // MockOverrides @Bean methods that share a name with the
            // production ones replace them entirely (the production factory
            // is never invoked, so its System.getenv() read can't fail).
            SpringApplication app = new SpringApplication(OrchestratorBeans.class, MockOverrides.class);
            app.setWebApplicationType(WebApplicationType.SERVLET);
            app.setRegisterShutdownHook(false);
            Map<String, Object> props = new LinkedHashMap<>(validProps());
            props.put("spring.main.allow-bean-definition-overriding", true);
            app.setDefaultProperties(props);
            return app.run();
        }

        @Test
        @DisplayName("boots and exposes every orchestrator singleton by type")
        void boots_and_exposes_singletons() {
            try (ConfigurableApplicationContext ctx = bootForTest()) {
                assertSoftly(softly -> {
                    softly.assertThat(ctx.getBean(OrchestratorConfig.class)).isNotNull();
                    softly.assertThat(ctx.getBean(BuildInfo.class)).isNotNull();
                    softly.assertThat(ctx.getBean(ArtifactStager.class))
                            .as("ArtifactStager is @Service-annotated and picked up by the open component scan")
                            .isNotNull();
                    softly.assertThat(ctx.getBean(CurrentRun.class)).isNotNull();
                    softly.assertThat(ctx.getBean(LogTail.class)).isNotNull();
                    softly.assertThat(ctx.getBean(JmxMetricsCollector.class)).isNotNull();
                    softly.assertThat(ctx.getBean(ResultSink.class)).isNotNull();
                    softly.assertThat(ctx.getBean(ArtifactSource.class)).isNotNull();
                    softly.assertThat(ctx.getBean(IngestReachabilityProbe.class)).isNotNull();
                    softly.assertThat(ctx.getBean(TestRunManager.class)).isNotNull();
                    softly.assertThat(ctx.getBean(TestRunGate.class))
                            .as("TestRunGate resolves to the testRunManager bean by polymorphism")
                            .isSameAs(ctx.getBean(TestRunManager.class));
                    softly.assertThat(ctx.getBean(ReadinessProbe.class)).isNotNull();
                    softly.assertThat(ctx.getBean(CountersSupplier.class)).isNotNull();
                    softly.assertThat(ctx.getBean(MetricsDispatcher.class)).isNotNull();
                });
            }
        }

        @Test
        @DisplayName("Tomcat binds an ephemeral port and serves /actuator/health with 200 + UP body")
        void actuator_health_serves_on_ephemeral_port() throws Exception {
            try (ConfigurableApplicationContext ctx = bootForTest()) {
                int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
                assertThat(port)
                        .as("ephemeral port assigned by the OS — must be > 0 once Tomcat has bound")
                        .isPositive();

                HttpResponse<String> health = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                assertSoftly(softly -> {
                    softly.assertThat(health.statusCode()).isEqualTo(200);
                    softly.assertThat(health.body())
                            .as("actuator health body must report UP — Spring Boot's default aggregator returns UP when no contributor is DOWN")
                            .contains("\"status\":\"UP\"");
                });
            }
        }

        @Test
        @DisplayName("context close does NOT call close() on long-lived Closeable beans — orchestrator shutdown hook owns lifecycle")
        void close_does_not_close_long_lived_beans() throws java.io.IOException {
            ConfigurableApplicationContext ctx = bootForTest();
            IngestReachabilityProbe probe = ctx.getBean(IngestReachabilityProbe.class);

            ctx.close();

            // probe is a mock supplied by MockOverrides — the production
            // bean sits on Closeable. With destroyMethod = "" on each
            // @Bean, context close must NOT cascade close() onto it; the
            // orchestrator shutdown hook owns that ordering.
            verify(probe, never()).close();
        }

        @Test
        @DisplayName("every @Bean returning a Closeable uses destroyMethod=\"\" — the lifecycle invariant in source")
        void every_closeable_bean_uses_empty_destroy_method() {
            for (Method m : OrchestratorBeans.class.getDeclaredMethods()) {
                Bean bean = m.getAnnotation(Bean.class);
                if (bean == null) continue;
                if (!Closeable.class.isAssignableFrom(m.getReturnType())) continue;

                assertThat(bean.destroyMethod())
                        .as("@Bean factory %s returns a Closeable; it must declare " +
                            "destroyMethod=\"\" so the orchestrator shutdown hook " +
                            "(not the Spring context) owns lifecycle.", m.getName())
                        .isEqualTo("");
            }
        }
    }

    /**
     * Test-side bean overrides supplying:
     * <ul>
     *   <li>A Map-driven {@link OrchestratorConfig} (the production
     *       {@code @Bean} reads {@code System.getenv()}, which is
     *       unwriteable in-process; the {@code @ConditionalOnMissingBean}
     *       on the production factory lets this one win).</li>
     *   <li>A mock for the probe bean whose production factory starts a
     *       daemon OPTIONS-polling thread against an unreachable consumer
     *       URL. The mock lets close-not-called assertions land on a
     *       verifiable surface and keeps the test quiet.</li>
     * </ul>
     */
    @TestConfiguration
    static class MockOverrides {
        // Bean names match the production @Bean method names so that
        // spring.main.allow-bean-definition-overriding lets these REPLACE
        // (not merely shadow) the production beans. The production
        // factories are never invoked, so System.getenv() and the probe's
        // daemon thread never run in tests.

        @Bean(name = "orchestratorConfig")
        OrchestratorConfig orchestratorConfig() {
            HashMap<String, String> env = new HashMap<>(Map.of(
                    "POD_NAME",            "jmeter-worker-0",
                    "TEST_REGION",         "us-east-1",
                    "RUN_ID",              "test-run-id",
                    "JTL_PATH",            "/results/results.jtl",
                    "SENTINEL_PATH",       "/results/.done"
            ));
            env.put("HTTP_PORT", "0");
            // Steer the metrics buffer (K-3) at a writable temp dir so the
            // production DiskBackedMetricsBuffer factory can mkdir it.
            // Without this, the default BASE_DIR=/opt/jmeter is unwriteable
            // in the test JVM and bean creation fails.
            env.put("BASE_DIR", System.getProperty("java.io.tmpdir") + "/orchestratorMainTest");
            return OrchestratorConfig.from(env);
        }

        @Bean(name = "ingestReachabilityProbe", destroyMethod = "")
        IngestReachabilityProbe ingestReachabilityProbe() {
            IngestReachabilityProbe probe = mock(IngestReachabilityProbe.class);
            // Default mock returns a null Snapshot — wire a sane default so
            // ReadinessProbe.snapshot().reachable() doesn't NPE on first poll.
            org.mockito.Mockito.when(probe.snapshot())
                    .thenReturn(IngestReachabilityProbe.Snapshot.up());
            return probe;
        }
    }
}
