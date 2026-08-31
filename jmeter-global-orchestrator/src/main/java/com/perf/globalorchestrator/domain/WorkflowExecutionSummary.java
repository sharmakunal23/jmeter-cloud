package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** The one execution row a workflow list shows: how the last launch went. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowExecutionSummary(
        String executionId,
        ExecutionState state,
        Instant startedAt,
        Instant completedAt) {}
