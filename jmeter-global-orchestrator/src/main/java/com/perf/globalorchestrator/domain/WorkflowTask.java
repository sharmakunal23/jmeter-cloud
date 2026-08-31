package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * One node of one execution — a row of {@code ORCH_WORKFLOW_TASK}.
 *
 * <p>{@code runId} is written after the launch commits, so a crash in that
 * window leaves a RUNNING load-test task with none; recovery is an exact lookup
 * on {@code ORCH_RUN.WORKFLOW_TASK_ID}, whose unique index guarantees the task
 * launched at most one run.
 *
 * @param dueAt  when this task next wants attention — a delay's due time, an
 *               approval's deadline, a health check's next attempt, a load
 *               test's watchdog
 * @param result per-type detail the UI renders; null until there is any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowTask(
        String taskId,
        String executionId,
        String nodeId,
        NodeType type,
        String name,
        TaskState state,
        int attempt,
        String applicationName,
        String runId,
        Instant startedAt,
        Instant completedAt,
        Instant dueAt,
        Map<String, Object> result,
        String errorReason) {}
