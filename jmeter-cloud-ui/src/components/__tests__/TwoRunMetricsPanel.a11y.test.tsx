import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { axe } from "vitest-axe";

import type { MetricsTimeseriesBatch } from "../../api/runs";

// Mock the chart so axe sees the panel's structural a11y, not uPlot's
// canvas. Canvas a11y is covered in TimeseriesChart.test.tsx.
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return {
    ...actual,
    TimeseriesChart: ({ title, series }: { title: string; series: Array<{ label: string }> }) => (
      <figure
        role="img"
        aria-label={`${title}: ${series.length} series`}
      >
        <figcaption className="visuallyHidden">{title}</figcaption>
      </figure>
    ),
  };
});

const hookState = vi.hoisted(() => ({
  current: null as ReturnType<typeof makeHookReturn> | null,
}));

vi.mock("../../hooks/useTwoRunTimeseries", () => ({
  useTwoRunTimeseries: () => hookState.current,
}));

import { TwoRunMetricsPanel } from "../TwoRunMetricsPanel";

function makeHookReturn(overrides: Partial<{
  status: { kind: "loading" } | { kind: "ok" } | { kind: "error"; message: string };
  data: MetricsTimeseriesBatch | null;
  lastUpdated: Date | null;
  isPaused: boolean;
  pauseReason: "manual" | "delayNull" | "documentHidden" | "offscreen" | null;
}> = {}) {
  return {
    status:      overrides.status ?? { kind: "ok" as const },
    data:        overrides.data === undefined ? populated() : overrides.data,
    lastUpdated: overrides.lastUpdated ?? new Date("2026-05-10T20:00:00Z"),
    isPaused:    overrides.isPaused ?? false,
    pauseReason: overrides.pauseReason ?? null,
  };
}

function populated(): MetricsTimeseriesBatch {
  return {
    runs: {
      "01J000A": {
        runId: "01J000A", bucketSize: 1, fromSecond: 1000, toSecond: 1002,
        series: {
          tps:      [{ sec: 1000, v: 5 }, { sec: 1001, v: 6 }],
          avgRtMs:  [{ sec: 1000, v: 10 }, { sec: 1001, v: 12 }],
          errorPct: [{ sec: 1000, v: 0 }, { sec: 1001, v: 0 }],
          statusCodes: { "200": [{ sec: 1000, v: 5 }] },
        },
      },
      "01J000B": {
        runId: "01J000B", bucketSize: 1, fromSecond: 9000, toSecond: 9002,
        series: {
          tps:      [{ sec: 9000, v: 8 }, { sec: 9001, v: 9 }],
          avgRtMs:  [{ sec: 9000, v: 15 }, { sec: 9001, v: 17 }],
          errorPct: [{ sec: 9000, v: 1 }, { sec: 9001, v: 0.5 }],
          statusCodes: { "200": [{ sec: 9000, v: 8 }] },
        },
      },
    },
    missing: [],
  };
}

beforeEach(() => {
  hookState.current = makeHookReturn();
});

afterEach(() => {
  vi.clearAllMocks();
});

/**
 * HM-8 — axe sweep for the two-run comparison panel across the four
 * states the operator actually sees:
 *
 * 1. **populated** — both runs present, three charts overlaid.
 * 2. **empty (both missing)** — friendly hint, no charts.
 * 3. **partial (one missing)** — chart still renders for the present
 *    run, "missing N" chip in the status row.
 * 4. **initial loading** — status=loading, data=null.
 * 5. **error** — fetch failure surfaces in the header without losing
 *    prior chart data.
 *
 * Each case asserts ZERO serious / critical axe violations.
 */
describe("TwoRunMetricsPanel — accessibility (vitest-axe)", () => {
  it("populated state has no axe violations", async () => {
    const { container } = render(<TwoRunMetricsPanel runIdA="01J000A" runIdB="01J000B" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("both-runs-missing empty state has no axe violations", async () => {
    hookState.current = makeHookReturn({
      data: { runs: {}, missing: ["01J000A", "01J000B"] },
    });
    const { container } = render(<TwoRunMetricsPanel runIdA="01J000A" runIdB="01J000B" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("partial (one missing, one present) state has no axe violations", async () => {
    const data = populated();
    delete data.runs["01J000B"];
    data.missing = ["01J000B"];
    hookState.current = makeHookReturn({ data });
    const { container } = render(<TwoRunMetricsPanel runIdA="01J000A" runIdB="01J000B" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("initial-loading state (no data, status=loading) has no axe violations", async () => {
    hookState.current = makeHookReturn({ status: { kind: "loading" }, data: null });
    const { container } = render(<TwoRunMetricsPanel runIdA="01J000A" runIdB="01J000B" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("error state (with prior data still painted) has no axe violations", async () => {
    hookState.current = makeHookReturn({ status: { kind: "error", message: "kaboom" } });
    const { container } = render(<TwoRunMetricsPanel runIdA="01J000A" runIdB="01J000B" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
