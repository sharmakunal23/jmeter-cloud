import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { NodeEditor } from "../workflow/NodeEditor";
import type { EmailNode, LoadTestNode, WorkflowNode } from "../../api/workflows";
import { newNode } from "../../lib/workflowGraph";

function renderEditor(node: WorkflowNode, onChange = vi.fn()) {
  render(
    <NodeEditor
      node={node}
      applications={[]}
      templates={[]}
      regions={[{ region: "na-east", maxAvailable: 4 }]}
      groupNotify={{ to: ["team@example.com"], cc: [], bcc: [] }}
      onChange={onChange}
      onDelete={vi.fn()}
    />,
  );
  return onChange;
}

describe("NodeEditor — email recipients", () => {
  const email = () => newNode("EMAIL", "m1", { x: 0, y: 0 }) as EmailNode;

  it("keeps the comma the operator types, so more than one recipient can be entered", () => {
    // The bug this pins: a fully controlled box rendering list.join(", ")
    // parses "a@x.com," back to one entry and erases the separator, making a
    // second address impossible to type.
    const onChange = renderEditor(email());
    const to = screen.getByPlaceholderText("team@example.com") as HTMLInputElement;

    fireEvent.change(to, { target: { value: "a@x.com," } });
    expect(to.value).toBe("a@x.com,");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ to: ["a@x.com"] }));

    fireEvent.change(to, { target: { value: "a@x.com, b@y.com" } });
    expect(to.value).toBe("a@x.com, b@y.com");
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ to: ["a@x.com", "b@y.com"] }));
  });

  it("an empty box says it will inherit the group's recipients", () => {
    renderEditor(email());
    expect(screen.getByText(/inherits the group's: team@example.com/)).toBeInTheDocument();
  });

  it("shows the addresses a node already carries", () => {
    const node = { ...email(), to: ["kept@example.com", "second@example.com"] } as WorkflowNode;
    renderEditor(node);
    expect((screen.getByDisplayValue("kept@example.com, second@example.com"))).toBeInTheDocument();
  });
});

describe("NodeEditor — load test properties", () => {
  const load = () => newNode("LOAD_TEST", "t1", { x: 0, y: 0 }) as LoadTestNode;

  it("'Add property' is disabled once a blank row exists, rather than doing nothing", () => {
    const onChange = renderEditor(load());
    const add = screen.getByRole("button", { name: "+ Add property" });
    expect(add).toBeEnabled();

    fireEvent.click(add);
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ properties: { "": "" } }));

    // Re-render with the blank row committed: a second click could only be a no-op.
    const node = { ...load(), properties: { "": "" } } as WorkflowNode;
    render(
      <NodeEditor
        node={node} applications={[]} templates={[]}
        regions={[]} groupNotify={{ to: [], cc: [], bcc: [] }}
        onChange={vi.fn()} onDelete={vi.fn()}
      />,
    );
    expect(screen.getAllByRole("button", { name: "+ Add property" }).at(-1)).toBeDisabled();
  });
});
