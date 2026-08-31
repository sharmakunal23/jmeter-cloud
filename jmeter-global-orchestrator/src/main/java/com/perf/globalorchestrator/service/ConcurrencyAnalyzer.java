package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.RegionCount;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Peak workers a workflow can want at once, per region — the number its group's
 * reservation has to cover.
 *
 * <p>Tasks run concurrently only when neither can reach the other, so the peak
 * is the <b>maximum-weight antichain</b> of the graph's reachability order, found
 * exactly by enumerating antichains over the load-test nodes ({@code MAX_LOAD_TEST_NODES}
 * = 16 keeps that at ~10<sup>6</sup> cheap operations). Level or "wave" counting was
 * rejected: with {@code A→B} and an independent {@code C} it misses that B and C
 * overlap, so it under-reports and would let a workflow half-run into a 409.
 *
 * <p><b>Known conservatism.</b> Mutually exclusive branches — one load test on a
 * node's {@code ON_SUCCESS} edge, another on its {@code ON_FAILURE} — are
 * incomparable, so they are counted as if both ran. {@link #peakSet} names the
 * tasks behind a number so an operator can see immediately why it is what it is.
 */
public final class ConcurrencyAnalyzer {

    private final WorkflowGraph graph;
    private final List<LoadTestNode> loadTests;
    /** Bit i set = load test i is comparable with this one, so they never overlap. */
    private final int[] comparable;

    public ConcurrencyAnalyzer(WorkflowGraph graph) {
        // One long per node holds the reachability closure, so 64 is not a
        // policy here — past it the shift wraps and the answer goes quietly wrong.
        if (graph.nodes().size() > WorkflowGraph.MAX_NODES) {
            throw new IllegalArgumentException(
                    "graph has " + graph.nodes().size() + " nodes; at most "
                            + WorkflowGraph.MAX_NODES + " can be analysed");
        }
        this.graph = graph;
        this.loadTests = graph.loadTestNodes();
        if (loadTests.size() > WorkflowGraph.MAX_LOAD_TEST_NODES) {
            throw new IllegalArgumentException(
                    "graph has " + loadTests.size() + " load-test nodes; at most "
                            + WorkflowGraph.MAX_LOAD_TEST_NODES + " can be analysed");
        }
        this.comparable = buildComparability();
    }

    /** Every region any load-test node places workers in, in first-seen order. */
    public Set<String> regions() {
        Set<String> out = new LinkedHashSet<>();
        for (LoadTestNode n : loadTests) {
            for (RegionCount rc : n.fleetAllocation()) out.add(rc.region());
        }
        return out;
    }

    /** Peak concurrent workers per region; empty when the graph runs no load test. */
    public Map<String, Integer> peakWorkersByRegion() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String region : regions()) {
            out.put(region, peakFor(region).total());
        }
        return out;
    }

    /** The peak for one region, with the task names that make it up. */
    public Peak peakFor(String region) {
        int k = loadTests.size();
        if (k == 0) return new Peak(0, List.of());
        int[] weight = new int[k];
        for (int i = 0; i < k; i++) weight[i] = loadTests.get(i).workersIn(region);

        int bestTotal = 0;
        int bestMask = 0;
        // Every subset of the load-test nodes; kept an antichain by the
        // comparability mask, which is why the node cap is what it is.
        for (int mask = 1; mask < (1 << k); mask++) {
            if (!isAntichain(mask, k)) continue;
            int total = 0;
            for (int i = 0; i < k; i++) {
                if ((mask & (1 << i)) != 0) total += weight[i];
            }
            if (total > bestTotal) {
                bestTotal = total;
                bestMask = mask;
            }
        }
        return new Peak(bestTotal, peakSet(bestMask, region));
    }

    private boolean isAntichain(int mask, int k) {
        for (int i = 0; i < k; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if ((comparable[i] & mask) != 0) return false;
        }
        return true;
    }

    private List<String> peakSet(int mask, String region) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < loadTests.size(); i++) {
            if ((mask & (1 << i)) != 0 && loadTests.get(i).workersIn(region) > 0) {
                names.add(loadTests.get(i).name());
            }
        }
        return names;
    }

    /**
     * Reachability over all nodes, projected onto the load-test ones. Node count
     * is capped at 64, so one {@code long} per node holds the whole closure.
     */
    private int[] buildComparability() {
        List<WorkflowNode> nodes = graph.nodes();
        int n = nodes.size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(nodes.get(i).id(), i);

        List<List<Integer>> succ = new ArrayList<>(n);
        for (int i = 0; i < n; i++) succ.add(new ArrayList<>());
        int[] inDegree = new int[n];
        for (WorkflowEdge e : graph.edges()) {
            Integer s = index.get(e.source());
            Integer t = index.get(e.target());
            if (s == null || t == null) continue;   // validator rejects these before we get here
            succ.get(s).add(t);
            inDegree[t]++;
        }

        // Kahn's order, then relax reachability along its reverse.
        int[] order = new int[n];
        int filled = 0;
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) ready.add(i);
        }
        while (!ready.isEmpty()) {
            int u = ready.poll();
            order[filled++] = u;
            for (int v : succ.get(u)) {
                if (--inDegree[v] == 0) ready.add(v);
            }
        }
        if (filled != n) {
            throw new IllegalArgumentException("graph is cyclic — validate before analysing");
        }

        long[] reach = new long[n];
        for (int i = n - 1; i >= 0; i--) {
            int u = order[i];
            long r = 0L;
            for (int v : succ.get(u)) r |= (1L << v) | reach[v];
            reach[u] = r;
        }

        int k = loadTests.size();
        int[] out = new int[k];
        for (int a = 0; a < k; a++) {
            int ia = index.get(loadTests.get(a).id());
            for (int b = 0; b < k; b++) {
                if (a == b) continue;
                int ib = index.get(loadTests.get(b).id());
                boolean ordered = (reach[ia] & (1L << ib)) != 0 || (reach[ib] & (1L << ia)) != 0;
                if (ordered) out[a] |= (1 << b);
            }
        }
        return out;
    }

    /** A region's peak and the tasks that produce it — the message an operator can act on. */
    public record Peak(int total, List<String> tasks) {}
}
