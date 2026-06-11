import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import type { MetricsTimeseries, MetricsTimeseriesBatch } from "../../api/runs";

// ── Mock the chart so we can introspect props (series, syncKey,
//    xAxisFormatter presence, resetVersion) without rendering uPlot. ──
const chartCalls = vi.hoisted(() => ({
  instances: [] as Array<{
    title: string;
    seriesLabels: string[];
    seriesColors: string[];
    seriesFirstX: number[];   // first x of each series — exercises elapsed shift
    seriesFirstV: number[];
    syncKey?: string;
    resetVersion?: number;
    height?: number;
    xAxisFormatterIsSet: boolean;
  }>,
}));

vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return {
    ...actual,
    TimeseriesChart: (props: {
      title: string;
      series: Array<{ label: string; color: string; data: ReadonlyArray<{ sec: number; v: number }> }>;
      height?: number;
      syncKey?: string;
      resetVersion?: number;
      xAxisFormatter?: (sec: number) => string;
    }) => {
      chartCalls.instances.push({
        title:        props.title,
        seriesLabels: props.series.map((s) => s.label),
        seriesColors: props.series.map((s) => s.color),
        seriesFirstX: props.series.map((s) => s.data[0]?.sec ?? NaN),
        seriesFirstV: props.series.map((s) => s.data[0]?.v ?? NaN),
        height:       props.height,
        syncKey:      props.syncKey,
        resetVersion: props.resetVersion,
        xAxisFormatterIsSet: typeof props.xAxisFormatter === "function",
      });
      return (
        <div data-testid="chartMock" data-title={props.title}>
          {props.title}
        </div>
      );
    },
  };
});

// ── Mock the data hook so the panel renders deterministically. ──
const hookState = vi.hoisted(() => ({
  current: null as ReturnType<typeof makeHookReturn> | null,
}));

vi.mock("../../hooks/useTwoRunTimeseries", () => ({
  useTwoRunTimeseries: () => hookState.current,
}));

import { TwoRunMetricsPanel, TWO_RUN_PALETTE } from "../TwoRunMetricsPanel";

function singleRunTs(runId: string, fromSec: number, tpsBase: number): MetricsTimeseries {
  return {
    runId,
    bucketSize: 1,
    fromSecond: fromSec,
    toSecond:   fromSec + 2,
    series: {
      tps:      [{ sec: fromSec, v: tpsBase },     { sec: fromSec + 1, v: tpsBase + 2 }],
      avgRtMs:  [{ sec: fromSec, v: 10 },          { sec: fromSec + 1, v: 12 }],
      errorPct: [{ sec: fromSec, v: 0 },           { sec: fromSec + 1, v: 1.5 }],
      statusCodes: { "200": [{ sec: fromSec, v: tpsBase }] },
    },
  };
}

function sampleBatch(): MetricsTimeseriesBatch {
  return {
    runs: {
      // Run A starts at epoch 1000 (lower numbers — easier visual mental math)
      "01J0000A": singleRunTs("01J0000A", 1000, 5),
      // Run B starts much later — proves the elapsed shift collapses both onto 0
      "01J0000B": singleRunTs("01J0000B", 9000, 8),
    },
    missing: [],
  };
}

function makeHookReturn(overrides: Partial<{
  status: { kind: "loading" } | { kind: "ok" } | { kind: "error"; message: string };
  data: MetricsTimeseriesBatch | null;
  lastUpdated: Date | null;
  isPaused: boolean;
  pauseReason: "manual" | "delayNull" | "documentHidden" | "offscreen" | null;
}> = {}) {
  return {
    status:      overrides.status ?? { kind: "ok" as const },
    data:        overrides.data === undefined ? sampleBatch() : overrides.data,
    lastUpdated: overrides.lastUpdated ?? new Date("2026-05-10T20:00:00Z"),
    isPaused:    overrides.isPaused ?? false,
    pauseReason: overrides.pauseReason ?? null,
  };
}

beforeEach(() => {
  chartCalls.instances = [];
  hookState.current = makeHookReturn();
  window.localStorage.clear();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("TwoRunMetricsPanel — happy path", () => {
  it("renders THREE charts (TPS, RT, Error %) — status codes deliberately omitted in compare view", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(chartCalls.instances).toHaveLength(3);
    expect(chartCalls.instances.map((c) => c.title)).toEqual([
      "Total TPS",
      "Response Time",
      "Error %",
    ]);
  });

  it("each chart has TWO series — one per run — with the fixed palette colors", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    for (const c of chartCalls.instances) {
      expect(c.seriesLabels).toHaveLength(2);
      // Color-coded per the published palette — run A blue, run B amber.
      expect(c.seriesColors[0]).toBe(TWO_RUN_PALETTE.runA);
      expect(c.seriesColors[1]).toBe(TWO_RUN_PALETTE.runB);
    }
  });

  it("renders the color-legend chips in the header (matches the chart palette)", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    const legend = screen.getByRole("list", { name: /Run color legend/i });
    const items = legend.querySelectorAll("li");
    expect(items).toHaveLength(2);
    // Short id of each — last 8 chars prefixed with ellipsis would be
    // confusing here because the IDs are already short (< 12 chars), so
    // they render in full. Assert presence.
    expect(items[0]!.textContent).toContain("01J0000A");
    expect(items[1]!.textContent).toContain("01J0000B");
  });
});

describe("TwoRunMetricsPanel — time-axis modes", () => {
  it("defaults to Elapsed mode and shifts each run's points so they start at sec=0", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.getByRole("tab", { name: /Elapsed/ })).toHaveAttribute("aria-selected", "true");
    const tps = chartCalls.instances.find((c) => c.title === "Total TPS")!;
    // Run A: fromSec=1000, first point at 1000 → 0 after shift
    expect(tps.seriesFirstX[0]).toBe(0);
    // Run B: fromSec=9000, first point at 9000 → 0 after shift
    expect(tps.seriesFirstX[1]).toBe(0);
    // Values are unshifted.
    expect(tps.seriesFirstV[0]).toBe(5);
    expect(tps.seriesFirstV[1]).toBe(8);
    // Elapsed mode passes an xAxisFormatter to the chart (axis labels
    // become "+30s" style instead of uPlot's "epoch-time-of-day" default).
    expect(tps.xAxisFormatterIsSet).toBe(true);
  });

  it("clicking Absolute preserves raw epoch seconds and drops the elapsed formatter", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("tab", { name: /Absolute/ }));
    const tps = chartCalls.instances.find((c) => c.title === "Total TPS")!;
    // Raw epoch values — same as input.
    expect(tps.seriesFirstX[0]).toBe(1000);
    expect(tps.seriesFirstX[1]).toBe(9000);
    // In absolute mode the chart falls back to uPlot's default time
    // formatter (no custom xAxisFormatter prop).
    expect(tps.xAxisFormatterIsSet).toBe(false);
  });

  it("axis-mode toggle persists to localStorage across remounts", () => {
    const { unmount } = render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    fireEvent.click(screen.getByRole("tab", { name: /Absolute/ }));
    expect(window.localStorage.getItem("jmeterCloud.compareAxisMode")).toBe("absolute");
    unmount();
    chartCalls.instances = [];
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.getByRole("tab", { name: /Absolute/ })).toHaveAttribute("aria-selected", "true");
    const tps = chartCalls.instances.find((c) => c.title === "Total TPS")!;
    expect(tps.seriesFirstX[0]).toBe(1000); // raw epoch — absolute restored
  });

  it("switching axis modes rotates the sync key (no leftover sync-group state under the wrong x-coords)", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    const firstKey = chartCalls.instances[0]!.syncKey!;
    expect(firstKey).toContain("elapsed");

    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("tab", { name: /Absolute/ }));
    const nextKey = chartCalls.instances[0]!.syncKey!;
    expect(nextKey).toContain("absolute");
    expect(nextKey).not.toBe(firstKey);
  });
});

describe("TwoRunMetricsPanel — sync zoom + reset", () => {
  it("all three charts share the same syncKey so dragging on one zooms all three", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    const keys = chartCalls.instances.map((c) => c.syncKey);
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).toBe("compare:01J0000A:01J0000B:elapsed");
  });

  it("Reset Zoom button bumps resetVersion on every chart in lockstep", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(chartCalls.instances.map((c) => c.resetVersion)).toEqual([0, 0, 0]);

    chartCalls.instances = [];
    fireEvent.click(screen.getByRole("button", { name: /Reset zoom/ }));
    expect(chartCalls.instances.map((c) => c.resetVersion)).toEqual([1, 1, 1]);
  });

  it("Reset Zoom is disabled when there's no data anywhere", () => {
    hookState.current = makeHookReturn({
      data: { runs: {}, missing: ["01J0000A", "01J0000B"] },
    });
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.getByRole("button", { name: /Reset zoom/ })).toBeDisabled();
  });
});

describe("TwoRunMetricsPanel — empty / partial / error states", () => {
  it("both runs missing → empty hint calls out both ids, no charts", () => {
    hookState.current = makeHookReturn({
      data: { runs: {}, missing: ["01J0000A", "01J0000B"] },
    });
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.queryByTestId("chartMock")).toBeNull();
    expect(screen.getByText(/Neither/i)).toBeInTheDocument();
  });

  it("one missing → status row shows the 'missing N' chip", () => {
    const data = sampleBatch();
    delete data.runs["01J0000B"];
    data.missing = ["01J0000B"];
    hookState.current = makeHookReturn({ data });
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    // The chip lives in the status row and renders even when there's
    // still chart data (Run A is present).
    expect(screen.getByText(/missing 1/i)).toBeInTheDocument();
    // Charts still render — Run A's data is there.
    expect(chartCalls.instances).toHaveLength(3);
    // Charts only have 1 series now (Run B is gone).
    for (const c of chartCalls.instances) {
      expect(c.seriesLabels).toHaveLength(1);
      expect(c.seriesColors[0]).toBe(TWO_RUN_PALETTE.runA);
    }
  });

  it("initial-loading (status=loading, data=null) renders 'loading…' and no charts/hint", () => {
    hookState.current = makeHookReturn({ status: { kind: "loading" }, data: null });
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.queryByTestId("chartMock")).toBeNull();
    expect(screen.queryByText(/no metrics/i)).toBeNull();
    expect(screen.getByText(/loading…/i)).toBeInTheDocument();
  });

  it("error state surfaces in the header without losing prior chart data", () => {
    hookState.current = makeHookReturn({ status: { kind: "error", message: "boom" } });
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.getByText(/error: boom/i)).toBeInTheDocument();
    // Charts still draw — the panel keeps the prior snapshot in `data`.
    expect(chartCalls.instances).toHaveLength(3);
  });

  it("paused chip with the right reason for delayNull (both runs terminal)", () => {
    hookState.current = makeHookReturn({ isPaused: true, pauseReason: "delayNull" });
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.getByText(/paused — both terminal/i)).toBeInTheDocument();
  });
});

describe("TwoRunMetricsPanel — header structure", () => {
  it("status row reports seconds-of-overlap when both runs have data", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    // Sample batch has 2 points each — overlap label uses the max
    // (the longer side defines the visible x-range).
    expect(screen.getByText(/2 seconds of overlap/)).toBeInTheDocument();
  });

  it("axis-mode toggle is a tablist with descriptive aria-label", () => {
    render(<TwoRunMetricsPanel runIdA="01J0000A" runIdB="01J0000B" />);
    expect(screen.getByRole("tablist", { name: /Time axis/i })).toBeInTheDocument();
  });
});
