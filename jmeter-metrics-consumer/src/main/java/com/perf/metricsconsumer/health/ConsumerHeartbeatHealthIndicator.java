package com.perf.metricsconsumer.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Spring Boot health contributor that flips DOWN when no Kafka batch has
 * been processed for {@code metricsConsumer.heartbeat.staleAfterSeconds}
 * (default 300 s = 5 min).
 *
 * <p>Hooks into {@code /actuator/health} via Spring Boot's auto-discovery —
 * any non-UP component on the aggregate flips the entire endpoint to DOWN.
 * The compose healthcheck (curl /actuator/health | grep status:UP) then
 * marks the container UNHEALTHY, which downstream automation (k8s liveness
 * probe, ECS health check) can act on.
 *
 * <p>Caveats:
 * <ul>
 *   <li><b>No-traffic window.</b> If the producer side legitimately has no
 *       traffic for > staleAfterSeconds, this flips DOWN even though
 *       nothing's wrong. For a metrics pipeline that's expected to have
 *       continuous traffic during a test run that's the right semantic;
 *       between runs the operator can either tune
 *       {@code staleAfterSeconds} up or accept brief DOWN windows that
 *       won't trigger restart automation (default 5 min is wider than
 *       most idle gaps).</li>
 *   <li><b>Boot grace.</b> {@code lastBatchAtMillis} initialises to the
 *       boot timestamp, so a fresh boot has age=0 and won't flip DOWN
 *       until {@code staleAfterSeconds} have actually elapsed.</li>
 * </ul>
 */
// Bean name "kafkaConsumerProgress" — Spring Boot Actuator strips the
// trailing "HealthIndicator" suffix automatically and uses the bean name
// (or class-name minus suffix) as the /actuator/health key. Picking a
// short stable name here keeps the JSON output predictable for alerting
// rules. Avoid the obvious "consumerHeartbeat" — that clashes with the
// auto-named ConsumerHeartbeat bean.
@Component("kafkaConsumerProgress")
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
