package com.perf.globalorchestrator.report;

import com.perf.globalorchestrator.domain.ApplicationGroup;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
import com.perf.globalorchestrator.domain.ExecutionState;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.NodeType;
import com.perf.globalorchestrator.domain.TaskState;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowExecution;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import com.perf.globalorchestrator.domain.WorkflowTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The placeholder vocabulary, and the one distinction that matters in a result
 * email: what the execution IS right now versus what it is going to be.
 */
@DisplayName("WorkflowEmailComposer — placeholders")
class WorkflowEmailComposerTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final NodePosition P = new NodePosition(0, 0);
    private final WorkflowEmailComposer composer = new WorkflowEmailComposer();

    private static WorkflowNode node(String id) {
        return new DelayNode(id, "Task " + id, P, JoinPolicy.ALL, 5);
    }

    private static WorkflowTask task(String nodeId, NodeType type, TaskState state, String app, String runId) {
        return new WorkflowTask("t-" + nodeId, "ex", nodeId, type, "Task " + nodeId, state, 1,
                app, runId, NOW, null, null, null, null);
    }

    private static WorkflowExecution execution(WorkflowGraph graph) {
        return new WorkflowExecution("ex1", "wf1", "cps", "Nightly", graph,
                // A result email is composed while the execution is still RUNNING —
                // it is itself the task that has not finished.
                ExecutionState.RUNNING, null, "kunal", NOW, null, NOW, List.of());
    }

    private static final ApplicationGroup GROUP = new ApplicationGroup(
            "cps", "Servicing MQ", null, null, null, 7, null, null, null, false,
            "Payments Platform", List.of(), List.of(), List.of(), NOW, null, null);

    @Test
    @DisplayName("outcome reports the verdict a result email is actually about, where state says RUNNING")
    void outcomeIsTheVerdictNotTheCurrentState() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("load"), node("report")),
                List.of(new WorkflowEdge("e", "load", "report", EdgeCondition.ALWAYS)));
        List<WorkflowTask> tasks = List.of(
                task("load", NodeType.LOAD_TEST, TaskState.SUCCEEDED, "card-auth", "run-1"),
                task("report", NodeType.EMAIL, TaskState.RUNNING, null, null));

        assertThat(composer.renderText("${execution.state}", execution(g), GROUP, tasks)).isEqualTo("RUNNING");
        assertThat(composer.renderText("${execution.outcome}", execution(g), GROUP, tasks)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("an unhandled failure shows in the outcome before the execution has settled")
    void outcomeSeesAnUnhandledFailure() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("load"), node("report")),
                List.of(new WorkflowEdge("e", "load", "report", EdgeCondition.ALWAYS)));
        List<WorkflowTask> tasks = List.of(
                task("load", NodeType.LOAD_TEST, TaskState.FAILED, "card-auth", "run-1"),
                task("report", NodeType.EMAIL, TaskState.RUNNING, null, null));

        assertThat(composer.renderText("${execution.outcome}", execution(g), GROUP, tasks)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("per-task values are addressed by node id, and the group's team is available")
    void taskAndGroupPlaceholders() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("load")), List.of());
        List<WorkflowTask> tasks =
                List.of(task("load", NodeType.LOAD_TEST, TaskState.SUCCEEDED, "card-auth", "run-42"));

        String out = composer.renderText(
                "[${group.team}] ${task.load.state} ${task.load.runId} ${applications}",
                execution(g), GROUP, tasks);

        assertThat(out).isEqualTo("[Payments Platform] SUCCEEDED run-42 card-auth");
    }

    @Test
    @DisplayName("an unknown placeholder renders empty rather than failing the send")
    void unknownPlaceholderIsEmpty() {
        WorkflowGraph g = new WorkflowGraph(1, List.of(node("a")), List.of());
        assertThat(composer.renderText("x${nope.at.all}y", execution(g), GROUP, List.of())).isEqualTo("xy");
    }
}
