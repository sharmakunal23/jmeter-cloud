package com.perf.k8sorchestrator.provision;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the provisioner beans:
 * <ul>
 *   <li>{@link ProvisionerProperties} — env-var-driven config bundle.</li>
 *   <li>{@link KubernetesClient} — fabric8 client. Configuration is
 *       auto-detected: in-cluster ServiceAccount when running as a
 *       Deployment (the normal mode, K8S-ORCHESTRATOR D-9), kubeconfig
 *       ({@code ~/.kube/config} / {@code $KUBECONFIG}) when run on a
 *       developer host. Connections are lazy, so this bean constructs
 *       cleanly in tests with no cluster reachable.</li>
 * </ul>
 *
 * <p>{@link K8sPodProvisioner} is a {@code @Component} and picks up these
 * beans by constructor injection.
 */
@Configuration
public class ProvisionerConfig {

    @Bean
    public ProvisionerProperties provisionerProperties(
            // Pod namespace + the headless Service that gives workers their
            // {podName}.{service} DNS names (K8S-ORCHESTRATOR D-6). Both are
            // K8s resource names → DNS-1123 lowercase (camelCase exemption).
            @Value("${k8sOrchestrator.podProvisioner.namespace:jmeter-cloud}")                 String namespace,
            @Value("${k8sOrchestrator.podProvisioner.headlessService:workers}")                String headlessService,
            @Value("${k8sOrchestrator.podProvisioner.image:jmeter-local-orchestrator:dev}")    String image,
            @Value("${k8sOrchestrator.podProvisioner.localOrchestratorPort:8080}")             int    localOrchestratorPort,
            @Value("${k8sOrchestrator.podProvisioner.globalOrchestratorUrl:http://jmeter-orchestrator-k8s:8088}") String globalUrl,
            // Workers POST straight to the metrics-consumer.
            @Value("${k8sOrchestrator.podProvisioner.metricsIngestUrl:http://metrics-consumer:8083/api/v1/ingest}") String metricsIngestUrl,
            @Value("${k8sOrchestrator.podProvisioner.documentServiceUrl:http://document-service:8084}") String documentServiceUrl,
            // Hard memory limit per worker Pod (resources.limits.memory).
            // RELIABILITY Round 6 sizing — see ProvisionerProperties javadoc.
            @Value("${k8sOrchestrator.podProvisioner.workerMemoryMb:6144}")                    long   workerMemoryMb,
            // CPU request per worker; request-only (no limit — throttling a
            // load generator skews its measurements). 500m schedules two
            // workers per core locally; size up in cloud.
            @Value("${k8sOrchestrator.podProvisioner.workerCpuRequest:500m}")                  String workerCpuRequest,
            // Aggregator grace (seconds) stamped onto each
            // spawned worker as GRACE_PERIOD_SECONDS. 10s captures slow samples
            // written to the JTL after their start second instead of dropping
            // them as late; per-run POST /test override still wins.
            @Value("${k8sOrchestrator.podProvisioner.gracePeriodSeconds:10}")                  int    gracePeriodSeconds) {
        return new ProvisionerProperties(
                namespace, headlessService, image, localOrchestratorPort,
                globalUrl, metricsIngestUrl, documentServiceUrl,
                workerMemoryMb, workerCpuRequest, gracePeriodSeconds);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionerConfig.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
    public KubernetesClient kubernetesClient(ProvisionerProperties props) {
        // No explicit master URL / auth here on purpose: fabric8's default
        // Config resolution handles both deployment modes (in-cluster SA
        // token + CA, or the developer's kubeconfig current-context) and an
        // operator can still override via the standard KUBERNETES_MASTER /
        // KUBECONFIG env vars. The client doesn't touch the network until
        // the first API call.
        KubernetesClient client = new KubernetesClientBuilder().build();
        LOG.info("Configured KubernetesClient master={} podNamespace={}",
                client.getMasterUrl(), props.namespace());
        return client;
    }
}
