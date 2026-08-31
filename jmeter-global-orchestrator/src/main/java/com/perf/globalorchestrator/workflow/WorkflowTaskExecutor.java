package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.WorkflowNode;

/**
 * Runs one kind of workflow node. {@link #start} is called once when the node's
 * join is satisfied and {@link #poll} on every later tick while the task is
 * still open.
 *
 * <p>Neither may block for longer than a tick: a task that needs to wait
 * returns {@link TaskOutcome#running} with a {@code dueAt}. Neither may throw —
 * the engine treats an escaped exception as a failed task, which is right but
 * loses the detail a proper outcome would have carried.
 */
public interface WorkflowTaskExecutor<N extends WorkflowNode> {

    TaskOutcome start(N node, TaskContext ctx);

    TaskOutcome poll(N node, TaskContext ctx);
}
