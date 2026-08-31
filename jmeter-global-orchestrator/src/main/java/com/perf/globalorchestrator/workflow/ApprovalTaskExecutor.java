package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.ApprovalNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parks the branch for an operator. The decision itself arrives through
 * {@code POST …/tasks/{taskId}/approve|reject}, which settles the row directly
 * and pulls the execution's next tick forward — so {@link #poll} only ever sees
 * a task nobody has answered yet, and its one job is the deadline.
 */
@Component
public class ApprovalTaskExecutor implements WorkflowTaskExecutor<ApprovalNode> {

    @Override
    public TaskOutcome start(ApprovalNode node, TaskContext ctx) {
        Instant deadline = node.deadlineMinutes() == null
                ? null
                : ctx.now().plusSeconds(node.deadlineMinutes() * 60L);
        Map<String, Object> result = new LinkedHashMap<>();
        if (node.instructions() != null) result.put("instructions", node.instructions());
        if (deadline != null) result.put("deadline", deadline.toString());
        return TaskOutcome.awaitingApproval(deadline, result);
    }

    @Override
    public TaskOutcome poll(ApprovalNode node, TaskContext ctx) {
        Instant deadline = ctx.task().dueAt();
        if (deadline != null && !ctx.now().isBefore(deadline)) {
            return TaskOutcome.failed(
                    "no answer within " + node.deadlineMinutes() + " minutes", ctx.task().result());
        }
        return TaskOutcome.awaitingApproval(deadline, ctx.task().result());
    }
}
