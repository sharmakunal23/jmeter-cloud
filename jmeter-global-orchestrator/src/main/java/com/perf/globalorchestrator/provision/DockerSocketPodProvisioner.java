package com.perf.globalorchestrator.provision;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link PodProvisioner} backed by a mounted host docker daemon
 * ({@code /var/run/docker.sock}) — the compose default, selected by
 * {@code podProvisioner.substrate=docker}. {@link K8sPodProvisioner} takes over
 * at {@code substrate=k8s} for private-cloud deployments.
 *
 * <p>Containers take their name and hostname from {@code podName}, so the
 * control plane reaches a worker at {@code http://{podName}:8080} over the
 * shared network, and carry {@code com.perf.jmeterCloud.*} labels so the
 * reconciler can list and adopt them by app and region without consulting the
 * registry. There is no host port mapping and no host volume mount: traffic
 * stays on the internal network, and the worker's BASE_DIR lives in an
 * anonymous volume.
 *
 * <p>{@link DockerClient} connections are lazy — the socket is not opened until
 * the first command — so this bean constructs cleanly even where the socket is
 * not mounted, which is what lets unit tests import the context without
 * exercising it.
 *
 * <p>Also gated on {@code PROVISIONING_MODE=DYNAMIC}. Substrate answers *how*
 * workers are created; mode answers *whether*. In static mode
 * {@link StaticPodProvisioner} is the only provisioner bean and the substrate
 * key is ignored entirely.
 */
@Component
@ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
@ConditionalOnProperty(name = "globalOrchestrator.podProvisioner.substrate",
                       havingValue = "docker", matchIfMissing = true)
public class DockerSocketPodProvisioner implements PodProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(DockerSocketPodProvisioner.class);

    private final DockerClient docker;
    private final ProvisionerProperties props;

    public DockerSocketPodProvisioner(DockerClient docker, ProvisionerProperties props) {
        this.docker = docker;
        this.props  = props;
    }

    @Override
    public ProvisionResult createAndStart(PodSpec spec) {
        boolean freshlyCreated = false;
        if (!exists(spec.podName())) {
            createContainer(spec);
            freshlyCreated = true;
        } else {
            LOG.info("Container {} already exists; reusing", spec.podName());
        }
        try {
            docker.startContainerCmd(spec.podName()).exec();
        } catch (NotModifiedException already) {
            // Container is already running — treat as success.
            LOG.debug("Container {} was already running; treating createAndStart as no-op", spec.podName());
        }

        // Capture the metadata the registry will
        // anchor the recycle lifecycle on. Image digest comes from
        // inspecting the container (which records the image ID at create
        // time, immune to subsequent re-tags of the same name). createdAt
        // is read off the same inspect response so adoption of an
        // already-existing container reports the daemon-truth time, not
        // "now()" — Phase D's max-age check would otherwise restart
        // its clock on every reconcile pass.
        String imageDigest = null;
        Instant createdAt = null;
        try {
            var inspect = docker.inspectContainerCmd(spec.podName()).exec();
            imageDigest = inspect.getImageId();
            String created = inspect.getCreated();
            if (created != null && !created.isBlank()) {
                createdAt = Instant.parse(created);
            }
        } catch (RuntimeException probe) {
            // Don't fail provisioning if the inspect call hiccups — the
            // pod is up either way, and the reconciler will eventually
            // try to back-fill on its next pass.
            LOG.debug("Could not capture image digest for {} ({}); leaving null",
                    spec.podName(), probe.toString());
        }
        if (freshlyCreated && createdAt == null) {
            // Best-effort fallback — at least pin a wall-clock for the
            // brand-new container so Phase D has SOMETHING to anchor on.
            createdAt = Instant.now();
        }
        return new ProvisionResult(baseUrlFor(spec.podName()), imageDigest, createdAt);
    }

    @Override
    public void stopAndRemove(String podName) {
        if (!exists(podName)) return;
        try {
            docker.stopContainerCmd(podName).withTimeout(10).exec();
        } catch (NotModifiedException stopped) {
            // Already stopped — fine.
        } catch (NotFoundException missing) {
            return;
        }
        try {
            docker.removeContainerCmd(podName).withForce(true).exec();
        } catch (NotFoundException missing) {
            // Removed concurrently — fine.
        }
    }

    @Override
    public void stop(String podName) {
        try {
            docker.stopContainerCmd(podName).withTimeout(10).exec();
        } catch (NotModifiedException | NotFoundException ignore) {
            // Already stopped or missing — drain semantics tolerate both.
        }
    }

    @Override
    public void start(String podName) {
        try {
            docker.startContainerCmd(podName).exec();
        } catch (NotModifiedException already) {
            // Already running — fine.
        }
    }

    @Override
    public void restart(String podName) {
        // restart_in_place keeps the container ID + volumes; equivalent to
        // `docker restart`. Throws NotFoundException if the container is gone.
        docker.restartContainerCmd(podName).withTimeout(10).exec();
    }

    @Override
    public boolean exists(String podName) {
        try {
            docker.inspectContainerCmd(podName).exec();
            return true;
        } catch (NotFoundException missing) {
            return false;
        }
    }

    @Override
    public boolean isRunning(String podName) {
        try {
            return Boolean.TRUE.equals(docker.inspectContainerCmd(podName).exec().getState().getRunning());
        } catch (NotFoundException missing) {
            return false;
        }
    }

    @Override
    public List<ProvisionedPod> listFor(String applicationId, String region) {
        var cmd = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(
                        ProvisionerProperties.LABEL_MANAGED_BY,    ProvisionerProperties.MANAGED_BY,
                        ProvisionerProperties.LABEL_APPLICATION_ID, applicationId));
        List<Container> containers = cmd.exec();
        return containers.stream()
                .filter(c -> region == null
                        || region.equals(c.getLabels().get(ProvisionerProperties.LABEL_REGION)))
                .map(c -> new ProvisionedPod(
                        firstName(c),
                        c.getLabels().get(ProvisionerProperties.LABEL_APPLICATION_ID),
                        c.getLabels().get(ProvisionerProperties.LABEL_REGION),
                        c.getState(),
                        c.getCreated() == null ? null : Instant.ofEpochSecond(c.getCreated()),
                        c.getImageId()))
                .collect(Collectors.toList());
    }

    @Override
    public String currentImageDigest() {
        try {
            return docker.inspectImageCmd(props.image()).exec().getId();
        } catch (NotFoundException missing) {
            // Image hasn't been built/pulled — recycler treats null as
            // "skip the mismatch check this tick" so this is a benign
            // miss, not a recycle failure.
            LOG.debug("Image {} not found via inspect; returning null digest", props.image());
            return null;
        } catch (RuntimeException probe) {
            LOG.warn("Could not inspect image {}: {}", props.image(), probe.toString());
            return null;
        }
    }

    private void createContainer(PodSpec spec) {
        Map<String, String> envMap = buildEnv(spec);
        List<String> envList = envMap.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.toList());

        Map<String, String> labels = Map.of(
                ProvisionerProperties.LABEL_APPLICATION_ID,   spec.applicationId(),
                ProvisionerProperties.LABEL_APPLICATION_NAME, spec.applicationName(),
                ProvisionerProperties.LABEL_REGION,           spec.region(),
                ProvisionerProperties.LABEL_ROLE,             ProvisionerProperties.ROLE_LOCAL_ORCHESTRATOR,
                ProvisionerProperties.LABEL_MANAGED_BY,       ProvisionerProperties.MANAGED_BY);

        // Reliability (2026-05-27) — pin a
        // hard memory limit so a busy worker (orchestrator JVM + JMeter child)
        // OOMs cleanly inside the cgroup (→ ExitOnOutOfMemoryError, reaped by
        // PodSweeper) instead of letting the host OOM-killer reap an arbitrary
        // process — including a noisy-neighbour Postgres on the same
        // host. Sized (default 6 GiB, RELIABILITY Round 6) for the real workload —
        // a 12 h run at 200-250 rps across ~100 endpoints per worker — to fit the
        // orchestrator JVM (-Xmx1g + native ≈ 1.5 GiB) + the JMeter child
        // (-Xmx2g + native ≈ 2.5 GiB) + ~2 GiB page-cache headroom for the
        // multi-GB JTL the orchestrator tails. (Round 5's 4 GiB / 1g-child sizing
        // left no headroom once the child grew and workers OOM'd near 1 h.)
        // memory-swap == memory disables swap so we fail fast rather than thrash.
        //
        // No restart policy is set on workers on purpose: their lifecycle is
        // owned by the control plane (spin/delete, reconciler drain-and-replace,
        // recycler) — a Docker restart would resurrect a container the control
        // plane means to delete.
        //
        // Log rotation: cap the container's json-file stdout log at 5 × 50 MiB
        // so 12 h of (rate-limited) orchestrator + JMeter console output can't
        // fill the host disk over a long run. Without this, docker's default
        // json-file driver grows the log unbounded for the life of the run.
        long workerMemoryBytes = props.workerMemoryMb() * 1024L * 1024L;
        LogConfig logConfig = new LogConfig(
                LogConfig.LoggingType.JSON_FILE,
                Map.of("max-size", "50m", "max-file", "5"));
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode(props.network())
                .withMemory(workerMemoryBytes)
                .withMemorySwap(workerMemoryBytes)
                .withLogConfig(logConfig);

        CreateContainerResponse resp = docker.createContainerCmd(props.image())
                .withName(spec.podName())
                .withHostName(spec.podName())
                .withEnv(envList)
                .withLabels(labels)
                .withHostConfig(hostConfig)
                .exec();

        LOG.info("Created container {} (id={}) for app={} region={}",
                spec.podName(), resp.getId(), spec.applicationName(), spec.region());
    }

    private Map<String, String> buildEnv(PodSpec spec) {
        // LinkedHashMap so the env list has a stable order — easier to diff
        // against the static orchestrator-1/-2 compose env when something
        // misbehaves.
        Map<String, String> e = new LinkedHashMap<>();
        // Identity — what the local-orch reports as itself + how the global
        // reaches it back. Mirrors orchestrator-1's compose env.
        e.put("POD_ID",                  spec.podName());
        e.put("POD_NAME",                spec.podName());
        e.put("POD_BASE_URL",            baseUrlFor(spec.podName()));
        e.put("REGION",                  spec.region());
        e.put("TEST_REGION",             spec.region());
        e.put("APPLICATION_ID",          spec.applicationId());
        // Control-plane wiring.
        e.put("GLOBAL_ORCHESTRATOR_URL", props.globalOrchestratorUrl());
        // Streaming pipeline.
        // Workers POST straight to the metrics-consumer.
        e.put("METRICS_INGEST_URL",      props.metricsIngestUrl());
        // Aggregator late-arrival grace (seconds). Set
        // here so the local-orch boots with it AND forwards it to every per-run
        // config (TestRunManager.buildPerRunConfig); a per-run POST /test
        // gracePeriodSeconds still overrides.
        e.put("GRACE_PERIOD_SECONDS",    String.valueOf(props.gracePeriodSeconds()));
        // Artifact + result wiring — same defaults as orchestrator-1.
        e.put("ARTIFACT_SOURCE",         "DOCUMENT_SERVICE");
        e.put("DOCUMENT_SERVICE_URL",    props.documentServiceUrl());
        // Boot the Document Service result sink so per-run saveResults=true
        // (→ AUTO_UPLOAD_RESULTS via the POST /test body) can upload. The
        // sink object is fixed at boot; AUTO_UPLOAD_RESULTS stays false by
        // default so runs that don't opt in upload nothing.
        e.put("RESULT_SINK",             "DOCUMENT_SERVICE");
        e.put("AUTO_UPLOAD_RESULTS",     "false");
        // Filesystem layout — uses anonymous volume, paths inside the container.
        e.put("BASE_DIR",                "/var/lib/jmeter-orchestrator");
        e.put("JTL_PATH",                "/var/lib/jmeter-orchestrator/results/results.jtl");
        e.put("SENTINEL_PATH",           "/var/lib/jmeter-orchestrator/results/.done");
        // Required by OrchestratorConfig boot validation; real value arrives via POST /test.
        e.put("RUN_ID",                  "placeholder-pre-first-run");
        // Tomcat.
        e.put("HTTP_BIND_ADDRESS",       "0.0.0.0");
        e.put("HTTP_PORT",               String.valueOf(props.localOrchestratorPort()));
        // SLIMDOWN SL-E (2026-07-22): the tracing stamps
        // (MANAGEMENT_TRACING_* / MANAGEMENT_OTLP_* / OTEL_RESOURCE_ATTRIBUTES)
        // are gone — workers were slimmed in SL-C and ignore them (D-5).
        return e;
    }

    @Override
    public String baseUrlFor(String podName) {
        return "http://" + podName + ":" + props.localOrchestratorPort();
    }

    private static String firstName(Container c) {
        String[] names = c.getNames();
        if (names == null || names.length == 0) return null;
        // docker-java returns names with a leading slash, e.g. "/payments-east-worker-1".
        String n = names[0];
        return n.startsWith("/") ? n.substring(1) : n;
    }
}
