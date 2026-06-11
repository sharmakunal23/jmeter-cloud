import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import type { MetricsTimeseries, MetricsTimeseriesBatch } from "../../api/runs";

// Stub the chart (uPlot) — this file is about the AI "Explain the delta" toggle.
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return { ...actual, TimeseriesChart: () => <div data-testid="chartMock" /> };
});

const mocks = vi.hoisted(() => ({
  useTwoRunTimeseries: vi.fn(),
  useAiStatus: vi.fn(),
  useCompareInsights: vi.fn(),
}));
vi.mock("../../hooks/useTwoRunTimeseries", () => ({ useTwoRunTimeseries: mocks.useTwoRunTimeseries }));
vi.mock("../../hooks/useAiStatus", () => ({ useAiStatus: mocks.useAiStatus }));
vi.mock("../../hooks/useCompareInsights", () => ({ useCompareInsights: mocks.useCompareInsights }));

import { TwoRunMetricsPanel } from "../TwoRunMetricsPanel";

const generate = vi.fn();
const regenerate = vi.fn();

function series(runId: string, n: number): MetricsTimeseries {
  const tps = Array.from({ length: n }, (_, i) => ({ sec: 1000 + i, v: 10 }));
  return {
    runId, bucketSize: 1, fromSecond: 1000, toSecond: 1000 + n,
    series: { tps, avgRtMs: tps, errorPct: tps.map((p) => ({ ...p, v: 0 })), statusCodes: {} },
  };
}

function batch(a: string, b: string, n: number): MetricsTimeseriesBatch {
  return { runs: { [a]: series(a, n), [b]: series(b, n) }, missing: [] };
}

beforeEach(() => {
  mocks.useTwoRunTimeseries.mockReset();
  mocks.useAiStatus.mockReset();
  mocks.useCompareInsights.mockReset();
  generate.mockReset();
  regenerate.mockReset();
  mocks.useTwoRunTimeseries.mockReturnValue({
    status: { kind: "ok" }, data: batch("01J0A", "01J0B", 5),
    lastUpdated: new Date(0), isPaused: false, pauseReason: null,
  });
  mocks.useCompareInsights.mockReturnValue({ status: { kind: "idle" }, data: null, generate, regenerate });
});

describe("TwoRunMetricsPanel — Explain the delta toggle", () => {
  it("hides the button when AI is disabled", () => {
    mocks.useAiStatus.mockReturnValue({ enabled: false, model: "", loading: false });
    render(<TwoRunMetricsPanel runIdA="01J0A" runIdB="01J0B" runStateA="COMPLETED" runStateB="COMPLETED" />);
    expect(screen.queryByRole("button", { name: /explain the delta/i })).not.toBeInTheDocument();
  });

  it("opens the side column on click and auto-generates when both runs have data", () => {
    mocks.useAiStatus.mockReturnValue({ enabled: true, model: "claude-test", loading: false });
    render(<TwoRunMetricsPanel runIdA="01J0A" runIdB="01J0B" runStateA="COMPLETED" runStateB="COMPLETED" />);

    const toggle = screen.getByRole("button", { name: /explain the delta/i });
    expect(screen.queryByRole("button", { name: /close ai comparison insights/i })).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /close ai comparison insights/i })).toBeInTheDocument();
    expect(generate).toHaveBeenCalledTimes(1);
  });

  it("does not auto-generate when one run has no data", () => {
    mocks.useAiStatus.mockReturnValue({ enabled: true, model: "claude-test", loading: false });
    mocks.useTwoRunTimeseries.mockReturnValue({
      status: { kind: "ok" },
      data: { runs: { "01J0A": series("01J0A", 5) }, missing: ["01J0B"] },
      lastUpdated: new Date(0), isPaused: false, pauseReason: null,
    });
    render(<TwoRunMetricsPanel runIdA="01J0A" runIdB="01J0B" runStateA="COMPLETED" runStateB="FAILED" />);
    fireEvent.click(screen.getByRole("button", { name: /explain the delta/i }));
    expect(generate).not.toHaveBeenCalled();
    expect(screen.getByText(/both runs have metrics/i)).toBeInTheDocument();
  });
});
