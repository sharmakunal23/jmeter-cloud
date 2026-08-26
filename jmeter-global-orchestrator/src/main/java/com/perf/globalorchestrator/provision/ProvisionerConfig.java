package com.perf.globalorchestrator.provision;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Wires the Phase 1 provisioner beans:
 * <ul>
 *   <li>{@link ProvisionerProperties} — env-var-driven config bundle.</li>
 *   <li>{@link DockerClient} — docker-java client over the mounted socket.
 *       The connection is lazy, so this bean is safe to construct in tests
 *       that don't have docker.sock mounted.</li>
 * </ul>
 *
 * <p>{@link DockerSocketPodProvisioner} is a {@code @Component} and picks
 * up these beans by constructor injection.
 */
@Configuration
public class ProvisionerConfig {

    @Bean
    public ProvisionerProperties provisionerProperties(
            @Value("${globalOrchestrator.podProvisioner.dockerHost:unix:///var/run/docker.sock}") String dockerHost,
            @Value("${globalOrchestrator.podProvisioner.network:jmeter-cloud_default}")           String network,
            // KUBE-5 Option A — K8s-substrate settings; ignored on docker.
            // Namespace + headless-Service names are DNS-1123 (exemption).
            @Value("${globalOrchestrator.podProvisioner.namespace:jmeter-cloud}")                 String namespace,
            @Value("${globalOrchestrator.podProvisioner.headlessService:workers}")                String headlessService,
            @Value("${globalOrchestrator.podProvisioner.image:jmeter-local-orchestrator:dev}")    String image,
            @Value("${globalOrchestrator.podProvisioner.localOrchestratorPort:8080}")             int    localOrchestratorPort,
            @Value("${globalOrchestrator.podProvisioner.globalOrchestratorUrl:http://global-orchestrator:8082}") String globalUrl,
            // Workers POST straight to the metrics-consumer's ingest endpoint.
            @Value("${globalOrchestrator.podProvisioner.metricsIngestUrl:http://metrics-consumer:8083/api/v1/ingest}") String metricsIngestUrl,
            @Value("${globalOrchestrator.podProvisioner.documentServiceUrl:http://document-service:8084}") String documentServiceUrl,
            // Reliability (2026-05-27) — hard Docker memory limit per spawned
            // worker. 4 GiB fits the
            // orchestrator JVM (-Xmx1g) + JMeter child (-Xmx1g) + ~1 GiB native
            // + ~1 GiB page-cache headroom for the multi-GB JTL of a 12 h run.
            @Value("${globalOrchestrator.podProvisioner.workerMemoryMb:4096}")                    long   workerMemoryMb,
            // K8s substrate — CPU request per worker (no limit; throttling a
            // load generator skews its measurements). 500m schedules two
            // workers per core locally; size up in cloud.
            @Value("${globalOrchestrator.podProvisioner.workerCpuRequest:500m}")                  String workerCpuRequest,
            // Aggregator grace (seconds) stamped onto each
            // spawned worker as GRACE_PERIOD_SECONDS. 10s captures slow samples
            // written to the JTL after their start second instead of dropping
            // them as late; per-run POST /test override still wins.
            @Value("${globalOrchestrator.podProvisioner.gracePeriodSeconds:10}")                  int    gracePeriodSeconds) {
        return new ProvisionerProperties(
                dockerHost, network, namespace, headlessService,
                image, localOrchestratorPort,
                globalUrl, metricsIngestUrl, documentServiceUrl,
                workerMemoryMb, workerCpuRequest, gracePeriodSeconds);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionerConfig.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
    @ConditionalOnProperty(name = "globalOrchestrator.podProvisioner.substrate",
                           havingValue = "docker", matchIfMissing = true)
    public DockerClient dockerClient(ProvisionerProperties props) {
        // Build the URI explicitly and pass it through both the DockerClientConfig
        // and the ApacheDockerHttpClient builder. docker-java's
        // createDefaultConfigBuilder() reads ambient env (DOCKER_HOST, the
        // ~/.docker/config.json metadata file) and can silently override
        // .withDockerHost(String) — passing a Properties map + explicit URI to
        // the http client too is what actually wins.
        URI dockerHost = URI.create(props.dockerHost());
        LOG.info("Configuring DockerClient with dockerHost={}", dockerHost);

        java.util.Properties dockerProps = new java.util.Properties();
        dockerProps.setProperty("DOCKER_HOST",       props.dockerHost());
        dockerProps.setProperty("DOCKER_TLS_VERIFY", "");
        dockerProps.setProperty("DOCKER_CERT_PATH",  "");

        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withProperties(dockerProps)
                .withDockerHost(props.dockerHost())
                .build();

        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(dockerHost)
                .sslConfig(clientConfig.getSSLConfig())
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    /**
     * KUBE-5 Option A — fabric8 client for {@code substrate=k8s} (the
     * private-cloud deployment). No explicit master URL / auth on purpose:
     * fabric8's default Config resolution handles both modes (in-cluster
     * ServiceAccount token + CA when running as a Deployment, the
     * developer's kubeconfig current-context on a host) and an operator can
     * still override via the standard KUBERNETES_MASTER / KUBECONFIG env
     * vars. The client doesn't touch the network until the first API call,
     * so the bean constructs cleanly with no cluster reachable.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProvisioningMode(ProvisioningMode.DYNAMIC)
    @ConditionalOnProperty(name = "globalOrchestrator.podProvisioner.substrate",
                           havingValue = "k8s")
    public io.fabric8.kubernetes.client.KubernetesClient kubernetesClient(ProvisionerProperties props) {
        io.fabric8.kubernetes.client.KubernetesClient client =
                new io.fabric8.kubernetes.client.KubernetesClientBuilder().build();
        LOG.info("Configured KubernetesClient master={} podNamespace={}",
                client.getMasterUrl(), props.namespace());
        return client;
    }
}
