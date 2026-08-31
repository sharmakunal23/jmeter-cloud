import type { ReactNode } from "react";

import type { WorkflowGraph, WorkflowNode, WorkflowValidation } from "../../api/workflows";

export interface BuilderStatusBarProps {
  graph: WorkflowGraph;
  validation: WorkflowValidation | null;
  /** The task being edited, if any — the next step is usually inside it. */
  selectedNode: WorkflowNode | null;
  /** The group's reservation per cluster: what this workflow has to spend. */
  regions: { region: string; maxAvailable: number }[];
  name: string;
  dirty: boolean;
}

interface Step { tone: "info" | "warn" | "ok"; text: ReactNode }

/**
 * One line: what to do next, and what capacity is left to spend.
 *
 * <p>It replaces a card that said "No load tests, so this workflow reserves no
 * workers" — true, unchanging, and occupying the space the task settings
 * needed. Everything here changes as the graph does.
 */
export function BuilderStatusBar({
  graph, validation, selectedNode, regions, name, dirty,
}: BuilderStatusBarProps) {
  const step = nextStep({ graph, validation, selectedNode, name });
  // Peak per cluster, so "free" means free once this workflow is running.
  const peaks = new Map((validation?.capacity ?? []).map((c) => [c.region, c.peakWorkers]));

  return (
    <div className="builderStatus">
      {/* Only the guidance is a live region: the capacity chips change on every
          keystroke in a worker box, and announcing those would be noise. */}
      <span
        role="status"
        className={`builderStatus__step builderStatus__step--${step.tone}`}
      >{step.text}</span>
      <span className="builderStatus__spacer" />
      {regions.length > 0 && (
        <span className="builderStatus__capacity">
          {regions.map((r) => {
            const peak = peaks.get(r.region) ?? 0;
            const free = r.maxAvailable - peak;
            return (
              <span
                key={r.region}
                className={`chip chip--xs ${free < 0 ? "chip--err" : peak > 0 ? "chip--info" : "chip--muted"}`}
                title={free < 0
                  ? `${-free} more than ${r.region} reserves`
                  : `${peak} of ${r.maxAvailable} reserved workers used at peak`}
              >
                <span className="mono">{r.region}</span>
                {free < 0 ? ` over by ${-free}` : ` ${free} of ${r.maxAvailable} free`}
              </span>
            );
          })}
        </span>
      )}
      {dirty && <span className="builderStatus__dirty">Unsaved changes</span>}
    </div>
  );
}

/** The first thing standing between this graph and a workflow that runs. */
function nextStep({ graph, validation, selectedNode, name }: {
  graph: WorkflowGraph;
  validation: WorkflowValidation | null;
  selectedNode: WorkflowNode | null;
  name: string;
}): Step {
  if (graph.nodes.length === 0) {
    return { tone: "info", text: "Add a task from the left to begin." };
  }
  // A half-filled task the operator is looking at beats a general complaint:
  // it names the very control under their cursor.
  if (selectedNode?.type === "LOAD_TEST") {
    if (!selectedNode.application) return { tone: "info", text: "Choose the application this load test targets." };
    if (!selectedNode.templateBlobId) {
      return { tone: "info", text: `Choose a template for ${selectedNode.application}.` };
    }
  }
  if (selectedNode?.type === "HEALTH_CHECK" && !selectedNode.application) {
    return { tone: "info", text: "Choose the application to check." };
  }
  if (validation && validation.errors.length > 0) {
    return {
      tone: "warn",
      text: validation.errors.length === 1
        ? validation.errors[0]
        : `${validation.errors[0]} (+${validation.errors.length - 1} more)`,
    };
  }
  if (graph.nodes.length > 1 && graph.edges.length === 0) {
    return { tone: "info", text: "Link the tasks — drag from the bottom of one to the top of the next." };
  }
  if (name.trim().length === 0) return { tone: "info", text: "Name the workflow to save it." };
  if (!validation) return { tone: "info", text: "Checking…" };
  return { tone: "ok", text: "Ready to save." };
}
