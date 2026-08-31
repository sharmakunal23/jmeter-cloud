package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.ApprovalNode;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
import com.perf.globalorchestrator.domain.EmailNode;
import com.perf.globalorchestrator.domain.HealthCheckNode;
import com.perf.globalorchestrator.domain.HealthRequirement;
import com.perf.globalorchestrator.domain.JoinPolicy;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.NodePosition;
import com.perf.globalorchestrator.domain.RegionCount;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowGraphValidator — structural rules")
class WorkflowGraphValidatorTest {

    private static final NodePosition P = new NodePosition(0, 0);

    private static HealthCheckNode health(String id) {
        return new HealthCheckNode(id, "Check " + id, P, JoinPolicy.ALL, "payments",
                HealthRequirement.ALL, null, 3, 15, 5);
    }

    private static LoadTestNode load(String id) {
        return new LoadTestNode(id, "Test " + id, P, JoinPolicy.ALL, "payments", "blob",
                List.of(new RegionCount("na-east", 2)), Map.of(), null, null, 60);
    }

    private static EmailNode email(String id) {
        return new EmailNode(id, "Mail " + id, P, JoinPolicy.ALL,
                List.of("ops@example.com"), List.of(), List.of(), "Subject", "Body", true);
    }

    private static WorkflowGraph graph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return new WorkflowGraph(WorkflowGraph.VERSION, nodes, edges);
    }

    private static WorkflowEdge edge(String id, String from, String to, EdgeCondition c) {
        return new WorkflowEdge(id, from, to, c);
    }

    @Test
    @DisplayName("the shape from the spec — three gates, a parallel fan-out, then mail — is valid")
    void theCanonicalWorkflowIsValid() {
        List<WorkflowNode> nodes = new ArrayList<>(List.of(
                health("h1"), health("h2"), health("h3"),
                load("t1"), load("t2"), load("t3"), email("m1")));
        List<WorkflowEdge> edges = List.of(
                edge("e1", "h1", "h2", EdgeCondition.ON_SUCCESS),
                edge("e2", "h2", "h3", EdgeCondition.ON_SUCCESS),
                edge("e3", "h3", "t1", EdgeCondition.ON_SUCCESS),
                edge("e4", "h3", "t2", EdgeCondition.ON_SUCCESS),
                edge("e5", "h3", "t3", EdgeCondition.ON_SUCCESS),
                // Mail regardless of how the tests went — the ALWAYS edge is the point.
                edge("e6", "t1", "m1", EdgeCondition.ALWAYS),
                edge("e7", "t2", "m1", EdgeCondition.ALWAYS),
                edge("e8", "t3", "m1", EdgeCondition.ALWAYS));

        assertThat(WorkflowGraphValidator.validate(graph(nodes, edges))).isEmpty();
    }

    @Test
    @DisplayName("a missing graph is a violation, not a crash — the reference checks read it")
    void nullGraph() {
        assertThat(WorkflowGraphValidator.validate(null)).containsExactly("graph is required");
    }

    @Test
    @DisplayName("an empty canvas is rejected")
    void emptyGraph() {
        assertThat(WorkflowGraphValidator.validate(WorkflowGraph.empty()))
                .containsExactly("a workflow needs at least one task");
    }

    @Test
    @DisplayName("a loop is rejected")
    void cycle() {
        WorkflowGraph g = graph(List.of(health("a"), health("b")), List.of(
                edge("e1", "a", "b", EdgeCondition.ON_SUCCESS),
                edge("e2", "b", "a", EdgeCondition.ON_SUCCESS)));
        assertThat(WorkflowGraphValidator.validate(g))
                .anySatisfy(e -> assertThat(e).contains("loop"));
    }

    @Test
    @DisplayName("an edge naming a task that isn't there is rejected")
    void danglingEdge() {
        WorkflowGraph g = graph(List.of(health("a")),
                List.of(edge("e1", "a", "ghost", EdgeCondition.ON_SUCCESS)));
        assertThat(WorkflowGraphValidator.validate(g))
                .anySatisfy(e -> assertThat(e).contains("no task 'ghost'"));
    }

    @Test
    @DisplayName("a task depending on itself is rejected")
    void selfLoop() {
        WorkflowGraph g = graph(List.of(health("a")),
                List.of(edge("e1", "a", "a", EdgeCondition.ON_SUCCESS)));
        assertThat(WorkflowGraphValidator.validate(g))
                .anySatisfy(e -> assertThat(e).contains("cannot depend on itself"));
    }

    @Test
    @DisplayName("a load test with no workers allocated is rejected")
    void loadTestWithoutFleet() {
        LoadTestNode n = new LoadTestNode("t", "Test", P, JoinPolicy.ALL, "payments", "blob",
                List.of(), Map.of(), null, null, 60);
        assertThat(WorkflowGraphValidator.validate(graph(List.of(n), List.of())))
                .anySatisfy(e -> assertThat(e).contains("allocate workers in at least one cluster"));
    }

    @Test
    @DisplayName("the same cluster allocated twice on one task is rejected")
    void duplicateRegionAllocation() {
        LoadTestNode n = new LoadTestNode("t", "Test", P, JoinPolicy.ALL, "payments", "blob",
                List.of(new RegionCount("na-east", 2), new RegionCount("na-east", 3)),
                Map.of(), null, null, 60);
        assertThat(WorkflowGraphValidator.validate(graph(List.of(n), List.of())))
                .anySatisfy(e -> assertThat(e).contains("allocated twice"));
    }

    @Test
    @DisplayName("a property name a run would reject is rejected here too")
    void invalidPropertyKey() {
        LoadTestNode n = new LoadTestNode("t", "Test", P, JoinPolicy.ALL, "payments", "blob",
                List.of(new RegionCount("na-east", 1)), Map.of("bad key!", "v"), null, null, 60);
        assertThat(WorkflowGraphValidator.validate(graph(List.of(n), List.of())))
                .anySatisfy(e -> assertThat(e).contains("property name 'bad key!'"));
    }

    @Test
    @DisplayName("a malformed recipient is rejected")
    void badEmailAddress() {
        EmailNode n = new EmailNode("m", "Mail", P, JoinPolicy.ALL,
                List.of("not-an-address"), List.of(), List.of(), "S", "B", false);
        assertThat(WorkflowGraphValidator.validate(graph(List.of(n), List.of())))
                .anySatisfy(e -> assertThat(e).contains("is not a valid email"));
    }

    @Test
    @DisplayName("'at least' without a minimum is rejected")
    void atLeastWithoutMinimum() {
        HealthCheckNode n = new HealthCheckNode("h", "Check", P, JoinPolicy.ALL, "payments",
                HealthRequirement.AT_LEAST, null, 1, 15, 5);
        assertThat(WorkflowGraphValidator.validate(graph(List.of(n), List.of())))
                .anySatisfy(e -> assertThat(e).contains("at least"));
    }

    @Test
    @DisplayName("out-of-range numbers are rejected with the bound in the message")
    void outOfRangeValues() {
        DelayNode d = new DelayNode("d", "Wait", P, JoinPolicy.ALL, 0);
        ApprovalNode a = new ApprovalNode("a", "Approve", P, JoinPolicy.ALL, null, 0);
        assertThat(WorkflowGraphValidator.validate(graph(List.of(d, a), List.of())))
                .anySatisfy(e -> assertThat(e).contains("wait (seconds) must be between 1 and 86400"))
                .anySatisfy(e -> assertThat(e).contains("deadline (minutes) must be between 1 and 10080"));
    }

    @Test
    @DisplayName("past the load-test cap the graph is rejected — the analysis is what bounds it")
    void tooManyLoadTests() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i <= WorkflowGraph.MAX_LOAD_TEST_NODES; i++) nodes.add(load("t" + i));
        assertThat(WorkflowGraphValidator.validate(graph(nodes, List.of())))
                .anySatisfy(e -> assertThat(e).contains("at most 16 load tests"));
    }

    @Test
    @DisplayName("past the node cap the graph is rejected before anything else is checked")
    void tooManyNodes() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i <= WorkflowGraph.MAX_NODES; i++) {
            nodes.add(new DelayNode("d" + i, "W" + i, P, JoinPolicy.ALL, 5));
        }
        assertThat(WorkflowGraphValidator.validate(graph(nodes, List.of())))
                .containsExactly("a workflow holds at most 64 tasks (got 65)");
    }

    @Test
    @DisplayName("duplicate task ids are rejected")
    void duplicateNodeIds() {
        assertThat(WorkflowGraphValidator.validate(graph(List.of(health("a"), load("a")), List.of())))
                .anySatisfy(e -> assertThat(e).contains("duplicate task id 'a'"));
    }
}
