package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * One launch of a workflow — a row of {@code ORCH_WORKFLOW_EXECUTION} plus its
 * tasks. {@code workflowName} and {@code graph} are snapshots: renaming or
 * editing the workflow never rewrites what already ran.
 *
 * <p>{@code nextTickAt} is both the engine's schedule and its claim lease, and
 * is non-null exactly while the state is RUNNING.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowExecution(
        String executionId,
        String workflowId,
        String groupId,
        String workflowName,
        WorkflowGraph graph,
        ExecutionState state,
        String stateReason,
        String triggeredBy,
        Instant startedAt,
        Instant completedAt,
        Instant nextTickAt,
        /** Archived when set — off the default history, still readable by id. */
        Instant hiddenAt,
        List<WorkflowTask> tasks) {

    public WorkflowExecution {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** Pre-archive callers (tests, the engine's own construction). */
    public WorkflowExecution(String executionId, String workflowId, String groupId, String workflowName,
                             WorkflowGraph graph, ExecutionState state, String stateReason, String triggeredBy,
                             Instant startedAt, Instant completedAt, Instant nextTickAt,
                             List<WorkflowTask> tasks) {
        this(executionId, workflowId, groupId, workflowName, graph, state, stateReason, triggeredBy,
                startedAt, completedAt, nextTickAt, null, tasks);
    }

    public WorkflowExecution withTasks(List<WorkflowTask> rows) {
        return new WorkflowExecution(executionId, workflowId, groupId, workflowName, graph, state,
                stateReason, triggeredBy, startedAt, completedAt, nextTickAt, hiddenAt, rows);
    }
}
