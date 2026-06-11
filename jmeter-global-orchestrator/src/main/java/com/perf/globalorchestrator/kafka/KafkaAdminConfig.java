package com.perf.globalorchestrator.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.Properties;

/**
 * KAFKA-PER-APP Phase B — exposes a singleton {@link AdminClient} bean for
 * {@link KafkaTopicProvisioner}. Bootstrap servers come from the existing
 * {@code KAFKA_BROKERS_INTERNAL} env var (already set in the global-orch's
 * compose service so the {@code PodProvisioner} can pass it through to
 * spawned worker pods).
 *
 * <p>Configured for fast-fail: 0 retries, the per-call timeout is owned by
 * {@link KafkaTopicProvisioner} via {@code adminTimeoutMs} so the request
 * thread never hangs longer than the operator's tolerance for app-create
 * latency.
 */
@Configuration
public class KafkaAdminConfig {

    @Bean(destroyMethod = "close")
    @Lazy
    public AdminClient adminClient(
            @Value("${globalOrchestrator.kafka.brokers:${KAFKA_BROKERS_INTERNAL:kafka:29092}}")
            String bootstrapServers,
            @Value("${globalOrchestrator.kafka.adminTimeoutMs:15000}") int requestTimeoutMs) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        // No client-side retries — we surface failures fast so the controller
        // can roll back the application row instead of holding the request
        // thread on a slow-failing broker.
        p.put(AdminClientConfig.RETRIES_CONFIG, 0);
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, "globalOrchestratorTopicAdmin");
        return AdminClient.create(p);
    }
}
