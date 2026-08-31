package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowTask;

import java.time.Instant;

/**
 * Everything an executor may read about the attempt it is making. Deliberately
 * read-only: an executor decides an outcome, the engine writes it, so a task
 * row is never half-updated by a failed attempt.
 *
 * @param now the tick's clock — every executor in one tick shares it, so
 *            recorded timings are consistent across a fan-out
 */
public record TaskContext(WorkflowExecution execution, ApplicationGroup group,
                          WorkflowTask task, Instant now) {}
