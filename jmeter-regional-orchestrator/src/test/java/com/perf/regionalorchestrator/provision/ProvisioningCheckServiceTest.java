package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registration dry-run against the fabric8 mock server: the SSAR path is
 * exercised over the wire, the quota check through a mocked provisioner.
 */
@EnableKubernetesMockClient
@DisplayName("ProvisioningCheckService — cluster-registration dry-run checks")
class ProvisioningCheckServiceTest {

    private static final String SSAR_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews";

    KubernetesClient client;
    KubernetesMockServer server;

    private static ProvisionerProperties props(String image) {
        return new ProvisionerProperties("jmeter-cloud", "workers", image, 8080,
                "http://metrics-consumer:8083/api/v1/ingest", "http://document-service:8084",
                6144, "500m", 10, null, null);
    }

    private static PodProvisioner capacityOf(NamespaceCapacity c) {
        PodProvisioner p = Mockito.mock(PodProvisioner.class);
        Mockito.when(p.capacity()).thenReturn(c);
        return p;
    }

    private void answerSsar(boolean allowed) {
        SelfSubjectAccessReview answer = new SelfSubjectAccessReviewBuilder()
                .withNewStatus().withAllowed(allowed).endStatus().build();
        server.expect().post().withPath(SSAR_PATH).andReturn(201, answer).always();
    }

    private static Map<String, ProvisioningCheck> byName(List<ProvisioningCheck> checks) {
        return checks.stream().collect(java.util.stream.Collectors.toMap(ProvisioningCheck::name, c -> c));
    }

    @Test
    @DisplayName("everything green: image set, all verbs allowed, quota admits a worker")
    void allChecksPass() {
        answerSsar(true);
        var service = new ProvisioningCheckService(client, props("jmeter-local-orchestrator:dev"),
                capacityOf(new NamespaceCapacity(5, 20480L, null, 25600L, 5)));

        Map<String, ProvisioningCheck> checks = byName(service.run());
        assertThat(checks).containsOnlyKeys("imageConfigured", "rbacPods", "rbacPodsLog", "rbacResourceQuotas", "quotaHeadroom");
        assertThat(checks.values()).allMatch(ProvisioningCheck::ok);
        assertThat(checks.get("rbacPods").detail()).isEqualTo("pods: create, delete, get, list, watch allowed");
        assertThat(checks.get("quotaHeadroom").detail()).contains("5 worker(s) fit").contains("ephemeralMi=25600");
    }

    @Test
    @DisplayName("a denied verb fails the RBAC check and names the missing verbs + namespace")
    void deniedVerbsAreNamed() {
        answerSsar(false);
        var service = new ProvisioningCheckService(client, props("jmeter-local-orchestrator:dev"),
                capacityOf(NamespaceCapacity.UNBOUNDED));

        Map<String, ProvisioningCheck> checks = byName(service.run());
        assertThat(checks.get("rbacPods").ok()).isFalse();
        assertThat(checks.get("rbacPods").detail())
                .isEqualTo("ServiceAccount lacks pods verbs: create, delete, get, list, watch in namespace jmeter-cloud");
        assertThat(checks.get("rbacPodsLog").detail()).contains("pods/log verbs: get");
        assertThat(checks.get("quotaHeadroom").ok())
                .as("no quota bounding workers is a pass, not a failure")
                .isTrue();
    }

    @Test
    @DisplayName("a blank image and an exhausted quota each fail their own check — never an HTTP error")
    void blankImageAndExhaustedQuota() {
        answerSsar(true);
        var service = new ProvisioningCheckService(client, props("  "),
                capacityOf(new NamespaceCapacity(0, 100L, null, null, 0)));

        Map<String, ProvisioningCheck> checks = byName(service.run());
        assertThat(checks.get("imageConfigured").ok()).isFalse();
        assertThat(checks.get("imageConfigured").detail()).contains("PODPROVISIONER_IMAGE");
        assertThat(checks.get("quotaHeadroom").ok()).isFalse();
        assertThat(checks.get("quotaHeadroom").detail()).contains("0 worker(s) fit");
    }

    @Test
    @DisplayName("an access-review transport failure fails the check with the cause, not a 5xx")
    void ssarFailureIsACheckFailure() {
        // 403, not 500: fabric8 retries 5xx with backoff, which only slows the test.
        server.expect().post().withPath(SSAR_PATH).andReturn(403, "forbidden").always();
        var service = new ProvisioningCheckService(client, props("jmeter-local-orchestrator:dev"),
                capacityOf(NamespaceCapacity.UNBOUNDED));

        Map<String, ProvisioningCheck> checks = byName(service.run());
        assertThat(checks.get("rbacPods").ok()).isFalse();
        assertThat(checks.get("rbacPods").detail()).startsWith("access review failed:");
    }
}
