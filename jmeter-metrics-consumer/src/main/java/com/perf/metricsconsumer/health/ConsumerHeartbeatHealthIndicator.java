package com.perf.metricsconsumer.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Flips {@code /actuator/health} DOWN when no envelope has been ingested for
 * {@code metricsConsumer.heartbeat.staleAfterSeconds} (default 300).
 *
 * <p><b>An idle platform is DOWN by design.</b> Between test runs there is
 * legitimately no traffic, so this contributor — and therefore the aggregate
 * endpoint — reports DOWN with nothing wrong. <b>A Kubernetes liveness probe
 * must never point at aggregate health</b>, or an idle consumer restart-loops;
 * point liveness at a probe group that excludes this contributor. A fresh boot
 * is exempt: the clock starts at boot, not at zero.
 */
// Bean name "ingestProgress": Actuator strips the "HealthIndicator" suffix and
// uses the bean name as the /actuator/health key, so a short stable name keeps
// the JSON predictable for alerting. Not "consumerHeartbeat" — that would clash
// with the auto-named ConsumerHeartbeat bean.
@Component("ingestProgress")
public class ConsumerHeartbeatHealthIndicator implements HealthIndicator {

    private final ConsumerHeartbeat heartbeat;
    private final long staleAfterSeconds;

    public ConsumerHeartbeatHealthIndicator(
            ConsumerHeartbeat heartbeat,
            @Value("${metricsConsumer.heartbeat.staleAfterSeconds:300}") long staleAfterSeconds) {
        this.heartbeat = heartbeat;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    @Override
    public Health health() {
        Duration age = heartbeat.ageSinceLastBatch();
        long ageSec = age.getSeconds();
        Health.Builder builder = ageSec > staleAfterSeconds ? Health.down() : Health.up();
        return builder
                .withDetail("lastBatchAgeSeconds", ageSec)
                .withDetail("staleThresholdSeconds", staleAfterSeconds)
                .withDetail("totalBatchesProcessed", heartbeat.totalBatchesProcessed())
                .withDetail("totalRowsProcessed", heartbeat.totalRowsProcessed())
                .build();
    }
}
