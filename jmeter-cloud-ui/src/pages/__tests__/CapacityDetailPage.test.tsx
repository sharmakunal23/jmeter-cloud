import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { CapacityDetailPage } from "../CapacityDetailPage";
import type { ApplicationGroup } from "../../api/applicationGroups";
import type { CapacitySnapshot, PodView } from "../../api/capacity";
import { ApplicationApiError } from "../../api/applications";

vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return {
    ...actual,
    applicationGroupsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/capacity", async () => {
  const actual = await vi.importActual<typeof import("../../api/capacity")>("../../api/capacity");
  return {
    ...actual,
    capacityApi: {
      setMax: vi.fn(),
      listPods: vi.fn(),
      spinPod: vi.fn(),
      restartPod: vi.fn(),
      drainPod: vi.fn(),
    },
  };
});

import { applicationGroupsApi } from "../../api/applicationGroups";
import { capacityApi } from "../../api/capacity";
const groups = applicationGroupsApi as unknown as { get: ReturnType<typeof vi.fn> };
const cap = capacityApi as unknown as {
  setMax: ReturnType<typeof vi.fn>;
  listPods: ReturnType<typeof vi.fn>;
  spinPod: ReturnType<typeof vi.fn>;
  restartPod: ReturnType<typeof vi.fn>;
  drainPod: ReturnType<typeof vi.fn>;
};

beforeEach(() => {
  groups.get.mockReset();
  cap.setMax.mockReset();
  cap.listPods.mockReset();
  cap.spinPod.mockReset();
  cap.restartPod.mockReset();
  cap.drainPod.mockReset();
});

function fixtureGroup(): ApplicationGroup {
  return {
    groupId: "cps",
    name: "Servicing MQ",
    description: null,
    capacity: [{ region: "us-east", maxAvailable: 3 }],
    createdAt: "2026-05-12T00:00:00Z",
    applicationCount: 2,
  };
}
function pod(podName: string, state: PodView["state"], blocked?: PodView["blockedBy"]): PodView {
  return {
    podName, state,
    containerRunning: state !== "LOST",
    lastHeartbeat: new Date().toISOString(),
    blockedBy: blocked ?? null,
  };
}
function snap(partial: Partial<CapacitySnapshot> = {}): CapacitySnapshot {
  return {
    groupId: "cps", region: "us-east",
    maxAvailable: 3, provisioned: 0, ready: 0, inUse: 0, spinnable: 3, pods: [],
    ...partial,
  };
}

function renderAt(groupId: string) {
  return render(
    <MemoryRouter initialEntries={[`/capacity/groups/${encodeURIComponent(groupId)}`]}>
      <Routes>
        <Route path="/capacity/groups/:groupId" element={<CapacityDetailPage />} />
        <Route path="/capacity" element={<div>capacity-list-stub</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("CapacityDetailPage — per application group", () => {
  it("shows notFound message when the group doesn't exist", async () => {
    groups.get.mockRejectedValue(new ApplicationApiError(404, "APPLICATION_GROUP_NOT_FOUND", "no such group"));
    cap.listPods.mockResolvedValue(snap());

    renderAt("does-not-exist");

    expect(await screen.findByText(/not found/i)).toBeInTheDocument();
  });

  it("renders the group's name and the region panel with chips + worker table, fetched by group id", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap({
      provisioned: 2, ready: 1, inUse: 1, spinnable: 1,
      pods: [
        pod("checkout-us-east-worker-1", "READY"),
        pod("checkout-us-east-worker-2", "IN_USE", {
          runId: "01RUNAAA", state: "RUNNING", startedAt: null, initiatedBy: "alice",
        }),
      ],
    }));

    renderAt("cps");

    expect(await screen.findByText("checkout-us-east-worker-1")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Servicing MQ");
    expect(cap.listPods).toHaveBeenCalledWith("cps", "us-east", expect.anything());
    expect(screen.getByText("Ready 1")).toBeInTheDocument();
    expect(screen.getByText("In Use 1")).toBeInTheDocument();
    // Phase 5c — "Provisioned" chip renamed to "Usage 2/3".
    expect(screen.getByText(/Usage 2\/3/)).toBeInTheDocument();
  });

  it("header links to the group's applications — a group has many, so there is no single launcher", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap());

    renderAt("cps");

    expect(await screen.findByRole("link", { name: /Applications \(2\)/ })).toHaveAttribute("href", "/applications");
    expect(screen.queryByRole("link", { name: /Launch a Run/ })).toBeNull();
    // The pool's lifecycle policy is edited with the group, not here.
    expect(screen.queryByText(/Worker lifecycle policy/)).toBeNull();
  });

  it("Reserve capacity success toast carries an Open Applications CTA", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap());
    cap.setMax.mockResolvedValue({
      groupId: "cps", region: "us-east", maxAvailable: 5,
    });

    renderAt("cps");

    fireEvent.click(await screen.findByRole("button", { name: /Reserve capacity/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/New reservation/), { target: { value: "5" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /Reserve 5/ }));

    await waitFor(() => {
      // Toast appears with the follow-up CTA pointing at the Applications list.
      const toast = screen.getByRole("status");
      const cta = within(toast).getByRole("link", { name: /Open Applications/ });
      expect(cta).toHaveAttribute("href", "/applications");
    });
  });

  it("Phase 5c — Drain All Ready opens the bulk dialog with only READY pods", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap({
      ready: 1, inUse: 1, provisioned: 2, spinnable: 1,
      pods: [
        pod("ready-w", "READY"),
        pod("busy-w",  "IN_USE", {
          runId: "01RUNXXXXXXXXXXXXXXXXXXXXX", state: "RUNNING",
          startedAt: null, initiatedBy: "alice",
        }),
      ],
    }));

    renderAt("cps");

    const drainAll = await screen.findByRole("button", { name: /Drain All Ready/ });
    fireEvent.click(drainAll);

    const dialog = await screen.findByRole("dialog");
    // Only the READY worker is in the proceed list — busy-w isn't even
    // in the selection (caller filtered before opening the modal).
    expect(within(dialog).getByText("ready-w")).toBeInTheDocument();
    expect(within(dialog).queryByText("busy-w")).not.toBeInTheDocument();
  });

  it("Provision Workers — count input + button calls spinPod N times", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap({ spinnable: 3 }));
    cap.spinPod.mockResolvedValue({
      podName: "x", groupId: "cps", region: "us-east",
      baseUrl: "http://x:8080", provisioned: 1, maxAvailable: 3,
    });

    renderAt("cps");

    const input = await screen.findByLabelText(/Number of workers to provision/);
    fireEvent.change(input, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: /Provision Workers/ }));

    await waitFor(() => {
      expect(cap.spinPod).toHaveBeenCalledTimes(2);
      expect(cap.spinPod).toHaveBeenCalledWith("cps", "us-east");
    });
  });

  it("selecting workers reveals bulk toolbar with Restart + Drain Selected", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap({
      ready: 2, provisioned: 2, spinnable: 1,
      pods: [pod("w-1", "READY"), pod("w-2", "READY")],
    }));

    renderAt("cps");

    const checkboxes = await screen.findAllByRole("checkbox");
    // checkboxes[0] = select-all; toggle it.
    fireEvent.click(checkboxes[0]);

    expect(await screen.findByText("2 selected")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Restart Selected/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Drain Selected/ })).toBeInTheDocument();
  });

  it("Drain Selected dialog partitions selection into 'will drain' vs 'skipped (IN_USE)'", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap({
      ready: 1, inUse: 1, provisioned: 2, spinnable: 1,
      pods: [
        pod("ready-w", "READY"),
        pod("busy-w",  "IN_USE", {
          runId: "01RUNXXXXXXXXXXXXXXXXXXXXX", state: "RUNNING",
          startedAt: null, initiatedBy: "alice",
        }),
      ],
    }));
    cap.drainPod.mockResolvedValue({ podName: "ready-w", drained: true });

    renderAt("cps");

    const checkboxes = await screen.findAllByRole("checkbox");
    fireEvent.click(checkboxes[0]); // select all

    fireEvent.click(screen.getByRole("button", { name: /Drain Selected/ }));

    // Modal opened: shows the proceed-list + skip-list.
    const dialog = await screen.findByRole("dialog");
    // "Will drain (1)" lives inside the proceed section's <h4> heading;
    // the subtitle also contains "will drain" but isn't a heading. Query
    // by role to disambiguate.
    expect(within(dialog).getByRole("heading", { name: /Will drain/i })).toBeInTheDocument();
    expect(within(dialog).getByRole("heading", { name: /Skipped/i })).toBeInTheDocument();
    expect(within(dialog).getByText("ready-w")).toBeInTheDocument();
    expect(within(dialog).getByText("busy-w")).toBeInTheDocument();

    // Confirm "Drain 1" — only the READY pod gets drained.
    fireEvent.click(within(dialog).getByRole("button", { name: /Drain 1/ }));

    await waitFor(() => {
      expect(cap.drainPod).toHaveBeenCalledTimes(1);
      expect(cap.drainPod).toHaveBeenCalledWith("cps", "us-east", "ready-w");
    });
  });

  it("Reserve capacity dialog → setMax(newMax) on submit", async () => {
    groups.get.mockResolvedValue(fixtureGroup());
    cap.listPods.mockResolvedValue(snap());
    cap.setMax.mockResolvedValue({
      groupId: "cps", region: "us-east", maxAvailable: 5,
    });

    renderAt("cps");

    fireEvent.click(await screen.findByRole("button", { name: /Reserve capacity/ }));
    const dialog = await screen.findByRole("dialog");
    const input = within(dialog).getByLabelText(/New reservation/);
    fireEvent.change(input, { target: { value: "5" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /Reserve 5/ }));

    await waitFor(() => {
      expect(cap.setMax).toHaveBeenCalledWith("cps", "us-east", 5);
    });
  });
});
