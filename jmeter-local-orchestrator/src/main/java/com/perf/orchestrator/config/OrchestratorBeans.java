package com.perf.orchestrator.config;

import com.perf.orchestrator.OrchestratorMain;
import com.perf.orchestrator.buffer.AsyncMetricsDispatcher;
import com.perf.orchestrator.buffer.DiskBackedMetricsBuffer;
import com.perf.orchestrator.buffer.DiskBackedMetricsBuffer.DiskBackedMetricsBufferConfig;
import com.perf.orchestrator.buffer.HttpIngestClient;
import com.perf.orchestrator.buffer.JdkHttpIngestClient;
import com.perf.orchestrator.buffer.MetricsBuffer;
import com.perf.orchestrator.buffer.MetricsDispatcher;
import com.perf.orchestrator.http.BuildInfo;
import com.perf.orchestrator.http.ReadinessProbe;
import com.perf.orchestrator.lifecycle.ArtifactSources;
import com.perf.orchestrator.lifecycle.ArtifactStager;
import com.perf.orchestrator.lifecycle.CurrentRun;
import com.perf.orchestrator.lifecycle.JmeterProcessManager;
import com.perf.orchestrator.lifecycle.ResultSinks;
import com.perf.orchestrator.lifecycle.StreamingPipeline;
import com.perf.orchestrator.lifecycle.TestRunManager;
import com.perf.orchestrator.logs.LogTail;
import com.perf.orchestrator.metrics.CountersSupplier;
import com.perf.orchestrator.metrics.IngestReachabilityProbe;
import com.perf.orchestrator.metrics.JmxMetricsCollector;
import com.perf.orchestrator.metrics.OrchestratorCounters;
import com.perf.orchestrator.storage.ArtifactSource;
import com.perf.orchestrator.storage.ResultSink;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spring configuration for the orchestrator: every singleton is a {@code @Bean}
 * here, and the component scan covers {@code com.perf.orchestrator} so
 * {@code @Service} classes are picked up alongside them.
 *
 * <p><b>Shutdown ordering is owned by the explicit hook in
 * {@link OrchestratorMain#main}, not by Spring.</b> That is why every bean sets
 * {@code destroyMethod = ""} — context close must not call {@code close()}
 * itself, because Spring's default ordering would not produce this sequence:
 * <ol>
 *   <li>{@code ingestProbe.close()} — flips health DOWN so K8s stops routing.</li>
 *   <li>{@code runManager.shutdownGracefully(grace)} — drains the in-flight test.</li>
 *   <li>{@code metricsDispatcher.close()} — stops dispatch; unsent envelopes stay
 *       in the on-disk buffer for the next process.</li>
 *   <li>{@code jmx.close()} — releases the JMX connector.</li>
 *   <li>{@code springCtx.close()} — stops Tomcat.</li>
 * </ol>
 *
 * <p>{@code @SpringBootApplication} rather than plain {@code @Configuration}:
 * the scan then honours Boot's {@code TypeExcludeFilter}, without which
 * {@code @TestConfiguration} fixtures are auto-detected in slice tests and trip
 * {@code BeanDefinitionOverrideException}.
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
     * a monotonically-increasing counter on {@code GET /api/v1/metrics/orchestrator}
     * rather than scattered WARN log lines. Bean name distinguishes it in case
     * more {@link LongAdder} counters are added later.
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

    // SLIMDOWN (2026-07-21): the JmeterJvmMetrics MeterBinder (jmeter_jvm_*
    // Prometheus gauges) left with the Micrometer stack. The JMX collector
    // itself stays — GET /api/v1/metrics/jmeterJvm serves the same snapshot.

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
    // Metrics-consumer reachability probe
    // -----------------------------------------------------------------------

    /**
     * Daemon-thread cached probe via HTTP {@code OPTIONS} against the ingest
     * URL. Started immediately so /actuator/health reflects the real consumer
     * state by the time Tomcat starts accepting traffic; until the first poll
     * returns the snapshot is DOWN/{@code startup_in_progress} so /ready
     * never reports a false UP.
     */
    @Bean(destroyMethod = "")
    public IngestReachabilityProbe ingestReachabilityProbe(OrchestratorConfig config) {
        IngestReachabilityProbe probe = IngestReachabilityProbe.create(config);
        probe.start();
        return probe;
    }

    // -----------------------------------------------------------------------
    // Metrics buffer + dispatcher (K-3)
    // -----------------------------------------------------------------------

    /**
     * Disk-backed write-ahead queue for {@code WorkerMetricBatch} envelopes.
     * Persists each envelope to {@code BASE_DIR/metricsBuffer/<id>.envelope.gz}
     * before publish, so envelopes survive metrics-consumer outages and
     * process crashes. JMeter-considerate sizing: caps total bytes, reserves
     * free disk for JTL writes, drops oldest first when over cap.
     */
    @Bean(destroyMethod = "")
    public MetricsBuffer metricsBuffer(OrchestratorConfig config,
                                       Clock clock) {
        DiskBackedMetricsBufferConfig cfg = new DiskBackedMetricsBufferConfig(
                config.getMetricsBufferMaxBytes(),
                config.getMetricsBufferMaxFileBytes(),
                config.getMetricsBufferMinFreeDiskBytes(),
                Duration.ofHours(config.getMetricsBufferMaxAgeHours()));
        return new DiskBackedMetricsBuffer(
                Paths.get(config.getMetricsBufferPath()), cfg, clock);
    }

    /**
     * The metrics sink — POSTs JSON envelopes to the metrics-consumer's
     * {@code /api/v1/ingest} — the only publish path.
     */
    @Bean(destroyMethod = "")
    public HttpIngestClient httpIngestClient(OrchestratorConfig config) {
        return new JdkHttpIngestClient(
                config.getMetricsIngestUrl(),
                java.time.Duration.ofMillis(config.getMetricsIngestConnectTimeoutMs()),
                java.time.Duration.ofMillis(config.getMetricsIngestRequestTimeoutMs()));
    }

    /**
     * Single-thread coordinator between the aggregator (producer) and
     * ingest client + buffer. {@code dispatcher.offer(envelope)} is a
     * sub-microsecond CAS — the aggregator's poll thread never blocks on
     * disk I/O. The dispatch thread persists to the buffer, POSTs to the
     * consumer, and deletes from the buffer on success. Periodic retry
     * sweeper republishes envelopes left on disk by prior publish failures
     * (e.g. consumer outage).
     *
     * <p>{@code destroyMethod = ""} so the orchestrator's shutdown hook (not
     * Spring) decides when to stop the dispatch thread.
     */
    @Bean(destroyMethod = "")
    public MetricsDispatcher metricsDispatcher(MetricsBuffer metricsBuffer,
                                               HttpIngestClient httpIngestClient) {
        return new AsyncMetricsDispatcher(metricsBuffer, httpIngestClient);
    }

    // -----------------------------------------------------------------------
    // Run management
    // -----------------------------------------------------------------------

    /**
     * Owns the orchestrator's single in-flight test run.
     *
     * <p>The {@code StreamingPipeline} factory lambda captures the singleton
     * {@code metricsDispatcher} and {@code offsetSaveFailures} counter — those
     * stay constant across runs while the per-run {@link OrchestratorConfig}
     * changes (different {@code runId}, JTL paths, etc.).
     */
    @Bean(destroyMethod = "")
    public TestRunManager testRunManager(OrchestratorConfig config,
                                         ArtifactStager stager,
                                         CurrentRun currentRun,
                                         LogTail logTail,
                                         MetricsDispatcher metricsDispatcher,
                                         LongAdder offsetSaveFailures,
                                         ResultSink resultSink,
                                         ArtifactSource artifactSource,
                                         Clock clock,
                                         com.perf.orchestrator.hygiene.OrphanJmeterReaper orphanReaper,
                                         com.perf.orchestrator.hygiene.RunArtifactRetention retention) {
        return new TestRunManager(
                config, stager, currentRun,
                new JmeterProcessManager(logTail),
                cfg -> new StreamingPipeline(cfg, metricsDispatcher, offsetSaveFailures),
                resultSink,
                artifactSource,
                clock,
                logTail,
                orphanReaper,
                retention);
    }

    // -----------------------------------------------------------------------
    // Readiness + counters — process-level supplier lambdas
    // -----------------------------------------------------------------------

    /**
     * Composes the readiness response from the live signals: metrics-consumer
     * reachability (most severe — DOWN if unreachable), then disk pressure,
     * then UP. The precedence rules are factored into
     * {@link OrchestratorMain#composeReadinessSnapshot} so they can be
     * unit-tested without booting Tomcat.
     */
    @Bean
    public ReadinessProbe readinessProbe(OrchestratorConfig config,
                                         CurrentRun currentRun,
                                         IngestReachabilityProbe ingestProbe,
                                         com.perf.orchestrator.hygiene.OrphanJmeterReaper orphanReaper) {
        long minFreeDiskBytes = config.getMinFreeDiskMb() * 1024L * 1024L;
        Path baseDir = Path.of(config.getBaseDir());
        return () -> OrchestratorMain.composeReadinessSnapshot(
                ingestProbe.snapshot(),
                OrchestratorMain.diskFreeBytes(baseDir),
                minFreeDiskBytes,
                currentRun.state().name(),
                orphanReaper.unresolvedOrphan());
    }

    /**
     * Finds a JMeter child that outlived its run.
     * Ownership is decided by the command line naming BOTH
     * {@code ApacheJMeter.jar} and this orchestrator's {@code BASE_DIR}, so
     * a co-tenant's JMeter on the same host can never be a candidate.
     */
    @Bean
    public com.perf.orchestrator.hygiene.OrphanJmeterReaper orphanJmeterReaper(
            OrchestratorConfig config, CurrentRun currentRun) {
        return new com.perf.orchestrator.hygiene.OrphanJmeterReaper(
                currentRun::isActive,
                config.getBaseDir(),
                com.perf.orchestrator.hygiene.OrphanJmeterReaper.Policy
                        .valueOf(config.getOrphanJmeterPolicy()));
    }

    /**
     * Bounds what preserved run artifacts cost on a
     * worker that is never recycled.
     */
    @Bean
    public com.perf.orchestrator.hygiene.RunArtifactRetention runArtifactRetention(
            OrchestratorConfig config) {
        return new com.perf.orchestrator.hygiene.RunArtifactRetention(
                Path.of(config.getResultsDir()),
                Path.of(config.getLogsDir()),
                config.getRunArtifactRetentionCount(),
                java.time.Duration.ofDays(config.getRunArtifactRetentionDays()));
    }

    /**
     * The idle hygiene tick.
     *
     * <p>A plain {@link java.util.concurrent.ScheduledExecutorService} rather
     * than {@code @Scheduled}: the cadence comes from
     * {@code OrchestratorConfig}, which is this service's single config
     * entrypoint (see its CLAUDE.md — {@code @Value} / {@code application.yml}
     * are deliberately not used for runtime knobs here), and
     * {@code @Scheduled} would have forced the interval back into a property
     * placeholder.
     *
     * <p>Both sweeps are no-ops while a test is in flight: the reaper gates
     * on {@code CurrentRun.isActive}, and retention is handed the live runId
     * so it can never delete artifacts being written.
     */
    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ScheduledExecutorService hygieneScheduler(
            OrchestratorConfig config,
            CurrentRun currentRun,
            com.perf.orchestrator.hygiene.OrphanJmeterReaper reaper,
            com.perf.orchestrator.hygiene.RunArtifactRetention retention) {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "workerHygiene");
                    t.setDaemon(true);
                    return t;
                });
        long periodSeconds = config.getOrphanJmeterScanIntervalSeconds();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                reaper.sweep();
                if (!currentRun.isActive()) {
                    retention.sweep(protectedRunIds(currentRun));
                }
            } catch (Exception e) {
                // Never let a sweep kill the scheduler — the next tick retries.
                HYGIENE_LOG.warn("Worker hygiene tick failed: {}", e.toString());
            }
        }, periodSeconds, periodSeconds, java.util.concurrent.TimeUnit.SECONDS);
        return scheduler;
    }

    private static final org.slf4j.Logger HYGIENE_LOG =
            org.slf4j.LoggerFactory.getLogger("workerHygiene");

    /** The in-flight run's directories are never retention candidates. */
    static java.util.Set<String> protectedRunIds(CurrentRun currentRun) {
        return currentRun.snapshotIfPresent()
                .map(s -> s.runId() == null ? java.util.Set.<String>of() : java.util.Set.of(s.runId()))
                .orElse(java.util.Set.of());
    }

    /**
     * Snapshots the orchestrator's process-level counters: rows ingested,
     * windows published, publish errors, last publish ack timestamp, upload
     * in-flight bytes, free disk, offset-save failures. Consumed by
     * {@code ObservabilityController} ({@code GET /api/v1/metrics/orchestrator}).
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
                    s.publishErrors(),
                    s.lastPublishAckMs() == null ? 0L : s.lastPublishAckMs(),
                    stager.getUploadInflightBytes(),
                    OrchestratorMain.diskFreeBytes(baseDir),
                    offsetSaveFailures.sum());
        };
    }
}
