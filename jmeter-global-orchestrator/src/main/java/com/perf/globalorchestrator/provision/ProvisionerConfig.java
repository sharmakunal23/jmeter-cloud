package com.perf.globalorchestrator.provision;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
            @Value("${globalOrchestrator.podProvisioner.image:jmeter-local-orchestrator:dev}")    String image,
            @Value("${globalOrchestrator.podProvisioner.localOrchestratorPort:8080}")             int    localOrchestratorPort,
            @Value("${globalOrchestrator.podProvisioner.globalOrchestratorUrl:http://global-orchestrator:8082}") String globalUrl,
            @Value("${globalOrchestrator.podProvisioner.kafkaBrokers:kafka:29092}")               String kafkaBrokers,
            @Value("${globalOrchestrator.podProvisioner.schemaRegistryUrl:http://schema-registry:8081}") String schemaRegistryUrl,
            @Value("${globalOrchestrator.podProvisioner.documentServiceUrl:http://document-service:8084}") String documentServiceUrl,
            @Value("${globalOrchestrator.podProvisioner.kafkaTopic:jmeter.metrics.perSecond}")    String kafkaTopic,
            // OBSERVABILITY Phase B — tracing knobs threaded into each spawned
            // local-orch container. Strings (not double / URI) so an operator
            // override via PODPROVISIONER_* env wins without any parse step.
            @Value("${globalOrchestrator.podProvisioner.tracingSamplingProbability:0.01}")        String tracingSamplingProbability,
            @Value("${globalOrchestrator.podProvisioner.otlpTracingEndpoint:http://jaeger:4318/v1/traces}") String otlpTracingEndpoint,
            // Reliability (2026-05-27) — hard Docker memory limit per spawned
            // worker. 4 GiB fits the
            // orchestrator JVM (-Xmx1g) + JMeter child (-Xmx1g) + ~1 GiB native
            // + ~1 GiB page-cache headroom for the multi-GB JTL of a 12 h run.
            @Value("${globalOrchestrator.podProvisioner.workerMemoryMb:4096}")                    long   workerMemoryMb,
            // RELIABILITY Round 8 — aggregator grace (seconds) stamped onto each
            // spawned worker as GRACE_PERIOD_SECONDS. 10s captures slow samples
            // written to the JTL after their start second instead of dropping
            // them as late; per-run POST /test override still wins.
            @Value("${globalOrchestrator.podProvisioner.gracePeriodSeconds:10}")                  int    gracePeriodSeconds) {
        return new ProvisionerProperties(
                dockerHost, network, image, localOrchestratorPort,
                globalUrl, kafkaBrokers, schemaRegistryUrl, documentServiceUrl, kafkaTopic,
                tracingSamplingProbability, otlpTracingEndpoint, workerMemoryMb, gracePeriodSeconds);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionerConfig.class);

    @Bean(destroyMethod = "close")
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
}
