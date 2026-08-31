import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";

import { WorkflowTaskNode } from "../workflow/WorkflowTaskNode";
import type { WorkflowNode } from "../../api/workflows";
import { newNode } from "../../lib/workflowGraph";

function renderNode(node: WorkflowNode, extras: { state?: string; problems?: string[] } = {}) {
  return render(
    <ReactFlowProvider>
      <WorkflowTaskNode
        id={node.id}
        data={{ node, ...extras }}
        selected={false}
        type="workflowTask"
        dragging={false}
        draggable={false}
        selectable
        deletable={false}
        zIndex={0}
        isConnectable
        positionAbsoluteX={0}
        positionAbsoluteY={0}
      />
    </ReactFlowProvider>,
  );
}

describe("WorkflowTaskNode", () => {
  it("says what a health check will actually do", () => {
    // A renamed node, so the type eyebrow and the title are distinguishable —
    // a fresh node defaults its name to the type label.
    const node = {
      ...newNode("HEALTH_CHECK", "h1", { x: 0, y: 0 }),
      name: "Check payments", application: "payments",
    } as WorkflowNode;
    renderNode(node);
    expect(screen.getByText("Health check")).toBeInTheDocument();
    expect(screen.getByText("Check payments")).toBeInTheDocument();
    expect(screen.getByText(/payments · all endpoints/)).toBeInTheDocument();
  });

  it("a load test shows its workers and where they run — the thing capacity is counted on", () => {
    const node = {
      ...newNode("LOAD_TEST", "t1", { x: 0, y: 0 }),
      application: "payments",
      fleetAllocation: [{ region: "na-east", count: 2 }, { region: "na-west", count: 3 }],
    } as WorkflowNode;
    renderNode(node);
    expect(screen.getByText(/payments · 5 workers in na-east, na-west/)).toBeInTheDocument();
  });

  it("an email with no recipients says it inherits the group's", () => {
    renderNode(newNode("EMAIL", "m1", { x: 0, y: 0 }));
    expect(screen.getByText("to the group's recipients")).toBeInTheDocument();
  });

  it("an approval says whether it can time out", () => {
    renderNode(newNode("APPROVAL", "p1", { x: 0, y: 0 }));
    expect(screen.getByText("waits indefinitely")).toBeInTheDocument();
    renderNode({ ...newNode("APPROVAL", "p2", { x: 0, y: 0 }), deadlineMinutes: 30 } as WorkflowNode);
    expect(screen.getByText("times out after 30 min")).toBeInTheDocument();
  });

  it("an execution's state is on the card, so the graph reads at a glance", () => {
    const { container } = renderNode(newNode("DELAY", "d1", { x: 0, y: 0 }), { state: "SUCCEEDED" });
    expect(screen.getByText("Succeeded")).toBeInTheDocument();
    expect(container.querySelector(".wfNode--state-succeeded")).not.toBeNull();
  });

  it("a task the validator complained about is marked, with the reason on hover", () => {
    const { container } = renderNode(newNode("DELAY", "d1", { x: 0, y: 0 }),
      { problems: ["task 'Wait': wait (seconds) must be between 1 and 86400"] });
    expect(container.querySelector(".wfNode.hasProblem")).not.toBeNull();
    expect(screen.getByTitle(/must be between 1 and 86400/)).toBeInTheDocument();
  });
});
