package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The dry-run half of cluster registration (CLUSTER-CAPACITY): proves this
 * regional can create worker Pods without creating one — the worker image is
 * configured, the ServiceAccount holds the Role's verbs (one
 * {@code SelfSubjectAccessReview} per verb; creating SSARs is granted to every
 * authenticated principal via {@code system:basic-user}, so no extra Role rule
 * is needed), and the namespace quota admits at least one worker. Every
 * failure lands in a check's {@code detail}, never in an HTTP 5xx.
 */
@Component
public class ProvisioningCheckService {

    private final KubernetesClient k8s;
    private final ProvisionerProperties props;
    private final PodProvisioner provisioner;

    public ProvisioningCheckService(KubernetesClient k8s, ProvisionerProperties props, PodProvisioner provisioner) {
        this.k8s = k8s;
        this.props = props;
        this.provisioner = provisioner;
    }

    public List<ProvisioningCheck> run() {
        List<ProvisioningCheck> checks = new ArrayList<>();
        checks.add(imageConfigured());
        checks.add(rbac("rbacPods", "pods", null, List.of("create", "delete", "get", "list", "watch")));
        checks.add(rbac("rbacPodsLog", "pods", "log", List.of("get")));
        checks.add(rbac("rbacResourceQuotas", "resourcequotas", null, List.of("get", "list")));
        checks.add(quotaHeadroom());
        return checks;
    }

    private ProvisioningCheck imageConfigured() {
        String image = props.image();
        boolean ok = image != null && !image.isBlank();
        return new ProvisioningCheck("imageConfigured", ok,
                ok ? image : "PODPROVISIONER_IMAGE is not set — this regional cannot create workers");
    }

    private ProvisioningCheck rbac(String name, String resource, String subresource, List<String> verbs) {
        StringJoiner denied = new StringJoiner(", ");
        try {
            for (String verb : verbs) {
                SelfSubjectAccessReview review = new SelfSubjectAccessReviewBuilder()
                        .withNewSpec()
                            .withNewResourceAttributes()
                                .withNamespace(props.namespace())
                                .withVerb(verb)
                                .withResource(resource)
                                .withSubresource(subresource)
                            .endResourceAttributes()
                        .endSpec()
                        .build();
                SelfSubjectAccessReview answer = k8s.authorization().v1().selfSubjectAccessReview().create(review);
                boolean allowed = answer != null && answer.getStatus() != null
                        && Boolean.TRUE.equals(answer.getStatus().getAllowed());
                if (!allowed) denied.add(verb);
            }
        } catch (RuntimeException e) {
            return new ProvisioningCheck(name, false, "access review failed: " + e.getMessage());
        }
        String target = subresource == null ? resource : resource + "/" + subresource;
        return denied.length() == 0
                ? new ProvisioningCheck(name, true, target + ": " + String.join(", ", verbs) + " allowed")
                : new ProvisioningCheck(name, false,
                        "ServiceAccount lacks " + target + " verbs: " + denied + " in namespace " + props.namespace());
    }

    private ProvisioningCheck quotaHeadroom() {
        NamespaceCapacity c;
        try {
            c = provisioner.capacity();
        } catch (RuntimeException e) {
            return new ProvisioningCheck("quotaHeadroom", false, "quota read failed: " + e.getMessage());
        }
        if (c.workersFree() == null) {
            return new ProvisioningCheck("quotaHeadroom", true,
                    "namespace " + props.namespace() + " has no quota bounding workers");
        }
        String numbers = c.workersFree() + " worker(s) fit — pods=" + c.podsFree()
                + ", memoryMi=" + c.memoryFreeMi() + ", cpuMillis=" + c.cpuFreeMillis()
                + ", ephemeralMi=" + c.ephemeralFreeMi();
        return new ProvisioningCheck("quotaHeadroom", c.workersFree() >= 1, numbers);
    }
}
