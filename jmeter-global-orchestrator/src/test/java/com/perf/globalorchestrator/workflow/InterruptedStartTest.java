package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EmailNode;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowTask;
import com.perf.globalorchestrator.email.EmailSender;
import com.perf.globalorchestrator.report.WorkflowEmailComposer;
import com.perf.globalorchestrator.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What a task does when its start was interrupted — the engine claims a task
 * before running it, so a process that dies in between leaves a RUNNING row
 * that the next tick polls.
 *
 * <p>The rule both cases follow: never claim an effect that may not have
 * happened.
 */
@DisplayName("Executors — recovery from an interrupted start")
class InterruptedStartTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final NodePosition P = new NodePosition(0, 0);

    private static TaskContext ctx(WorkflowTask task) {
        WorkflowExecution ex = new WorkflowExecution("ex", "wf", "cps", "WF", WorkflowGraph.empty(),
                ExecutionState.RUNNING, null, "tester", NOW, null, NOW, List.of());
        return new TaskContext(ex, new ApplicationGroup("cps", "Group", null, NOW, null), task, NOW);
    }

    /** A task the engine claimed but never settled: RUNNING, started, no due time, no result. */
    private static WorkflowTask claimedButUnsettled(NodeType type) {
        return new WorkflowTask("t", "ex", "n", type, "Task", TaskState.RUNNING, 1,
                null, null, NOW, null, null, null, null);
    }

    @Test
    @DisplayName("an interrupted email fails honestly rather than reporting a send it cannot vouch for")
    void interruptedEmailDoesNotClaimSuccess() {
        EmailTaskExecutor executor = new EmailTaskExecutor(
                mock(EmailSender.class), new WorkflowEmailComposer(), mock(WorkflowTaskRepository.class));
        EmailNode node = new EmailNode("n", "Tell the team", P, JoinPolicy.ALL,
                List.of("a@b.com"), List.of(), List.of(), "Subject", "Body", false);

        TaskOutcome out = executor.poll(node, ctx(claimedButUnsettled(NodeType.EMAIL)));

        assertThat(out.state()).isEqualTo(TaskState.FAILED);
        assertThat(out.errorReason()).contains("may or may not have been sent");
    }

    @Test
    @DisplayName("an interrupted wait serves its full period rather than counting as already waited")
    void interruptedDelayRestartsItsWait() {
        DelayNode node = new DelayNode("n", "Settle", P, JoinPolicy.ALL, 120);

        TaskOutcome out = new DelayTaskExecutor().poll(node, ctx(claimedButUnsettled(NodeType.DELAY)));

        assertThat(out.state()).isEqualTo(TaskState.RUNNING);
        assertThat(out.dueAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("a wait that has served its time still completes")
    void dueDelayCompletes() {
        DelayNode node = new DelayNode("n", "Settle", P, JoinPolicy.ALL, 30);
        WorkflowTask due = new WorkflowTask("t", "ex", "n", NodeType.DELAY, "Settle", TaskState.RUNNING, 1,
                null, null, NOW.minusSeconds(60), null, NOW.minusSeconds(30), null, null);

        assertThat(new DelayTaskExecutor().poll(node, ctx(due)).state()).isEqualTo(TaskState.SUCCEEDED);
    }
}
