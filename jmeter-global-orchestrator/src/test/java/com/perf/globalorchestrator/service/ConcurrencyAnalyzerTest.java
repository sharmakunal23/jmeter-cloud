package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EdgeCondition;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the peak-workers analysis. The case that matters is the one the level /
 * "wave" model gets wrong — {@code A→B} with an independent {@code C}, where B
 * and C do overlap.
 */
@DisplayName("ConcurrencyAnalyzer — peak workers per region")
class ConcurrencyAnalyzerTest {

    private static LoadTestNode load(String id, String name, String region, int workers) {
        return new LoadTestNode(id, name, new NodePosition(0, 0), JoinPolicy.ALL,
                "app-" + id, "blob", List.of(new RegionCount(region, workers)),
                Map.of(), null, null, 60);
    }

    private static LoadTestNode load(String id, String name, List<RegionCount> alloc) {
        return new LoadTestNode(id, name, new NodePosition(0, 0), JoinPolicy.ALL,
                "app-" + id, "blob", alloc, Map.of(), null, null, 60);
    }

    private static WorkflowEdge edge(String from, String to) {
        return new WorkflowEdge(from + "-" + to, from, to, EdgeCondition.ON_SUCCESS);
    }

    private static WorkflowGraph graph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return new WorkflowGraph(WorkflowGraph.VERSION, nodes, edges);
    }

    @Test
    @DisplayName("a chain never overlaps — peak is the biggest single task")
    void chainPeaksAtTheLargestStep() {
        WorkflowGraph g = graph(
                List.of(load("a", "A", "na-east", 3),
                        load("b", "B", "na-east", 5),
                        load("c", "C", "na-east", 2)),
                List.of(edge("a", "b"), edge("b", "c")));

        assertThat(new ConcurrencyAnalyzer(g).peakWorkersByRegion())
                .containsExactly(Map.entry("na-east", 5));
    }

    @Test
    @DisplayName("a fan-out sums — three parallel tests are three tests' worth of workers")
    void fanOutSums() {
        WorkflowGraph g = graph(
                List.of(new DelayNode("gate", "Settle", new NodePosition(0, 0), JoinPolicy.ALL, 30),
                        load("a", "A", "na-east", 2),
                        load("b", "B", "na-east", 3),
                        load("c", "C", "na-east", 4)),
                List.of(edge("gate", "a"), edge("gate", "b"), edge("gate", "c")));

        ConcurrencyAnalyzer.Peak peak = new ConcurrencyAnalyzer(g).peakFor("na-east");
        assertThat(peak.total()).isEqualTo(9);
        assertThat(peak.tasks()).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    @DisplayName("A→B with an independent C: B and C overlap, which wave-counting misses")
    void independentBranchOverlapsTheChain() {
        // Waves would say level 0 = {A, C} = 3+4 = 7 and level 1 = {B} = 5.
        // The real peak is {B, C} = 9: nothing orders B before or after C.
        WorkflowGraph g = graph(
                List.of(load("a", "A", "na-east", 3),
                        load("b", "B", "na-east", 5),
                        load("c", "C", "na-east", 4)),
                List.of(edge("a", "b")));

        ConcurrencyAnalyzer.Peak peak = new ConcurrencyAnalyzer(g).peakFor("na-east");
        assertThat(peak.total()).isEqualTo(9);
        assertThat(peak.tasks()).containsExactlyInAnyOrder("B", "C");
    }

    @Test
    @DisplayName("ordering through a non-load-test node still counts as ordered")
    void orderingThroughAnIntermediateNode() {
        WorkflowGraph g = graph(
                List.of(load("a", "A", "na-east", 3),
                        new DelayNode("wait", "Settle", new NodePosition(0, 0), JoinPolicy.ALL, 60),
                        load("b", "B", "na-east", 5)),
                List.of(edge("a", "wait"), edge("wait", "b")));

        assertThat(new ConcurrencyAnalyzer(g).peakFor("na-east").total()).isEqualTo(5);
    }

    @Test
    @DisplayName("regions are independent — a task in one never adds to the other")
    void regionsAreCountedSeparately() {
        WorkflowGraph g = graph(
                List.of(load("a", "A", List.of(new RegionCount("na-east", 2), new RegionCount("na-west", 6))),
                        load("b", "B", List.of(new RegionCount("na-east", 3)))),
                List.of());

        assertThat(new ConcurrencyAnalyzer(g).peakWorkersByRegion())
                .containsOnly(Map.entry("na-east", 5), Map.entry("na-west", 6));
    }

    @Test
    @DisplayName("a diamond peaks on its two middle tasks, not on all four")
    void diamond() {
        WorkflowGraph g = graph(
                List.of(load("a", "A", "na-east", 9),
                        load("b", "B", "na-east", 4),
                        load("c", "C", "na-east", 4),
                        load("d", "D", "na-east", 9)),
                List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));

        ConcurrencyAnalyzer.Peak peak = new ConcurrencyAnalyzer(g).peakFor("na-east");
        assertThat(peak.total()).isEqualTo(9);
        assertThat(peak.tasks()).hasSize(1);   // A alone (or D alone) beats B+C = 8
    }

    @Test
    @DisplayName("no load tests — no regions, no peak")
    void graphWithoutLoadTests() {
        WorkflowGraph g = graph(
                List.of(new DelayNode("d", "Wait", new NodePosition(0, 0), JoinPolicy.ALL, 5)),
                List.of());

        assertThat(new ConcurrencyAnalyzer(g).peakWorkersByRegion()).isEmpty();
        assertThat(new ConcurrencyAnalyzer(g).peakFor("na-east").total()).isZero();
    }

    @Test
    @DisplayName("the widest allowed fan-out is still exact")
    void sixteenParallelLoadTests() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i < WorkflowGraph.MAX_LOAD_TEST_NODES; i++) {
            nodes.add(load("n" + i, "T" + i, "na-east", 1));
        }
        assertThat(new ConcurrencyAnalyzer(graph(nodes, List.of())).peakFor("na-east").total())
                .isEqualTo(WorkflowGraph.MAX_LOAD_TEST_NODES);
    }

    @Test
    @DisplayName("a cycle is rejected rather than silently analysed")
    void cycleIsRejected() {
        WorkflowGraph g = graph(
                List.of(load("a", "A", "na-east", 1), load("b", "B", "na-east", 1)),
                List.of(edge("a", "b"), edge("b", "a")));

        assertThatThrownBy(() -> new ConcurrencyAnalyzer(g))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cyclic");
    }

    @Test
    @DisplayName("past 64 nodes the reachability bitmask would wrap — refuse instead")
    void tooManyNodesIsRejected() {
        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i <= WorkflowGraph.MAX_NODES; i++) {
            nodes.add(new DelayNode("d" + i, "W" + i, new NodePosition(0, 0), JoinPolicy.ALL, 5));
        }
        assertThatThrownBy(() -> new ConcurrencyAnalyzer(graph(nodes, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 64");
    }
}
