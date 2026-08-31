package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.DelayNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Waits out the node's {@code seconds} across ticks — the engine never sleeps. */
@Component
public class DelayTaskExecutor implements WorkflowTaskExecutor<DelayNode> {

    @Override
    public TaskOutcome start(DelayNode node, TaskContext ctx) {
        return TaskOutcome.running(ctx.now().plusSeconds(node.seconds()),
                Map.of("waitSeconds", node.seconds()));
    }

    @Override
    public TaskOutcome poll(DelayNode node, TaskContext ctx) {
        // No due time means the start was interrupted before it could record
        // one; wait the full period rather than treat the wait as served.
        if (ctx.task().dueAt() == null) {
            return TaskOutcome.running(ctx.now().plusSeconds(node.seconds()),
                    Map.of("waitSeconds", node.seconds()));
        }
        if (ctx.now().isBefore(ctx.task().dueAt())) {
            return TaskOutcome.running(ctx.task().dueAt(), ctx.task().result());
        }
        return TaskOutcome.succeeded(Map.of("waitSeconds", node.seconds()));
    }
}
