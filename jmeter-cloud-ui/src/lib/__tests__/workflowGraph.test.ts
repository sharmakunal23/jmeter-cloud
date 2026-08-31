import { describe, expect, it } from "vitest";

import type { WorkflowGraph } from "../../api/workflows";
import { autoArrange, needsArrange, newNode, nextNodeId, toFlowEdges } from "../workflowGraph";

const at = (x: number, y: number) => ({ x, y });

function graphOf(ids: string[], edges: Array<[string, string]>): WorkflowGraph {
  return {
    v: 1,
    nodes: ids.map((id) => newNode("DELAY", id, at(0, 0))),
    edges: edges.map(([source, target]) => ({ id: `${source}-${target}`, source, target })),
  };
}

describe("workflowGraph — new tasks", () => {
  it("gives each type usable defaults rather than an empty shell", () => {
    const health = newNode("HEALTH_CHECK", "h1", at(0, 0));
    expect(health).toMatchObject({ type: "HEALTH_CHECK", attempts: 3, intervalSeconds: 15, joinPolicy: "ALL" });

    const load = newNode("LOAD_TEST", "t1", at(0, 0));
    expect(load).toMatchObject({ type: "LOAD_TEST", successWhen: "COMPLETED_ONLY", maxDurationMinutes: 120 });
    // Required and empty on purpose: the operator must choose the workers.
    expect(load).toHaveProperty("fleetAllocation", []);

    expect(newNode("DELAY", "d1", at(0, 0))).toMatchObject({ seconds: 60 });
  });

  it("names tasks readably and never reuses an id — it appears in email placeholders", () => {
    const used = new Set(["healthCheck1", "healthCheck2"]);
    expect(nextNodeId("HEALTH_CHECK", used)).toBe("healthCheck3");
    expect(nextNodeId("LOAD_TEST", new Set())).toBe("loadTest1");
  });
});

describe("workflowGraph — edges", () => {
  it("only the non-default conditions get a label — a graph of success paths stays readable", () => {
    const edges = toFlowEdges({
      v: 1, nodes: [],
      edges: [
        { id: "e1", source: "a", target: "b", condition: "ON_SUCCESS" },
        { id: "e2", source: "a", target: "c", condition: "ON_FAILURE" },
        { id: "e3", source: "a", target: "d", condition: "ALWAYS" },
      ],
    });
    expect(edges.map((e) => e.label)).toEqual([undefined, "on failure", "always"]);
  });
});

describe("workflowGraph — auto arrange", () => {
  it("puts a chain in one column, one row per step", () => {
    const arranged = autoArrange(graphOf(["a", "b", "c"], [["a", "b"], ["b", "c"]]));
    const y = arranged.nodes.map((n) => n.position.y);
    expect(y[0]).toBeLessThan(y[1]);
    expect(y[1]).toBeLessThan(y[2]);
    // A chain has one node per row, so they share a column.
    expect(new Set(arranged.nodes.map((n) => n.position.x)).size).toBe(1);
  });

  it("puts parallel tasks side by side on the same row", () => {
    const arranged = autoArrange(graphOf(["gate", "a", "b"], [["gate", "a"], ["gate", "b"]]));
    const byId = new Map(arranged.nodes.map((n) => [n.id, n.position]));
    expect(byId.get("a")!.y).toBe(byId.get("b")!.y);
    expect(byId.get("a")!.x).not.toBe(byId.get("b")!.x);
    expect(byId.get("gate")!.y).toBeLessThan(byId.get("a")!.y);
  });

  it("a task sits below its deepest upstream, not its first", () => {
    // d depends on both b (depth 2) and a (depth 1) — it belongs under b.
    const arranged = autoArrange(graphOf(["a", "b", "c", "d"],
      [["a", "b"], ["b", "c"], ["a", "d"], ["c", "d"]]));
    const byId = new Map(arranged.nodes.map((n) => [n.id, n.position]));
    expect(byId.get("d")!.y).toBeGreaterThan(byId.get("c")!.y);
  });

  it("a cycle still lays out instead of hanging the canvas", () => {
    const arranged = autoArrange(graphOf(["a", "b"], [["a", "b"], ["b", "a"]]));
    expect(arranged.nodes).toHaveLength(2);
    expect(arranged.nodes.every((n) => Number.isFinite(n.position.y))).toBe(true);
  });

  it("only an unpositioned graph asks to be arranged", () => {
    expect(needsArrange(graphOf(["a"], []))).toBe(true);
    expect(needsArrange({ v: 1, nodes: [newNode("DELAY", "a", at(40, 90))], edges: [] })).toBe(false);
    expect(needsArrange({ v: 1, nodes: [], edges: [] })).toBe(false);
  });
});
