package com.perf.globalorchestrator.workflow;

import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import com.perf.globalorchestrator.domain.WorkflowTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's graph rules, as pure functions: when a task may start, when it
 * can never start, and how the execution's verdict reads a failure.
 */
@DisplayName("WorkflowEngine — join, skip and verdict rules")
class WorkflowEngineRulesTest {

    private static final NodePosition P = new NodePosition(0, 0);

    private static WorkflowNode node(String id, JoinPolicy join) {
        return new DelayNode(id, "Task " + id, P, join, 1);
    }

    private static WorkflowEdge edge(String from, String to, EdgeCondition c) {
        return new WorkflowEdge(from + c + to, from, to, c);
    }

    private static WorkflowTask task(String nodeId, TaskState state) {
        return new WorkflowTask("t-" + nodeId, "exec", nodeId, NodeType.DELAY, "Task " + nodeId,
                state, 0, null, null, null, null, null, null, null);
    }

    private static Map<String, WorkflowTask> tasks(WorkflowTask... rows) {
        Map<String, WorkflowTask> m = new LinkedHashMap<>();
        for (WorkflowTask t : rows) m.put(t.nodeId(), t);
        return m;
    }

    @Test
    @DisplayName("a task with no upstream is a root and starts immediately")
    void rootIsReady() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("a", JoinPolicy.ALL)), List.of());
        assertThat(WorkflowEngine.decide(g, "a", tasks(task("a", TaskState.PENDING))))
                .isEqualTo(JoinDecision.READY);
    }

    @Test
    @DisplayName("ALL waits for every upstream, then starts")
    void allJoinWaitsForEveryEdge() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL), node("c", JoinPolicy.ALL)),
                List.of(edge("a", "c", EdgeCondition.ON_SUCCESS), edge("b", "c", EdgeCondition.ON_SUCCESS)));

        assertThat(WorkflowEngine.decide(g, "c", tasks(
                task("a", TaskState.SUCCEEDED), task("b", TaskState.RUNNING), task("c", TaskState.PENDING))))
                .isEqualTo(JoinDecision.WAIT);

        assertThat(WorkflowEngine.decide(g, "c", tasks(
                task("a", TaskState.SUCCEEDED), task("b", TaskState.SUCCEEDED), task("c", TaskState.PENDING))))
                .isEqualTo(JoinDecision.READY);
    }

    @Test
    @DisplayName("ALL skips as soon as one upstream refuses — no point waiting for the rest")
    void allJoinSkipsOnFirstRefusal() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL), node("c", JoinPolicy.ALL)),
                List.of(edge("a", "c", EdgeCondition.ON_SUCCESS), edge("b", "c", EdgeCondition.ON_SUCCESS)));

        assertThat(WorkflowEngine.decide(g, "c", tasks(
                task("a", TaskState.FAILED), task("b", TaskState.RUNNING), task("c", TaskState.PENDING))))
                .isEqualTo(JoinDecision.SKIP);
    }

    @Test
    @DisplayName("ANY starts on the first satisfied edge and skips only when every one has refused")
    void anyJoin() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL), node("c", JoinPolicy.ANY)),
                List.of(edge("a", "c", EdgeCondition.ON_SUCCESS), edge("b", "c", EdgeCondition.ON_SUCCESS)));

        assertThat(WorkflowEngine.decide(g, "c", tasks(
                task("a", TaskState.SUCCEEDED), task("b", TaskState.RUNNING), task("c", TaskState.PENDING))))
                .isEqualTo(JoinDecision.READY);

        assertThat(WorkflowEngine.decide(g, "c", tasks(
                task("a", TaskState.FAILED), task("b", TaskState.RUNNING), task("c", TaskState.PENDING))))
                .isEqualTo(JoinDecision.WAIT);

        assertThat(WorkflowEngine.decide(g, "c", tasks(
                task("a", TaskState.FAILED), task("b", TaskState.FAILED), task("c", TaskState.PENDING))))
                .isEqualTo(JoinDecision.SKIP);
    }

    @Test
    @DisplayName("an ON_FAILURE edge fires exactly when its source failed")
    void failureBranch() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL)),
                List.of(edge("a", "b", EdgeCondition.ON_FAILURE)));

        assertThat(WorkflowEngine.decide(g, "b", tasks(
                task("a", TaskState.FAILED), task("b", TaskState.PENDING)))).isEqualTo(JoinDecision.READY);
        assertThat(WorkflowEngine.decide(g, "b", tasks(
                task("a", TaskState.SUCCEEDED), task("b", TaskState.PENDING)))).isEqualTo(JoinDecision.SKIP);
    }

    @Test
    @DisplayName("ALWAYS fires after success or failure — but never after a skip, which never ran")
    void alwaysEdgeDoesNotFollowASkip() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL)),
                List.of(edge("a", "b", EdgeCondition.ALWAYS)));

        assertThat(WorkflowEngine.decide(g, "b", tasks(
                task("a", TaskState.SUCCEEDED), task("b", TaskState.PENDING)))).isEqualTo(JoinDecision.READY);
        assertThat(WorkflowEngine.decide(g, "b", tasks(
                task("a", TaskState.FAILED), task("b", TaskState.PENDING)))).isEqualTo(JoinDecision.READY);
        // The one that would have leaked a dead branch back to life.
        assertThat(WorkflowEngine.decide(g, "b", tasks(
                task("a", TaskState.SKIPPED), task("b", TaskState.PENDING)))).isEqualTo(JoinDecision.SKIP);
        assertThat(WorkflowEngine.decide(g, "b", tasks(
                task("a", TaskState.CANCELLED), task("b", TaskState.PENDING)))).isEqualTo(JoinDecision.SKIP);
    }

    @Test
    @DisplayName("a skip decides the next join, so skipping walks the whole dead branch")
    void skipPropagates() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL), node("c", JoinPolicy.ALL)),
                List.of(edge("a", "b", EdgeCondition.ON_SUCCESS), edge("b", "c", EdgeCondition.ON_SUCCESS)));

        Map<String, WorkflowTask> state = tasks(
                task("a", TaskState.FAILED), task("b", TaskState.PENDING), task("c", TaskState.PENDING));
        assertThat(WorkflowEngine.decide(g, "b", state)).isEqualTo(JoinDecision.SKIP);

        state.put("b", task("b", TaskState.SKIPPED));
        assertThat(WorkflowEngine.decide(g, "c", state)).isEqualTo(JoinDecision.SKIP);
    }

    @Test
    @DisplayName("an unhandled failure fails the execution")
    void unhandledFailureFailsTheExecution() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL)),
                List.of(edge("a", "b", EdgeCondition.ON_SUCCESS)));

        assertThat(WorkflowEngine.outcomeOf(g, List.of(
                task("a", TaskState.FAILED), task("b", TaskState.SKIPPED))))
                .isEqualTo(ExecutionState.FAILED);
    }

    @Test
    @DisplayName("drawing a failure branch is what marks the failure handled — the execution succeeds")
    void handledFailureSucceeds() {
        // "run the test, mail the team either way, and don't call the workflow broken."
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("test", JoinPolicy.ALL), node("mail", JoinPolicy.ALL)),
                List.of(edge("test", "mail", EdgeCondition.ALWAYS),
                        edge("test", "mail", EdgeCondition.ON_FAILURE)));

        assertThat(WorkflowEngine.outcomeOf(g, List.of(
                task("test", TaskState.FAILED), task("mail", TaskState.SUCCEEDED))))
                .isEqualTo(ExecutionState.SUCCEEDED);
    }

    @Test
    @DisplayName("a cancelled task makes the execution CANCELLED, not FAILED")
    void cancelledWins() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("a", JoinPolicy.ALL)), List.of());
        assertThat(WorkflowEngine.outcomeOf(g, List.of(task("a", TaskState.CANCELLED))))
                .isEqualTo(ExecutionState.CANCELLED);
    }

    @Test
    @DisplayName("the outcome names what did not run — a skipped branch is why an expected email never came")
    void terminalReasonNamesSkippedTasks() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("gate", JoinPolicy.ALL), node("wait", JoinPolicy.ALL), node("mail", JoinPolicy.ALL)),
                List.of(edge("gate", "wait", EdgeCondition.ON_SUCCESS),
                        edge("wait", "mail", EdgeCondition.ALWAYS)));
        List<WorkflowTask> tasks = List.of(
                task("gate", TaskState.FAILED), task("wait", TaskState.SKIPPED), task("mail", TaskState.SKIPPED));

        String reason = WorkflowEngine.terminalReason(g, tasks, ExecutionState.FAILED);

        assertThat(reason).contains("2 task(s) did not run: Task wait, Task mail");
    }

    @Test
    @DisplayName("a clean pass says nothing, because there is nothing to explain")
    void terminalReasonIsSilentOnACleanPass() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("a", JoinPolicy.ALL)), List.of());
        assertThat(WorkflowEngine.terminalReason(g, List.of(task("a", TaskState.SUCCEEDED)),
                ExecutionState.SUCCEEDED)).isNull();
    }

    @Test
    @DisplayName("all-succeeded is a clean pass")
    void allSucceeded() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(node("a", JoinPolicy.ALL), node("b", JoinPolicy.ALL)),
                List.of(edge("a", "b", EdgeCondition.ON_SUCCESS)));
        assertThat(WorkflowEngine.outcomeOf(g, List.of(
                task("a", TaskState.SUCCEEDED), task("b", TaskState.SUCCEEDED))))
                .isEqualTo(ExecutionState.SUCCEEDED);
    }
}
