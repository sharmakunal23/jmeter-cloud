package com.perf.globalorchestrator.domain;

/**
 * The workflow task a run was launched for. Passed to {@code RunService.startRun}
 * as its own argument rather than a field of {@code StartRunRequest}, so an API
 * caller cannot claim a run belongs to someone's workflow.
 *
 * <p>{@code taskId} lands in {@code ORCH_RUN.WORKFLOW_TASK_ID}, whose unique
 * index is what makes one task launch at most one run — the engine's crash
 * recovery reads it back rather than guessing whether a launch happened.
 */
public record WorkflowOrigin(String executionId, String taskId) {}
