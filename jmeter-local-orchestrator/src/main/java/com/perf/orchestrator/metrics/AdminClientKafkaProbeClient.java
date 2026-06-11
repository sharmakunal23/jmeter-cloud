package com.perf.orchestrator.metrics;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * {@link KafkaProbeClient} backed by a real {@link AdminClient}.
 *
 * <p>Lightweight on purpose — uses {@link AdminClient#describeCluster} which
 * issues a single metadata fetch against any reachable broker. No batch
 * buffers, no sender thread, no schema registry dependency. Footprint is
 * ~3-5 MB heap + the AdminClient's internal NetworkClient thread.
 *
 * <p>Construction can fail (bad broker URL, auth misconfig). When that
 * happens, the calling probe records a DOWN snapshot with a clear reason
 * and the orchestrator boots normally — the readiness check just stays
 * "DOWN" until the operator fixes the config and restarts. Construction
 * never propagates to caller; an exception here would prevent the
 * orchestrator from starting at all, which is worse than DOWN-and-fix.
 */
public final class AdminClientKafkaProbeClient implements KafkaProbeClient {

    private static final Logger LOG = LoggerFactory.getLogger(AdminClientKafkaProbeClient.class);

    private final AdminClient admin;

    private AdminClientKafkaProbeClient(AdminClient admin) {
        this.admin = admin;
    }

    /**
     * Builds a probe client connected to {@code bootstrapServers}. Returns
     * {@code null} when the AdminClient fails to construct — caller should
     * fall back to a permanently-DOWN probe so {@code /ready} reports the
     * misconfiguration instead of hiding it.
     */
    public static KafkaProbeClient tryCreate(String bootstrapServers, Duration requestTimeout) {
        try {
            Properties p = new Properties();
            p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,    bootstrapServers);
            p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,   (int) requestTimeout.toMillis());
            // Match the request timeout — admin client's own retry budget
            // should not exceed our probe budget, otherwise a failing broker
            // could pin the daemon thread well past the probe interval.
            p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) requestTimeout.toMillis());
            p.put(AdminClientConfig.RETRIES_CONFIG, 0);
            return new AdminClientKafkaProbeClient(AdminClient.create(p));
        } catch (RuntimeException e) {
            LOG.error("Failed to construct Kafka AdminClient for readiness probe (brokers={}): {}",
                    bootstrapServers, e.toString());
            return null;
        }
    }

    @Override
    public Result checkReachable(Duration timeout) {
        try {
            DescribeClusterResult res = admin.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) timeout.toMillis()));
            Collection<?> nodes = res.nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (nodes == null || nodes.isEmpty()) {
                return Result.unreachable("kafka_no_nodes");
            }
            return Result.up();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Result.unreachable("kafka_probe_interrupted");
        } catch (Exception e) {
            // TimeoutException, ExecutionException(KafkaException), etc.
            // Translate uniformly — the probe loop must not see these escape.
            return Result.unreachable("kafka_unreachable");
        }
    }

    @Override
    public void close() {
        // 1s grace then force. The admin client itself uses daemon threads,
        // so leaking briefly during shutdown is harmless, but a clean close
        // releases the network resources promptly.
        admin.close(Duration.ofSeconds(1));
    }
}
