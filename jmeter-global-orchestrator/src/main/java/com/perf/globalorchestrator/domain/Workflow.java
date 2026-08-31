package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * A saved workflow — one row of {@code ORCH_WORKFLOW}, scoped to the
 * application group whose worker pool its load tests draw on.
 *
 * <p>{@code revision} is the optimistic lock: a PUT carrying a stale value is
 * rejected 409, so two operators on one canvas cannot silently overwrite each
 * other.
 *
 * @param lastExecution the most recent execution's summary — hydrated on list
 *                      reads, null when not requested
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Workflow(
        String workflowId,
        String groupId,
        String name,
        String description,
        WorkflowGraph graph,
        boolean enabled,
        int revision,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        WorkflowExecutionSummary lastExecution) {

    public Workflow withLastExecution(WorkflowExecutionSummary summary) {
        return new Workflow(workflowId, groupId, name, description, graph, enabled, revision,
                createdBy, createdAt, updatedBy, updatedAt, summary);
    }
}
