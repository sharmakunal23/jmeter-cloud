package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@link PodProvisioner}: worker Pods through the Kubernetes API via
 * fabric8, using the in-cluster ServiceAccount.
 *
 * <p>Workers are <b>bare Pods</b> in {@link ProvisionerProperties#namespace()},
 * bare on purpose: the global orchestrator IS the controller — spin, claim,
 * drain, recycle — and a Deployment would fight it by resurrecting pods the
 * control plane means to delete.
 *
 * <p>Each pod sets {@code metadata.name == spec.hostname == podName} with
 * {@code spec.subdomain == headlessService}, so with the headless Service from
 * {@code kube/} a worker resolves at {@code {podName}.{headlessService}}. It runs
 * {@code restartPolicy: Never}, because worker lifecycle belongs to the control
 * plane and not the kubelet: a crash lands the pod in {@code Failed}, the
 * global's sweeper marks the row LOST, and its reconciler or recycler decides
 * what happens next. {@code resources.requests == limits == workerMemoryMb}
 * keeps the OOM failure mode inside the pod's own cgroup, and the HTTP
 * readiness probe is ops visibility only — the global still gates on health.
 *
 * <p>Two semantics a caller can get wrong. <b>Bare Pods have no
 * stopped-but-present state</b>, so {@code stop} deletes, {@code start} no-ops
 * on a live pod and recreates a terminal one, and {@code restart} is
 * delete-and-recreate; a recreated pod is rebuilt from its own labels using the
 * currently configured image and env, since there is no container filesystem
 * worth preserving. And <b>{@link #currentImageDigest()} returns the configured
 * image reference</b>, so IMAGE_MISMATCH recycling fires on a config rollout,
 * never on a local image rebuild. The kubelet owns log rotation.
 *
 * <p>Pods carry the {@code com.perf.jmeterCloud.*} labels so the global's
 * reconciler can list and adopt by group and region with server-side
 * selectors. The pool is the application group's (GROUP-CAPACITY, 2026-08-30):
 * the label is {@code com.perf.jmeterCloud.groupId}, and a worker Pod created
 * before that change (labelled {@code applicationId}) does not match
 * {@link #listFor} — recreate such Pods; the local kind clusters are recycled
 * by the smoke run.
 */
@Component
public class K8sPodProvisioner implements PodProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(K8sPodProvisioner.class);

    static final String CONTAINER_NAME = "local-orchestrator";

    /** Graceful-delete window handed to the kubelet — must exceed the worker's
     * own drain ({@code ORCHESTRATOR_SHUTDOWN_GRACE_S}, 30 s: stop JMeter, flush
     * the last window to the disk buffer), or an eviction SIGKILLs the flush. */
    private static final long DELETE_GRACE_SECONDS = 45;

    /** How long restart() waits for the old Pod to disappear before recreating. */
    private static final long DELETE_AWAIT_MS = 30_000;

    private final KubernetesClient k8s;
    private final ProvisionerProperties props;

    public K8sPodProvisioner(KubernetesClient k8s, ProvisionerProperties props) {
        this.k8s = k8s;
        this.props = props;
    }

    @Override
    public ProvisionResult createAndStart(PodSpec spec) {
        Pod existing = getPod(spec.podName());
        Pod pod;
        if (existing == null) {
            checkCapacity();
            pod = create(buildPod(spec));
        } else if (isTerminal(existing)) {
            // A terminal bare Pod can't be restarted in place — recreate.
            LOG.info("Pod {} exists in terminal phase {}; recreating", spec.podName(), phase(existing));
            deleteAndAwait(spec.podName());
            checkCapacity();
            pod = create(buildPod(spec));
        } else {
            LOG.info("Pod {} already exists (phase {}); reusing", spec.podName(), phase(existing));
            pod = existing;
        }

        // WORKER-HYGIENE Phase B metadata the registry anchors the recycle
        // lifecycle on. imageDigest deliberately records the CONFIGURED
        // reference — same value space as currentImageDigest() so the
        // recycler's mismatch diff is meaningful. createdAt comes from the
        // API server's creationTimestamp (authoritative even for adopted
        // pods — Phase D's max-age check must not restart its clock).
        Instant createdAt = creationInstant(pod);
        return new ProvisionResult(baseUrlFor(spec.podName()), podImage(pod), createdAt);
    }

    @Override
    public void stopAndRemove(String podName) {
        deleteAndAwait(podName);
    }

    /**
     * No K8s equivalent of a stopped-but-present container — deletes the
     * Pod. (No production callers; kept for interface completeness.)
     */
    @Override
    public void stop(String podName) {
        deleteAndAwait(podName);
    }

    @Override
    public void start(String podName) {
        Pod pod = getPod(podName);
        if (pod == null) {
            throw new IllegalStateException("Pod " + podName + " does not exist; cannot start");
        }
        if (!isTerminal(pod)) {
            // Pending or Running — the kubelet is already driving it.
            return;
        }
        LOG.info("Pod {} is terminal (phase {}); recreating from its labels", podName, phase(pod));
        recreateFromLabels(pod);
    }

    @Override
    public void restart(String podName) {
        Pod pod = getPod(podName);
        if (pod == null) {
            // Interface contract: restart throws when the target is missing.
            throw new IllegalStateException("Pod " + podName + " does not exist; cannot restart");
        }
        recreateFromLabels(pod);
    }

    @Override
    public boolean exists(String podName) {
        return getPod(podName) != null;
    }

    @Override
    public boolean isRunning(String podName) {
        Pod pod = getPod(podName);
        // Running == process up, not app health (PodSpinService gates on health).
        return pod != null && "Running".equals(phase(pod));
    }

    @Override
    public List<ProvisionedPod> listFor(String groupId, String region) {
        Map<String, String> selector = new LinkedHashMap<>();
        selector.put(ProvisionerProperties.LABEL_MANAGED_BY, ProvisionerProperties.MANAGED_BY);
        selector.put(ProvisionerProperties.LABEL_GROUP_ID, groupId);
        if (region != null) {
            // Server-side region filter.
            selector.put(ProvisionerProperties.LABEL_REGION, region);
        }
        return k8s.pods().inNamespace(props.namespace()).withLabels(selector).list().getItems().stream()
                .map(p -> new ProvisionedPod(
                        p.getMetadata().getName(),
                        p.getMetadata().getLabels().get(ProvisionerProperties.LABEL_GROUP_ID),
                        p.getMetadata().getLabels().get(ProvisionerProperties.LABEL_REGION),
                        statusOf(p),
                        creationInstant(p),
                        podImage(p)))
                .collect(Collectors.toList());
    }

    @Override
    public List<WorkerState> listWorkers() {
        return k8s.pods().inNamespace(props.namespace())
                .withLabel(ProvisionerProperties.LABEL_MANAGED_BY, ProvisionerProperties.MANAGED_BY)
                .list().getItems().stream()
                .map(WorkerState::from)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<WorkerState> workerState(String podName) {
        Pod pod = getPod(podName);
        return pod == null ? Optional.empty() : Optional.of(WorkerState.from(pod));
    }

    /**
     * The container's stdout as the kubelet kept it — available after the
     * worker process is gone, which is when it is needed.
     */
    @Override
    public Optional<String> podLog(String podName, int tailLines) {
        if (getPod(podName) == null) return Optional.empty();
        try {
            return Optional.ofNullable(k8s.pods().inNamespace(props.namespace()).withName(podName)
                    .tailingLines(Math.max(1, tailLines)).getLog());
        } catch (KubernetesClientException e) {
            LOG.debug("podLog {} failed: {}", podName, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The configured image reference IS the identity the recycler diffs
     * against. Pin the config to {@code repo:tag@sha256:...} in cloud for
     * exact rollout semantics; a bare tag means "recycle when the
     * configured tag string changes."
     */
    @Override
    public String currentImageDigest() {
        return props.image();
    }

    @Override
    public String baseUrlFor(String podName) {
        // {podName}.{headlessService} resolves inside the namespace via the
        // headless Service selecting the managed pods.
        return "http://" + podName + "." + props.headlessService() + ":" + props.localOrchestratorPort();
    }

    // ── internals ────────────────────────────────────────────────────────

    private Pod getPod(String podName) {
        return k8s.pods().inNamespace(props.namespace()).withName(podName).get();
    }

    private Pod create(Pod pod) {
        try {
            Pod created = k8s.pods().inNamespace(props.namespace()).resource(pod).create();
            LOG.info("Created pod {} (group={}, region={})",
                    pod.getMetadata().getName(),
                    pod.getMetadata().getLabels().get(ProvisionerProperties.LABEL_GROUP_ID),
                    pod.getMetadata().getLabels().get(ProvisionerProperties.LABEL_REGION));
            return created;
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                // Lost a create race — idempotency contract says reuse.
                LOG.info("Pod {} was created concurrently; reusing", pod.getMetadata().getName());
                return getPod(pod.getMetadata().getName());
            }
            throw e;
        }
    }

    private void deleteAndAwait(String podName) {
        var op = k8s.pods().inNamespace(props.namespace()).withName(podName);
        if (op.get() == null) return;
        op.withGracePeriod(DELETE_GRACE_SECONDS).delete();
        long deadline = System.currentTimeMillis() + DELETE_AWAIT_MS;
        while (op.get() != null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (op.get() != null) {
            LOG.warn("Pod {} still present {} ms after delete; continuing (API server will finish the delete)",
                    podName, DELETE_AWAIT_MS);
        }
    }

    /**
     * Rebuilds the {@link PodSpec} from the pod's own labels (the durable
     * record of its identity) and recreates it with the CURRENT configured
     * image + env. Used by restart() and by start() on terminal pods.
     */
    private void recreateFromLabels(Pod pod) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        PodSpec spec = new PodSpec(
                pod.getMetadata().getName(),
                labels.get(ProvisionerProperties.LABEL_GROUP_ID),
                labels.get(ProvisionerProperties.LABEL_REGION));
        deleteAndAwait(spec.podName());
        create(buildPod(spec));
    }

    /**
     * The worker Pod spec: labels the hub selects on (+ the platform's extra
     * labels), the headless-DNS identity, the three-probe pattern (startup +
     * readiness on {@code /actuator/health} — the worker is usable only once
     * its ingest probe is green; liveness on {@code /actuator/keepalive} — the
     * process only), and whatever resources / security context the platform's
     * quota and LimitRange dictate ({@link WorkerPodShape}). The SA token is
     * never mounted: workers do not call the cluster API.
     */
    private Pod buildPod(PodSpec spec) {
        WorkerPodShape shape = props.shape();
        List<EnvVar> env = buildEnv(spec).entrySet().stream()
                .map(e -> new EnvVar(e.getKey(), e.getValue(), null))
                .collect(Collectors.toList());
        Map<String, String> labels = new LinkedHashMap<>(shape.extraLabels());
        labels.put(ProvisionerProperties.LABEL_GROUP_ID,         spec.groupId());
        labels.put(ProvisionerProperties.LABEL_REGION,           spec.region());
        labels.put(ProvisionerProperties.LABEL_ROLE,             ProvisionerProperties.ROLE_LOCAL_ORCHESTRATOR);
        labels.put(ProvisionerProperties.LABEL_MANAGED_BY,       ProvisionerProperties.MANAGED_BY);

        ResourceRequirements resources = new ResourceRequirements();
        Map<String, Quantity> requests = new LinkedHashMap<>();
        Map<String, Quantity> limits = new LinkedHashMap<>();
        if (shape.cpuMemoryResources()) {
            Quantity memory = new Quantity(props.workerMemoryMb() + "Mi");
            requests.put("memory", memory);
            limits.put("memory", memory);
            requests.put("cpu", new Quantity(props.workerCpuRequest()));
            if (shape.workerCpuLimit() != null) {
                limits.put("cpu", new Quantity(shape.workerCpuLimit()));
            }
        }
        if (shape.ephemeralStorage() != null) {
            Quantity eph = new Quantity(shape.ephemeralStorage());
            requests.put("ephemeral-storage", eph);
            limits.put("ephemeral-storage", eph);
        }
        resources.setRequests(requests);
        resources.setLimits(limits);

        PodSecurityContext securityContext = null;
        if (shape.hasSecurityContext()) {
            securityContext = new PodSecurityContext();
            securityContext.setRunAsNonRoot(true);
            securityContext.setRunAsUser(shape.runAsUser());
            securityContext.setRunAsGroup(shape.runAsGroup());
            securityContext.setFsGroup(shape.fsGroup());
        }
        List<LocalObjectReference> pullSecrets = shape.imagePullSecret() == null
                ? List.of() : List.of(new LocalObjectReference(shape.imagePullSecret()));

        int port = props.localOrchestratorPort();
        return new PodBuilder()
                .withNewMetadata()
                    .withName(spec.podName())
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withEnableServiceLinks(false)
                    .withAutomountServiceAccountToken(false)
                    .withServiceAccountName(shape.serviceAccountName())
                    .withImagePullSecrets(pullSecrets)
                    .withSecurityContext(securityContext)
                    .withHostname(spec.podName())
                    .withSubdomain(props.headlessService())
                    .withRestartPolicy("Never")
                    .withTerminationGracePeriodSeconds(DELETE_GRACE_SECONDS)
                    .addNewContainer()
                        .withName(CONTAINER_NAME)
                        .withImage(props.image())
                        .withEnv(env)
                        .addNewPort()
                            .withContainerPort(port)
                            .withName("http")
                        .endPort()
                        .withResources(resources)
                        .withNewStartupProbe()
                            .withNewHttpGet().withPath("/actuator/health").withNewPort(port).endHttpGet()
                            .withPeriodSeconds(5).withTimeoutSeconds(5).withFailureThreshold(36)   // 180 s: image pull + JVM
                        .endStartupProbe()
                        .withNewReadinessProbe()
                            .withNewHttpGet().withPath("/actuator/health").withNewPort(port).endHttpGet()
                            .withPeriodSeconds(5).withTimeoutSeconds(5).withFailureThreshold(3)
                        .endReadinessProbe()
                        .withNewLivenessProbe()
                            .withNewHttpGet().withPath("/actuator/keepalive").withNewPort(port).endHttpGet()
                            .withPeriodSeconds(10).withTimeoutSeconds(5).withFailureThreshold(3)
                        .endLivenessProbe()
                    .endContainer()
                .endSpec()
                .build();
    }

    // ── Namespace-quota capacity guard (Track 8) ─────────────────────────

    /**
     * Refuses a spin the namespace's {@code ResourceQuota}s cannot admit —
     * before the API server is asked, so the run fails with the quota's
     * numbers instead of a Pod that sits Pending. Only dimensions a quota
     * bounds are checked; a namespace without quotas is unbounded.
     */
    void checkCapacity() {
        NamespaceCapacity c = capacity();
        if (c.workersFree() != null && c.workersFree() < 1) {
            throw new CapacityExhaustedException("namespace " + props.namespace()
                    + " cannot admit another worker — quota headroom: pods=" + c.podsFree()
                    + ", memoryMi=" + c.memoryFreeMi() + ", cpuMillis=" + c.cpuFreeMillis()
                    + ", ephemeralMi=" + c.ephemeralFreeMi()
                    + " (worker needs " + (props.shape().cpuMemoryResources()
                            ? props.workerMemoryMb() + "Mi, " + props.workerCpuRequest() + " cpu" : "no cpu/memory")
                    + (props.shape().ephemeralStorage() != null ? ", " + props.shape().ephemeralStorage() + " ephemeral" : "") + ")");
        }
    }

    @Override
    public NamespaceCapacity capacity() {
        List<ResourceQuota> quotas = k8s.resourceQuotas().inNamespace(props.namespace()).list().getItems();
        Long podsFree = null, memFree = null, cpuFree = null, ephFree = null;
        for (ResourceQuota q : quotas) {
            if (q.getStatus() == null || q.getStatus().getHard() == null) continue;
            Map<String, Quantity> hard = q.getStatus().getHard();
            Map<String, Quantity> used = q.getStatus().getUsed() == null ? Map.of() : q.getStatus().getUsed();
            for (String key : List.of("pods", "count/pods")) {
                if (hard.containsKey(key)) podsFree = tighter(podsFree, headroom(hard, used, key).longValue());
            }
            if (props.shape().cpuMemoryResources()) {
                for (String key : List.of("requests.memory", "limits.memory")) {
                    if (hard.containsKey(key)) memFree = tighter(memFree, headroom(hard, used, key).divide(MI, 0, java.math.RoundingMode.DOWN).longValue());
                }
                for (String key : List.of("requests.cpu", "limits.cpu")) {
                    if (hard.containsKey(key)) cpuFree = tighter(cpuFree, headroom(hard, used, key).multiply(THOUSAND).longValue());
                }
            }
            if (props.shape().ephemeralStorage() != null) {
                // Workers request == limit, so either quota key bounds them the same way.
                for (String key : List.of("requests.ephemeral-storage", "limits.ephemeral-storage")) {
                    if (hard.containsKey(key)) ephFree = tighter(ephFree, headroom(hard, used, key).divide(MI, 0, java.math.RoundingMode.DOWN).longValue());
                }
            }
        }
        Integer workersFree = null;
        if (podsFree != null) workersFree = (int) Math.max(0, podsFree);
        if (memFree != null) {
            long fit = Math.max(0, memFree / Math.max(1, props.workerMemoryMb()));
            workersFree = workersFree == null ? (int) fit : (int) Math.min(workersFree, fit);
        }
        if (cpuFree != null) {
            long perWorker = Math.max(1, Quantity.getAmountInBytes(new Quantity(props.workerCpuRequest())).multiply(THOUSAND).longValue());
            long fit = Math.max(0, cpuFree / perWorker);
            workersFree = workersFree == null ? (int) fit : (int) Math.min(workersFree, fit);
        }
        if (ephFree != null) {
            long perWorkerMi = Math.max(1, Quantity.getAmountInBytes(new Quantity(props.shape().ephemeralStorage()))
                    .divide(MI, 0, java.math.RoundingMode.UP).longValue());
            long fit = Math.max(0, ephFree / perWorkerMi);
            workersFree = workersFree == null ? (int) fit : (int) Math.min(workersFree, fit);
        }
        return new NamespaceCapacity(podsFree == null ? null : (int) Math.max(0, podsFree), memFree, cpuFree, ephFree, workersFree);
    }

    private static final java.math.BigDecimal MI = java.math.BigDecimal.valueOf(1024L * 1024L);
    private static final java.math.BigDecimal THOUSAND = java.math.BigDecimal.valueOf(1000L);

    private static java.math.BigDecimal headroom(Map<String, Quantity> hard, Map<String, Quantity> used, String key) {
        java.math.BigDecimal h = Quantity.getAmountInBytes(hard.get(key));
        java.math.BigDecimal u = used.containsKey(key) ? Quantity.getAmountInBytes(used.get(key)) : java.math.BigDecimal.ZERO;
        return h.subtract(u);
    }

    private static Long tighter(Long current, long candidate) {
        return current == null ? candidate : Math.min(current, candidate);
    }

    private Map<String, String> buildEnv(PodSpec spec) {
        // The env contract every worker expects. LinkedHashMap so the env
        // list has a stable order.
        Map<String, String> e = new LinkedHashMap<>();
        // Identity — what the local-orch reports as itself + how the global
        // reaches it back.
        e.put("POD_ID",                  spec.podName());
        e.put("POD_NAME",                spec.podName());
        e.put("POD_BASE_URL",            baseUrlFor(spec.podName()));
        e.put("REGION",                  spec.region());
        e.put("TEST_REGION",             spec.region());
        e.put("GROUP_ID",                spec.groupId());
        // No GLOBAL_ORCHESTRATOR_URL: the worker's PodRegistrar is conditional
        // on it, so the worker never registers or heartbeats — the Pod list is
        // the hub's liveness truth. Workers POST straight to the metrics-consumer.
        e.put("METRICS_INGEST_URL",      props.metricsIngestUrl());
        if (props.metricsIngestAuth() != null) {
            e.put("METRICS_INGEST_AUTH", props.metricsIngestAuth());
        }
        // Aggregator late-arrival grace (seconds). Set
        // here so the local-orch boots with it AND forwards it to every per-run
        // config (TestRunManager.buildPerRunConfig); a per-run POST /test
        // gracePeriodSeconds still overrides.
        e.put("GRACE_PERIOD_SECONDS",    String.valueOf(props.gracePeriodSeconds()));
        // UX-DYNAMICS T5 posture: the worker's own default is 0 (secure by
        // default — bsh is unauthenticated code-exec). Provisioner-spun Pods
        // opt in here so runtime property pushes need no foresight; the port
        // stays pod-internal (never a Service port, NetworkPolicy never
        // admits 4446/4447).
        e.put("BEANSHELL_PORT",          String.valueOf(props.beanshellPort()));
        // Artifact + result wiring.
        e.put("ARTIFACT_SOURCE",         "DOCUMENT_SERVICE");
        e.put("DOCUMENT_SERVICE_URL",    props.documentServiceUrl());
        // Boot the Document Service result sink so per-run saveResults=true
        // (→ AUTO_UPLOAD_RESULTS via the POST /test body) can upload. The
        // sink object is fixed at boot; AUTO_UPLOAD_RESULTS stays false by
        // default so runs that don't opt in upload nothing.
        e.put("RESULT_SINK",             "DOCUMENT_SERVICE");
        e.put("AUTO_UPLOAD_RESULTS",     "false");
        // Filesystem layout — pod-ephemeral storage, paths inside the container.
        e.put("BASE_DIR",                "/var/lib/jmeter-orchestrator");
        e.put("JTL_PATH",                "/var/lib/jmeter-orchestrator/results/results.jtl");
        e.put("SENTINEL_PATH",           "/var/lib/jmeter-orchestrator/results/.done");
        // Required by OrchestratorConfig boot validation; real value arrives via POST /test.
        e.put("RUN_ID",                  "placeholder-pre-first-run");
        if (props.jmeterJvmArgs() != null) {
            e.put("JMETER_JVM_ARGS",     props.jmeterJvmArgs());
        }
        if (props.shape().workerJavaOpts() != null) {
            e.put("JAVA_OPTS",           props.shape().workerJavaOpts());   // the orchestrator JVM (image ENTRYPOINT honours it)
        }
        // Tomcat.
        e.put("HTTP_BIND_ADDRESS",       "0.0.0.0");
        e.put("HTTP_PORT",               String.valueOf(props.localOrchestratorPort()));
        // SLIMDOWN SL-E/SL-F (2026-07-22): no tracing env at all — the worker
        // image was slimmed in SL-C and has no OTel exporter to configure.
        return e;
    }

    /** The image the pod was created from — the recycler's identity value space. */
    private static String podImage(Pod pod) {
        if (pod == null || pod.getSpec() == null || pod.getSpec().getContainers().isEmpty()) return null;
        return pod.getSpec().getContainers().get(0).getImage();
    }

    private static Instant creationInstant(Pod pod) {
        if (pod == null || pod.getMetadata() == null) return Instant.now();
        String ts = pod.getMetadata().getCreationTimestamp();
        if (ts == null || ts.isBlank()) {
            // Freshly built object the API server hasn't stamped yet — pin a
            // wall-clock so the max-age recycle check has SOMETHING to anchor on.
            return Instant.now();
        }
        return Instant.parse(ts);
    }

    private static String phase(Pod pod) {
        return pod.getStatus() == null ? null : pod.getStatus().getPhase();
    }

    private static boolean isTerminal(Pod pod) {
        String phase = phase(pod);
        return "Succeeded".equals(phase) || "Failed".equals(phase);
    }

    /**
     * Maps the Pod phase onto the status strings
     * ({@code running}/{@code created}/{@code exited}) the registry +
     * capacity snapshot display.
     */
    private static String statusOf(Pod pod) {
        String phase = phase(pod);
        if (phase == null) return "unknown";
        return switch (phase) {
            case "Running"             -> "running";
            case "Pending"             -> "created";
            case "Succeeded", "Failed" -> "exited";
            default                    -> "unknown";
        };
    }
}
