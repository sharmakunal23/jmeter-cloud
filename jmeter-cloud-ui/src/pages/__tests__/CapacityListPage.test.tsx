import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { CapacityListPage } from "../CapacityListPage";
import type { Application } from "../../api/applications";
import type { CapacitySnapshot } from "../../api/capacity";

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
      reconcileWorkers: vi.fn(),
    },
  };
});

import { applicationsApi } from "../../api/applications";
import { capacityApi } from "../../api/capacity";
const apps = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const cap = capacityApi as unknown as {
  listPods: ReturnType<typeof vi.fn>;
  reconcileWorkers: ReturnType<typeof vi.fn>;
};

beforeEach(() => {
  apps.list.mockReset();
  cap.listPods.mockReset();
  cap.reconcileWorkers.mockReset();
});

function appA(): Application {
  return {
    applicationId: "01APPA",
    name: "alpha",
    sealId: null,
    description: null,
    healthEndpoints: [],
    capacity: [{ region: "us-east", maxAvailable: 2 }, { region: "us-west", maxAvailable: 1 }],
    createdAt: "2026-05-12T00:00:00Z",
    lastHealthStatus: "HEALTHY",
  };
}
function appB(): Application {
  return {
    applicationId: "01APPB",
    name: "beta",
    sealId: null,
    description: null,
    healthEndpoints: [],
    capacity: [{ region: "us-east", maxAvailable: 1 }],
    createdAt: "2026-05-12T00:00:00Z",
    lastHealthStatus: "DEGRADED",
  };
}
function snap(applicationId: string, region: string, partial: Partial<CapacitySnapshot> = {}): CapacitySnapshot {
  return {
    applicationId, region,
    maxAvailable: 1, provisioned: 0, ready: 0, inUse: 0, spinnable: 1, pods: [],
    ...partial,
  };
}

describe("CapacityListPage — Phase 5b", () => {
  it("renders one row per app with aggregated counters across regions", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    cap.listPods.mockImplementation((appId: string, region: string) =>
      Promise.resolve(snap(appId, region, {
        provisioned: 1, ready: 1, inUse: 0,
      })),
    );

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);

    expect(await screen.findByRole("link", { name: "alpha" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "beta"  })).toBeInTheDocument();

    // alpha has 2 regions; beta has 1.
    const alphaRow = screen.getByRole("link", { name: "alpha" }).closest("tr")!;
    const betaRow  = screen.getByRole("link", { name: "beta"  }).closest("tr")!;
    // Row cells: name, health, regions, ready, in-use, provisioned/max, bar, link.
    // We check the regions count (3rd column, idx 2).
    expect(alphaRow.children[2]).toHaveTextContent("2");
    expect(betaRow.children[2]).toHaveTextContent("1");
  });

  it("filters apps by name substring", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    cap.listPods.mockResolvedValue(snap("01", "us-east"));

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const search = screen.getByLabelText(/Filter applications by name/);
    fireEvent.change(search, { target: { value: "bet" } });

    await waitFor(() => {
      expect(screen.queryByRole("link", { name: "alpha" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "beta" })).toBeInTheDocument();
    });
  });

  it("sorts when a sortable header is clicked (Provisioned)", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    // alpha: 2 regions × 2 provisioned each = 4; beta: 1 × 5 = 5. So beta > alpha by Provisioned.
    cap.listPods.mockImplementation((appId: string, region: string) =>
      Promise.resolve(snap(appId, region, {
        provisioned: appId === "01APPA" ? 2 : 5,
        ready:       appId === "01APPA" ? 2 : 5,
      })),
    );

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    // Phase 5c — column renamed from "Provisioned" to "Usage" but the
    // sortKey under the hood is still "provisioned".
    fireEvent.click(screen.getByRole("button", { name: /Usage/ }));

    await waitFor(() => {
      // First app row by document order should now be "beta" (higher
      // provisioned, default desc on first click of a numeric column).
      const links = screen.getAllByRole("link", { name: /^(alpha|beta)$/ });
      expect(links[0]).toHaveTextContent("beta");
    });
  });

  it("Phase 5c — Health column dropped from the Capacity list", async () => {
    apps.list.mockResolvedValue([appA()]);
    cap.listPods.mockResolvedValue(snap("01APPA", "us-east"));

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    // Health is shown on the Applications page (where it's primary
    // signal), but irrelevant in the capacity context per 2026-05-12
    // user direction.
    expect(screen.queryByText("HEALTHY")).not.toBeInTheDocument();
  });

  it("Phase 5c — entire row is clickable as role=link with keyboard support", async () => {
    apps.list.mockResolvedValue([appA()]);
    cap.listPods.mockResolvedValue(snap("01APPA", "us-east"));

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    // The row is rendered with role="link" + an aria-label so
    // assistive tech can identify the click target.
    const row = screen.getByRole("link", { name: /Open capacity for alpha/i });
    expect(row.tagName).toBe("TR");
  });

  it("Phase 5c — recent-activity chip shows 'no workers' when none provisioned", async () => {
    apps.list.mockResolvedValue([appA()]);
    cap.listPods.mockResolvedValue(snap("01APPA", "us-east", {
      provisioned: 0, ready: 0, inUse: 0, pods: [],
    }));

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    expect(screen.getAllByText("no workers").length).toBeGreaterThan(0);
  });

  it("Phase 5c — '/' keyboard shortcut focuses the search box", async () => {
    apps.list.mockResolvedValue([appA()]);
    cap.listPods.mockResolvedValue(snap("01APPA", "us-east"));

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const search = screen.getByLabelText(/Filter applications by name/) as HTMLInputElement;
    expect(document.activeElement).not.toBe(search);

    // Fire on document body so we don't simulate the key inside the input.
    fireEvent.keyDown(document.body, { key: "/" });

    expect(document.activeElement).toBe(search);
  });

  it("Grid view renders one .appCard per app + clicking it navigates to the drill-in", async () => {
    try { localStorage.setItem("jmeterCloud.capacity.listViewMode", "grid"); } catch { /* ignore */ }
    apps.list.mockResolvedValue([appA(), appB()]);
    cap.listPods.mockImplementation((appId: string, region: string) =>
      Promise.resolve(snap(appId, region, { provisioned: 0 })),
    );

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);

    const alphaCard = await screen.findByRole("link", { name: /Open capacity for alpha/i });
    expect(alphaCard.className).toContain("appCard");
    expect(alphaCard).toHaveAttribute("href", "/capacity/alpha");
    expect(screen.getByRole("link", { name: /Open capacity for beta/i })).toBeInTheDocument();
    try { localStorage.removeItem("jmeterCloud.capacity.listViewMode"); } catch { /* ignore */ }
  });

  it("renders the per-region totals chip strip aggregated across apps", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    cap.listPods.mockImplementation((appId: string, region: string) =>
      Promise.resolve(snap(appId, region, {
        provisioned: region === "us-east" ? 1 : 0,
      })),
    );

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    // Two apps × us-east → 1+1 = 2 pods. us-west exists for alpha only → 0 pods.
    // The chip is the outer .chip span; the region name lives in an inner
    // .mono span — climb to the outer container to read both segments.
    const eastChip = screen.getByText("us-east").closest("span.chip")!;
    expect(eastChip).toHaveTextContent(/2 workers/);
    const westChip = screen.getByText("us-west").closest("span.chip")!;
    expect(westChip).toHaveTextContent(/0 workers/);
  });

  it("Reconcile workers — header button opens dialog; confirm calls reconcileWorkers + toasts the summary", async () => {
    apps.list.mockResolvedValue([appA()]);
    cap.listPods.mockResolvedValue(snap("01APPA", "us-east"));
    cap.reconcileWorkers.mockResolvedValue({
      adopted: [], started: [], orphansDeleted: ["alpha-worker-1"], errors: [],
    });

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    // Header trigger (only one such button until the dialog opens).
    fireEvent.click(screen.getByRole("button", { name: /Reconcile workers/ }));

    // Confirm inside the dialog (scoped so it doesn't match the header button).
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /^Reconcile workers$/ }));

    await waitFor(() => expect(cap.reconcileWorkers).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/1 stale worker removed/)).toBeInTheDocument();
  });

  it("Reconcile workers — Cancel closes the dialog without calling the API", async () => {
    apps.list.mockResolvedValue([appA()]);
    cap.listPods.mockResolvedValue(snap("01APPA", "us-east"));

    render(<MemoryRouter><CapacityListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.click(screen.getByRole("button", { name: /Reconcile workers/ }));
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /^Cancel$/ }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(cap.reconcileWorkers).not.toHaveBeenCalled();
  });
});
