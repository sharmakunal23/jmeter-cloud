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
 *   globalOrchestrator.podProvisioner.metricsIngestUrl     = http://metrics-consumer:8083/api/v1/ingest
 *   globalOrchestrator.podProvisioner.documentServiceUrl   = http://document-service:8084
 *   globalOrchestrator.podProvisioner.workerMemoryMb       = 6144
 * </pre>
 *
 * <p><b>{@code workerMemoryMb} is the hard memory limit on every spawned
 * worker, and it must fit two JVMs.</b> A worker runs the orchestrator
 * ({@code -Xmx1g}) plus its JMeter child ({@code -Xmx2g} by default), and then
 * needs native overhead and page cache for the multi-GB JTL it tails. The
 * default 6 GiB is sized for a 12-hour run at 200-250 rps per worker: 3 GiB of
 * heap, ~1 GiB native, ~2 GiB page-cache headroom so the long JTL write-and-tail
 * stays smooth. Without an explicit limit the host OOM-killer reaps a worker —
 * or a noisy neighbour — non-deterministically under load. Memory-swap is pinned
 * equal to the limit so a worker dies on a clean in-JVM
 * {@code ExitOnOutOfMemoryError} instead of thrashing swap.
 * <p>These mirror the env vars hardcoded into the orchestrator-1 / -2
 * compose service definitions today. When Phase 6 deletes those static
 * services, these defaults become the single source of truth for what
 * a per-app local-orchestrator container looks like.
 */
public record ProvisionerProperties(
        // Docker substrate only (compose stack) — ignored when substrate=k8s.
        String dockerHost,
        String network,
        // K8s substrate only (KUBE-5 Option A) — Pod namespace + the headless
        // Service that gives workers their {podName}.{service} DNS names.
        // Both are K8s resource names → DNS-1123 lowercase (camelCase
        // exemption). Ignored when substrate=docker.
        String namespace,
        String headlessService,
        String image,
        int    localOrchestratorPort,
        String globalOrchestratorUrl,
        // DIRECT-METRICS (2026-07-20): the metrics-consumer ingest endpoint
        // stamped as METRICS_INGEST_URL on every spawned worker.
        String metricsIngestUrl,
        String documentServiceUrl,
        long   workerMemoryMb,
        // K8s substrate only — CPU REQUEST per worker Pod (e.g. "500m").
        // Request-only, deliberately NO cpu limit: cfs throttling on a load
        // generator skews the latencies it measures. The request just keeps
        // the scheduler from stacking more workers on a node than it can
        // run. (The docker substrate sets no CPU constraint at all.)
        String workerCpuRequest,
        // Aggregator late-arrival grace (seconds) stamped
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
