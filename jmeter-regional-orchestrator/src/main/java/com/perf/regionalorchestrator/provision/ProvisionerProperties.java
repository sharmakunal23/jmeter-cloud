package com.perf.regionalorchestrator.provision;

/**
 * Configuration bundle for {@link K8sPodProvisioner}, bound from the
 * {@code regionalOrchestrator.podProvisioner.*} keys — everything about a
 * worker that is specific to this cluster: image, namespace, sizes, and the
 * two hub data-plane URLs (ingest, artifacts) stamped onto workers as seen
 * from inside the cluster.
 *
 * <p><b>{@code workerMemoryMb} is the hard memory limit on every spawned worker
 * and must fit two JVMs</b> — the orchestrator ({@code -Xmx1g}) plus its JMeter
 * child ({@code -Xmx2g}), native overhead, and page cache for the multi-GB JTL
 * it tails; 6 GiB is sized for a 12-hour run at 200-250 rps. Requests equal
 * limits so a runaway worker OOMs inside its own cgroup.
 */
public record ProvisionerProperties(
        // Pod namespace + the headless Service that gives workers their
        // {podName}.{service} DNS names. Both are K8s resource names →
        // DNS-1123 lowercase (camelCase exemption).
        String namespace,
        String headlessService,
        String image,
        int    localOrchestratorPort,
        // Stamped as METRICS_INGEST_URL on every spawned worker.
        // Workers never call the hub's control plane: liveness comes from the
        // Pod list (WorkerState), so no GLOBAL_ORCHESTRATOR_URL is stamped.
        String metricsIngestUrl,
        String documentServiceUrl,
        long   workerMemoryMb,
        // CPU REQUEST per worker Pod (e.g. "500m"). Request-only, deliberately
        // NO cpu limit: cfs throttling on a load generator skews the latencies
        // it measures; the request only keeps the scheduler honest.
        String workerCpuRequest,
        // Stamped as GRACE_PERIOD_SECONDS. JMeter timestamps a sample at its
        // start but writes it at completion, so the aggregator keeps a
        // 1-second window open this long to record late rows instead of
        // dropping them (which biases p95/p99 low). The per-run POST /test
        // override wins.
        int    gracePeriodSeconds,
        // Stamped as JMETER_JVM_ARGS when non-blank — the JMeter child's heap.
        // The worker's default (-Xmx2g) is production sizing; a validation
        // cluster sets e.g. "-Xms256m -Xmx512m" alongside a smaller
        // workerMemoryMb. Blank keeps the worker's default.
        String jmeterJvmArgs) {

    /** Label namespace for every Pod the provisioner manages. */
    public static final String LABEL_PREFIX           = "com.perf.jmeterCloud.";
    public static final String LABEL_APPLICATION_ID   = LABEL_PREFIX + "applicationId";
    public static final String LABEL_APPLICATION_NAME = LABEL_PREFIX + "applicationName";
    public static final String LABEL_REGION           = LABEL_PREFIX + "region";
    public static final String LABEL_ROLE             = LABEL_PREFIX + "role";
    public static final String LABEL_MANAGED_BY       = LABEL_PREFIX + "managedBy";

    public static final String ROLE_LOCAL_ORCHESTRATOR = "local-orchestrator";
    /** Must match the headless {@code workers} Service selector in {@code kube/}. */
    public static final String MANAGED_BY              = "regional-orchestrator";
}
