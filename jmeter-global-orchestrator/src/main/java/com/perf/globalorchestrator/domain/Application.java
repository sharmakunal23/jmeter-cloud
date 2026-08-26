package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * D-AppRegistry — registered application with operator-managed metadata
 * + last health-check snapshot. Persisted in
 * {@code globalOrchestrator.application}; polled by
 * {@code ApplicationHealthPoller} every ~30s when {@code healthEndpoints}
 * is non-empty.
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
        /**
         * D-Capacity v2 — per-region capacity ceilings for this app.
         * Empty list means the operator hasn't allocated any capacity
         * yet (run-launch in any region will be rejected). Each entry
         * carries (region, maxAvailable). Lazily populated by
         * {@link com.perf.globalorchestrator.repo.ApplicationRepository}
         * via the joined query — null when not requested.
         */
        List<ApplicationCapacity> capacity,
        Instant createdAt,
        Instant lastHealthCheckedAt,
        HealthStatus lastHealthStatus,
        List<Map<String, Object>> lastHealthDetails,
        /**
         * Pod recycle policy for this app. See
         * {@link RecyclePolicy} for the per-policy threshold rules.
         * Backward-compat default is {@link RecyclePolicy#REUSE}; existing
         * apps pre-Phase-C land here via the V14 column default.
         */
        RecyclePolicy recyclePolicy,
        /** WORKER-HYGIENE Phase C — required for MAX_RUNS / BOTH; null otherwise. */
        Integer maxRunsPerPod,
        /** WORKER-HYGIENE Phase C — required for MAX_AGE / BOTH; null otherwise. */
        Integer podMaxAgeHours,
        /**
         * When true, scheduled DRAIN_REGION jobs SKIP for
         * this app (production-like, never auto-drained). PROVISION_REGION and
         * LAUNCH_RUN are unaffected. V22 column default is false.
         */
        boolean alwaysOn) {

    public Application {
        healthEndpoints = healthEndpoints == null ? List.of() : List.copyOf(healthEndpoints);
        capacity = capacity == null ? null : List.copyOf(capacity);
        lastHealthDetails = lastHealthDetails == null ? null : List.copyOf(lastHealthDetails);
        // Default policy is REUSE — keeps construction sites that don't
        // care about recycle from having to pass null explicitly. The
        // DB-side column already defaults to REUSE, so a row read with
        // null here would be a bug; defensively normalise it.
        recyclePolicy = recyclePolicy == null ? RecyclePolicy.REUSE : recyclePolicy;
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
