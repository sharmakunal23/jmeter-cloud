import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

const mocks = vi.hoisted(() => ({
  get: vi.fn(), validate: vi.fn(), apps: vi.fn(), groups: vi.fn(), templates: vi.fn(),
}));
vi.mock("../../api/workflows", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/workflows")>();
  return {
    ...actual,
    workflowsApi: { ...actual.workflowsApi, get: mocks.get, validate: mocks.validate },
  };
});
vi.mock("../../api/applications", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/applications")>();
  return { ...actual, applicationsApi: { ...actual.applicationsApi, list: mocks.apps } };
});
vi.mock("../../api/applicationGroups", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/applicationGroups")>();
  return { ...actual, applicationGroupsApi: { ...actual.applicationGroupsApi, list: mocks.groups } };
});
vi.mock("../../api/templates", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/templates")>();
  return { ...actual, templatesApi: { ...actual.templatesApi, list: mocks.templates } };
});

import { WorkflowBuilderPage } from "../WorkflowBuilderPage";

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.pathname}{l.search}</div>;
}

/** Mounts the builder at `entry`, with the rest of the app as a landing strip. */
function renderBuilder(entry: { pathname: string; state?: unknown }) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/workflows/:workflowId/edit" element={<WorkflowBuilderPage />} />
        <Route path="/workflows/groups/:groupId/new" element={<WorkflowBuilderPage />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.apps.mockResolvedValue([]);
  mocks.templates.mockResolvedValue([]);
  mocks.groups.mockResolvedValue([{ groupId: "cps", name: "Servicing MQ", capacity: [] }]);
  mocks.validate.mockResolvedValue({ valid: true, errors: [], warnings: [], capacity: [] });
  mocks.get.mockResolvedValue({
    workflowId: "wf1", groupId: "cps", name: "Nightly", description: null,
    enabled: true, revision: 3, graph: { v: 1, nodes: [], edges: [] },
  });
});

describe("WorkflowBuilderPage — leaving the editor", () => {
  it("returns to the exact page the operator opened it from, tab and all", async () => {
    renderBuilder({ pathname: "/workflows/wf1/edit", state: { from: "/workflows/wf1?tab=runs" } });
    fireEvent.click(await screen.findByRole("button", { name: "Exit edit" }));
    await waitFor(() =>
      expect(screen.getByTestId("where")).toHaveTextContent("/workflows/wf1?tab=runs"));
  });

  it("falls back to the workflow itself when there is no history — a deep link or a reload", async () => {
    renderBuilder({ pathname: "/workflows/wf1/edit" });
    fireEvent.click(await screen.findByRole("button", { name: "Exit edit" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/workflows/wf1"));
  });

  it("a new workflow says Cancel, and falls back to its group's list", async () => {
    renderBuilder({ pathname: "/workflows/groups/cps/new" });
    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(screen.getByTestId("where")).toHaveTextContent("/workflows/groups/cps"));
  });

  it("asks before dropping unsaved work, and 'Keep editing' stays put", async () => {
    renderBuilder({ pathname: "/workflows/wf1/edit", state: { from: "/workflows/wf1" } });
    fireEvent.change(await screen.findByDisplayValue("Nightly"), { target: { value: "Renamed" } });

    fireEvent.click(screen.getByRole("button", { name: "Exit edit" }));
    expect(await screen.findByText(/have not been saved/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(screen.queryByText(/have not been saved/)).toBeNull());
    expect(screen.queryByTestId("where")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Exit edit" }));
    fireEvent.click(await screen.findByRole("button", { name: "Exit without saving" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/workflows/wf1"));
  });

  it("leaves without asking when nothing has been touched", async () => {
    renderBuilder({ pathname: "/workflows/wf1/edit", state: { from: "/workflows/wf1" } });
    fireEvent.click(await screen.findByRole("button", { name: "Exit edit" }));
    expect(screen.queryByText(/have not been saved/)).toBeNull();
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/workflows/wf1"));
  });
});
