package com.perf.globalorchestrator.provision;

/**
 * Strongly-typed bundle of provisioner configuration. All values come from
 * env vars (Spring relaxed binding, prefix {@code globalOrchestrator.podProvisioner})
 * with sensible defaults for the local docker-compose stack:
 *
 * <pre>
 *   globalOrchestrator.podProvisioner.dockerHost           = unix:///var/run/docker.sock
 *   globalOrchestrator.podProvisioner.network              = jmeter-cloud_default
 *   globalOrchestrator.podProvisioner.image                = jmeter-local-orchestrator:dev
 *   globalOrchestrator.podProvisioner.localOrchestratorPort = 8080
 *   globalOrchestrator.podProvisioner.globalOrchestratorUrl = http://global-orchestrator:8082
 *   globalOrchestrator.podProvisioner.kafkaBrokers         = kafka:29092
 *   globalOrchestrator.podProvisioner.schemaRegistryUrl    = http://schema-registry:8081
 *   globalOrchestrator.podProvisioner.documentServiceUrl   = http://document-service:8084
 *   globalOrchestrator.podProvisioner.kafkaTopic                  = jmeter.metrics.perSecond
 *   globalOrchestrator.podProvisioner.tracingSamplingProbability  = 0.01
 *   globalOrchestrator.podProvisioner.otlpTracingEndpoint         = http://jaeger:4318/v1/traces
 *   globalOrchestrator.podProvisioner.workerMemoryMb              = 4096
 * </pre>
 *
 * <p><b>Worker memory limit (reliability, 2026-05-27):</b>
 * {@code workerMemoryMb} is the hard Docker memory limit applied
 * to every spawned worker container. Each worker runs <em>two</em> JVMs — the
 * orchestrator ({@code -Xmx1g}) and the JMeter child ({@code -Xmx1g} by
 * default) — plus native overhead and page cache for the multi-GB JTL the
 * orchestrator tails. Sized for the real workload (a 12 h run at 200-250 rps
 * per worker): 4 GiB fits both 1 GiB heaps + ~1 GiB native + ~1 GiB
 * page-cache headroom so the long JTL write/tail stays smooth. Without an
 * explicit limit the host's OOM-killer can reap a worker (or a noisy
 * neighbour like Kafka/Postgres) non-deterministically under load.
 * Memory-swap is pinned equal to the limit so a worker hits a clean in-JVM
 * {@code ExitOnOutOfMemoryError} rather than thrashing swap.
 *
 * <p>These mirror the env vars hardcoded into the orchestrator-1 / -2
 * compose service definitions today. When Phase 6 deletes those static
 * services, these defaults become the single source of truth for what
 * a per-app local-orchestrator container looks like.
 *
 * <p>The two tracing fields (added in OBSERVABILITY Phase B) are
 * propagated to every spawned local-orch container so its OTLP exporter
 * reaches the same Jaeger as the global-orch. Sampling defaults to
 * 1% to match production; the local docker-compose env overrides to
 * 100% so single-pod dev shows every span.
 */
public record ProvisionerProperties(
        String dockerHost,
        String network,
        String image,
        int    localOrchestratorPort,
        String globalOrchestratorUrl,
        String kafkaBrokers,
        String schemaRegistryUrl,
        String documentServiceUrl,
        String kafkaTopic,
        String tracingSamplingProbability,
        String otlpTracingEndpoint,
        long   workerMemoryMb,
        // RELIABILITY Round 8 — aggregator late-arrival grace (seconds) stamped
        // as GRACE_PERIOD_SECONDS on every spawned worker. JMeter timestamps a
        // sample at its start but writes it at completion, so a slow sample
        // lands in the JTL out of order; a larger grace keeps its 1-second
        // window open long enough to record it instead of dropping it as late
        // (which biases p95/p99 low). Default 10s; per-run override via the
        // POST /test body's gracePeriodSeconds still wins.
        int    gracePeriodSeconds) {

    /** Label namespace for every container the provisioner manages. */
    public static final String LABEL_PREFIX        = "com.perf.jmeterCloud.";
    public static final String LABEL_APPLICATION_ID   = LABEL_PREFIX + "applicationId";
    public static final String LABEL_APPLICATION_NAME = LABEL_PREFIX + "applicationName";
    public static final String LABEL_REGION           = LABEL_PREFIX + "region";
    public static final String LABEL_ROLE             = LABEL_PREFIX + "role";
    public static final String LABEL_MANAGED_BY       = LABEL_PREFIX + "managedBy";

    public static final String ROLE_LOCAL_ORCHESTRATOR = "local-orchestrator";
    public static final String MANAGED_BY              = "global-orchestrator";
}
