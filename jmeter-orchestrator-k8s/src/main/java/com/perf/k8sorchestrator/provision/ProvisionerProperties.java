package com.perf.k8sorchestrator.provision;

/**
 * Strongly-typed bundle of provisioner configuration. All values come from
 * env vars (Spring relaxed binding, prefix {@code globalOrchestrator.podProvisioner})
 * with sensible defaults for a local kind / Docker Desktop cluster:
 *
 * <pre>
 *   globalOrchestrator.podProvisioner.namespace            = jmeter-cloud
 *   globalOrchestrator.podProvisioner.headlessService      = workers
 *   globalOrchestrator.podProvisioner.image                = jmeter-local-orchestrator:dev
 *   globalOrchestrator.podProvisioner.localOrchestratorPort = 8080
 *   k8sOrchestrator.podProvisioner.globalOrchestratorUrl = http://jmeter-orchestrator-k8s:8088
 *   globalOrchestrator.podProvisioner.metricsIngestUrl    = http://metrics-consumer:8083/api/v1/ingest
 *   globalOrchestrator.podProvisioner.documentServiceUrl   = http://document-service:8084
 *   globalOrchestrator.podProvisioner.workerMemoryMb              = 6144
 *   globalOrchestrator.podProvisioner.gracePeriodSeconds          = 10
 * </pre>
 *
 * <p><b>Worker addressing (K8S-ORCHESTRATOR D-6):</b> every worker Pod is
 * created with {@code hostname = podName} and {@code subdomain =
 * headlessService}, and a headless Service of that name (manifests under
 * {@code k8s/}) selects the managed pods. That gives each worker the stable
 * in-namespace DNS name {@code {podName}.{headlessService}} — mirroring the
 * Docker-network {@code http://{podName}:8080} shape the registry and
 * fan-out already assume.
 *
 * <p><b>{@code workerMemoryMb} becomes the Pod's
 * {@code resources.limits.memory} and its request</b>, so the kubelet cannot
 * overcommit a worker mid-run. It must fit two JVMs — the orchestrator
 * ({@code -Xmx1g}) and its JMeter child ({@code -Xmx2g}) — plus native overhead
 * and page cache for the multi-GB JTL; the 6 GiB default is sized for 12-hour
 * runs at 200-250 rps. With a hard limit the worker OOMs cleanly inside its own
 * cgroup and is reaped by PodSweeper, rather than triggering the node
 * OOM-killer.
 *
 * <p><b>Workers get no tracing-related env at all</b> — the worker image ships
 * no OTel exporter, so there is nothing to configure or silence.
 */
public record ProvisionerProperties(
        String namespace,
        String headlessService,
        String image,
        int    localOrchestratorPort,
        String globalOrchestratorUrl,
        // DIRECT-METRICS (2026-07-20, supersedes K8S-ORCHESTRATOR D-1):
        // the metrics-consumer ingest endpoint stamped as METRICS_INGEST_URL
        // on every worker Pod.
        String metricsIngestUrl,
        String documentServiceUrl,
        long   workerMemoryMb,
        // K8s CPU REQUEST for each worker (e.g. "500m", "1"). Request-only,
        // deliberately NO cpu limit: cfs throttling on a load generator
        // skews the latencies it measures. The request just keeps the
        // scheduler from stacking more workers on a node than it can run.
        String workerCpuRequest,
        // Aggregator late-arrival grace (seconds) stamped
        // as GRACE_PERIOD_SECONDS on every spawned worker. JMeter timestamps a
        // sample at its start but writes it at completion, so a slow sample
        // lands in the JTL out of order; a larger grace keeps its 1-second
        // window open long enough to record it instead of dropping it as late
        // (which biases p95/p99 low). Default 10s; per-run override via the
        // POST /test body's gracePeriodSeconds still wins.
        int    gracePeriodSeconds) {

    /**
     * Label namespace for every Pod the provisioner manages. K8s label keys
     * use the {@code <dns-prefix>/<name>} convention; the name segment keeps
     * the repo's camelCase.
     */
    public static final String LABEL_PREFIX        = "jmetercloud.io/";
    public static final String LABEL_APPLICATION_ID   = LABEL_PREFIX + "applicationId";
    public static final String LABEL_APPLICATION_NAME = LABEL_PREFIX + "applicationName";
    public static final String LABEL_REGION           = LABEL_PREFIX + "region";
    public static final String LABEL_ROLE             = LABEL_PREFIX + "role";
    public static final String LABEL_MANAGED_BY       = LABEL_PREFIX + "managedBy";

    public static final String ROLE_LOCAL_ORCHESTRATOR = "local-orchestrator";
    public static final String MANAGED_BY              = "k8s-orchestrator";
}
