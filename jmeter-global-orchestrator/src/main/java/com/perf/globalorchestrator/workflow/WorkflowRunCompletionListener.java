package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.RunTerminalEvent;
import com.perf.globalorchestrator.repo.WorkflowExecutionRepository;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Wakes the workflow execution waiting on a run the moment that run finishes,
 * instead of letting its next poll discover it up to {@code runPollSeconds}
 * later.
 *
 * <p><b>This is an accelerator, not the mechanism.</b> The wake is a durable
 * write — it pulls the execution's {@code NEXT_TICK_AT} forward — so it crosses
 * replicas and survives a restart, and losing it costs only the latency it was
 * saving: {@code LoadTestTaskExecutor} still polls the run, so the workflow
 * always finds out. Nothing here may be the only path by which a run's ending
 * reaches its workflow.
 *
 * <p>{@code AFTER_COMMIT} matters: waking before the run's terminal state is
 * visible would have the engine read it as still running and reschedule, which
 * is worse than not waking at all. {@code fallbackExecution} covers the terminal
 * paths that write outside a transaction.
 */
@Component
public class WorkflowRunCompletionListener {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunCompletionListener.class);

    private final WorkflowTaskRepository tasks;
    private final WorkflowExecutionRepository executions;

    public WorkflowRunCompletionListener(WorkflowTaskRepository tasks,
                                         WorkflowExecutionRepository executions) {
        this.tasks = tasks;
        this.executions = executions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunTerminal(RunTerminalEvent event) {
        try {
            // One indexed probe. The overwhelming majority of runs belong to no
            // workflow, and those cost exactly this lookup and nothing else.
            tasks.findByRunId(event.runId()).ifPresent(task -> {
                if (executions.nudge(task.executionId(), Instant.now())) {
                    LOG.info("run {} ended {} — waking workflow execution {} for task {}",
                            event.runId(), event.state(), task.executionId(), task.nodeId());
                }
            });
        } catch (RuntimeException e) {
            // The load-test task's own poll is the backstop; a failed wake is a
            // slower workflow, never a stuck one.
            LOG.warn("could not wake the workflow waiting on run {}: {}", event.runId(), e.toString());
        }
    }
}
