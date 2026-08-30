import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { ApplicationGroupsDialog } from "../ApplicationGroupsDialog";

vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return {
    ...actual,
    applicationGroupsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
import { applicationGroupsApi } from "../../api/applicationGroups";
import { ApplicationApiError } from "../../api/applications";
const api = applicationGroupsApi as unknown as {
  list: ReturnType<typeof vi.fn>;
  create: ReturnType<typeof vi.fn>;
  update: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

const cps = { groupId: "cps", name: "Servicing MQ", description: "MQ apps", createdAt: "2026-08-29T00:00:00Z", applicationCount: 2 };
const empty = { groupId: "demo", name: "Demo", createdAt: "2026-08-29T00:00:00Z", applicationCount: 0 };

beforeEach(() => {
  api.list.mockReset();
  api.create.mockReset();
  api.update.mockReset();
  api.delete.mockReset();
  api.list.mockResolvedValue([cps, empty]);
});

describe("ApplicationGroupsDialog", () => {
  it("lists groups with id and application count", async () => {
    render(<ApplicationGroupsDialog onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("Servicing MQ")).toBeInTheDocument());
    const rows = Array.from(document.querySelectorAll("tbody tr")).map((r) => r.textContent ?? "");
    expect(rows[0]).toContain("cps");
    expect(rows[0]).toContain("2");
    expect(rows[1]).toContain("demo");
  });

  it("adds a group: validates the id, posts, reloads, and tells the caller", async () => {
    api.create.mockResolvedValue({ groupId: "cpp", name: "Card Payments", createdAt: "x", applicationCount: 0 });
    const onChanged = vi.fn();
    render(<ApplicationGroupsDialog onClose={vi.fn()} onChanged={onChanged} />);
    await waitFor(() => expect(screen.getByText("Servicing MQ")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/^Id \*/i), { target: { value: "CPP" } });
    fireEvent.change(screen.getByLabelText(/^Name \*/i), { target: { value: "Card Payments" } });
    expect(screen.getByRole("button", { name: /Add group/i })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent(/lowercase/);

    fireEvent.change(screen.getByLabelText(/^Id \*/i), { target: { value: "cpp" } });
    fireEvent.click(screen.getByRole("button", { name: /Add group/i }));
    await waitFor(() => expect(api.create).toHaveBeenCalledWith({
      groupId: "cpp", name: "Card Payments", description: undefined, grafanaLiveUrl: undefined, grafanaHistoryUrl: undefined, hotDays: 7,
      recyclePolicy: "REUSE", maxRunsPerPod: null, podMaxAgeHours: null, alwaysOn: false,
    }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it("surfaces a duplicate id as an inline error", async () => {
    api.create.mockRejectedValue(new ApplicationApiError(409, "APPLICATION_GROUP_ID_TAKEN", "taken"));
    render(<ApplicationGroupsDialog onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("Servicing MQ")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText(/^Id \*/i), { target: { value: "cps" } });
    fireEvent.change(screen.getByLabelText(/^Name \*/i), { target: { value: "Again" } });
    fireEvent.click(screen.getByRole("button", { name: /Add group/i }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("A group with this id already exists."));
  });

  it("renames inline through PUT", async () => {
    api.update.mockResolvedValue({ ...cps, name: "Servicing MQ (Card)" });
    render(<ApplicationGroupsDialog onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("Servicing MQ")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "edit group cps" }));
    fireEvent.change(screen.getByLabelText("name of group cps"), { target: { value: "Servicing MQ (Card)" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.update).toHaveBeenCalledWith("cps", {
      name: "Servicing MQ (Card)", description: "MQ apps", grafanaLiveUrl: undefined, grafanaHistoryUrl: undefined, hotDays: 7,
      recyclePolicy: "REUSE", maxRunsPerPod: null, podMaxAgeHours: null, alwaysOn: false,
    }));
  });

  it("the pod policy is the group's: add and edit carry the lifecycle policy + always-on, and every save sends the full record", async () => {
    api.list.mockResolvedValue([
      { ...cps, grafanaLiveUrl: "https://g.example.com/d/cps", hotDays: 14, recyclePolicy: "EVERY_RUN", alwaysOn: true },
      empty,
    ]);
    api.create.mockResolvedValue({ groupId: "cpp", name: "Card Payments", createdAt: "x", applicationCount: 0 });
    api.update.mockResolvedValue(cps);
    render(<ApplicationGroupsDialog onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("Servicing MQ")).toBeInTheDocument());
    // The read-only row summarises the policy.
    expect(screen.getByText(/After every run — drain \+ spin a fresh replacement\. Always on\./)).toBeInTheDocument();

    // Add: pick "Drain after every run" + always on.
    fireEvent.change(screen.getByLabelText(/^Id \*/i), { target: { value: "cpp" } });
    fireEvent.change(screen.getByLabelText(/^Name \*/i), { target: { value: "Card Payments" } });
    const addForm = screen.getByRole("button", { name: /Add group/i }).closest("form")!;
    fireEvent.click(addForm.querySelector('input[name="newRecyclePolicy"][value="DRAIN_AFTER_RUN"]')!);
    fireEvent.click(addForm.querySelector("#newAlwaysOn")!);
    fireEvent.click(screen.getByRole("button", { name: /Add group/i }));
    await waitFor(() => expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
      groupId: "cpp", recyclePolicy: "DRAIN_AFTER_RUN", alwaysOn: true, maxRunsPerPod: null, podMaxAgeHours: null,
    })));

    // Edit: the row hydrates the stored policy; saving sends the whole group, Grafana + hot days included.
    fireEvent.click(screen.getByRole("button", { name: "edit group cps" }));
    const everyRun = document.querySelector('input[name="edit-cpsRecyclePolicy"][value="EVERY_RUN"]') as HTMLInputElement;
    expect(everyRun.checked).toBe(true);
    expect((document.querySelector("#edit-cpsAlwaysOn") as HTMLInputElement).checked).toBe(true);
    fireEvent.click(document.querySelector('input[name="edit-cpsRecyclePolicy"][value="REUSE"]')!);
    fireEvent.click(document.querySelector("#edit-cpsAlwaysOn")!);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.update).toHaveBeenCalledWith("cps", {
      name: "Servicing MQ", description: "MQ apps", grafanaLiveUrl: "https://g.example.com/d/cps", grafanaHistoryUrl: undefined, hotDays: 14,
      recyclePolicy: "REUSE", maxRunsPerPod: null, podMaxAgeHours: null, alwaysOn: false,
    }));
  });

  it("a group that still has workers or capacity cannot be deleted — the toast says where to fix it", async () => {
    api.delete.mockRejectedValueOnce(new ApplicationApiError(409, "APPLICATION_GROUP_HAS_WORKERS", "has workers"));
    render(<ApplicationGroupsDialog onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("Demo")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "delete group demo" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete group" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("This group still has workers or capacity."));
    expect(screen.getByRole("status")).toHaveTextContent(/Capacity tab/);
  });

  it("deletes through the confirm dialog and reports a group that still has applications", async () => {
    api.delete.mockResolvedValueOnce(undefined);
    const onChanged = vi.fn();
    render(<ApplicationGroupsDialog onClose={vi.fn()} onChanged={onChanged} />);
    await waitFor(() => expect(screen.getByText("Demo")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "delete group demo" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete group" }));
    await waitFor(() => expect(api.delete).toHaveBeenCalledWith("demo"));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());

    api.delete.mockRejectedValueOnce(new ApplicationApiError(409, "APPLICATION_GROUP_HAS_APPLICATIONS", "in use"));
    fireEvent.click(screen.getByRole("button", { name: "delete group cps" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete group" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("This group still has applications."));
  });
});
