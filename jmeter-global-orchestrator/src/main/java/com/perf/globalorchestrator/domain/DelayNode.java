package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Waits {@code seconds} (1..86400) — the settle time between a health gate and
 * the load that follows it. The engine sleeps nothing: the task's {@code dueAt}
 * is when the next tick should look again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DelayNode(
        String id,
        String name,
        NodePosition position,
        JoinPolicy joinPolicy,
        int seconds) implements WorkflowNode {

    public DelayNode {
        joinPolicy = joinPolicy == null ? JoinPolicy.ALL : joinPolicy;
    }

    @Override public NodeType type() { return NodeType.DELAY; }
}
