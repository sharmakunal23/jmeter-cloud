import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

import type { MetricsTimeseries } from "../../api/runs";

// a11y assertions live in MetricsTabPanel.a11y.test.tsx so this
// file stays focused on behavior. Kept in mind here when picking
// data-* attribute names + roles, but not asserted.

// ── Mock the chart so we can assert on the props the panel passes
//    without exercising uPlot itself (it's tested in TimeseriesChart). ──
const chartCalls = vi.hoisted(() => ({
  instances: [] as Array<{
    title: string;
    seriesLabels: string[];
    height?: number;
    syncKey?: string;
    resetVersion?: number;
  }>,
}));
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  // Keep the real formatter exports (formatCompactNumber, etc.) — the
  // panel imports them as siblings of TimeseriesChart and they're pure
  // functions worth exercising in panel tests.
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return {
    ...actual,
    TimeseriesChart: (props: {
      title: string;
      series: Array<{ label: string }>;
      height?: number;
      syncKey?: string;
      resetVersion?: number;
    }) => {
      chartCalls.instances.push({
        title: props.title,
        seriesLabels: props.series.map((s) => s.label),
        height: props.height,
        syncKey: props.syncKey,
        resetVersion: props.resetVersion,
      });
      return (
        <div
          data-testid="chartMock"
          data-title={props.title}
          data-labels={props.series.map((s) => s.label).join(",")}
          data-height={String(props.height)}
          data-synckey={props.syncKey ?? ""}
          data-resetversion={String(props.resetVersion ?? 0)}
        >
          chart: {props.title}
        </div>
      );
    },
  };
});

// ── Mock the data-fetching hook so the panel renders deterministically. ──
const hookState = vi.hoisted(() => ({
  current: null as ReturnType<typeof makeHookReturn> | null,
}));

vi.mock("../../hooks/useMetricsTimeseries", async (importOriginal) => {
  // Keep the real sibling exports (isTerminalRunState) — the panel uses
  // them to pick the initial time window.
  const actual = await importOriginal<typeof import("../../hooks/useMetricsTimeseries")>();
  return { ...actual, useMetricsTimeseries: () => hookState.current };
});

import { MetricsTabPanel } from "../MetricsTabPanel";

function makeHookReturn(overrides: Partial<{
  status: { kind: "loading" } | { kind: "ok" } | { kind: "error"; message: string };
  data: MetricsTimeseries | null;
  lastUpdated: Date | null;
  isPaused: boolean;
  pauseReason: "manual" | "delayNull" | "documentHidden" | "offscreen" | null;
}> = {}) {
  return {
    status:      overrides.status ?? { kind: "ok" as const },
    data:        overrides.data === undefined ? sampleData() : overrides.data,
    lastUpdated: overrides.lastUpdated ?? new Date("2026-05-10T20:00:00Z"),
    isPaused:    overrides.isPaused ?? false,
    pauseReason: overrides.pauseReason ?? null,
  };
}

function sampleData(): MetricsTimeseries {
  return {
    runId: "01J000RUN",
    bucketSize: 1,
    fromSecond: 1_700_000_000,
    toSecond:   1_700_000_005,
    series: {
      tps:      [{ sec: 1_700_000_000, v: 5 }, { sec: 1_700_000_001, v: 6 }],
      avgRtMs:  [{ sec: 1_700_000_000, v: 12 }, { sec: 1_700_000_001, v: 14 }],
      errorPct: [{ sec: 1_700_000_000, v: 0 }, { sec: 1_700_000_001, v: 1.5 }],
      statusCodes: {
        "200": [{ sec: 1_700_000_000, v: 5 }, { sec: 1_700_000_001, v: 5 }],
        "500": [{ sec: 1_700_000_001, v: 1 }],
      },
    },
  };
}

beforeEach(() => {
  chartCalls.instances = [];
  hookState.current = makeHookReturn();
  // Layout + split-by-region persist to localStorage; clear it so each
  // test starts from the documented defaults (grid layout, region off).
  try { window.localStorage.clear(); } catch { /* jsdom always has it */ }
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("MetricsTabPanel — happy path", () => {
  it("renders four charts (TPS, RT, Error %, Status codes) when data is present", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(chartCalls.instances).toHaveLength(4);
    expect(chartCalls.instances.map((c) => c.title)).toEqual([
      "Total TPS",
      "Response Time",
      "Error %",
      "Status codes",
    ]);
  });

  it("status-codes chart sorts numeric codes ascending and color-codes them", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    const statusChart = chartCalls.instances.find((c) => c.title === "Status codes")!;
    expect(statusChart.seriesLabels).toEqual(["200", "500"]);
  });

  it("renders the data-summary text with point count + last fetch time", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.getByText(/2 seconds of data/)).toBeInTheDocument();
    expect(screen.getByText(/last fetch/i)).toBeInTheDocument();
  });

  it("the bucketSize > 1 case surfaces in the status text", () => {
    const data = sampleData();
    data.bucketSize = 5;
    hookState.current = makeHookReturn({ data });
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.getByText(/bucketed at 5-s windows/)).toBeInTheDocument();
  });
});

describe("MetricsTabPanel — empty state", () => {
  it("renders a friendly hint instead of empty charts when the run has no metrics yet", () => {
    hookState.current = makeHookReturn({
      data: { ...sampleData(), series: { tps: [], avgRtMs: [], errorPct: [], statusCodes: {} },
              fromSecond: null, toSecond: null },
    });
    render(<MetricsTabPanel runId="01J000RUN" runState="PREPARING" />);
    expect(screen.queryByTestId("chartMock")).toBeNull();
    // Match on the explanatory copy (unique to the empty-state hint)
    // because the status row also surfaces "no metrics yet".
    expect(screen.getByText(/the consumer writes within ~1 s/i)).toBeInTheDocument();
  });

  it("renders nothing chart-like during the initial load (status=loading, data=null)", () => {
    hookState.current = makeHookReturn({ status: { kind: "loading" }, data: null });
    render(<MetricsTabPanel runId="01J000RUN" runState="PREPARING" />);
    expect(screen.queryByTestId("chartMock")).toBeNull();
    expect(screen.queryByText(/No metrics yet/i)).toBeNull();
    expect(screen.getByText(/loading…/i)).toBeInTheDocument();
  });
});

describe("MetricsTabPanel — error state", () => {
  it("surfaces fetch errors in the header without losing the prior data", () => {
    hookState.current = makeHookReturn({ status: { kind: "error", message: "kaboom" } });
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.getByText(/error: kaboom/)).toBeInTheDocument();
    // Prior snapshot still renders the 4 charts.
    expect(chartCalls.instances).toHaveLength(4);
  });
});

// The retired "Open in Grafana" action never lived in the Metrics panel; keep the panel
// tab bar (RunDetailPage) — it's a run-level action, not a metrics one.
describe("MetricsTabPanel — no external dashboard link", () => {
  it("does not render an external dashboard link", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    expect(screen.queryByRole("link", { name: /Open in/ })).toBeNull();
  });
});

describe("MetricsTabPanel — paused state", () => {
  it("shows the paused chip with a reason-specific label when polling is gated", () => {
    hookState.current = makeHookReturn({ isPaused: true, pauseReason: "delayNull" });
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    expect(screen.getByText(/paused — terminal/i)).toBeInTheDocument();
  });

  it("no paused chip when polling is active", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.queryByText(/paused/i)).toBeNull();
  });
});

describe("MetricsTabPanel — layout toggle (Grid / Stacked)", () => {
  // Each test starts with a fresh localStorage so the toggle defaults
  // deterministically to "grid" — stale state from a previous test
  // could otherwise flip the initial selection.
  beforeEach(() => { window.localStorage.clear(); });

  it("defaults to Grid (2-column) and applies the matching CSS modifier", () => {
    const { container } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.getByRole("tab", { name: /Grid/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /Stacked/ })).toHaveAttribute("aria-selected", "false");
    expect(container.querySelector(".metricsPanel__chartGrid--grid")).not.toBeNull();
    expect(container.querySelector(".metricsPanel__chartGrid--stacked")).toBeNull();
  });

  it("clicking Stacked flips aria-selected + the CSS modifier + the chart height", () => {
    chartCalls.instances = [];
    const { container } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    const initialHeights = chartCalls.instances.map((c) => c.height);
    expect(initialHeights.every((h) => h === 220)).toBe(true);

    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("tab", { name: /Stacked/ }));
    expect(screen.getByRole("tab", { name: /Stacked/ })).toHaveAttribute("aria-selected", "true");
    expect(container.querySelector(".metricsPanel__chartGrid--stacked")).not.toBeNull();
    expect(container.querySelector(".metricsPanel__chartGrid--grid")).toBeNull();
    // Stacked uses taller charts (260 vs grid's 220), matching the two-run
    // comparison view's height (shared STACKED_CHART_HEIGHT constant).
    expect(chartCalls.instances.every((c) => c.height === 260)).toBe(true);
  });

  it("persists the selection to localStorage and restores it on remount", () => {
    const { unmount } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    fireEvent.click(screen.getByRole("tab", { name: /Stacked/ }));
    expect(window.localStorage.getItem("jmeterCloud.metricsLayout")).toBe("stacked");

    unmount();
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.getByRole("tab", { name: /Stacked/ })).toHaveAttribute("aria-selected", "true");
  });
});

describe("MetricsTabPanel — sync zoom + Reset zoom button", () => {
  it("all four charts share the same syncKey (keyed by runId) so zoom propagates across them", () => {
    chartCalls.instances = [];
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    const keys = chartCalls.instances.map((c) => c.syncKey);
    expect(keys).toHaveLength(4);
    expect(new Set(keys).size).toBe(1); // identical across all four
    expect(keys[0]).toBe("metrics:01J000RUN");
  });

  it("different runIds get different sync keys (no cross-run group leakage)", () => {
    chartCalls.instances = [];
    const { rerender } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    const firstKey = chartCalls.instances[0]!.syncKey;

    chartCalls.instances = [];
    rerender(<MetricsTabPanel runId="01J999OTHER" runState="RUNNING" />);
    const secondKey = chartCalls.instances[0]!.syncKey;

    expect(firstKey).toBe("metrics:01J000RUN");
    expect(secondKey).toBe("metrics:01J999OTHER");
    expect(firstKey).not.toBe(secondKey);
  });

  it("Reset zoom button increments resetVersion on ALL four charts", () => {
    chartCalls.instances = [];
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    // Initial render: resetVersion=0 across the board
    expect(chartCalls.instances.map((c) => c.resetVersion)).toEqual([0, 0, 0, 0]);

    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("button", { name: /Reset zoom/ }));
    // After click: every chart sees resetVersion=1, all in lockstep
    expect(chartCalls.instances.map((c) => c.resetVersion)).toEqual([1, 1, 1, 1]);

    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("button", { name: /Reset zoom/ }));
    expect(chartCalls.instances.map((c) => c.resetVersion)).toEqual([2, 2, 2, 2]);
  });

  it("Reset zoom is disabled when there's no data (avoids the dead click)", () => {
    hookState.current = makeHookReturn({
      data: { ...sampleData(), series: { tps: [], avgRtMs: [], errorPct: [], statusCodes: {} } },
    });
    render(<MetricsTabPanel runId="01J000RUN" runState="PREPARING" />);
    expect(screen.getByRole("button", { name: /Reset zoom/ })).toBeDisabled();
  });
});

describe("MetricsTabPanel — header no longer has a Refresh button (5s polling is enough)", () => {
  it("there is no Refresh button anywhere in the panel", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    expect(screen.queryByRole("button", { name: /Refresh/ })).toBeNull();
  });

  it("there is no description paragraph in the header (header is tight: status + actions only)", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.queryByText(/Per-second timeseries from/)).toBeNull();
  });
});

describe("MetricsTabPanel — structural a11y hooks (axe sweep lives in .a11y.test.tsx)", () => {
  it("layout toggle is a tablist with descriptive aria-label", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(screen.getByRole("tablist", { name: /Chart layout/i })).toBeInTheDocument();
  });
});

describe("MetricsTabPanel — Split by region", () => {
  function sampleDataWithRegions(): MetricsTimeseries {
    const base = sampleData();
    return {
      ...base,
      regions: {
        "us-west-2": {
          tps:      [{ sec: 1_700_000_000, v: 2 }],
          avgRtMs:  [{ sec: 1_700_000_000, v: 20 }],
          errorPct: [{ sec: 1_700_000_000, v: 1 }],
          statusCodes: { "200": [{ sec: 1_700_000_000, v: 2 }] },
        },
        "us-east-1": {
          tps:      [{ sec: 1_700_000_000, v: 3 }],
          avgRtMs:  [{ sec: 1_700_000_000, v: 10 }],
          errorPct: [{ sec: 1_700_000_000, v: 0 }],
          statusCodes: { "200": [{ sec: 1_700_000_000, v: 3 }], "500": [{ sec: 1_700_000_000, v: 1 }] },
        },
      },
    };
  }

  it("defaults to OFF — aggregate charts, no region series", () => {
    hookState.current = makeHookReturn({ data: sampleDataWithRegions() });
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    // 4 aggregate charts; the toggle is present but not pressed.
    expect(chartCalls.instances).toHaveLength(4);
    expect(screen.getByRole("button", { name: /Split by region/ }))
      .toHaveAttribute("aria-pressed", "false");
  });

  it("toggling ON renders one line per region + small-multiple status charts", () => {
    hookState.current = makeHookReturn({ data: sampleDataWithRegions() });
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);

    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("button", { name: /Split by region/ }));

    const titles = chartCalls.instances.map((c) => c.title);
    // 3 numeric "by region" charts + one Status-codes chart per region (2).
    expect(titles).toEqual([
      "TPS by region",
      "Response Time by region",
      "Error % by region",
      "Status codes — us-east-1",
      "Status codes — us-west-2",
    ]);

    // Numeric charts carry one series per region, region names as labels,
    // sorted (us-east-1 before us-west-2).
    const tpsChart = chartCalls.instances.find((c) => c.title === "TPS by region")!;
    expect(tpsChart.seriesLabels).toEqual(["us-east-1", "us-west-2"]);

    // Per-region status chart shows that region's codes only.
    const eastStatus = chartCalls.instances.find((c) => c.title === "Status codes — us-east-1")!;
    expect(eastStatus.seriesLabels).toEqual(["200", "500"]);
    const westStatus = chartCalls.instances.find((c) => c.title === "Status codes — us-west-2")!;
    expect(westStatus.seriesLabels).toEqual(["200"]);

    expect(screen.getByRole("button", { name: /Split by region/ }))
      .toHaveAttribute("aria-pressed", "true");
  });

  it("falls back to aggregate when the toggle is on but no region data is present", () => {
    // e.g. first poll right after flipping the toggle, before the
    // byRegion payload arrives — render aggregate rather than blank.
    hookState.current = makeHookReturn({ data: sampleData() }); // no `regions`
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    fireEvent.click(screen.getByRole("button", { name: /Split by region/ }));
    expect(chartCalls.instances.map((c) => c.title)).toContain("Total TPS");
    expect(chartCalls.instances.map((c) => c.title)).not.toContain("TPS by region");
  });
});

describe("MetricsTabPanel — time-window selector", () => {
  const windowSelect = () =>
    screen.getByRole("combobox", { name: /Time window/i }) as HTMLSelectElement;

  it("LIVE run defaults to 'Last 30 min' (whole-test is bounded while data is still arriving) and offers the fixed window set", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(windowSelect().value).toBe("30m");
    const labels = Array.from(windowSelect().options).map((o) => o.textContent);
    expect(labels).toEqual([
      "Whole test", "Last 5 min", "Last 10 min", "Last 30 min",
      "Last 1 hour", "Last 2 hours", "Last 4 hours",
    ]);
  });

  it("terminal run defaults to 'Whole test' (served from the terminal-run cache)", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    expect(windowSelect().value).toBe("all");
  });

  it("a stored 'Whole test' preference is honored on terminal runs but bounded to 30 min on live runs", () => {
    window.localStorage.setItem("jmeterCloud.metricsWindow", "all");
    const { unmount } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(windowSelect().value).toBe("30m");
    unmount();

    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    expect(windowSelect().value).toBe("all");
  });

  it("a stored bounded window is honored on live and terminal runs alike", () => {
    window.localStorage.setItem("jmeterCloud.metricsWindow", "1h");
    const { unmount } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(windowSelect().value).toBe("1h");
    unmount();

    render(<MetricsTabPanel runId="01J000RUN" runState="COMPLETED" />);
    expect(windowSelect().value).toBe("1h");
  });

  it("persists an explicit selection to localStorage and restores it on remount", () => {
    const { unmount } = render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    fireEvent.change(windowSelect(), { target: { value: "1h" } });
    expect(window.localStorage.getItem("jmeterCloud.metricsWindow")).toBe("1h");
    unmount();

    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    expect(windowSelect().value).toBe("1h");
  });

  it("picking 'Whole test' mid-run is honored for the session and stored", () => {
    render(<MetricsTabPanel runId="01J000RUN" runState="RUNNING" />);
    fireEvent.change(windowSelect(), { target: { value: "all" } });
    expect(windowSelect().value).toBe("all");
    expect(window.localStorage.getItem("jmeterCloud.metricsWindow")).toBe("all");
  });
});

// (within is imported above so we can scope cell lookups when tests grow)
void within;
