/**
 * Conversions between the saved `WorkflowGraph` and what React Flow renders,
 * plus the layered auto-arrange.
 *
 * <p>The conversion is one-way on purpose. React Flow decorates every node it
 * owns (`measured`, `selected`, `dragging`, …), and the builder never feeds
 * those objects back: it edits the saved graph directly and takes only `x`/`y`
 * from a drag, so the extras can never reach the graph CLOB.
 */

import type { Edge, Node } from "@xyflow/react";

import type { EdgeCondition, NodeType, WorkflowGraph, WorkflowNode } from "../api/workflows";

export const NODE_WIDTH = 210;
export const NODE_HEIGHT = 78;
const LAYER_GAP_Y = 130;
const SIBLING_GAP_X = 60;

/** Human labels for the palette and for any message naming a task type. */
export const NODE_LABELS: Record<NodeType, string> = {
  HEALTH_CHECK: "Health check",
  LOAD_TEST: "Load test",
  EMAIL: "Email",
  DELAY: "Wait",
  APPROVAL: "Approval",
};

export const EDGE_LABELS: Record<EdgeCondition, string> = {
  ON_SUCCESS: "on success",
  ON_FAILURE: "on failure",
  ALWAYS: "always",
};

/** What the builder drops on the canvas for a new task of each type. */
export function newNode(type: NodeType, id: string, position: { x: number; y: number }): WorkflowNode {
  const base = { id, name: NODE_LABELS[type], position, joinPolicy: "ALL" as const };
  switch (type) {
    case "HEALTH_CHECK":
      return { ...base, type, application: "", requirement: "ALL", attempts: 3, intervalSeconds: 15, timeoutSeconds: 5 };
    case "LOAD_TEST":
      return {
        ...base, type, application: "", templateBlobId: "", fleetAllocation: [],
        properties: {}, successWhen: "COMPLETED_ONLY", maxDurationMinutes: 120,
      };
    case "EMAIL":
      return {
        ...base, type, to: [], cc: [], bcc: [],
        subject: "${workflow.name} — ${execution.state}",
        body: "", includeSummary: true,
      };
    case "DELAY":
      return { ...base, type, seconds: 60 };
    case "APPROVAL":
      return { ...base, type, instructions: "" };
  }
}

/** A stable, readable node id — the operator sees it in `${task.<id>.state}`. */
export function nextNodeId(type: NodeType, existing: Set<string>): string {
  const stem = type.toLowerCase().replace(/_(.)/g, (_, c: string) => c.toUpperCase());
  for (let i = 1; i < 1000; i++) {
    const candidate = `${stem}${i}`;
    if (!existing.has(candidate)) return candidate;
  }
  return `${stem}${Date.now()}`;
}

export interface FlowNodeData extends Record<string, unknown> {
  node: WorkflowNode;
  /** Present on an execution view; drives the node's state colouring. */
  state?: string;
  /** Validation messages naming this task, shown as a marker on the node. */
  problems?: string[];
}

export function toFlowNodes(graph: WorkflowGraph, extras?: {
  states?: Record<string, string>;
  problems?: Record<string, string[]>;
}): Node<FlowNodeData>[] {
  return graph.nodes.map((n) => ({
    id: n.id,
    type: "workflowTask",
    position: n.position,
    data: { node: n, state: extras?.states?.[n.id], problems: extras?.problems?.[n.id] },
  }));
}

export function toFlowEdges(graph: WorkflowGraph): Edge[] {
  return graph.edges.map((e) => {
    const condition = e.condition ?? "ON_SUCCESS";
    return {
      id: e.id,
      source: e.source,
      target: e.target,
      type: "smoothstep",
      // The default success path stays unlabelled: labelling every edge
      // "on success" is noise on a graph that is mostly success paths.
      label: condition === "ON_SUCCESS" ? undefined : EDGE_LABELS[condition],
      className: `wfEdge wfEdge--${condition.toLowerCase()}`,
      data: { condition },
      markerEnd: { type: "arrowclosed" as const, width: 16, height: 16 },
    };
  });
}

/**
 * Layered arrange: every task sits one row below its deepest upstream task, and
 * rows are centred. Runs on a graph with no saved positions and behind the
 * builder's "Auto arrange", never on every render — dragging is the operator's.
 *
 * <p>A cycle would make the depth walk non-terminating, so nodes it cannot
 * place land in a final row rather than hanging the canvas.
 */
export function autoArrange(graph: WorkflowGraph): WorkflowGraph {
  const incoming = new Map<string, string[]>();
  const outgoing = new Map<string, string[]>();
  for (const n of graph.nodes) {
    incoming.set(n.id, []);
    outgoing.set(n.id, []);
  }
  for (const e of graph.edges) {
    incoming.get(e.target)?.push(e.source);
    outgoing.get(e.source)?.push(e.target);
  }

  // Kahn layering: a node's row is one past its deepest satisfied predecessor.
  const depth = new Map<string, number>();
  const pending = new Map<string, number>();
  for (const n of graph.nodes) pending.set(n.id, incoming.get(n.id)?.length ?? 0);
  const queue = graph.nodes.filter((n) => (pending.get(n.id) ?? 0) === 0).map((n) => n.id);
  for (const id of queue) depth.set(id, 0);
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i];
    for (const next of outgoing.get(id) ?? []) {
      depth.set(next, Math.max(depth.get(next) ?? 0, (depth.get(id) ?? 0) + 1));
      const left = (pending.get(next) ?? 1) - 1;
      pending.set(next, left);
      if (left === 0) queue.push(next);
    }
  }
  // Anything a cycle kept out of the walk still needs a home.
  const maxDepth = Math.max(0, ...[...depth.values()]);
  for (const n of graph.nodes) if (!depth.has(n.id)) depth.set(n.id, maxDepth + 1);

  const rows = new Map<number, string[]>();
  for (const n of graph.nodes) {
    const d = depth.get(n.id) ?? 0;
    if (!rows.has(d)) rows.set(d, []);
    rows.get(d)!.push(n.id);
  }
  const widest = Math.max(...[...rows.values()].map((r) => r.length), 1);
  const canvasWidth = widest * (NODE_WIDTH + SIBLING_GAP_X);

  const positions = new Map<string, { x: number; y: number }>();
  for (const [d, ids] of rows) {
    const rowWidth = ids.length * (NODE_WIDTH + SIBLING_GAP_X);
    const startX = (canvasWidth - rowWidth) / 2;
    ids.forEach((id, i) => {
      positions.set(id, {
        x: Math.round(startX + i * (NODE_WIDTH + SIBLING_GAP_X)),
        y: d * (NODE_HEIGHT + LAYER_GAP_Y),
      });
    });
  }

  return {
    ...graph,
    nodes: graph.nodes.map((n) => ({ ...n, position: positions.get(n.id) ?? n.position })),
  };
}

/** True when no node carries a position — a graph that has never been arranged. */
export function needsArrange(graph: WorkflowGraph): boolean {
  return graph.nodes.length > 0
    && graph.nodes.every((n) => !n.position || (n.position.x === 0 && n.position.y === 0));
}
