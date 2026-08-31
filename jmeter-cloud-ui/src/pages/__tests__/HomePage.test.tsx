import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { HomePage } from "../HomePage";
import type { Application } from "../../api/applications";

vi.mock("../../api/applications", async () => {
  const actual = await vi.importActual<typeof import("../../api/applications")>("../../api/applications");
  return {
    ...actual,
    applicationsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
vi.mock("../../api/runs", async () => {
  const actual = await vi.importActual<typeof import("../../api/runs")>("../../api/runs");
  return {
    ...actual,
    runsApi: { ...actual.runsApi, listPage: vi.fn() },
  };
});
vi.mock("../../api/regions", async () => {
  const actual = await vi.importActual<typeof import("../../api/regions")>("../../api/regions");
  return {
    ...actual,
    regionsApi: { list: vi.fn(), status: vi.fn() },
  };
});
vi.mock("../../api/automation", async () => {
  const actual = await vi.importActual<typeof import("../../api/automation")>("../../api/automation");
  return { ...actual, cronJobsApi: { list: vi.fn() } };
});

vi.mock("../../api/platformHealth", async () => {
  const actual = await vi.importActual<typeof import("../../api/platformHealth")>("../../api/platformHealth");
  return { ...actual, platformHealthApi: { ...actual.platformHealthApi, get: vi.fn() } };
});
vi.mock("../../api/applicationGroups", async () => {
  const actual = await vi.importActual<typeof import("../../api/applicationGroups")>("../../api/applicationGroups");
  return {
    ...actual,
    applicationGroupsApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  };
});
import { applicationsApi } from "../../api/applications";
import { runsApi } from "../../api/runs";
import { regionsApi } from "../../api/regions";
import { cronJobsApi } from "../../api/automation";
import { applicationGroupsApi } from "../../api/applicationGroups";
import { platformHealthApi, type PlatformHealth } from "../../api/platformHealth";
const healthMock = platformHealthApi as unknown as { get: ReturnType<typeof vi.fn> };
const groupsMock = applicationGroupsApi as unknown as { list: ReturnType<typeof vi.fn> };
const apps = applicationsApi as unknown as { list: ReturnType<typeof vi.fn> };
const runs = runsApi as unknown as { listPage: ReturnType<typeof vi.fn> };
const regions = regionsApi as unknown as { list: ReturnType<typeof vi.fn>; status: ReturnType<typeof vi.fn> };
const cronJobs = cronJobsApi as unknown as { list: ReturnType<typeof vi.fn> };

// Mock global fetch for the per-backend probes (HomePage hits /actuator/health,
// /api/v1/blob, /api/v1/regions directly via fetch).
const realFetch = globalThis.fetch;
beforeEach(() => {
  apps.list.mockReset();
  runs.listPage.mockReset();
  regions.list.mockReset();
  regions.status.mockReset();
  regions.status.mockResolvedValue([]);
  cronJobs.list.mockReset();
  cronJobs.list.mockResolvedValue([]);
  groupsMock.list.mockReset();
  groupsMock.list.mockResolvedValue([]);
  healthMock.get.mockReset();
  healthMock.get.mockResolvedValue(healthFixture());
  // Defaults — empty active runs + empty regions roll up to no capacity
  // section beyond the empty-state. Tests override per scenario.
  runs.listPage.mockResolvedValue({ runs: [], total: 0, offset: 0, limit: 200 });
  regions.list.mockResolvedValue([]);
  globalThis.fetch = vi.fn(async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })) as unknown as typeof fetch;
});
afterEach(() => { globalThis.fetch = realFetch; });

function fixtureApp(name: string, overrides: Partial<Application> = {}): Application {
  return {
    applicationId: "01J0" + name.replace(/[^A-Z0-9]/gi, "0").padEnd(22, "0").slice(0, 22).toUpperCase(),
    name,
    sealId: null,
    description: null,
    healthEndpoints: [],
    metricsGroupId: "cps",
    createdAt: "2026-05-11T12:00:00Z",
    lastHealthStatus: "UNKNOWN",
    lastHealthCheckedAt: null,
    lastHealthDetails: null,
    ...overrides,
  };
}

function healthFixture(overrides: Partial<PlatformHealth> = {}): PlatformHealth {
  return {
    status: "UP", checkedAt: "2026-08-30T02:30:00Z",
    components: [
      { id: "global-orchestrator", name: "Global orchestrator", kind: "service", status: "UP", detail: "provisioning DYNAMIC",
        components: [
          { id: "db.globalrunDataSource", name: "Oracle · run state", kind: "dependency", status: "UP", detail: "Oracle" },
          { id: "db.metricsDataSource", name: "Oracle · metrics (reader)", kind: "dependency", status: "UP", detail: "Oracle" },
        ] },
      { id: "metrics-consumer", name: "Metrics consumer", kind: "service", status: "UP", detail: "idle — last envelope 12 min ago", latencyMs: 4 },
      { id: "document-service", name: "Document service", kind: "service", status: "UP", detail: "721 GB free", latencyMs: 3 },
      { id: "regions", name: "Data centers", kind: "regions", status: "UP", detail: "1 region(s) serving", components: [] },
    ],
    ...overrides,
  };
}

function renderPage() {
  return render(<MemoryRouter><HomePage /></MemoryRouter>);
}

import { afterEach } from "vitest";

describe("HomePage — health checklist", () => {
  it("renders Platform + Applications + Schedule sections", async () => {
    apps.list.mockResolvedValue([fixtureApp("checkout-svc")]);
    renderPage();
    await waitFor(() => expect(screen.getByRole("heading", { name: "Platform", level: 2 })).toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Applications", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Upcoming scheduled runs", level: 2 })).toBeInTheDocument();
  });

  it("Platform section shows one row per service plus a single Database row — no facts, no details toggle", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() => {
      const platform = screen.getByRole("list", { name: "platform checks" });
      expect(platform).toHaveTextContent("Global orchestrator");
      expect(platform).toHaveTextContent("Database");
      expect(platform).toHaveTextContent("Metrics consumer");
      expect(platform).toHaveTextContent("Document service");
      expect(platform).toHaveTextContent("Data centers");
    });
    // Service-level rows only: the dependency tree, free-space / envelope
    // facts, and probe latencies stay off Home (operator request 2026-08-30).
    expect(screen.queryByTestId("health-db.globalrunDataSource")).toBeNull();
    expect(screen.queryByText(/last envelope/)).toBeNull();
    expect(screen.queryByText(/GB free/)).toBeNull();
    expect(screen.queryByText(/4 ms/)).toBeNull();
    expect(screen.getByTestId("health-database")).toHaveTextContent("UP");
    expect(screen.queryByRole("button", { name: "Show details" })).toBeNull();
    expect(screen.getByText(/All healthy/)).toBeInTheDocument();
  });

  it("a region DOWN surfaces as the Data centers row's reason — no tree, and the header counts the failing rows", async () => {
    apps.list.mockResolvedValue([]);
    healthMock.get.mockResolvedValue(healthFixture({
      status: "DEGRADED",
      components: [
        ...healthFixture().components.slice(0, 3),
        { id: "regions", name: "Data centers", kind: "regions", status: "DEGRADED", detail: "1 of 2 region(s) down", components: [
          { id: "region.na-east", name: "na-east", kind: "region", status: "UP", detail: "1 idle · 0 busy", components: [
            { id: "region.na-east.regional-orchestrator", name: "Regional orchestrator", kind: "regional-orchestrator", status: "UP", detail: "version dev", url: "http://na-east-control-plane:30088" },
            { id: "region.na-east.workers", name: "Workers", kind: "workers", status: "UP", detail: "1 idle · 0 busy" },
          ] },
          { id: "region.na-west", name: "na-west", kind: "region", status: "DOWN", detail: "regional orchestrator unreachable", components: [
            { id: "region.na-west.regional-orchestrator", name: "Regional orchestrator", kind: "regional-orchestrator", status: "DOWN", detail: "connection refused", url: "http://na-west-control-plane:30088" },
            { id: "region.na-west.workers", name: "Workers", kind: "workers", status: "UP", detail: "no workers" },
          ] },
        ] },
      ],
    }));
    renderPage();
    await waitFor(() => expect(screen.getByTestId("health-regions")).toBeInTheDocument());
    expect(screen.getByTestId("health-regions")).toHaveTextContent(/DEGRADED/);
    expect(screen.getByTestId("health-regions")).toHaveTextContent(/1 of 2 region\(s\) down/);
    // The failing branch is folded into the service row — Home shows no tree.
    expect(screen.queryByTestId("health-region.na-west")).toBeNull();
    expect(screen.queryByTestId("health-region.na-west.regional-orchestrator")).toBeNull();
    expect(screen.getByText(/1 component needs attention/)).toBeInTheDocument();
    expect(regions.status).not.toHaveBeenCalled();
  });

  it("apps with HEALTHY status render a HEALTHY badge", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { lastHealthStatus: "HEALTHY", lastHealthCheckedAt: new Date().toISOString() }),
    ]);
    renderPage();
    await waitFor(() => {
      const appList = document.querySelector('ul[aria-label="application checks"]');
      expect(appList).not.toBeNull();
      const healthy = appList!.querySelectorAll('.healthBadge--healthy');
      expect(healthy.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("apps without health endpoints show 'no health endpoints configured' detail", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { healthEndpoints: [], lastHealthStatus: "UNKNOWN" }),
    ]);
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/no health endpoints configured/i)).toBeInTheDocument();
    });
  });

  it("clicking an application row navigates to /applications/<encoded>", async () => {
    apps.list.mockResolvedValue([fixtureApp("svc beta")]);
    renderPage();
    await waitFor(() => {
      const link = document.querySelector('ul[aria-label="application checks"] a') as HTMLAnchorElement;
      expect(link).not.toBeNull();
      expect(link.getAttribute("href")).toBe("/applications/svc%20beta");
    });
  });

  it("empty applications list shows the welcome empty-state CTA", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText(/No applications registered yet/i)).toBeInTheDocument());
  });

  it("status chip reads 'all healthy' when every check is HEALTHY", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { lastHealthStatus: "HEALTHY" }),
    ]);
    // All backend probes succeed (200 from default mock).
    renderPage();
    await waitFor(() => {
      // Standardization sweep (2026-05-13) — bespoke .homeChip is gone;
      // the page header now uses the shared .chip--ok variant from the
      // IA list pages so typography lines up across Home + Apps + IA tabs.
      const okChip = document.querySelector('.chip.chip--ok');
      expect(okChip).toHaveTextContent(/all healthy/i);
    });
  });

  it("Capacity section sits between Platform and Schedule + sums the groups' capacity per region", async () => {
    apps.list.mockResolvedValue([fixtureApp("checkout-svc"), fixtureApp("payment-api", { metricsGroupId: "demo" })]);
    groupsMock.list.mockResolvedValue([
      { groupId: "cps", name: "Servicing MQ", createdAt: "2026-08-29T00:00:00Z", capacity: [
        { region: "us-east", maxAvailable: 10 },
        { region: "us-west", maxAvailable: 5 },
      ] },
      { groupId: "demo", name: "Demo", createdAt: "2026-08-29T00:00:00Z", capacity: [
        { region: "us-east", maxAvailable: 4 },
      ] },
    ]);
    renderPage();
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Capacity", level: 2 })).toBeInTheDocument(),
    );
    // The rows render in alphabetical region order; us-east aggregates 10+4=14.
    const rows = document.querySelectorAll('.capacityRollupTable tbody tr');
    expect(rows.length).toBeGreaterThanOrEqual(2);
    expect(rows[0]).toHaveTextContent("us-east");
    expect(rows[0]).toHaveTextContent("14"); // Max column
    expect(rows[1]).toHaveTextContent("us-west");
    expect(rows[1]).toHaveTextContent("5");
    // Footer total = 14 + 5 = 19 max, 0 in use (no active runs).
    expect(document.querySelector('.capacityRollup__footer'))
      .toHaveTextContent(/0.*of.*19.*workers in use/);
  });

  it("Capacity section shows the empty state when no group has capacity, linking the per-group breakdown", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Capacity", level: 2 })).toBeInTheDocument(),
    );
    expect(screen.getByText(/nothing to roll up yet/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Per-group breakdown/i }))
      .toHaveAttribute("href", "/capacity");
  });

  it("Schedule section shows the 'no scheduled jobs yet' stub", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText(/No scheduled jobs yet/i)).toBeInTheDocument());
    expect(screen.getByRole("link", { name: /Configure/i }))
      .toHaveAttribute("href", "/automation");
  });

  it("renders a future fire as a 'Next up' call-out with an upcoming time, not a negative 'ago'", async () => {
    apps.list.mockResolvedValue([]);
    // A schedule due ~6h from now — the old code formatted this future instant
    // with a past-relative helper and printed "fires -NNNNs ago".
    const sixHoursOut = new Date(Date.now() + 6 * 3600_000).toISOString();
    cronJobs.list.mockResolvedValue([
      {
        cronJobId: "01J", name: "nightly", kind: "LAUNCH_WORKFLOW",
        groupId: "cps", workflowId: "01WF", workflowName: "checkout-svc",
        cronExpression: "0 2 * * *", timeZone: "America/New_York",
        enabled: true, createdAt: "2026-05-12T00:00:00Z",
        lastFiredAt: null, lastFiredExecutionId: null, lastFireStatus: null,
        nextFireAt: sixHoursOut, region: null,
      },
    ]);
    renderPage();
    await waitFor(() => expect(screen.getByText("Next up")).toBeInTheDocument());
    expect(screen.getByText("nightly → checkout-svc")).toBeInTheDocument();
    // The relative gloss reads forward ("in 6h"), never a negative "ago".
    expect(screen.getByText(/in \d+h/)).toBeInTheDocument();
    expect(screen.queryByText(/-\d+s ago/)).not.toBeInTheDocument();
    // Plain-language cadence is surfaced too.
    expect(screen.getByText(/Every day at 2:00 AM/)).toBeInTheDocument();
  });

  it("Applications section previews up to 15 apps + a 'view all' link (bounded page)", async () => {
    const many = Array.from({ length: 30 }, (_, i) =>
      fixtureApp(`svc-${String(i).padStart(2, "0")}`));
    apps.list.mockResolvedValue(many);
    renderPage();
    await waitFor(() => {
      const appList = document.querySelector('ul[aria-label="application checks"]');
      expect(appList).not.toBeNull();
      // Capped to the apps preview limit (15) so the dashboard can't grow long —
      // the group heading rows (one per run of apps in a group) are not apps.
      expect(appList!.querySelectorAll('li:not(.checklist__group)')).toHaveLength(15);
    });
    // No paginator on Home any more; a "view all" footer links to the full list.
    expect(document.querySelector('.paginator')).toBeNull();
    const more = document.querySelector('.checklist__more');
    expect(more).not.toBeNull();
    expect(more).toHaveTextContent(/View all 30/);
    expect(more!.querySelector('a')).toHaveAttribute("href", "/applications");
  });

  it("the page renders 'Performance Platform' as its h1 (brand 'jmeter-cloud' lives in the nav, not duplicated here)", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Performance Platform", level: 1 })).toBeInTheDocument();
    });
  });
});

describe("HomePage — application groups", () => {
  it("labels each run of applications with its group, groups in name order — every app has one", async () => {
    groupsMock.list.mockResolvedValue([
      { groupId: "cps", name: "Servicing MQ", createdAt: "2026-08-29T00:00:00Z", applicationCount: 1 },
      { groupId: "zed", name: "Zed team", createdAt: "2026-08-29T00:00:00Z", applicationCount: 1 },
    ]);
    apps.list.mockResolvedValue([
      fixtureApp("zeta-svc", { metricsGroupId: "zed" }),
      fixtureApp("cps-pci", { metricsGroupId: "cps" }),
    ]);
    renderPage();
    await waitFor(() => {
      const items = Array.from(document.querySelectorAll('ul[aria-label="application checks"] > li')).map((li) => li.textContent ?? "");
      expect(items[0]).toBe("Servicing MQ");
      expect(items[1]).toContain("cps-pci");
      expect(items[2]).toBe("Zed team");
      expect(items[3]).toContain("zeta-svc");
      expect(items).not.toContain("Ungrouped");
    });
  });
});
