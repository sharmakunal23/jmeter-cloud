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

import { applicationsApi } from "../../api/applications";
import { runsApi } from "../../api/runs";
import { regionsApi } from "../../api/regions";
import { cronJobsApi } from "../../api/automation";
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
    createdAt: "2026-05-11T12:00:00Z",
    lastHealthStatus: "UNKNOWN",
    lastHealthCheckedAt: null,
    lastHealthDetails: null,
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

  it("Platform section lists global-orchestrator, document-service, oracle", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() => {
      const platform = document.querySelector('ul[aria-label="platform checks"]');
      expect(platform).not.toBeNull();
      expect(platform).toHaveTextContent("global-orchestrator");
      expect(platform).toHaveTextContent("document-service");
      expect(platform).toHaveTextContent("oracle");
    });
  });


  it("a routed region is a checklist row — HEALTHY when its regional orchestrator answered, UNHEALTHY with the probe error when it did not; direct regions are not rows", async () => {
    apps.list.mockResolvedValue([]);
    regions.status.mockResolvedValue([
      { region: "na-east", url: "http://na-east-control-plane:30088", routed: true, reachable: true },
      { region: "na-west", url: "http://na-west-control-plane:30088", routed: true, reachable: false, lastError: "connection refused" },
      { region: "lab", routed: false },
    ]);
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText(/region na-east/)).toBeInTheDocument());
    const east = screen.getByText(/region na-east/).closest("li") ?? screen.getByText(/region na-east/).parentElement!;
    expect(east).toHaveTextContent(/HEALTHY/);
    const west = screen.getByText(/region na-west/).closest("li") ?? screen.getByText(/region na-west/).parentElement!;
    expect(west).toHaveTextContent(/UNHEALTHY/);
    expect(west).toHaveTextContent(/connection refused/);
    expect(screen.queryByText(/region lab/)).not.toBeInTheDocument();
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

  it("Capacity section sits between Platform and Schedule + sums capacity per region", async () => {
    apps.list.mockResolvedValue([
      fixtureApp("checkout-svc", { capacity: [
        { region: "us-east", maxAvailable: 10 },
        { region: "us-west", maxAvailable: 5 },
      ] }),
      fixtureApp("payment-api", { capacity: [
        { region: "us-east", maxAvailable: 4 },
      ] }),
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

  it("Capacity section shows the empty state when no apps are registered", async () => {
    apps.list.mockResolvedValue([]);
    renderPage();
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "Capacity", level: 2 })).toBeInTheDocument(),
    );
    expect(screen.getByText(/nothing to roll up yet/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Per-app breakdown/i }))
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
        cronJobId: "01J", name: "nightly", applicationName: "checkout-svc",
        templateBlobId: "01TPL", cronExpression: "0 2 * * *", timeZone: "America/New_York",
        enabled: true, createdAt: "2026-05-12T00:00:00Z",
        lastFiredAt: null, lastFiredRunId: null, lastFireStatus: null,
        nextFireAt: sixHoursOut, kind: "LAUNCH_RUN", region: null,
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
      // Capped to the apps preview limit (15) so the dashboard can't grow long.
      expect(appList!.querySelectorAll('li')).toHaveLength(15);
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
