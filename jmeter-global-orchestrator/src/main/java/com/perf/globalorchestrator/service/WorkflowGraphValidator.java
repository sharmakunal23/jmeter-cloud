package com.perf.globalorchestrator.service;

import com.perf.globalorchestrator.domain.ApprovalNode;
import com.perf.globalorchestrator.domain.DelayNode;
import com.perf.globalorchestrator.domain.EmailNode;
import com.perf.globalorchestrator.domain.HealthCheckNode;
import com.perf.globalorchestrator.domain.HealthRequirement;
import com.perf.globalorchestrator.domain.LoadTestNode;
import com.perf.globalorchestrator.domain.RegionCount;
import com.perf.globalorchestrator.domain.WorkflowEdge;
import com.perf.globalorchestrator.domain.WorkflowGraph;
import com.perf.globalorchestrator.domain.WorkflowNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural rules a saved graph must satisfy: acyclic, within the size caps,
 * every edge endpoint real, every node's own config in range. Referential rules
 * — does this application exist, is it in this group — need the registry and
 * live in {@code WorkflowService}.
 *
 * <p>Returns every violation rather than the first, so the builder can mark all
 * the bad nodes in one pass.
 */
public final class WorkflowGraphValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+");
    /** The launcher's rule for a JMeter {@code -J} key; kept identical so a workflow cannot set what a run cannot. */
    private static final Pattern PROPERTY_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,63}");

    private static final int MAX_NAME = 255;
    private static final int MAX_RECIPIENTS = 50;
    private static final int MAX_BODY = 20_000;

    private WorkflowGraphValidator() {}

    public static List<String> validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        if (graph == null) {
            return List.of("graph is required");
        }
        if (graph.v() != WorkflowGraph.VERSION) {
            errors.add("graph.v must be " + WorkflowGraph.VERSION + " (got " + graph.v() + ")");
        }
        List<WorkflowNode> nodes = graph.nodes();
        if (nodes.isEmpty()) {
            errors.add("a workflow needs at least one task");
            return errors;   // nothing below can say anything useful
        }
        if (nodes.size() > WorkflowGraph.MAX_NODES) {
            errors.add("a workflow holds at most " + WorkflowGraph.MAX_NODES
                    + " tasks (got " + nodes.size() + ")");
            return errors;   // the analyser's bitmask cannot take more
        }
        int loadTests = graph.loadTestNodes().size();
        if (loadTests > WorkflowGraph.MAX_LOAD_TEST_NODES) {
            errors.add("a workflow holds at most " + WorkflowGraph.MAX_LOAD_TEST_NODES
                    + " load tests (got " + loadTests + ") — split it into two workflows");
        }

        Set<String> ids = new HashSet<>();
        for (WorkflowNode n : nodes) {
            String where = "task '" + (n.name() == null ? n.id() : n.name()) + "'";
            if (n.id() == null || !ID.matcher(n.id()).matches()) {
                errors.add(where + ": id must match " + ID.pattern());
            } else if (!ids.add(n.id())) {
                errors.add("duplicate task id '" + n.id() + "'");
            }
            if (isBlank(n.name())) {
                errors.add("task " + n.id() + ": name is required");
            } else if (n.name().length() > MAX_NAME) {
                errors.add(where + ": name is longer than " + MAX_NAME + " characters");
            }
            validateNode(n, where, errors);
        }

        Set<String> edgeIds = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (WorkflowEdge e : graph.edges()) {
            String where = "link " + e.source() + " → " + e.target();
            if (isBlank(e.id())) {
                errors.add(where + ": id is required");
            } else if (!edgeIds.add(e.id())) {
                errors.add("duplicate link id '" + e.id() + "'");
            }
            if (!ids.contains(e.source())) errors.add(where + ": no task '" + e.source() + "'");
            if (!ids.contains(e.target())) errors.add(where + ": no task '" + e.target() + "'");
            if (e.source() != null && e.source().equals(e.target())) {
                errors.add(where + ": a task cannot depend on itself");
            }
            if (!pairs.add(e.source() + "→" + e.target() + ":" + e.condition())) {
                errors.add(where + ": duplicate " + e.condition() + " link");
            }
        }

        if (errors.isEmpty() && hasCycle(graph)) {
            errors.add("the tasks form a loop — a workflow must flow one way");
        }
        return errors;
    }

    private static void validateNode(WorkflowNode node, String where, List<String> errors) {
        switch (node) {
            case HealthCheckNode n -> {
                if (isBlank(n.application())) errors.add(where + ": pick an application to check");
                range(where, "attempts", n.attempts(), 1, 10, errors);
                range(where, "interval (seconds)", n.intervalSeconds(), 5, 300, errors);
                range(where, "timeout (seconds)", n.timeoutSeconds(), 1, 30, errors);
                if (n.requirement() == HealthRequirement.AT_LEAST
                        && (n.minHealthy() == null || n.minHealthy() < 1)) {
                    errors.add(where + ": 'at least' needs a minimum of 1 or more healthy endpoints");
                }
            }
            case LoadTestNode n -> {
                if (isBlank(n.application())) errors.add(where + ": pick an application to test");
                if (isBlank(n.templateBlobId())) errors.add(where + ": pick a template");
                if (n.fleetAllocation().isEmpty()) {
                    errors.add(where + ": allocate workers in at least one cluster");
                }
                Set<String> seen = new HashSet<>();
                for (RegionCount rc : n.fleetAllocation()) {
                    if (isBlank(rc.region())) {
                        errors.add(where + ": a worker allocation is missing its cluster");
                    } else if (!seen.add(rc.region())) {
                        errors.add(where + ": cluster '" + rc.region() + "' is allocated twice");
                    }
                    range(where + " cluster '" + rc.region() + "'", "workers", rc.count(), 1, 1000, errors);
                }
                range(where, "max duration (minutes)", n.maxDurationMinutes(), 1, 1440, errors);
                n.properties().forEach((k, v) -> {
                    if (k == null || !PROPERTY_KEY.matcher(k).matches()) {
                        errors.add(where + ": property name '" + k + "' must match " + PROPERTY_KEY.pattern());
                    }
                    if (v != null && v.length() > 256) {
                        errors.add(where + ": property '" + k + "' is longer than 256 characters");
                    }
                });
            }
            case EmailNode n -> {
                if (isBlank(n.subject())) errors.add(where + ": subject is required");
                if (n.subject() != null && n.subject().length() > MAX_NAME) {
                    errors.add(where + ": subject is longer than " + MAX_NAME + " characters");
                }
                if (isBlank(n.body())) errors.add(where + ": message is required");
                if (n.body() != null && n.body().length() > MAX_BODY) {
                    errors.add(where + ": message is longer than " + MAX_BODY + " characters");
                }
                int total = n.to().size() + n.cc().size() + n.bcc().size();
                if (total > MAX_RECIPIENTS) {
                    errors.add(where + ": at most " + MAX_RECIPIENTS + " recipients (got " + total + ")");
                }
                addresses(where, "To", n.to(), errors);
                addresses(where, "Cc", n.cc(), errors);
                addresses(where, "Bcc", n.bcc(), errors);
            }
            case DelayNode n -> range(where, "wait (seconds)", n.seconds(), 1, 86_400, errors);
            case ApprovalNode n -> {
                if (n.deadlineMinutes() != null) {
                    range(where, "deadline (minutes)", n.deadlineMinutes(), 1, 10_080, errors);
                }
                if (n.instructions() != null && n.instructions().length() > 4000) {
                    errors.add(where + ": instructions are longer than 4000 characters");
                }
            }
        }
    }

    private static void addresses(String where, String field, List<String> list, List<String> errors) {
        for (String a : list) {
            if (a == null || !EMAIL.matcher(a.trim()).matches()) {
                errors.add(where + ": " + field + " address '" + a + "' is not a valid email");
            }
        }
    }

    private static void range(String where, String field, int value, int min, int max, List<String> errors) {
        if (value < min || value > max) {
            errors.add(where + ": " + field + " must be between " + min + " and " + max + " (got " + value + ")");
        }
    }

    /** Kahn's algorithm — anything it cannot drain sits on a cycle. */
    private static boolean hasCycle(WorkflowGraph graph) {
        List<WorkflowNode> nodes = graph.nodes();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) index.put(nodes.get(i).id(), i);
        int[] inDegree = new int[nodes.size()];
        List<List<Integer>> succ = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) succ.add(new ArrayList<>());
        for (WorkflowEdge e : graph.edges()) {
            Integer s = index.get(e.source());
            Integer t = index.get(e.target());
            if (s == null || t == null) continue;
            succ.get(s).add(t);
            inDegree[t]++;
        }
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < inDegree.length; i++) {
            if (inDegree[i] == 0) ready.add(i);
        }
        int drained = 0;
        while (!ready.isEmpty()) {
            int u = ready.poll();
            drained++;
            for (int v : succ.get(u)) {
                if (--inDegree[v] == 0) ready.add(v);
            }
        }
        return drained != nodes.size();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
