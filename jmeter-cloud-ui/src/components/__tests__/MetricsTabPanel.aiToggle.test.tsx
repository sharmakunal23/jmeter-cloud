import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import type { MetricsTimeseries } from "../../api/runs";

// Stub the chart (uPlot) — this file is about the AI-insights toggle, not charts.
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return { ...actual, TimeseriesChart: () => <div data-testid="chartMock" /> };
});

const mocks = vi.hoisted(() => ({
  useMetricsTimeseries: vi.fn(),
  useAiStatus: vi.fn(),
  useRunInsights: vi.fn(),
}));
vi.mock("../../hooks/useMetricsTimeseries", () => ({ useMetricsTimeseries: mocks.useMetricsTimeseries }));
vi.mock("../../hooks/useAiStatus", () => ({ useAiStatus: mocks.useAiStatus }));
vi.mock("../../hooks/useRunInsights", () => ({ useRunInsights: mocks.useRunInsights }));

import { MetricsTabPanel } from "../MetricsTabPanel";

const generate = vi.fn();
const regenerate = vi.fn();

/** Timeseries with N seconds of data so insights are "ready" (≥30s). */
function data(seconds: number): MetricsTimeseries {
  const tps = Array.from({ length: seconds }, (_, i) => ({ sec: 1000 + i, v: 10 }));
  return {
    runId: "01J0RUN",
    bucketSize: 1,
    fromSecond: 1000,
    toSecond: 1000 + seconds,
    series: { tps, avgRtMs: tps, errorPct: tps.map((p) => ({ ...p, v: 0 })), statusCodes: {} },
  };
}

beforeEach(() => {
  mocks.useMetricsTimeseries.mockReset();
  mocks.useAiStatus.mockReset();
  mocks.useRunInsights.mockReset();
  generate.mockReset();
  regenerate.mockReset();
  mocks.useMetricsTimeseries.mockReturnValue({
    status: { kind: "ok" }, data: data(60), lastUpdated: new Date(0), isPaused: false, pauseReason: null,
  });
  mocks.useRunInsights.mockReturnValue({ status: { kind: "idle" }, data: null, generate, regenerate });
});

describe("MetricsTabPanel — AI insights toggle", () => {
  it("hides the AI insights button when AI is disabled", () => {
    mocks.useAiStatus.mockReturnValue({ enabled: false, model: "", loading: false });
    render(<MetricsTabPanel runId="01J0RUN" runState="COMPLETED" />);
    expect(screen.queryByRole("button", { name: /ai insights/i })).not.toBeInTheDocument();
  });

  it("shows the button next to Split by region when enabled, and opens the side column on click", () => {
    mocks.useAiStatus.mockReturnValue({ enabled: true, model: "claude-test", loading: false });
    render(<MetricsTabPanel runId="01J0RUN" runState="COMPLETED" />);

    const toggle = screen.getByRole("button", { name: /ai insights/i });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    // Panel not mounted until opened.
    expect(screen.queryByRole("button", { name: /close ai insights/i })).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /close ai insights/i })).toBeInTheDocument();
    // Opening with ≥30s of data auto-generates (one click → insights).
    expect(generate).toHaveBeenCalledTimes(1);
  });

  it("does not auto-generate when there is < 30s of data", () => {
    mocks.useAiStatus.mockReturnValue({ enabled: true, model: "claude-test", loading: false });
    mocks.useMetricsTimeseries.mockReturnValue({
      status: { kind: "ok" }, data: data(10), lastUpdated: new Date(0), isPaused: false, pauseReason: null,
    });
    render(<MetricsTabPanel runId="01J0RUN" runState="RUNNING" />);
    fireEvent.click(screen.getByRole("button", { name: /ai insights/i }));
    expect(generate).not.toHaveBeenCalled();
    expect(screen.getByText(/~30 s of metrics/i)).toBeInTheDocument();
  });
});
