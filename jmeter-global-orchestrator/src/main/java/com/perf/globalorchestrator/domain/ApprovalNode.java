package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parks the branch until an operator approves or rejects it. A reject fails the
 * task, so an {@code ON_FAILURE} edge is how a workflow offers "not now" a path.
 *
 * @param deadlineMinutes fail the task if nobody answers within this many
 *                        minutes; null waits indefinitely, and the execution
 *                        stays RUNNING (and claimable) the whole time
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalNode(
        String id,
        String name,
        NodePosition position,
        JoinPolicy joinPolicy,
        String instructions,
        Integer deadlineMinutes) implements WorkflowNode {

    public ApprovalNode {
        joinPolicy = joinPolicy == null ? JoinPolicy.ALL : joinPolicy;
    }

    @Override public NodeType type() { return NodeType.APPROVAL; }
}
