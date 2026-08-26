import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { axe } from "vitest-axe";

import type { MetricsTimeseries } from "../../api/runs";

// ── Mock the chart so axe sees the panel's structural a11y, not
//    uPlot's canvas (canvas is opaque to axe and tested separately
//    in TimeseriesChart.test.tsx). The mock keeps the role="img" +
//    aria-label that the real chart provides so the panel-level a11y
//    semantics are preserved. ──
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

// ── Mock the data hook so the panel renders deterministically across
//    the three a11y states. ──
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
  refresh: () => void;
}> = {}) {
  return {
    status:      overrides.status ?? { kind: "ok" as const },
    data:        overrides.data === undefined ? populatedData() : overrides.data,
    lastUpdated: overrides.lastUpdated ?? new Date("2026-05-10T20:00:00Z"),
    isPaused:    overrides.isPaused ?? false,
    pauseReason: overrides.pauseReason ?? null,
    refresh:     overrides.refresh ?? vi.fn(),
  };
}

function populatedData(): MetricsTimeseries {
  return {
    runId: "01J000A11Y",
    bucketSize: 1,
    fromSecond: 1_700_000_000,
    toSecond:   1_700_000_005,
    series: {
      tps:      [{ sec: 1_700_000_000, v: 5 }, { sec: 1_700_000_001, v: 6 }],
      avgRtMs:  [{ sec: 1_700_000_000, v: 12 }, { sec: 1_700_000_001, v: 14 }],
      errorPct: [{ sec: 1_700_000_000, v: 0 }, { sec: 1_700_000_001, v: 1.5 }],
      statusCodes: { "200": [{ sec: 1_700_000_000, v: 5 }] },
    },
  };
}

function emptyData(): MetricsTimeseries {
  return {
    runId: "01J000A11Y",
    bucketSize: 1,
    fromSecond: null,
    toSecond:   null,
    series: { tps: [], avgRtMs: [], errorPct: [], statusCodes: {} },
  };
}

beforeEach(() => {
  hookState.current = makeHookReturn();
});

afterEach(() => {
  vi.clearAllMocks();
});

/**
 * Dedicated axe sweep for the historical Metrics tab panel
 * across the three states the operator actually sees:
 *
 * 1. **populated** — the common case: 4 charts + header + status
 *    text + chart controls (Split by region / Reset zoom / window
 *    select / Grid-Stacked toggle).
 * 2. **empty** — PREPARING / no-rows-yet: empty-state hint replaces
 *    the chart grid.
 * 3. **loading** — initial fetch in flight, no data yet.
 *
 * Each case asserts ZERO serious / critical axe violations. Canvas
 * a11y is covered separately in {@code TimeseriesChart.test.tsx}; the
 * chart mock here keeps the {@code role="img"} + aria-label contract
 * so the panel's own structural a11y is what's under test.
 */
describe("MetricsTabPanel — accessibility (vitest-axe, 3 states)", () => {
  it("populated state has no axe violations", async () => {
    const { container } = render(<MetricsTabPanel runId="01J000A11Y" runState="RUNNING" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("empty state has no axe violations", async () => {
    hookState.current = makeHookReturn({ data: emptyData() });
    const { container } = render(<MetricsTabPanel runId="01J000A11Y" runState="PREPARING" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("initial loading state (no data, status=loading) has no axe violations", async () => {
    hookState.current = makeHookReturn({ status: { kind: "loading" }, data: null });
    const { container } = render(<MetricsTabPanel runId="01J000A11Y" runState="PREPARING" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });

  it("error state (with prior data still painted) has no axe violations", async () => {
    hookState.current = makeHookReturn({ status: { kind: "error", message: "kaboom" } });
    const { container } = render(<MetricsTabPanel runId="01J000A11Y" runState="RUNNING" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
