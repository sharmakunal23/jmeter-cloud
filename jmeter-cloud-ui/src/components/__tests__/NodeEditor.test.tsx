import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { NodeEditor } from "../workflow/NodeEditor";
import type { EmailNode, LoadTestNode, WorkflowNode } from "../../api/workflows";
import type { Application } from "../../api/applications";
import type { TemplateBody, TemplateSummary } from "../../api/templates";
import { newNode } from "../../lib/workflowGraph";

// The load-test editor reads the chosen template to pre-fill its settings.
const mocks = vi.hoisted(() => ({ load: vi.fn() }));
vi.mock("../../api/templates", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/templates")>();
  return { ...actual, templatesApi: { ...actual.templatesApi, load: mocks.load } };
});

const APPS = [
  { applicationId: "a1", name: "card-auth" },
  { applicationId: "a2", name: "card-capture" },
] as Application[];

const TEMPLATES: TemplateSummary[] = [
  { blobId: "t-auth",    name: "Auth 30m",    application: "card-auth",    uploadedAt: "", sizeBytes: 1 },
  { blobId: "t-capture", name: "Capture 10m", application: "card-capture", uploadedAt: "", sizeBytes: 1 },
];

function body(over: Partial<TemplateBody> = {}): TemplateBody {
  return {
    v: 2,
    application: "card-auth",
    testPlanBlobId: "plan-1",
    fleetAllocation: [{ region: "na-east", count: 2 }],
    saveResults: true,
    globalProperties: { threads: "50" },
    ...over,
  };
}

function renderEditor(node: WorkflowNode, opts: {
  onChange?: (next: WorkflowNode) => void;
  inboundCount?: number;
  regions?: { region: string; maxAvailable: number }[];
} = {}) {
  const onChange = opts.onChange ?? vi.fn();
  render(
    <MemoryRouter>
      <NodeEditor
        node={node}
        applications={APPS}
        templates={TEMPLATES}
        regions={opts.regions ?? [{ region: "na-east", maxAvailable: 4 }]}
        groupNotify={{ to: ["team@example.com"], cc: [], bcc: [] }}
        inboundCount={opts.inboundCount ?? 0}
        onChange={onChange}
        onDelete={vi.fn()}
      />
    </MemoryRouter>,
  );
  return onChange;
}

/** Renders one node and hands back a rerender that swaps the node in place. */
function renderBuilderNode(node: WorkflowNode, onChange: (n: WorkflowNode) => void) {
  const ui = (n: WorkflowNode) => (
    <MemoryRouter>
      <NodeEditor
        node={n}
        applications={APPS}
        templates={[...TEMPLATES,
          { blobId: "t-other", name: "Other", application: "card-auth", uploadedAt: "", sizeBytes: 1 }]}
        regions={[{ region: "na-east", maxAvailable: 4 }]}
        groupNotify={{ to: [], cc: [], bcc: [] }}
        inboundCount={0}
        onChange={onChange}
        onDelete={vi.fn()}
      />
    </MemoryRouter>
  );
  const r = render(ui(node));
  return { rerender: (n: WorkflowNode) => r.rerender(ui(n)) };
}

beforeEach(() => {
  mocks.load.mockReset();
  mocks.load.mockResolvedValue(body());
});

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

describe("NodeEditor — the join policy only appears when there is a join", () => {
  it("is hidden with one inbound link — there is nothing to join", () => {
    renderEditor(newNode("DELAY", "d1", { x: 0, y: 0 }), { inboundCount: 1 });
    expect(screen.queryByText(/links arrive/)).toBeNull();
  });

  it("appears once two links arrive, and says how many", () => {
    renderEditor(newNode("DELAY", "d2", { x: 0, y: 0 }), { inboundCount: 2 });
    expect(screen.getByText(/its 2 links arrive/)).toBeInTheDocument();
  });
});

describe("NodeEditor — a load test is asked for in the order the answers depend", () => {
  const load = () => newNode("LOAD_TEST", "t1", { x: 0, y: 0 }) as LoadTestNode;

  it("asks only for the application first — no template, workers or settings yet", () => {
    renderEditor(load());
    expect(screen.getByText("Application")).toBeInTheDocument();
    expect(screen.queryByText("Template")).toBeNull();
    expect(screen.queryByText("Workers")).toBeNull();
    expect(screen.queryByText("Save results")).toBeNull();
    expect(screen.queryByRole("button", { name: "+ Add property" })).toBeNull();
  });

  it("offers only the chosen application's templates", () => {
    renderEditor({ ...load(), application: "card-auth" } as WorkflowNode);
    expect(screen.getByRole("option", { name: "Auth 30m" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Capture 10m" })).toBeNull();
  });

  it("says so when the application has no templates, rather than offering another application's", () => {
    renderEditor({ ...load(), application: "smokeapp" } as WorkflowNode);
    expect(screen.getByText(/No templates saved for smokeapp/)).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Auth 30m" })).toBeNull();
  });

  it("changing the application drops a template that belonged to the old one", () => {
    const onChange = renderEditor(
      { ...load(), application: "card-auth", templateBlobId: "t-auth" } as WorkflowNode);
    fireEvent.change(screen.getByDisplayValue("card-auth"), { target: { value: "card-capture" } });
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      application: "card-capture", templateBlobId: "", fleetAllocation: [],
    }));
  });

  it("picking a template fills the workers and Save results it was saved with", async () => {
    const onChange = vi.fn();
    renderEditor(
      { ...load(), application: "card-auth", templateBlobId: "t-auth" } as WorkflowNode,
      { onChange });

    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      fleetAllocation: [{ region: "na-east", count: 2 }],
      saveResults: true,
      properties: { threads: "50" },
    }));
  });

  it("leaves out workers for a cluster the group has not reserved, and says it did", async () => {
    mocks.load.mockResolvedValue(body({
      fleetAllocation: [{ region: "na-east", count: 2 }, { region: "eu-west", count: 3 }],
    }));
    const onChange = vi.fn();
    renderEditor(
      { ...load(), application: "card-auth", templateBlobId: "t-auth" } as WorkflowNode,
      { onChange });

    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      fleetAllocation: [{ region: "na-east", count: 2 }],
    }));
    expect(await screen.findByText(/also used eu-west/)).toBeInTheDocument();
  });

  it("clamps a template that wants more workers than the group reserves", async () => {
    mocks.load.mockResolvedValue(body({ fleetAllocation: [{ region: "na-east", count: 9 }] }));
    const onChange = vi.fn();
    renderEditor(
      { ...load(), application: "card-auth", templateBlobId: "t-auth" } as WorkflowNode,
      { onChange });

    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      fleetAllocation: [{ region: "na-east", count: 4 }],
    }));
  });

  it("changing the template replaces the previous one's property defaults", async () => {
    // Keeping template A's -J values while the box reads template B is the
    // opposite of what picking a template means — and workers and Save results
    // beside them are replaced, so leaving properties behind is incoherent.
    mocks.load.mockResolvedValue(body({ globalProperties: { threads: "50" } }));
    const onChange = vi.fn();
    const { rerender } = renderBuilderNode(
      { ...load(), application: "card-auth", templateBlobId: "t-auth" } as WorkflowNode, onChange);
    await waitFor(() => expect(onChange).toHaveBeenCalled());

    mocks.load.mockResolvedValue(body({ globalProperties: { threads: "200", rampUp: "60" } }));
    rerender({
      ...load(), application: "card-auth", templateBlobId: "t-other",
      properties: { threads: "50" }, fleetAllocation: [{ region: "na-east", count: 2 }],
    } as WorkflowNode);

    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({
      properties: { threads: "200", rampUp: "60" },
    })));
  });

  it("does NOT re-seed a saved task — the fleet the operator saved survives a reopen", async () => {
    const onChange = vi.fn();
    renderEditor({
      ...load(),
      application: "card-auth",
      templateBlobId: "t-auth",
      fleetAllocation: [{ region: "na-east", count: 1 }],
    } as WorkflowNode, { onChange });

    await waitFor(() => expect(mocks.load).toHaveBeenCalled());
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue("1")).toBeInTheDocument();
  });

  it("an unreadable template does not block the task — it says the defaults are missing", async () => {
    mocks.load.mockRejectedValue(new Error("404 not found"));
    renderEditor(
      { ...load(), application: "card-auth", templateBlobId: "t-auth" } as WorkflowNode);
    expect(await screen.findByText(/Could not read the template/)).toBeInTheDocument();
  });

  it("'Add property' adds one blank row", async () => {
    const onChange = renderEditor({
      ...load(), application: "card-auth", templateBlobId: "t-auth",
      fleetAllocation: [{ region: "na-east", count: 1 }], properties: {},
    } as WorkflowNode);

    const add = await screen.findByRole("button", { name: "+ Add property" });
    expect(add).toBeEnabled();
    fireEvent.click(add);
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ properties: { "": "" } }));
  });

  it("'Add property' is disabled once a blank row exists, rather than doing nothing", async () => {
    renderEditor({
      ...load(), application: "card-auth", templateBlobId: "t-auth",
      fleetAllocation: [{ region: "na-east", count: 1 }], properties: { "": "" },
    } as WorkflowNode);

    expect(await screen.findByRole("button", { name: "+ Add property" })).toBeDisabled();
  });
});
