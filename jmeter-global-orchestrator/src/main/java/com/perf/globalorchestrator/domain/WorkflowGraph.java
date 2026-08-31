package com.perf.globalorchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The saved canvas: {@code { v, nodes[], edges[] }}. Structural rules (acyclic,
 * size caps, per-type config) are {@code WorkflowGraphValidator}'s — this record
 * only stores the document and answers adjacency questions about it.
 *
 * <p>Every accessor here assumes a graph the validator accepted; {@link #nodeById}
 * returning null on an edge's endpoint means the caller skipped validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowGraph(int v, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

    /** The only wire version. A graph arriving with anything else is rejected. */
    public static final int VERSION = 1;

    /** Nodes one canvas may hold — keeps validation, analysis and the execution's task set bounded. */
    public static final int MAX_NODES = 64;

    /**
     * Load-test nodes one canvas may hold. The capacity analysis enumerates
     * antichains over exactly these, so the bound is what keeps an exact answer
     * cheap; 16 concurrent runs is already past a 20-worker cluster's ceiling.
     */
    public static final int MAX_LOAD_TEST_NODES = 16;

    public WorkflowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static WorkflowGraph empty() {
        return new WorkflowGraph(VERSION, List.of(), List.of());
    }

    @JsonIgnore
    public Map<String, WorkflowNode> nodesById() {
        Map<String, WorkflowNode> m = new LinkedHashMap<>();
        for (WorkflowNode n : nodes) m.put(n.id(), n);
        return m;
    }

    public WorkflowNode nodeById(String id) {
        for (WorkflowNode n : nodes) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }

    /** Edges arriving at {@code nodeId} — the node's join is evaluated over these. */
    public List<WorkflowEdge> inboundOf(String nodeId) {
        List<WorkflowEdge> out = new ArrayList<>();
        for (WorkflowEdge e : edges) {
            if (e.target().equals(nodeId)) out.add(e);
        }
        return out;
    }

    public List<WorkflowEdge> outboundOf(String nodeId) {
        List<WorkflowEdge> out = new ArrayList<>();
        for (WorkflowEdge e : edges) {
            if (e.source().equals(nodeId)) out.add(e);
        }
        return out;
    }

    /** Nodes with no inbound edge — they all start at once when the execution opens. */
    @JsonIgnore
    public List<WorkflowNode> roots() {
        List<WorkflowNode> out = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            if (inboundOf(n.id()).isEmpty()) out.add(n);
        }
        return out;
    }

    @JsonIgnore
    public List<LoadTestNode> loadTestNodes() {
        List<LoadTestNode> out = new ArrayList<>();
        for (WorkflowNode n : nodes) {
            if (n instanceof LoadTestNode lt) out.add(lt);
        }
        return out;
    }

    /** True when the node declares a failure branch, which is what marks its failure handled. */
    public boolean handlesFailure(String nodeId) {
        for (WorkflowEdge e : edges) {
            if (e.source().equals(nodeId) && e.condition() == EdgeCondition.ON_FAILURE) return true;
        }
        return false;
    }
}
