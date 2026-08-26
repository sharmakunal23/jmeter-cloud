package com.perf.orchestrator;

import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.config.OrchestratorBeans;
import com.perf.orchestrator.config.OrchestratorConfig;
import com.perf.orchestrator.http.ReadinessProbe;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.IngestReachabilityProbe;
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
 * Process entry point: boots the Spring context from {@link OrchestratorBeans},
 * then registers the shutdown hook that owns lifecycle ordering.
 *
 * <p>{@code main()} stays small on purpose. Construction and wiring belong to
 * {@link OrchestratorBeans}; this class owns only the ordering Spring's destroy
 * phase cannot express — ingest probe DOWN before drain, drain before dispatcher
 * close, dispatcher close before context close.
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
        //   1. ingestProbe.close() — flips /actuator/health to DOWN
        //      immediately so the K8s Service stops routing new traffic to
        //      this pod within one probe interval. Cheap (≤2 s).
        //   2. runManager.shutdownGracefully(grace) — blocks while the
        //      in-flight test (if any) goes SIGTERM → JMeter exits → write
        //      sentinel → drain pipeline → publish final window → terminal
        //      state. Tomcat is still up so operators can poll
        //      GET /api/v1/test and watch the state transition. The state
        //      machine drains the dispatch queue at end-of-run, so by the
        //      time this returns every envelope from the last run has
        //      reached the disk buffer (and ideally the consumer).
        //   3. metricsDispatcher.close() — stops the dispatch thread. Must
        //      happen AFTER runManager.shutdownGracefully so we don't kill
        //      the dispatcher while the in-flight run is still publishing.
        //      Unsent envelopes stay in the on-disk buffer and are replayed
        //      by the next process (boot scrub + retry sweeper).
        //   4. jmx.close() — releases the JMX connector after the
        //      pipeline / observability path no longer needs it.
        //   5. springCtx.close() — last. Stops Tomcat (no new requests),
        //      then disposes the DispatcherServlet. Every @Bean has
        //      destroyMethod="" so closing the context does NOT double-close
        //      the resources we just released above.
        Duration shutdownGrace = Duration.ofSeconds(config.getOrchestratorShutdownGraceSeconds());
        IngestReachabilityProbe ingestProbe = springCtx.getBean(IngestReachabilityProbe.class);
        TestRunManager runManager = springCtx.getBean(TestRunManager.class);
        MetricsDispatcher metricsDispatcher = springCtx.getBean(MetricsDispatcher.class);
        JmxMetricsCollector jmx = springCtx.getBean(JmxMetricsCollector.class);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook firing — graceful drain budget: {}s", shutdownGrace.toSeconds());
            ingestProbe.close();
            runManager.shutdownGracefully(shutdownGrace);
            try {
                metricsDispatcher.close();
            } catch (Exception e) {
                LOG.warn("metricsDispatcher.close() failed during shutdown", e);
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
        // Actuator — SLIMDOWN (2026-07-21): health,info ONLY. Never drop
        // health — compose healthchecks, both provisioners' health-waits,
        // and K8s probes depend on it. /actuator/prometheus is 404 by
        // design (the hosting infra provides observability); the export
        // flag keeps the default in-memory registry from pretending
        // otherwise.
        props.put("management.endpoints.web.exposure.include", "health,info");
        props.put("management.endpoint.health.show-details", "always");
        props.put("management.defaults.metrics.export.enabled", false);
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
     *   <li>If the metrics-consumer is unreachable → DOWN with the probe's
     *       reason (most severe — no metrics flow beyond the disk buffer,
     *       new runs should not start).</li>
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
            IngestReachabilityProbe.Snapshot ingestSnap,
            long diskFreeBytes,
            long minFreeDiskBytes,
            String testState) {
        return composeReadinessSnapshot(ingestSnap, diskFreeBytes, minFreeDiskBytes, testState, null);
    }

    /**
     * STATIC-FLEET Phase 6 overload — adds the orphan-JMeter signal.
     *
     * <p>Precedence is unchanged at the top (ingest, then disk); the orphan
     * check sits last because it is the least severe of the three: the
     * worker is reachable and has room, it just has a leftover child that
     * would contend with the next run. It is reported only when
     * <em>unresolved</em> — the reaper's normal path kills the orphan and
     * clears the signal, so a healthy worker never flaps. A non-null value
     * means either the kill failed or the policy is REPORT, and in both
     * cases this worker should stop being handed runs.
     *
     * @param unresolvedOrphan null when clean; otherwise the reason
     */
    public static ReadinessProbe.Snapshot composeReadinessSnapshot(
            IngestReachabilityProbe.Snapshot ingestSnap,
            long diskFreeBytes,
            long minFreeDiskBytes,
            String testState,
            String unresolvedOrphan) {
        if (!ingestSnap.reachable()) {
            return ReadinessProbe.Snapshot.down(ingestSnap.reason(), diskFreeBytes, testState);
        }
        if (minFreeDiskBytes > 0 && diskFreeBytes < minFreeDiskBytes) {
            // The consumer is fine — keep ingestReachable=true in the JSON so
            // operators see the actual consumer state, not a false negative.
            return ReadinessProbe.Snapshot.downIngestUp("disk_pressure", diskFreeBytes, testState);
        }
        if (unresolvedOrphan != null) {
            return ReadinessProbe.Snapshot.downIngestUp(unresolvedOrphan, diskFreeBytes, testState);
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
