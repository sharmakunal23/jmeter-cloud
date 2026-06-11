package com.perf.orchestrator.config;

import com.perf.orchestrator.OrchestratorMain;
import com.perf.orchestrator.buffer.AsyncMetricsDispatcher;
import com.perf.orchestrator.buffer.DiskBackedMetricsBuffer;
import com.perf.orchestrator.buffer.DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig;
import com.perf.orchestrator.buffer.HttpFallbackClient;
import com.perf.orchestrator.buffer.JdkHttpFallbackClient;
import com.perf.orchestrator.buffer.MetricsBuffer;
import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.http.BuildInfo;
import com.perf.orchestrator.http.ReadinessProbe;
import com.perf.orchestrator.kafka.KafkaMetricPublisher;
import com.perf.orchestrator.kafka.MetricPublisher;
import com.perf.orchestrator.lifecycle.ArtifactSources;
import com.perf.orchestrator.lifecycle.ArtifactStager;
import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.JmeterProcessManager;
import com.perf.orchestrator.lifecycle.ResultSinks;
import com.perf.orchestrator.lifecycle.StreamingPipeline;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.logs.LogTail;
import com.perf.orchestrator.metrics.CountersSupplier;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.KafkaReachabilityProbe;
import com.perf.orchestrator.metrics.OrchestratorCounters;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.ResultSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spring configuration entry point for the orchestrator.
 *
 * <h2>Wiring model</h2>
 * Every singleton in the orchestrator is a {@code @Bean} declared on this
 * class — Spring constructs them in dependency order and injects them into
 * controllers, the {@code TestRunManager}, and the streaming pipeline. The
 * legacy "pre-publish bridge" (where {@code OrchestratorMain.main} hand-built
 * each singleton then registered them as pre-existing instances via
 * {@code GenericApplicationContext.registerBean}) was retired in the
 * {@code @Bean}-factory migration so {@code main()} can shrink to "boot
 * Spring, register a shutdown hook, park."
 *
 * <h2>Component scan</h2>
 * Scan opens to {@code com.perf.orchestrator} so {@code @Service}-annotated
 * classes ({@link ArtifactStager}, {@link com.perf.orchestrator.lifecycle.ResultUploader})
 * get auto-instantiated alongside the {@code @Bean}-declared singletons —
 * the previous narrow scan ({@code com.perf.orchestrator.http} only) was a
 * holdover from the migration period when those classes' instances were
 * pre-published from {@code OrchestratorMain}.
 *
 * <h2>Lifecycle and shutdown ordering</h2>
 * Every {@code @Bean} below uses {@code destroyMethod = ""} so context close
 * does NOT auto-invoke {@code Closeable.close()} on the bean. Shutdown
 * ordering is owned by the explicit hook in {@link OrchestratorMain#main}:
 * <ol>
 *   <li>{@code kafkaProbe.close()} — flips /actuator/health DOWN; K8s stops routing.</li>
 *   <li>{@code runManager.shutdownGracefully(grace)} — drains in-flight test.</li>
 *   <li>{@code metricPublisher.close()} — disposes the singleton Kafka producer.</li>
 *   <li>{@code jmx.close()} — releases the JMX connector.</li>
 *   <li>{@code springCtx.close()} — stops Tomcat (no-ops the bean closes).</li>
 * </ol>
 * Spring's default destroy ordering would not produce this sequence; the
 * explicit hook is load-bearing.
 *
 * <h2>{@code @SpringBootApplication}</h2>
 * Used (instead of plain {@code @Configuration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan}) so the scan picks up Spring Boot's
 * {@code TypeExcludeFilter} and {@code AutoConfigurationExcludeFilter}
 * — without them, {@code @TestConfiguration} fixtures in slice tests get
 * auto-detected and trip {@code BeanDefinitionOverrideException}.
 */
@SpringBootApplication(scanBasePackages = "com.perf.orchestrator")
@org.springframework.scheduling.annotation.EnableScheduling
public class OrchestratorBeans {

    // -----------------------------------------------------------------------
    // Configuration + identity
    // -----------------------------------------------------------------------

    /**
     * Reads {@link OrchestratorConfig} from environment variables exactly once
     * at boot. The orchestrator's config contract is env-var-driven (NOT
     * {@code application.yml} / {@code @ConfigurationProperties}) — this is a
     * deliberate, load-bearing design choice.
     *
     * <p>Tests override this bean by registering a {@code @Bean(name = "orchestratorConfig")}
     * via a {@code @TestConfiguration} and turning on
     * {@code spring.main.allow-bean-definition-overriding=true}; the
     * production factory then doesn't run, so {@code System.getenv()}
     * (unwriteable in-process) never gets read in tests.
     */
    @Bean
    public OrchestratorConfig orchestratorConfig() {
        return OrchestratorConfig.fromEnvironment();
    }

    @Bean
    public BuildInfo buildInfo() {
        return BuildInfo.detect(Instant.now());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Process-level counter for JTL byte-offset persist failures. Threaded
     * into each per-run {@link StreamingPipeline} so a flaky disk shows up as
     * a monotonically-increasing Prometheus counter rather than scattered
     * WARN log lines. Bean name distinguishes it in case more {@link LongAdder}
     * counters are added later.
     */
    @Bean
    public LongAdder offsetSaveFailures() {
        return new LongAdder();
    }

    // -----------------------------------------------------------------------
    // Run state + artifact staging
    // -----------------------------------------------------------------------

    @Bean(destroyMethod = "")
    public CurrentRun currentRun(OrchestratorConfig config, Clock clock) {
        return CurrentRun.load(Path.of(config.getRunStateFile()), clock);
    }

    // ArtifactStager is @Service-annotated and picked up by component scan.

    // -----------------------------------------------------------------------
    // Logs + JMX
    // -----------------------------------------------------------------------

    @Bean(destroyMethod = "")
    public LogTail logTail(OrchestratorConfig config) {
        return new LogTail(
                config.getLogBufferLines(),
                Path.of(config.getLogsDir()).resolve("jmeter.log"));
    }

    @Bean(destroyMethod = "")
    public JmxMetricsCollector jmxMetricsCollector(OrchestratorConfig config) {
        return new JmxMetricsCollector(config.getJmxPort());
    }

    /**
     * Publishes the JMeter child's JMX snapshot as {@code jmeter_jvm_*}
     * Prometheus gauges on the orchestrator's actuator endpoint, so the
     * "Worker Pod JVM" Grafana dashboard can chart the orchestrator and the
     * JMeter child JVMs side by side. Spring Boot auto-binds {@link MeterBinder}
     * beans to the primary {@link MeterRegistry}.
     */
    @Bean
    public com.perf.orchestrator.metrics.JmeterJvmMetrics jmeterJvmMetrics(
            JmxMetricsCollector jmxMetricsCollector) {
        return new com.perf.orchestrator.metrics.JmeterJvmMetrics(jmxMetricsCollector);
    }

    // -----------------------------------------------------------------------
    // Storage backends — gated by env-driven factories so the same fat JAR
    // boots in HTTP_UPLOAD / S3 / DocumentService deployments without code
    // changes (the optional source roots gate the AWS SDK / etc.).
    // -----------------------------------------------------------------------

    /** Eager-validate the configured artifact source — a misbuilt JAR
     *  (e.g. {@code ARTIFACT_SOURCE=S3} without {@code -Pstorage-s3}) fails
     *  at boot rather than on the first {@code POST /test}. */
    @Bean(destroyMethod = "")
    public ArtifactSource artifactSource(OrchestratorConfig config) {
        return ArtifactSources.forConfig(config);
    }

    @Bean(destroyMethod = "")
    public ResultSink resultSink(OrchestratorConfig config) {
        return ResultSinks.forConfig(config);
    }

    // -----------------------------------------------------------------------
    // Kafka — reachability probe + metric publisher
    // -----------------------------------------------------------------------

    /**
     * Daemon-thread cached probe via {@code AdminClient}. Started immediately
     * so /actuator/health reflects the real broker state by the time Tomcat
     * starts accepting traffic; until the first poll returns the snapshot is
     * DOWN/{@code startup_in_progress} so /ready never reports a false UP.
     */
    @Bean(destroyMethod = "")
    public KafkaReachabilityProbe kafkaReachabilityProbe(OrchestratorConfig config) {
        KafkaReachabilityProbe probe = KafkaReachabilityProbe.create(config);
        probe.start();
        return probe;
    }

    /**
     * Per-process Kafka producer singleton. Shared across every test run —
     * the producer config (brokers, schema-registry URL, client.id from pod
     * name) doesn't change between runs, so warming one producer once saves
     * ~100-200 ms per {@code POST /test}, accumulates broker-side metrics
     * across the orchestrator's lifetime, and lets KafkaTemplate's
     * Micrometer binder expose {@code /actuator/metrics/kafka.producer.*}
     * once.
     *
     * <p>Injecting {@link MeterRegistry} here lets us call
     * {@link KafkaMetricPublisher#enableMicrometer(MeterRegistry)} during
     * construction — Spring Boot's auto-config has registered the registry
     * by the time this {@code @Bean} method runs.
     *
     * <p>{@code destroyMethod = ""} so the orchestrator shutdown hook (not
     * Spring) decides when to close the producer; see the class javadoc for
     * shutdown ordering.
     */
    @Bean(destroyMethod = "")
    public KafkaMetricPublisher metricPublisher(OrchestratorConfig config, MeterRegistry meterRegistry) {
        KafkaMetricPublisher publisher = KafkaMetricPublisher.create(config);
        publisher.enableMicrometer(meterRegistry);
        return publisher;
    }

    // -----------------------------------------------------------------------
    // Metrics buffer + dispatcher (K-3)
    // -----------------------------------------------------------------------

    /**
     * Disk-backed write-ahead queue for {@code WorkerMetricBatch} envelopes.
     * Persists each envelope to {@code BASE_DIR/metricsBuffer/<id>.envelope.gz}
     * before publish, so envelopes survive Kafka outages and process crashes.
     * JMeter-considerate sizing: caps total bytes, reserves free disk for
     * JTL writes, drops oldest first when over cap.
     */
    @Bean(destroyMethod = "")
    public MetricsBuffer metricsBuffer(OrchestratorConfig config,
                                       MeterRegistry meterRegistry,
                                       Clock clock) {
        DiskBackedMetricsBufferConfig cfg = new DiskBackedMetricsBufferConfig(
                config.getMetricsBufferMaxBytes(),
                config.getMetricsBufferMaxFileBytes(),
                config.getMetricsBufferMinFreeDiskBytes(),
                Duration.ofHours(config.getMetricsBufferMaxAgeHours()));
        return new DiskBackedMetricsBuffer(
                Paths.get(config.getMetricsBufferPath()), cfg, meterRegistry, clock);
    }

    /**
     * Single-thread coordinator between the aggregator (producer) and
     * publisher + buffer. {@code dispatcher.offer(envelope)} is a
     * sub-microsecond CAS — the aggregator's poll thread never blocks on
     * disk I/O. The dispatch thread persists to the buffer, publishes to
     * Kafka, and deletes from the buffer on success. Periodic retry sweeper
     * republishes envelopes left on disk by prior publish failures (e.g.
     * Kafka outage).
     *
     * <p>{@code destroyMethod = ""} so the orchestrator's shutdown hook (not
     * Spring) decides when to stop the dispatch thread.
     */
    /**
     * K-5 — HTTP fallback to metrics-consumer's {@code /api/v1/ingest} when a
     * Kafka send fails. Returns {@code null} when {@code metricsHttpFallbackEnabled=false}
     * — in that case the dispatcher leaves failed envelopes on disk for the
     * K-3 retry sweeper, but never attempts HTTP. Useful for environments
     * without a metrics-consumer reachable from the local-orchestrator.
     */
    @Bean(destroyMethod = "")
    public HttpFallbackClient httpFallbackClient(OrchestratorConfig config) {
        if (!config.isMetricsHttpFallbackEnabled()) {
            return null;
        }
        return new JdkHttpFallbackClient(
                config.getMetricsHttpFallbackUrl(),
                java.time.Duration.ofMillis(config.getMetricsHttpFallbackConnectTimeoutMs()),
                java.time.Duration.ofMillis(config.getMetricsHttpFallbackRequestTimeoutMs()));
    }

    @Bean(destroyMethod = "")
    public MetricsDispatcher metricsDispatcher(MetricsBuffer metricsBuffer,
                                               KafkaMetricPublisher metricPublisher,
                                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                                               HttpFallbackClient httpFallbackClient,
                                               MeterRegistry meterRegistry) {
        return new AsyncMetricsDispatcher(metricsBuffer, metricPublisher, httpFallbackClient, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // Run management
    // -----------------------------------------------------------------------

    /**
     * Owns the orchestrator's single in-flight test run.
     *
     * <p>The {@code StreamingPipeline} factory lambda captures the singleton
     * {@code metricPublisher} and {@code offsetSaveFailures} counter — those
     * stay constant across runs while the per-run {@link OrchestratorConfig}
     * changes (different {@code runId}, JTL paths, etc.).
     */
    @Bean(destroyMethod = "")
    public TestRunManager testRunManager(OrchestratorConfig config,
                                         ArtifactStager stager,
                                         CurrentRun currentRun,
                                         LogTail logTail,
                                         MetricPublisher metricPublisher,
                                         MetricsDispatcher metricsDispatcher,
                                         LongAdder offsetSaveFailures,
                                         ResultSink resultSink,
                                         ArtifactSource artifactSource,
                                         Clock clock) {
        return new TestRunManager(
                config, stager, currentRun,
                new JmeterProcessManager(logTail),
                cfg -> new StreamingPipeline(cfg, metricPublisher, metricsDispatcher, offsetSaveFailures),
                resultSink,
                artifactSource,
                clock,
                logTail);
    }

    // -----------------------------------------------------------------------
    // Readiness + counters — process-level supplier lambdas
    // -----------------------------------------------------------------------

    /**
     * Composes the readiness response from the live signals: Kafka reachability
     * (most severe — DOWN if unreachable), then disk pressure, then UP. The
     * precedence rules are factored into {@link OrchestratorMain#composeReadinessSnapshot}
     * so they can be unit-tested without booting Tomcat.
     */
    @Bean
    public ReadinessProbe readinessProbe(OrchestratorConfig config,
                                         CurrentRun currentRun,
                                         KafkaReachabilityProbe kafkaProbe) {
        long minFreeDiskBytes = config.getMinFreeDiskMb() * 1024L * 1024L;
        Path baseDir = Path.of(config.getBaseDir());
        return () -> OrchestratorMain.composeReadinessSnapshot(
                kafkaProbe.snapshot(),
                OrchestratorMain.diskFreeBytes(baseDir),
                minFreeDiskBytes,
                currentRun.state().name());
    }

    /**
     * Snapshots the orchestrator's process-level counters: rows ingested,
     * windows published, Kafka send errors, last Kafka ack timestamp, upload
     * in-flight bytes, free disk, offset-save failures. Consumed by
     * {@code ObservabilityController} ({@code GET /api/v1/metrics/orchestrator})
     * and the Prometheus actuator binding.
     */
    @Bean
    public CountersSupplier countersSupplier(OrchestratorConfig config,
                                             CurrentRun currentRun,
                                             ArtifactStager stager,
                                             LongAdder offsetSaveFailures) {
        Path baseDir = Path.of(config.getBaseDir());
        return () -> {
            CurrentRun.Snapshot s = currentRun.snapshot();
            return new OrchestratorCounters(
                    s.rowsIngested(),
                    s.windowsPublished(),
                    s.kafkaSendErrors(),
                    s.lastKafkaAckMs() == null ? 0L : s.lastKafkaAckMs(),
                    stager.getUploadInflightBytes(),
                    OrchestratorMain.diskFreeBytes(baseDir),
                    offsetSaveFailures.sum());
        };
    }
}
