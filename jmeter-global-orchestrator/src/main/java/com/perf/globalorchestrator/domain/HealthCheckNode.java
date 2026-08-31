package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Probes one application's configured {@code healthEndpoints} live, rather than
 * reading the poller's snapshot — a workflow gate must reflect the SUT now, not
 * up to a minute ago.
 *
 * <p>Retries span engine ticks: the task stays RUNNING with {@code dueAt} set to
 * the next attempt, so a 10-attempt check never occupies a thread.
 *
 * @param attempts        total tries, 1..10; the first is immediate
 * @param intervalSeconds gap between tries, 5..300
 * @param timeoutSeconds  per-endpoint request timeout, 1..30
 * @param minHealthy      endpoints that must pass when {@code requirement} is AT_LEAST
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthCheckNode(
        String id,
        String name,
        NodePosition position,
        JoinPolicy joinPolicy,
        String application,
        HealthRequirement requirement,
        Integer minHealthy,
        int attempts,
        int intervalSeconds,
        int timeoutSeconds) implements WorkflowNode {

    public HealthCheckNode {
        joinPolicy      = joinPolicy  == null ? JoinPolicy.ALL : joinPolicy;
        requirement     = requirement == null ? HealthRequirement.ALL : requirement;
        attempts        = attempts        == 0 ? 1 : attempts;
        intervalSeconds = intervalSeconds == 0 ? 15 : intervalSeconds;
        timeoutSeconds  = timeoutSeconds  == 0 ? 5 : timeoutSeconds;
    }

    @Override public NodeType type() { return NodeType.HEALTH_CHECK; }

    @Override @JsonIgnore public String applicationName() { return application; }
}
