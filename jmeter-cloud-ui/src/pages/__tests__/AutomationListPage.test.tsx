import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { AutomationListPage } from "../AutomationListPage";
import type { Application } from "../../api/applications";
import type { CronJobSummary } from "../../api/automation";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/automation", async () => {
  const actual = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
  return {
    ...actual,
    cronJobsApi: { list: vi.fn() },
  };
});

import { applicationsApi } from "../../api/applications";
import { cronJobsApi } from "../../api/automation";
const apps     = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const cronJobs = cronJobsApi    as unknown as { list: ReturnType<typeof vi.fn> };

beforeEach(() => {
  apps.list.mockReset();
  cronJobs.list.mockReset();
});

function appA(): Application {
  return {
    applicationId: "01APPA",
    name: "alpha",
    sealId: null, description: null, healthEndpoints: [],
    metricsGroupId: "cps",
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function appB(): Application {
  return {
    applicationId: "01APPB",
    name: "beta",
    sealId: null, description: null, healthEndpoints: [],
    metricsGroupId: "cps",
    createdAt: "2026-05-12T00:00:00Z",
  };
}
function job(over: Partial<CronJobSummary> = {}): CronJobSummary {
  return {
    cronJobId: "01JOB",
    name: "nightly",
    applicationName: "alpha",
    templateBlobId: "01TPL",
    cronExpression: "0 2 * * *",
    timeZone: "UTC",
    enabled: true,
    createdAt: "2026-05-12T00:00:00Z",
    lastFiredAt: null,
    lastFiredRunId: null,
    lastFireStatus: null,
    nextFireAt: null,
    kind: "LAUNCH_RUN",
    region: null,
    ...over,
  };
}

describe("AutomationListPage — Phase IA-Automation", () => {
  it("renders one row per app with zero schedules when backend stub returns []", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    cronJobs.list.mockResolvedValue([]);

    render(<MemoryRouter><AutomationListPage /></MemoryRouter>);

    expect(await screen.findByRole("link", { name: "alpha" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "beta" })).toBeInTheDocument();

    const alphaRow = screen.getByRole("link", { name: "alpha" }).closest("tr")!;
    // Cells: name | activity | lastFired | nextFire | enabled | total
    expect(alphaRow.children[5]).toHaveTextContent("0");
  });

  it("aggregates per-app counts (enabled / disabled) when the backend ships fake data", async () => {
    // Forward-compat — verify the aggregation logic is wired even though the
    // real backend returns []. Mock returns a small dataset so we can assert.
    apps.list.mockResolvedValue([appA(), appB()]);
    cronJobs.list.mockResolvedValue([
      job({ cronJobId: "1", applicationName: "alpha", enabled: true }),
      job({ cronJobId: "2", applicationName: "alpha", enabled: false }),
      job({ cronJobId: "3", applicationName: "alpha", enabled: true }),
      job({ cronJobId: "4", applicationName: "beta",  enabled: true }),
      // Tagged with an unknown app — should be excluded.
      job({ cronJobId: "5", applicationName: "ghost", enabled: true }),
    ]);

    render(<MemoryRouter><AutomationListPage /></MemoryRouter>);

    expect(await screen.findByRole("link", { name: "alpha" })).toBeInTheDocument();

    const alphaRow = screen.getByRole("link", { name: "alpha" }).closest("tr")!;
    // total = 3 (1 disabled + 2 enabled)
    expect(alphaRow.children[5]).toHaveTextContent("3");
    // enabled column shows 2 enabled and references the 3 total via "/3"
    expect(alphaRow.children[4]).toHaveTextContent("2");

    const betaRow = screen.getByRole("link", { name: "beta" }).closest("tr")!;
    expect(betaRow.children[5]).toHaveTextContent("1");
  });

  it("filters apps by name substring", async () => {
    apps.list.mockResolvedValue([appA(), appB()]);
    cronJobs.list.mockResolvedValue([]);

    render(<MemoryRouter><AutomationListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    fireEvent.change(screen.getByLabelText(/Filter applications by name/), { target: { value: "bet" } });

    await waitFor(() => {
      expect(screen.queryByRole("link", { name: "alpha" })).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "beta" })).toBeInTheDocument();
    });
  });

  it("entire row is clickable as role=link", async () => {
    apps.list.mockResolvedValue([appA()]);
    cronJobs.list.mockResolvedValue([]);

    render(<MemoryRouter><AutomationListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const row = screen.getByRole("link", { name: /Open automation for alpha/i });
    expect(row.tagName).toBe("TR");
  });

  it("'/' keyboard shortcut focuses the search box", async () => {
    apps.list.mockResolvedValue([appA()]);
    cronJobs.list.mockResolvedValue([]);

    render(<MemoryRouter><AutomationListPage /></MemoryRouter>);
    await screen.findByRole("link", { name: "alpha" });

    const search = screen.getByLabelText(/Filter applications by name/) as HTMLInputElement;
    expect(document.activeElement).not.toBe(search);

    fireEvent.keyDown(document.body, { key: "/" });

    expect(document.activeElement).toBe(search);
  });

  it("empty registry shows the 'register an application first' empty state", async () => {
    apps.list.mockResolvedValue([]);
    cronJobs.list.mockResolvedValue([]);

    render(<MemoryRouter><AutomationListPage /></MemoryRouter>);

    await waitFor(() => {
      expect(screen.getByText(/No applications registered/i)).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Applications" })).toHaveAttribute("href", "/applications");
  });
});
