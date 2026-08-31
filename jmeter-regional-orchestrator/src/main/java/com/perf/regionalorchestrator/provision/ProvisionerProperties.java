package com.perf.regionalorchestrator.provision;

/**
 * Configuration bundle for {@link K8sPodProvisioner}, bound from the
 * {@code regionalOrchestrator.podProvisioner.*} keys — everything about a
 * worker that is specific to this cluster: image, namespace, sizes, and the
 * two hub data-plane URLs (ingest, artifacts) stamped onto workers as seen
 * from inside the cluster.
 *
 * <p><b>{@code workerMemoryMb} is the hard memory limit on every spawned worker
 * and must fit two JVMs WHOLE</b> — not just their {@code -Xmx}: two heaps, two
 * metaspaces and code caches, JMeter's direct buffers and thread stacks, plus
 * page cache for the JTL it tails. Sizing memory without re-sizing
 * {@code workerJavaOpts} + {@code jmeterJvmArgs} together is how this platform
 * has OOMKilled workers before. The hosted overlays pair 5120 MiB with
 * {@code -Xmx1536m} for BOTH JVMs — each process then lands at ~2 GiB, the
 * "2 GB local-orch + 2 GB JMeter" split, inside the 9 GB worker footprint
 * (5 Gi memory + 4 Gi ephemeral, 20 workers per 180 GB cluster). A 2 g JMeter
 * heap needs 6144 MiB (10 GB/worker, 18 per cluster). Requests equal limits so
 * a runaway worker OOMs inside its own cgroup.
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
        String jmeterJvmArgs,
        // Stamped as METRICS_INGEST_AUTH when non-null — the whole
        // Authorization value (`Bearer <token>`), the hosted ingest.auth; from
        // a Secret in a real cloud. Null = the consumer runs without auth (local).
        String metricsIngestAuth,
        // Stamped as BEANSHELL_PORT. The worker's own default is 0 (the bsh
        // server is unauthenticated code-exec, so unmanaged environments are
        // off); provisioner-spun Pods opt in here — pod-internal only, the
        // NetworkPolicy never admits it. 0 disables per deployment.
        int    beanshellPort,
        // What the hosting platform dictates about a worker Pod's spec
        // (PRIVATE-CLOUD-ALIGNMENT Track 8); WorkerPodShape.DEFAULTS locally.
        WorkerPodShape shape) {

    /** The pre-beanshellPort shape signature (tests) — bsh on its 4446 default. */
    public ProvisionerProperties(String namespace, String headlessService, String image, int localOrchestratorPort,
                                 String metricsIngestUrl, String documentServiceUrl, long workerMemoryMb,
                                 String workerCpuRequest, int gracePeriodSeconds, String jmeterJvmArgs,
                                 String metricsIngestAuth, WorkerPodShape shape) {
        this(namespace, headlessService, image, localOrchestratorPort, metricsIngestUrl, documentServiceUrl,
                workerMemoryMb, workerCpuRequest, gracePeriodSeconds, jmeterJvmArgs, metricsIngestAuth,
                4446, shape);
    }

    /** The local shape ({@link WorkerPodShape#DEFAULTS}). */
    public ProvisionerProperties(String namespace, String headlessService, String image, int localOrchestratorPort,
                                 String metricsIngestUrl, String documentServiceUrl, long workerMemoryMb,
                                 String workerCpuRequest, int gracePeriodSeconds, String jmeterJvmArgs,
                                 String metricsIngestAuth) {
        this(namespace, headlessService, image, localOrchestratorPort, metricsIngestUrl, documentServiceUrl,
                workerMemoryMb, workerCpuRequest, gracePeriodSeconds, jmeterJvmArgs, metricsIngestAuth,
                WorkerPodShape.DEFAULTS);
    }

    /** Label namespace for every Pod the provisioner manages. */
    public static final String LABEL_PREFIX           = "com.perf.jmeterCloud.";
    /** The application group whose pool the worker belongs to (GROUP-CAPACITY, 2026-08-30; was {@code applicationId}). */
    public static final String LABEL_GROUP_ID         = LABEL_PREFIX + "groupId";
    public static final String LABEL_REGION           = LABEL_PREFIX + "region";
    public static final String LABEL_ROLE             = LABEL_PREFIX + "role";
    public static final String LABEL_MANAGED_BY       = LABEL_PREFIX + "managedBy";

    public static final String ROLE_LOCAL_ORCHESTRATOR = "local-orchestrator";
    /** Must match the headless {@code workers} Service selector in {@code kube/}. */
    public static final String MANAGED_BY              = "regional-orchestrator";
}
