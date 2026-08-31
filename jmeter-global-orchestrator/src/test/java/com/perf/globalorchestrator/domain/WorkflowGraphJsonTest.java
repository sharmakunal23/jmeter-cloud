package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The graph is both the wire shape the builder posts and the CLOB the database
 * stores, so its JSON is a contract. Pins the {@code type} discriminator, the
 * defaults a sparse node gets, and that unknown fields — React Flow adds its own
 * to every node — survive a round trip instead of failing it.
 */
@DisplayName("WorkflowGraph — JSON contract")
class WorkflowGraphJsonTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    @DisplayName("every node type round-trips through its discriminator")
    void roundTripsAllNodeTypes() throws Exception {
        WorkflowGraph graph = new WorkflowGraph(1, List.of(
                new HealthCheckNode("h", "Check payments", new NodePosition(10, 20), JoinPolicy.ALL,
                        "payments", HealthRequirement.AT_LEAST, 2, 3, 15, 5),
                new LoadTestNode("t", "Peak load", new NodePosition(30, 40), JoinPolicy.ANY,
                        "payments", "blob-1", List.of(new RegionCount("na-east", 4)),
                        Map.of("threads", "50"), true, LoadTestSuccess.ANY_TERMINAL, 90),
                new EmailNode("m", "Tell the team", new NodePosition(50, 60), JoinPolicy.ALL,
                        List.of("a@b.com"), List.of("c@d.com"), List.of(), "Done", "Body", true),
                new DelayNode("d", "Settle", new NodePosition(70, 80), JoinPolicy.ALL, 120),
                new ApprovalNode("p", "Sign off", new NodePosition(90, 100), JoinPolicy.ALL, "check it", 60)),
                List.of(new WorkflowEdge("e1", "h", "t", EdgeCondition.ON_SUCCESS),
                        new WorkflowEdge("e2", "t", "m", EdgeCondition.ALWAYS)));

        String wire = json.writeValueAsString(graph);
        assertThat(wire).contains("\"type\":\"HEALTH_CHECK\"", "\"type\":\"LOAD_TEST\"",
                "\"type\":\"EMAIL\"", "\"type\":\"DELAY\"", "\"type\":\"APPROVAL\"");

        WorkflowGraph back = json.readValue(wire, WorkflowGraph.class);
        assertThat(back).isEqualTo(graph);
        assertThat(back.nodeById("t")).isInstanceOf(LoadTestNode.class);
        assertThat(back.nodeById("t").applicationName()).isEqualTo("payments");
    }

    @Test
    @DisplayName("React Flow's own node fields are ignored, not rejected")
    void toleratesUnknownFields() throws Exception {
        String wire = """
                {"v":1,
                 "nodes":[{"type":"DELAY","id":"d","name":"Settle","seconds":30,
                           "position":{"x":1,"y":2,"z":3},
                           "measured":{"width":180},"selected":true,"dragging":false}],
                 "edges":[]}""";

        WorkflowGraph g = json.readValue(wire, WorkflowGraph.class);
        assertThat(g.nodes()).hasSize(1);
        assertThat(((DelayNode) g.nodeById("d")).seconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("a sparse node gets the documented defaults, not nulls")
    void appliesDefaults() throws Exception {
        String wire = """
                {"v":1,
                 "nodes":[{"type":"HEALTH_CHECK","id":"h","name":"Check","application":"payments",
                           "position":{"x":0,"y":0}},
                          {"type":"LOAD_TEST","id":"t","name":"Test","application":"payments",
                           "templateBlobId":"b","position":{"x":0,"y":0}}],
                 "edges":[{"id":"e","source":"h","target":"t"}]}""";

        WorkflowGraph g = json.readValue(wire, WorkflowGraph.class);
        HealthCheckNode h = (HealthCheckNode) g.nodeById("h");
        assertThat(h.joinPolicy()).isEqualTo(JoinPolicy.ALL);
        assertThat(h.requirement()).isEqualTo(HealthRequirement.ALL);
        assertThat(h.attempts()).isEqualTo(1);
        assertThat(h.intervalSeconds()).isEqualTo(15);
        assertThat(h.timeoutSeconds()).isEqualTo(5);

        LoadTestNode t = (LoadTestNode) g.nodeById("t");
        assertThat(t.successWhen()).isEqualTo(LoadTestSuccess.COMPLETED_ONLY);
        assertThat(t.maxDurationMinutes()).isEqualTo(120);
        assertThat(t.fleetAllocation()).isEmpty();

        // An edge with no condition is a success edge — the common case stays untyped in the UI.
        assertThat(g.edges().get(0).condition()).isEqualTo(EdgeCondition.ON_SUCCESS);
    }

    @Test
    @DisplayName("drawing a failure branch is what marks a node's failure handled")
    void handlesFailureReadsTheEdges() {
        WorkflowGraph g = new WorkflowGraph(1,
                List.of(new DelayNode("a", "A", new NodePosition(0, 0), JoinPolicy.ALL, 5),
                        new DelayNode("b", "B", new NodePosition(0, 0), JoinPolicy.ALL, 5),
                        new DelayNode("c", "C", new NodePosition(0, 0), JoinPolicy.ALL, 5)),
                List.of(new WorkflowEdge("e1", "a", "b", EdgeCondition.ON_SUCCESS),
                        new WorkflowEdge("e2", "a", "c", EdgeCondition.ON_FAILURE)));

        assertThat(g.handlesFailure("a")).isTrue();
        assertThat(g.handlesFailure("b")).isFalse();
        assertThat(g.roots()).extracting(WorkflowNode::id).containsExactly("a");
    }
}
