package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provisioner unit tests against the fabric8 CRUD mock server (an
 * in-process API server; no cluster).
 */
@EnableKubernetesMockClient(crud = true)
@DisplayName("K8sPodProvisioner — fabric8 mock-server behavior")
class K8sPodProvisionerTest {

    static final String NS = "jmeter-cloud";

    KubernetesClient client;

    private K8sPodProvisioner provisioner;
    private ProvisionerProperties props;

    @BeforeEach
    void setUp() {
        props = new ProvisionerProperties(
                NS, "workers", "jmeter-local-orchestrator:dev", 8080,
                "http://metrics-consumer:8083/api/v1/ingest",
                "http://document-service:8084",
                6144, "500m", 10, "-Xms256m -Xmx512m", "Bearer testToken");
        provisioner = new K8sPodProvisioner(client, props);
        // Isolate tests — the mock is per-class, not per-method.
        client.pods().inNamespace(NS).delete();
    }

    private static PodSpec spec(String name) {
        return new PodSpec(name, "cps", "us-east-1");
    }

    @Test
    @DisplayName("createAndStart creates a labelled pod with env, resources, DNS identity and returns the headless-DNS baseUrl + configured-image identity")
    void createAndStartShapesThePod() {
        ProvisionResult result = provisioner.createAndStart(spec("payments-us-east-1-worker-1"));

        assertThat(result.baseUrl()).isEqualTo("http://payments-us-east-1-worker-1.workers:8080");
        assertThat(result.imageDigest())
                .as("provision records the configured image reference, same value space as currentImageDigest()")
                .isEqualTo("jmeter-local-orchestrator:dev");
        assertThat(result.createdAt()).isNotNull();

        Pod pod = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-1").get();
        assertThat(pod).isNotNull();
        assertThat(pod.getMetadata().getLabels())
                .containsEntry(ProvisionerProperties.LABEL_MANAGED_BY, "regional-orchestrator")
                .containsEntry(ProvisionerProperties.LABEL_GROUP_ID, "cps")
                .doesNotContainKey("com.perf.jmeterCloud.applicationId")
                .doesNotContainKey("com.perf.jmeterCloud.applicationName")
                .containsEntry(ProvisionerProperties.LABEL_REGION, "us-east-1")
                .containsEntry(ProvisionerProperties.LABEL_ROLE, "local-orchestrator");

        assertThat(pod.getSpec().getHostname()).isEqualTo("payments-us-east-1-worker-1");
        assertThat(pod.getSpec().getSubdomain()).isEqualTo("workers");
        assertThat(pod.getSpec().getRestartPolicy())
                .as("the orchestrator is the controller; the kubelet must not resurrect workers")
                .isEqualTo("Never");
        assertThat(pod.getSpec().getEnableServiceLinks())
                .as("service-link env injection collides with the worker's env-var config surface")
                .isFalse();

        var container = pod.getSpec().getContainers().get(0);
        assertThat(container.getImage()).isEqualTo("jmeter-local-orchestrator:dev");
        assertThat(container.getResources().getLimits().get("memory").toString()).isEqualTo("6144Mi");
        assertThat(container.getResources().getRequests().get("memory").toString()).isEqualTo("6144Mi");
        assertThat(container.getResources().getRequests().get("cpu").toString()).isEqualTo("500m");
        assertThat(container.getResources().getLimits())
                .as("no cpu limit — throttling a load generator skews its measurements")
                .doesNotContainKey("cpu");
        assertThat(container.getReadinessProbe().getHttpGet().getPath()).isEqualTo("/actuator/health");

        Map<String, String> env = container.getEnv().stream()
                .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        assertThat(env)
                .containsEntry("POD_ID", "payments-us-east-1-worker-1")
                .containsEntry("POD_BASE_URL", "http://payments-us-east-1-worker-1.workers:8080")
                .containsEntry("REGION", "us-east-1")
                .containsEntry("GROUP_ID", "cps")
                .doesNotContainKey("APPLICATION_ID")
                // Workers never call the hub's control plane — no registrar, no heartbeat.
                .doesNotContainKey("GLOBAL_ORCHESTRATOR_URL")
                .containsEntry("JMETER_JVM_ARGS", "-Xms256m -Xmx512m")
                // Workers POST straight to
                .containsEntry("METRICS_INGEST_URL", "http://metrics-consumer:8083/api/v1/ingest")
                .doesNotContainKey("SCHEMA_REGISTRY_URL")
                .containsEntry("GRACE_PERIOD_SECONDS", "10")
                .containsEntry("METRICS_INGEST_AUTH", "Bearer testToken")
                .containsEntry("ARTIFACT_SOURCE", "DOCUMENT_SERVICE")
                .containsEntry("HTTP_PORT", "8080")
                // SLIMDOWN SL-F — no tracing env at all: the slimmed worker
                // has no OTel exporter, so even the old 0.0 silencer is gone.
                .doesNotContainKey("MANAGEMENT_TRACING_SAMPLING_PROBABILITY")
                .doesNotContainKey("MANAGEMENT_OTLP_TRACING_ENDPOINT")
                .doesNotContainKey("OTEL_RESOURCE_ATTRIBUTES");
    }

    @Test
    @DisplayName("createAndStart is idempotent on podName — an existing live pod is reused, not rejected")
    void createAndStartIdempotent() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        ProvisionResult again = provisioner.createAndStart(spec("payments-us-east-1-worker-1"));

        assertThat(again.baseUrl()).isEqualTo("http://payments-us-east-1-worker-1.workers:8080");
        assertThat(client.pods().inNamespace(NS).list().getItems()).hasSize(1);
    }

    @Test
    @DisplayName("stopAndRemove deletes the pod; no-op when already gone")
    void stopAndRemoveDeletes() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        provisioner.stopAndRemove("payments-us-east-1-worker-1");

        assertThat(provisioner.exists("payments-us-east-1-worker-1")).isFalse();
        // Second call — must not throw.
        provisioner.stopAndRemove("payments-us-east-1-worker-1");
    }

    @Test
    @DisplayName("exists / isRunning — phase Running maps to running; Pending pod exists but is not running")
    void existsAndIsRunning() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        assertThat(provisioner.exists("payments-us-east-1-worker-1")).isTrue();
        // CRUD mock stamps no status — treated as not-Running (created,
        // not yet started).
        assertThat(provisioner.isRunning("payments-us-east-1-worker-1")).isFalse();

        markPhase("payments-us-east-1-worker-1", "Running");
        assertThat(provisioner.isRunning("payments-us-east-1-worker-1")).isTrue();

        assertThat(provisioner.exists("no-such-pod")).isFalse();
        assertThat(provisioner.isRunning("no-such-pod")).isFalse();
    }

    @Test
    @DisplayName("listFor filters by group (+ optional region) server-side and maps phases to status strings")
    void listForFiltersAndMaps() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        provisioner.createAndStart(new PodSpec("payments-us-west-2-worker-1", "cps", "us-west-2"));
        // A pod belonging to another group must never appear.
        provisioner.createAndStart(new PodSpec("search-us-east-1-worker-1", "demo", "us-east-1"));
        markPhase("payments-us-east-1-worker-1", "Running");
        markPhase("payments-us-west-2-worker-1", "Failed");

        List<ProvisionedPod> all = provisioner.listFor("cps", null);
        assertThat(all).extracting(ProvisionedPod::podName)
                .containsExactlyInAnyOrder("payments-us-east-1-worker-1", "payments-us-west-2-worker-1");
        assertThat(all).extracting(ProvisionedPod::status)
                .containsExactlyInAnyOrder("running", "exited");

        List<ProvisionedPod> east = provisioner.listFor("cps", "us-east-1");
        assertThat(east).singleElement()
                .satisfies(p -> {
                    assertThat(p.podName()).isEqualTo("payments-us-east-1-worker-1");
                    assertThat(p.region()).isEqualTo("us-east-1");
                    assertThat(p.groupId()).isEqualTo("cps");
                    assertThat(p.imageDigest()).isEqualTo("jmeter-local-orchestrator:dev");
                });
    }

    @Test
    @DisplayName("currentImageDigest returns the configured image reference (config-rollout recycle semantics)")
    void currentImageDigestIsConfigured() {
        assertThat(provisioner.currentImageDigest()).isEqualTo("jmeter-local-orchestrator:dev");
    }

    @Test
    @DisplayName("restart recreates the pod from its own labels; missing pod throws")
    void restartRecreates() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        markPhase("payments-us-east-1-worker-1", "Running");

        provisioner.restart("payments-us-east-1-worker-1");

        Pod recreated = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-1").get();
        assertThat(recreated).isNotNull();
        assertThat(recreated.getStatus() == null || recreated.getStatus().getPhase() == null)
                .as("recreated pod is a fresh object (no carried-over status)")
                .isTrue();
        assertThat(recreated.getMetadata().getLabels())
                .containsEntry(ProvisionerProperties.LABEL_REGION, "us-east-1");

        assertThatThrownBy(() -> provisioner.restart("no-such-pod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("start no-ops on a live pod, recreates a terminal one, throws on a missing one")
    void startSemantics() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        markPhase("payments-us-east-1-worker-1", "Running");
        provisioner.start("payments-us-east-1-worker-1"); // live → no-op
        assertThat(client.pods().inNamespace(NS).list().getItems()).hasSize(1);

        markPhase("payments-us-east-1-worker-1", "Failed");
        provisioner.start("payments-us-east-1-worker-1"); // terminal → recreate
        Pod recreated = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-1").get();
        assertThat(recreated.getStatus() == null || recreated.getStatus().getPhase() == null).isTrue();

        assertThatThrownBy(() -> provisioner.start("no-such-pod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("createAndStart on a terminal pod recreates it (a terminal pod cannot restart in place)")
    void createAndStartRecreatesTerminalPod() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        markPhase("payments-us-east-1-worker-1", "Failed");

        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));

        Pod pod = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-1").get();
        assertThat(pod.getStatus() == null || pod.getStatus().getPhase() == null)
                .as("terminal pod was recreated fresh")
                .isTrue();
    }

    @Test
    @DisplayName("listWorkers / workerState read the kubelet's verdict: ready, OOMKilled + exit code, Unschedulable")
    void livenessFromPodStatus() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        provisioner.createAndStart(spec("payments-us-east-1-worker-2"));
        provisioner.createAndStart(spec("payments-us-east-1-worker-3"));
        Pod ready = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-1").get();
        client.pods().inNamespace(NS).resource(new PodBuilder(ready).editOrNewStatus().withPhase("Running")
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .addNewContainerStatus().withName("local-orchestrator").withRestartCount(0).withReady(true)
                    .withNewState().withNewRunning().endRunning().endState().endContainerStatus()
                .endStatus().build()).update();
        Pod oom = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-2").get();
        client.pods().inNamespace(NS).resource(new PodBuilder(oom).editOrNewStatus().withPhase("Failed")
                .addNewContainerStatus().withName("local-orchestrator").withRestartCount(0)
                    .withNewState().withNewTerminated().withReason("OOMKilled").withExitCode(137).endTerminated().endState()
                .endContainerStatus().endStatus().build()).update();
        Pod pending = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-3").get();
        client.pods().inNamespace(NS).resource(new PodBuilder(pending).editOrNewStatus().withPhase("Pending")
                .addNewCondition().withType("PodScheduled").withStatus("False").withReason("Unschedulable")
                    .withMessage("0/1 nodes are available: 1 Insufficient memory.").endCondition()
                .endStatus().build()).update();

        List<WorkerState> all = provisioner.listWorkers();
        assertThat(all).extracting(WorkerState::podName)
                .containsExactlyInAnyOrder("payments-us-east-1-worker-1", "payments-us-east-1-worker-2", "payments-us-east-1-worker-3");
        WorkerState w1 = provisioner.workerState("payments-us-east-1-worker-1").orElseThrow();
        assertThat(w1.ready()).isTrue();
        assertThat(w1.dead()).isFalse();
        WorkerState w2 = provisioner.workerState("payments-us-east-1-worker-2").orElseThrow();
        assertThat(w2.dead()).isTrue();
        assertThat(w2.reason()).isEqualTo("OOMKilled");
        assertThat(w2.exitCode()).isEqualTo(137);
        WorkerState w3 = provisioner.workerState("payments-us-east-1-worker-3").orElseThrow();
        assertThat(w3.dead()).isTrue();
        assertThat(w3.reason()).isEqualTo("Unschedulable");
        assertThat(w3.message()).contains("Insufficient memory");
        assertThat(provisioner.workerState("no-such-pod")).isEmpty();
    }

    private void markPhase(String podName, String phase) {
        Pod pod = client.pods().inNamespace(NS).withName(podName).get();
        Pod updated = new PodBuilder(pod).editOrNewStatus().withPhase(phase).endStatus().build();
        client.pods().inNamespace(NS).resource(updated).update();
    }

    // ── PRIVATE-CLOUD-ALIGNMENT Track 8 ─────────────────────────────────

    private static ProvisionerProperties hosted(WorkerPodShape shape) {
        return new ProvisionerProperties(NS, "workers", "registry.example.test/c1/jmeter-local-orchestrator@sha256:0", 8080,
                "https://metrics-consumer.example.test/api/v1/ingest", "https://document-service.example.test",
                6144, "500m", 10, "-Xms2g -Xmx2g", "Bearer t", shape);
    }

    @Test
    @DisplayName("the local shape: cpu request + memory only, no SA token, no security context, three probes")
    void localShapeAndProbes() {
        provisioner.createAndStart(spec("payments-us-east-1-worker-1"));
        Pod pod = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-1").get();
        var c = pod.getSpec().getContainers().get(0);
        assertThat(c.getResources().getRequests()).containsOnlyKeys("memory", "cpu");
        assertThat(c.getResources().getLimits()).containsOnlyKeys("memory");
        assertThat(pod.getSpec().getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getSpec().getSecurityContext()).isNull();
        assertThat(pod.getSpec().getImagePullSecrets()).isEmpty();
        assertThat(c.getStartupProbe().getHttpGet().getPath()).isEqualTo("/actuator/health");
        assertThat(c.getReadinessProbe().getHttpGet().getPath()).isEqualTo("/actuator/health");
        assertThat(c.getLivenessProbe().getHttpGet().getPath()).isEqualTo("/actuator/keepalive");
        assertThat(c.getStartupProbe().getFailureThreshold()).isEqualTo(36);
        assertThat(c.getEnv().stream().map(e -> e.getName())).doesNotContain("JAVA_OPTS");
    }

    @Test
    @DisplayName("the hosted shape: cpu limit, ephemeral-storage request == limit, pull secret, security context, extra labels, JAVA_OPTS")
    void hostedShape() {
        WorkerPodShape shape = new WorkerPodShape(true, "2", "10Gi", "workers-sa", "regcred", 214818L, 99L, 99L,
                java.util.Map.of("app", "jmeter-worker"), "-Xmx1g -XX:+UseG1GC");
        provisioner = new K8sPodProvisioner(client, hosted(shape));
        provisioner.createAndStart(spec("payments-us-east-1-worker-2"));
        Pod pod = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-2").get();
        var c = pod.getSpec().getContainers().get(0);
        assertThat(c.getResources().getLimits().get("cpu").toString()).isEqualTo("2");
        assertThat(c.getResources().getRequests().get("ephemeral-storage")).isEqualTo(c.getResources().getLimits().get("ephemeral-storage"));
        assertThat(c.getResources().getLimits().get("ephemeral-storage").toString()).isEqualTo("10Gi");
        assertThat(pod.getSpec().getServiceAccountName()).isEqualTo("workers-sa");
        assertThat(pod.getSpec().getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getSpec().getImagePullSecrets()).extracting("name").containsExactly("regcred");
        assertThat(pod.getSpec().getSecurityContext().getRunAsUser()).isEqualTo(214818L);
        assertThat(pod.getSpec().getSecurityContext().getRunAsNonRoot()).isTrue();
        assertThat(pod.getMetadata().getLabels()).containsEntry("app", "jmeter-worker")
                .containsEntry(ProvisionerProperties.LABEL_MANAGED_BY, "regional-orchestrator");
        assertThat(c.getEnv().stream().filter(e -> e.getName().equals("JAVA_OPTS")).findFirst().orElseThrow().getValue())
                .isEqualTo("-Xmx1g -XX:+UseG1GC");
    }

    @Test
    @DisplayName("a hard-zero quota shape omits cpu and memory entirely")
    void hardZeroQuotaShape() {
        WorkerPodShape shape = new WorkerPodShape(false, null, "1Gi", null, null, null, null, null, java.util.Map.of(), null);
        provisioner = new K8sPodProvisioner(client, hosted(shape));
        provisioner.createAndStart(spec("payments-us-east-1-worker-3"));
        var c = client.pods().inNamespace(NS).withName("payments-us-east-1-worker-3").get().getSpec().getContainers().get(0);
        assertThat(c.getResources().getRequests()).containsOnlyKeys("ephemeral-storage");
        assertThat(c.getResources().getLimits()).containsOnlyKeys("ephemeral-storage");
    }

    @Test
    @DisplayName("capacity: the tightest quota dimension bounds workersFree; an exhausted quota refuses the spin before the API is asked")
    void capacityGuard() {
        assertThat(provisioner.capacity()).isEqualTo(NamespaceCapacity.UNBOUNDED);
        // pods: 3 of 5 used → 2 free; memory: 20 GiB hard, 2 GiB used → 18 GiB → 3 workers of 6 GiB; cpu unbounded.
        client.resourceQuotas().inNamespace(NS).resource(new io.fabric8.kubernetes.api.model.ResourceQuotaBuilder()
                .withNewMetadata().withName("default").endMetadata()
                .withNewStatus()
                    .addToHard("pods", new io.fabric8.kubernetes.api.model.Quantity("5"))
                    .addToHard("requests.memory", new io.fabric8.kubernetes.api.model.Quantity("20Gi"))
                    .addToUsed("pods", new io.fabric8.kubernetes.api.model.Quantity("3"))
                    .addToUsed("requests.memory", new io.fabric8.kubernetes.api.model.Quantity("2Gi"))
                .endStatus().build()).create();
        NamespaceCapacity c = provisioner.capacity();
        assertThat(c.podsFree()).isEqualTo(2);
        assertThat(c.memoryFreeMi()).isEqualTo(18L * 1024);
        assertThat(c.cpuFreeMillis()).isNull();
        assertThat(c.workersFree()).isEqualTo(2);
        provisioner.createAndStart(spec("payments-us-east-1-worker-4"));   // fits

        client.resourceQuotas().inNamespace(NS).withName("default").edit(q -> new io.fabric8.kubernetes.api.model.ResourceQuotaBuilder(q)
                .editStatus().addToUsed("pods", new io.fabric8.kubernetes.api.model.Quantity("5")).endStatus().build());
        assertThat(provisioner.capacity().workersFree()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provisioner.createAndStart(spec("payments-us-east-1-worker-5")))
                .isInstanceOf(CapacityExhaustedException.class)
                .hasMessageContaining("pods=0");
        assertThat(client.pods().inNamespace(NS).withName("payments-us-east-1-worker-5").get()).isNull();
        client.resourceQuotas().inNamespace(NS).withName("default").delete();
    }
}
