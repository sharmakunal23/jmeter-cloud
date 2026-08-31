package com.perf.globalorchestrator.sweep;

import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The workflow tick: claim the due executions and advance each.
 *
 * <p>The same DB-claim idiom as {@link CronJobScheduler}, and for the same
 * reason — run N replicas and each execution advances in exactly one of them,
 * with no leader election. The claim is transactional and lives in
 * {@link WorkflowEngine} so the call crosses the Spring proxy; advancing happens
 * outside it, because a health probe or run poll must never hold a row lock.
 */
@Component
public class WorkflowScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowEngine engine;

    public WorkflowScheduler(WorkflowEngine engine) {
        this.engine = engine;
    }

    @Scheduled(
            fixedDelayString = "${globalOrchestrator.workflow.sweepIntervalMs:5000}",
            initialDelayString = "${globalOrchestrator.workflow.sweepInitialDelayMs:15000}")
    public void sweep() {
        List<WorkflowExecution> claimed;
        try {
            claimed = engine.claimDue();
        } catch (Exception e) {
            LOG.warn("WorkflowScheduler: claimDue failed; skipping this tick", e);
            return;
        }
        for (WorkflowExecution execution : claimed) {
            engine.advance(execution);   // never throws
        }
    }
}
