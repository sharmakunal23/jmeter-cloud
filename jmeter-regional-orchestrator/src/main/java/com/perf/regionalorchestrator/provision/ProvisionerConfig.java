package com.perf.regionalorchestrator.provision;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link RegionalProperties}, {@link ProvisionerProperties} and the
 * fabric8 {@link KubernetesClient} that {@link K8sPodProvisioner} drives.
 *
 * <p>The client has no explicit master URL or auth on purpose: fabric8's default
 * resolution covers the in-cluster ServiceAccount and a developer kubeconfig,
 * and {@code KUBERNETES_MASTER} / {@code KUBECONFIG} still override. It touches
 * the network only on the first API call, so the bean constructs with no
 * cluster reachable.
 */
@Configuration
public class ProvisionerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionerConfig.class);

    @Bean
    public RegionalProperties regionalProperties(
            @Value("${regionalOrchestrator.region:}") String region) {
        return new RegionalProperties(region);
    }

    @Bean
    public ProvisionerProperties provisionerProperties(
            // Namespace + headless-Service names are DNS-1123 (camelCase exemption).
            @Value("${regionalOrchestrator.podProvisioner.namespace:jmeter-cloud}")                 String namespace,
            @Value("${regionalOrchestrator.podProvisioner.headlessService:workers}")                String headlessService,
            @Value("${regionalOrchestrator.podProvisioner.image:jmeter-local-orchestrator:dev}")    String image,
            @Value("${regionalOrchestrator.podProvisioner.localOrchestratorPort:8080}")             int    localOrchestratorPort,
            @Value("${regionalOrchestrator.podProvisioner.metricsIngestUrl:http://metrics-consumer:8083/api/v1/ingest}") String metricsIngestUrl,
            @Value("${regionalOrchestrator.podProvisioner.documentServiceUrl:http://document-service:8084}") String documentServiceUrl,
            @Value("${regionalOrchestrator.podProvisioner.workerMemoryMb:6144}")                    long   workerMemoryMb,
            @Value("${regionalOrchestrator.podProvisioner.workerCpuRequest:500m}")                  String workerCpuRequest,
            @Value("${regionalOrchestrator.podProvisioner.gracePeriodSeconds:10}")                  int    gracePeriodSeconds,
            @Value("${regionalOrchestrator.podProvisioner.jmeterJvmArgs:}")                          String jmeterJvmArgs) {
        return new ProvisionerProperties(
                namespace, headlessService, image, localOrchestratorPort,
                metricsIngestUrl, documentServiceUrl,
                workerMemoryMb, workerCpuRequest, gracePeriodSeconds,
                jmeterJvmArgs == null || jmeterJvmArgs.isBlank() ? null : jmeterJvmArgs.trim());
    }

    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient(ProvisionerProperties props, RegionalProperties region) {
        KubernetesClient client = new KubernetesClientBuilder().build();
        LOG.info("Configured KubernetesClient master={} podNamespace={} region={}",
                client.getMasterUrl(), props.namespace(), region.region());
        return client;
    }
}
