import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";

import type { WorkflowNode } from "../../api/workflows";
import type { FlowNodeData } from "../../lib/workflowGraph";
import { NODE_LABELS } from "../../lib/workflowGraph";
import { toneFor, labelFor } from "./ExecutionStateChip";

/**
 * One task on the canvas. The same node renders in the builder and on a live
 * execution — the execution just supplies a `state`, which is what tints the
 * card, so an operator reads one visual language in both places.
 */
export function WorkflowTaskNode({ data, selected }: NodeProps<Node<FlowNodeData>>) {
  const node = data.node;
  const state = data.state;
  const problems = data.problems ?? [];
  const classes = [
    "wfNode",
    `wfNode--${node.type.toLowerCase()}`,
    state ? `wfNode--state-${state.toLowerCase()}` : "",
    selected ? "isSelected" : "",
    problems.length > 0 ? "hasProblem" : "",
  ].filter(Boolean).join(" ");

  return (
    <div className={classes} title={problems.join("\n") || undefined}>
      <Handle type="target" position={Position.Top} />
      <div className="wfNode__head">
        <span className="wfNode__type">{NODE_LABELS[node.type]}</span>
        {state && <span className={`chip chip--xs ${toneFor(state as never)}`}>{labelFor(state as never)}</span>}
        {problems.length > 0 && (
          <span className="wfNode__problem" aria-label={`${problems.length} problem(s)`}>!</span>
        )}
      </div>
      <div className="wfNode__name">{node.name}</div>
      <div className="wfNode__detail">{summarise(node)}</div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

/** The one line that says what this task will actually do. */
function summarise(node: WorkflowNode): string {
  switch (node.type) {
    case "HEALTH_CHECK": {
      const rule = node.requirement === "ANY" ? "any endpoint"
        : node.requirement === "AT_LEAST" ? `${node.minHealthy ?? 1}+ endpoints`
        : "all endpoints";
      return `${node.application || "no application"} · ${rule}`;
    }
    case "LOAD_TEST": {
      const workers = node.fleetAllocation.reduce((sum, f) => sum + f.count, 0);
      const where = node.fleetAllocation.map((f) => f.region).join(", ");
      if (!node.application) return "no application";
      return `${node.application} · ${workers} worker${workers === 1 ? "" : "s"}${where ? ` in ${where}` : ""}`;
    }
    case "EMAIL": {
      const named = (node.to?.length ?? 0) + (node.cc?.length ?? 0) + (node.bcc?.length ?? 0);
      return named === 0 ? "to the group's recipients" : `${named} recipient${named === 1 ? "" : "s"}`;
    }
    case "DELAY":
      return node.seconds >= 60
        ? `${Math.round(node.seconds / 60)} min`
        : `${node.seconds} s`;
    case "APPROVAL":
      return node.deadlineMinutes ? `times out after ${node.deadlineMinutes} min` : "waits indefinitely";
  }
}
