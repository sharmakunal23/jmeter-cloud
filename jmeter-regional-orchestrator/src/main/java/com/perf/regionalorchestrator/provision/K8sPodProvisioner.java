package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.api.model.EnvVar;
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
 * reconciler can list and adopt by app and region with server-side selectors.
 */
@Component
public class K8sPodProvisioner implements PodProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(K8sPodProvisioner.class);

    static final String CONTAINER_NAME = "local-orchestrator";

    /** Graceful-delete window handed to the kubelet. */
    private static final long DELETE_GRACE_SECONDS = 10;

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
            pod = create(buildPod(spec));
        } else if (isTerminal(existing)) {
            // A terminal bare Pod can't be restarted in place — recreate.
            LOG.info("Pod {} exists in terminal phase {}; recreating", spec.podName(), phase(existing));
            deleteAndAwait(spec.podName());
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
    public List<ProvisionedPod> listFor(String applicationId, String region) {
        Map<String, String> selector = new LinkedHashMap<>();
        selector.put(ProvisionerProperties.LABEL_MANAGED_BY, ProvisionerProperties.MANAGED_BY);
        selector.put(ProvisionerProperties.LABEL_APPLICATION_ID, applicationId);
        if (region != null) {
            // Server-side region filter.
            selector.put(ProvisionerProperties.LABEL_REGION, region);
        }
        return k8s.pods().inNamespace(props.namespace()).withLabels(selector).list().getItems().stream()
                .map(p -> new ProvisionedPod(
                        p.getMetadata().getName(),
                        p.getMetadata().getLabels().get(ProvisionerProperties.LABEL_APPLICATION_ID),
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
            LOG.info("Created pod {} (app={}, region={})",
                    pod.getMetadata().getName(),
                    pod.getMetadata().getLabels().get(ProvisionerProperties.LABEL_APPLICATION_NAME),
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
                labels.get(ProvisionerProperties.LABEL_APPLICATION_ID),
                labels.get(ProvisionerProperties.LABEL_APPLICATION_NAME),
                labels.get(ProvisionerProperties.LABEL_REGION));
        deleteAndAwait(spec.podName());
        create(buildPod(spec));
    }

    private Pod buildPod(PodSpec spec) {
        List<EnvVar> env = buildEnv(spec).entrySet().stream()
                .map(e -> new EnvVar(e.getKey(), e.getValue(), null))
                .collect(Collectors.toList());

        Map<String, String> labels = Map.of(
                ProvisionerProperties.LABEL_APPLICATION_ID,   spec.applicationId(),
                ProvisionerProperties.LABEL_APPLICATION_NAME, spec.applicationName(),
                ProvisionerProperties.LABEL_REGION,           spec.region(),
                ProvisionerProperties.LABEL_ROLE,             ProvisionerProperties.ROLE_LOCAL_ORCHESTRATOR,
                ProvisionerProperties.LABEL_MANAGED_BY,       ProvisionerProperties.MANAGED_BY);

        Quantity memory = new Quantity(props.workerMemoryMb() + "Mi");

        return new PodBuilder()
                .withNewMetadata()
                    .withName(spec.podName())
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    // Kubelet service-link env injection (REDIS_PORT=tcp://...)
                    // collides with the env-var names the local-orch and its
                    // Spring relaxed binding read. Workers discover everything
                    // via DNS + the explicit env below.
                    .withEnableServiceLinks(false)
                    // Stable DNS via the headless Service.
                    .withHostname(spec.podName())
                    .withSubdomain(props.headlessService())
                    // Lifecycle is control-plane-owned; a kubelet restart
                    // would resurrect a worker the recycler means to drain.
                    .withRestartPolicy("Never")
                    .withTerminationGracePeriodSeconds(DELETE_GRACE_SECONDS)
                    .addNewContainer()
                        .withName(CONTAINER_NAME)
                        .withImage(props.image())
                        .withEnv(env)
                        .addNewPort()
                            .withContainerPort(props.localOrchestratorPort())
                            .withName("http")
                        .endPort()
                        .withNewResources()
                            // requests == limits: the worker's memory use is
                            // real (two JVMs + page cache); reserving less
                            // just invites node-level OOM under co-tenancy.
                            .addToRequests("memory", memory)
                            .addToLimits("memory", memory)
                            // CPU: request-only — a cfs-throttled load
                            // generator measures its own throttling, not
                            // the SUT.
                            .addToRequests("cpu", new Quantity(props.workerCpuRequest()))
                        .endResources()
                        .withNewReadinessProbe()
                            .withNewHttpGet()
                                .withPath("/actuator/health")
                                .withNewPort(props.localOrchestratorPort())
                            .endHttpGet()
                            .withInitialDelaySeconds(5)
                            .withPeriodSeconds(5)
                        .endReadinessProbe()
                    .endContainer()
                .endSpec()
                .build();
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
        e.put("APPLICATION_ID",          spec.applicationId());
        // No GLOBAL_ORCHESTRATOR_URL: the worker's PodRegistrar is conditional
        // on it, so the worker never registers or heartbeats — the Pod list is
        // the hub's liveness truth. Workers POST straight to the metrics-consumer.
        e.put("METRICS_INGEST_URL",      props.metricsIngestUrl());
        // Aggregator late-arrival grace (seconds). Set
        // here so the local-orch boots with it AND forwards it to every per-run
        // config (TestRunManager.buildPerRunConfig); a per-run POST /test
        // gracePeriodSeconds still overrides.
        e.put("GRACE_PERIOD_SECONDS",    String.valueOf(props.gracePeriodSeconds()));
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
