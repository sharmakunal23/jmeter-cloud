package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * D-AppRegistry — registered application with operator-managed metadata
 * + last health-check snapshot. Persisted in
 * {@code ORCH_APPLICATION}; polled by
 * {@code ApplicationHealthPoller} every minute when {@code healthEndpoints}
 * is non-empty. Capacity and the recycle policy are the group's
 * ({@link ApplicationGroup}), not the application's.
 *
 * <p>{@code lastHealthDetails} is a list of per-endpoint result maps
 * (keys: url, statusCode, latencyMs, error, ok) — flat JSON so we don't
 * couple the domain to a poll-result record (see HealthCheckResult).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Application(
        String applicationId,
        String name,
        String sealId,
        String description,
        List<String> healthEndpoints,
        Instant createdAt,
        Instant lastHealthCheckedAt,
        HealthStatus lastHealthStatus,
        List<Map<String, Object>> lastHealthDetails,
        /**
         * The {@link ApplicationGroup} the app belongs to — required: its
         * workers POST metrics with {@code ?groupId=} set to this, and the
         * group's worker pool (capacity, pods, recycle policy) is what its
         * runs draw on (GROUP-CAPACITY, 2026-08-31).
         */
        String metricsGroupId,
        /**
         * The value the group's label classifier assigns to this app's labels
         * ({@code LABEL.APPLICATION} in the metrics schema, e.g. {@code CPS-PCI});
         * how a group's rows are faceted back to this app. Defaults to the
         * upper-cased name when a group is set.
         */
        String metricsApplication) {



    public Application {
        healthEndpoints = healthEndpoints == null ? List.of() : List.copyOf(healthEndpoints);
        lastHealthDetails = lastHealthDetails == null ? null : List.copyOf(lastHealthDetails);
    }

    /** Aggregate health status for an application. */
    public enum HealthStatus {
        /** All configured endpoints returned 2xx within the latency cap. */
        HEALTHY,
        /** At least one endpoint failed; at least one succeeded. */
        DEGRADED,
        /** Every configured endpoint failed. */
        UNHEALTHY,
        /** No endpoints configured, or never polled yet. */
        UNKNOWN
    }
}
