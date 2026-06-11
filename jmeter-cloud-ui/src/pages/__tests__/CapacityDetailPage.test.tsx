import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { CapacityDetailPage } from "../CapacityDetailPage";
import type { Application } from "../../api/applications";
import type { CapacitySnapshot, PodView } from "../../api/capacity";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
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

import { applicationsApi } from "../../api/applications";
import { capacityApi } from "../../api/capacity";
const apps = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const cap = capacityApi as unknown as {
  setMax: ReturnType<typeof vi.fn>;
  listPods: ReturnType<typeof vi.fn>;
  spinPod: ReturnType<typeof vi.fn>;
  restartPod: ReturnType<typeof vi.fn>;
  drainPod: ReturnType<typeof vi.fn>;
};

beforeEach(() => {
  apps.list.mockReset();
  cap.setMax.mockReset();
  cap.listPods.mockReset();
  cap.spinPod.mockReset();
  cap.restartPod.mockReset();
  cap.drainPod.mockReset();
});

function fixtureApp(): Application {
  return {
    applicationId: "01CAP",
    name: "checkout",
    sealId: null,
    description: null,
    healthEndpoints: [],
    capacity: [{ region: "us-east", maxAvailable: 3 }],
    createdAt: "2026-05-12T00:00:00Z",
    lastHealthStatus: "HEALTHY",
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
    applicationId: "01CAP", region: "us-east",
    maxAvailable: 3, provisioned: 0, ready: 0, inUse: 0, spinnable: 3, pods: [],
    ...partial,
  };
}

function renderAt(appName: string) {
  return render(
    <MemoryRouter initialEntries={[`/capacity/${encodeURIComponent(appName)}`]}>
      <Routes>
        <Route path="/capacity/:appName" element={<CapacityDetailPage />} />
        <Route path="/capacity" element={<div>capacity-list-stub</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("CapacityDetailPage — Phase 5b", () => {
  it("shows notFound message when the app name doesn't exist", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap());

    renderAt("does-not-exist");

    expect(await screen.findByText(/not found/i)).toBeInTheDocument();
  });

  it("renders the region panel with chips + worker table", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap({
      provisioned: 2, ready: 1, inUse: 1, spinnable: 1,
      pods: [
        pod("checkout-us-east-worker-1", "READY"),
        pod("checkout-us-east-worker-2", "IN_USE", {
          runId: "01RUNAAA", state: "RUNNING", startedAt: null, initiatedBy: "alice",
        }),
      ],
    }));

    renderAt("checkout");

    expect(await screen.findByText("checkout-us-east-worker-1")).toBeInTheDocument();
    expect(screen.getByText("Ready 1")).toBeInTheDocument();
    expect(screen.getByText("In Use 1")).toBeInTheDocument();
    // Phase 5c — "Provisioned" chip renamed to "Usage 2/3".
    expect(screen.getByText(/Usage 2\/3/)).toBeInTheDocument();
  });

  it("Phase 5c — header has Open Application + Launch a Run links", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap());

    renderAt("checkout");

    expect(await screen.findByRole("link", { name: /Open Application/ })).toHaveAttribute(
      "href", "/applications/checkout",
    );
    expect(screen.getByRole("link", { name: /Launch a Run/ })).toHaveAttribute(
      "href", "/applications/checkout/runs/new",
    );
  });

  it("Phase 5c — Request Capacity success toast carries an Open Application CTA", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap());
    cap.setMax.mockResolvedValue({
      applicationId: "01CAP", region: "us-east", maxAvailable: 5,
    });

    renderAt("checkout");

    fireEvent.click(await screen.findByRole("button", { name: /Request Capacity/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/New maximum/), { target: { value: "5" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /Set max to 5/ }));

    await waitFor(() => {
      // Toast appears with the follow-up CTA pointing at the Application page.
      const toast = screen.getByRole("status");
      const cta = within(toast).getByRole("link", { name: /Open Application/ });
      expect(cta).toHaveAttribute("href", "/applications/checkout");
    });
  });

  it("Phase 5c — Drain All Ready opens the bulk dialog with only READY pods", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
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

    renderAt("checkout");

    const drainAll = await screen.findByRole("button", { name: /Drain All Ready/ });
    fireEvent.click(drainAll);

    const dialog = await screen.findByRole("dialog");
    // Only the READY worker is in the proceed list — busy-w isn't even
    // in the selection (caller filtered before opening the modal).
    expect(within(dialog).getByText("ready-w")).toBeInTheDocument();
    expect(within(dialog).queryByText("busy-w")).not.toBeInTheDocument();
  });

  it("Provision Workers — count input + button calls spinPod N times", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap({ spinnable: 3 }));
    cap.spinPod.mockResolvedValue({
      podName: "x", applicationId: "01CAP", region: "us-east",
      baseUrl: "http://x:8080", provisioned: 1, maxAvailable: 3,
    });

    renderAt("checkout");

    const input = await screen.findByLabelText(/Number of workers to provision/);
    fireEvent.change(input, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: /Provision Workers/ }));

    await waitFor(() => {
      expect(cap.spinPod).toHaveBeenCalledTimes(2);
    });
  });

  it("selecting workers reveals bulk toolbar with Restart + Drain Selected", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap({
      ready: 2, provisioned: 2, spinnable: 1,
      pods: [pod("w-1", "READY"), pod("w-2", "READY")],
    }));

    renderAt("checkout");

    const checkboxes = await screen.findAllByRole("checkbox");
    // checkboxes[0] = select-all; toggle it.
    fireEvent.click(checkboxes[0]);

    expect(await screen.findByText("2 selected")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Restart Selected/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Drain Selected/ })).toBeInTheDocument();
  });

  it("Drain Selected dialog partitions selection into 'will drain' vs 'skipped (IN_USE)'", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
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

    renderAt("checkout");

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
      expect(cap.drainPod).toHaveBeenCalledWith("01CAP", "us-east", "ready-w");
    });
  });

  it("Request Capacity dialog → setMax(newMax) on submit", async () => {
    apps.list.mockResolvedValue([fixtureApp()]);
    cap.listPods.mockResolvedValue(snap());
    cap.setMax.mockResolvedValue({
      applicationId: "01CAP", region: "us-east", maxAvailable: 5,
    });

    renderAt("checkout");

    fireEvent.click(await screen.findByRole("button", { name: /Request Capacity/ }));
    const dialog = await screen.findByRole("dialog");
    const input = within(dialog).getByLabelText(/New maximum/);
    fireEvent.change(input, { target: { value: "5" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /Set max to 5/ }));

    await waitFor(() => {
      expect(cap.setMax).toHaveBeenCalledWith("01CAP", "us-east", 5);
    });
  });
});
