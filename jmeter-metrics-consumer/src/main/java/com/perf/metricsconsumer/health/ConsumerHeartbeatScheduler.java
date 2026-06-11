package com.perf.metricsconsumer.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodic INFO heartbeat — emits one line every
 * {@code metricsConsumer.heartbeat.intervalMs} (default 30 s) so the
 * operator can confirm at a glance from the container logs that the
 * consumer is alive and processing. Lines look like:
 *
 * <pre>
 * Heartbeat: lastBatchAgeSeconds=2.34, totalBatches=12345, totalRows=987654
 * </pre>
 *
 * <p>If this line stops appearing in the logs, the consumer is wedged
 * (deadlocked, OOM-pending, etc) — independent of whether actual
 * batches are arriving from Kafka. Pairs with the
 * {@link ConsumerHeartbeatHealthIndicator} which translates "no batch
 * processed in N seconds" into a DOWN health status.
 */
@Component
public class ConsumerHeartbeatScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerHeartbeatScheduler.class);

    private final ConsumerHeartbeat heartbeat;

    public ConsumerHeartbeatScheduler(ConsumerHeartbeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    @Scheduled(fixedRateString = "${metricsConsumer.heartbeat.intervalMs:30000}",
               initialDelayString = "${metricsConsumer.heartbeat.intervalMs:30000}")
    public void heartbeat() {
        Duration age = heartbeat.ageSinceLastBatch();
        LOG.info("Heartbeat: lastBatchAgeSeconds={}, totalBatches={}, totalRows={}",
                String.format("%.2f", age.toMillis() / 1000.0),
                heartbeat.totalBatchesProcessed(),
                heartbeat.totalRowsProcessed());
    }
}
