package com.perf.orchestrator;

import com.perf.orchestrator.config.OrchestratorBeans;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.http.ReadinessProbe;
import com.perf.orchestrator.kafka.MetricPublisher;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.KafkaReachabilityProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for the orchestrator process. Boots the Spring application
 * context built from {@link OrchestratorBeans} (which declares every
 * orchestrator singleton as a {@code @Bean}), then registers the explicit
 * shutdown hook that owns lifecycle ordering.
 *
 * <p>{@code main()} stays small on purpose: every singleton's construction
 * and dependency wiring lives in {@link OrchestratorBeans}; this class only
 * owns the runtime ordering that Spring's default destroy phase can't
 * express (Kafka probe DOWN before drain, drain before producer close,
 * producer close before context close).
 */
public final class OrchestratorMain {

    private static final Logger LOG = LoggerFactory.getLogger(OrchestratorMain.class);

    private OrchestratorMain() {}

    public static void main(String[] args) throws InterruptedException {
        ConfigurableApplicationContext springCtx = bootSpringContext(args);

        OrchestratorConfig config = springCtx.getBean(OrchestratorConfig.class);
        int boundPort = ((WebServerApplicationContext) springCtx).getWebServer().getPort();
        LOG.info("Listening on port {} (Spring Boot Tomcat, beans={})",
                boundPort, springCtx.getBeanDefinitionCount());

        // Shutdown ordering matters for correctness:
        //
        //   1. kafkaProbe.close() — flips /actuator/health to DOWN
        //      immediately so the K8s Service stops routing new traffic to
        //      this pod within one probe interval. Cheap (≤2 s).
        //   2. runManager.shutdownGracefully(grace) — blocks while the
        //      in-flight test (if any) goes SIGTERM → JMeter exits → write
        //      sentinel → drain pipeline → publish final window → terminal
        //      state. Tomcat is still up so operators can poll
        //      GET /api/v1/test and watch the state transition. The state
        //      machine calls metricPublisher.flush() at end-of-run, so by
        //      the time this returns the singleton publisher has already
        //      drained its buffers for the last run.
        //   3. metricPublisher.close() — flushes any laggards (defensive)
        //      and disposes the underlying KafkaProducer. Must happen AFTER
        //      runManager.shutdownGracefully so we don't kill the producer
        //      while the in-flight run is still publishing.
        //   4. jmx.close() — releases the JMX connector after the
        //      pipeline / observability path no longer needs it.
        //   5. springCtx.close() — last. Stops Tomcat (no new requests),
        //      then disposes the DispatcherServlet. Every @Bean has
        //      destroyMethod="" so closing the context does NOT double-close
        //      the resources we just released above.
        Duration shutdownGrace = Duration.ofSeconds(config.getOrchestratorShutdownGraceSeconds());
        KafkaReachabilityProbe kafkaProbe = springCtx.getBean(KafkaReachabilityProbe.class);
        TestRunManager runManager = springCtx.getBean(TestRunManager.class);
        MetricPublisher metricPublisher = springCtx.getBean(MetricPublisher.class);
        JmxMetricsCollector jmx = springCtx.getBean(JmxMetricsCollector.class);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook firing — graceful drain budget: {}s", shutdownGrace.toSeconds());
            kafkaProbe.close();
            runManager.shutdownGracefully(shutdownGrace);
            try {
                metricPublisher.close();
            } catch (Exception e) {
                LOG.warn("metricPublisher.close() failed during shutdown", e);
            }
            jmx.close();
            springCtx.close();
            LOG.info("Shutdown hook complete.");
        }, "orchestrator-shutdown"));

        // Park the main thread so the JVM stays up while Tomcat's daemon
        // threads run. SIGTERM triggers the shutdown hook above.
        Thread.currentThread().join();
    }

    /**
     * Builds and starts the Spring {@link ConfigurableApplicationContext}.
     * Package-private so {@code OrchestratorMainTest} can boot a context
     * without invoking {@code main()} (no shutdown hook, no
     * {@code Thread.currentThread().join()}).
     *
     * <p>Property defaults are applied via {@link SpringApplication#setDefaultProperties}
     * — they map {@link OrchestratorConfig} env vars onto Tomcat /
     * actuator / Spring keys without forcing operators to learn new knob
     * names. The mapping is preserved from the prior pre-publish-bridge
     * implementation.
     */
    static ConfigurableApplicationContext bootSpringContext(String[] args) {
        SpringApplication app = new SpringApplication(OrchestratorBeans.class);
        // SERVLET so Spring Boot autoconfigures embedded Tomcat +
        // DispatcherServlet + actuator. Tomcat binds HTTP_PORT.
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setRegisterShutdownHook(false);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.setHeadless(true);
        app.setAddCommandLineProperties(false);

        // Load OrchestratorConfig once so we can map its env-driven values
        // onto Spring's Tomcat / actuator property surface. Spring's
        // OrchestratorConfig @Bean reads from the same env again at context
        // start; that's deliberately idempotent (validateRequiredPresent()
        // checks the same env vars).
        OrchestratorConfig config = OrchestratorConfig.fromEnvironment();
        LOG.info("jmeter-local-orchestrator starting on {}:{}",
                config.getHttpBindAddress(), config.getHttpPort());

        Map<String, Object> props = new LinkedHashMap<>();
        // Tomcat — bind HTTP_PORT + HTTP_BIND_ADDRESS exactly as the legacy
        // Javalin/Jetty server did. Thread pool is sized from the same env
        // vars (HTTP_MIN_THREADS / HTTP_MAX_THREADS) the operator already
        // sets, so deployments don't need to learn new knobs.
        props.put("server.port", config.getHttpPort());
        props.put("server.address", config.getHttpBindAddress());
        props.put("server.tomcat.threads.min-spare", config.getHttpMinThreads());
        props.put("server.tomcat.threads.max", config.getHttpMaxThreads());
        // Connection timeout converts seconds → ms for Tomcat.
        props.put("server.tomcat.connection-timeout",
                Duration.ofSeconds(config.getHttpRequestTimeoutSeconds()));
        // The orchestrator's data-files endpoint accepts up to 512 MB
        // (MAX_DATA_ZIP_SIZE_MB). Tomcat's default max-swallow-size is
        // 2 MB, which would silently truncate a half-streamed body if the
        // controller throws mid-read. -1 = no limit; the per-endpoint cap
        // is enforced by ArtifactStager streaming validation, not by
        // Tomcat's connector.
        props.put("server.tomcat.max-swallow-size", -1);
        // We don't use multipart/form-data anywhere — disable the filter
        // so it doesn't intercept octet-stream / zip POSTs.
        props.put("spring.servlet.multipart.enabled", false);
        // Actuator — expose only the endpoints we actually want public.
        // /actuator/prometheus is the scrape endpoint; health + info round
        // out the K8s probe surface. Other actuator endpoints (env, beans,
        // mappings) stay hidden by default.
        props.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        props.put("management.endpoint.health.show-details", "always");
        // Springdoc / Swagger UI — point the bundled UI at the
        // hand-curated YAML (bundled into the JAR via the pom's resource
        // mapping). Visitors hit /swagger-ui.html → redirect to
        // /swagger-ui/index.html → fetches /openapi.yaml.
        props.put("springdoc.swagger-ui.url", "/openapi.yaml");
        props.put("springdoc.swagger-ui.path", "/swagger-ui.html");
        // The auto-generated /v3/api-docs (Spring-MVC-introspected spec)
        // stays enabled as a sanity check — diverging from the curated
        // YAML means a controller change wasn't reflected in the spec.
        props.put("springdoc.api-docs.path", "/v3/api-docs");
        // Suppress Spring Boot's chatty Tomcat startup at INFO; the
        // controller path logs meaningful events itself.
        props.put("logging.level.org.springframework.boot.web.embedded.tomcat", "WARN");
        app.setDefaultProperties(props);

        return app.run(args);
    }

    /**
     * Composes the readiness response from the live signals.
     *
     * <p>Reason precedence is fixed and documented:
     * <ol>
     *   <li>If Kafka is unreachable → DOWN with the probe's reason
     *       (most severe — no metrics flow, no test can complete cleanly).</li>
     *   <li>Else if disk free is below the configured threshold (and the
     *       threshold is > 0) → DOWN with {@code disk_pressure} (less
     *       severe — an in-flight test may still finish; new runs should
     *       not start).</li>
     *   <li>Else → UP.</li>
     * </ol>
     *
     * <p>Public so {@link OrchestratorBeans#readinessProbe} can call it from
     * its supplier lambda; package-private visibility would require the
     * config package to import this class which is awkward and serves no
     * purpose.
     */
    public static ReadinessProbe.Snapshot composeReadinessSnapshot(
            KafkaReachabilityProbe.Snapshot kafkaSnap,
            long diskFreeBytes,
            long minFreeDiskBytes,
            String testState) {
        if (!kafkaSnap.reachable()) {
            return ReadinessProbe.Snapshot.down(kafkaSnap.reason(), diskFreeBytes, testState);
        }
        if (minFreeDiskBytes > 0 && diskFreeBytes < minFreeDiskBytes) {
            // Kafka is fine — keep kafkaReachable=true in the JSON so
            // operators see the actual Kafka state, not a false negative.
            return ReadinessProbe.Snapshot.downKafkaUp("disk_pressure", diskFreeBytes, testState);
        }
        return ReadinessProbe.Snapshot.up(diskFreeBytes, testState);
    }

    /**
     * Returns the usable bytes on the file system containing {@code probeAt}.
     * Best-effort — returns 0 rather than throwing if the path doesn't exist
     * yet (BASE_DIR may be created on first POST /test).
     *
     * <p>Public so {@link OrchestratorBeans#readinessProbe} and
     * {@link OrchestratorBeans#countersSupplier} can call it.
     */
    public static long diskFreeBytes(Path probeAt) {
        try {
            FileStore store = Files.getFileStore(probeAt.toAbsolutePath().getRoot());
            return store.getUsableSpace();
        } catch (Exception e) {
            return 0L;
        }
    }
}
