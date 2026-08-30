import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { summary, timeseries } from "./metricsFixtures";

// Stub the chart (uPlot) — this file is about the AI-insights toggle, not charts.
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return { ...actual, TimeseriesChart: () => <div data-testid="chartMock" /> };
});
const api = vi.hoisted(() => ({ summary: vi.fn(), timeseries: vi.fn(), metrics: vi.fn() }));
vi.mock("../../api/runs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/runs")>();
  return { ...actual, runsApi: { ...actual.runsApi, ...api } };
});
const mocks = vi.hoisted(() => ({ useAiStatus: vi.fn(), useRunInsights: vi.fn() }));
vi.mock("../../hooks/useAiStatus", () => ({ useAiStatus: mocks.useAiStatus }));
vi.mock("../../hooks/useRunInsights", () => ({ useRunInsights: mocks.useRunInsights }));

import { MetricsTabPanel } from "../MetricsTabPanel";

const generate = vi.fn();
const regenerate = vi.fn();

function renderPanel(runState: "RUNNING" | "COMPLETED") {
  return render(
    <MemoryRouter initialEntries={["/runs/01J0RUN"]}>
      <MetricsTabPanel runId="01J0RUN" runState={runState} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mocks.useAiStatus.mockReset();
  mocks.useRunInsights.mockReset();
  generate.mockReset();
  regenerate.mockReset();
  api.summary.mockReset().mockResolvedValue(summary());
  api.timeseries.mockReset().mockResolvedValue(timeseries({}, 4));   // 4 × 15 s = 60 s of data
  mocks.useRunInsights.mockReturnValue({ status: { kind: "idle" }, data: null, generate, regenerate });
  try { window.localStorage.clear(); } catch { /* */ }
});

describe("MetricsTabPanel — AI insights toggle", () => {
  it("hides the AI insights button when AI is disabled", async () => {
    mocks.useAiStatus.mockReturnValue({ enabled: false, model: "", loading: false });
    renderPanel("COMPLETED");
    await screen.findAllByTestId("chartMock");
    expect(screen.queryByRole("button", { name: /ai insights/i })).not.toBeInTheDocument();
  });

  it("shows the button in the toolbar when enabled, and opens the side column on click", async () => {
    mocks.useAiStatus.mockReturnValue({ enabled: true, model: "claude-test", loading: false });
    renderPanel("COMPLETED");
    await screen.findAllByTestId("chartMock");

    const toggle = screen.getByRole("button", { name: /ai insights/i });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByRole("button", { name: /close ai insights/i })).not.toBeInTheDocument();

    fireEvent.click(toggle);

    expect(toggle).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /close ai insights/i })).toBeInTheDocument();
    // Opening with ≥30s of data auto-generates (one click → insights).
    await waitFor(() => expect(generate).toHaveBeenCalledTimes(1));
  });

  it("does not auto-generate when there is < 30s of data", async () => {
    mocks.useAiStatus.mockReturnValue({ enabled: true, model: "claude-test", loading: false });
    api.timeseries.mockResolvedValue(timeseries({}, 1));   // one 15 s bucket
    renderPanel("RUNNING");
    await screen.findAllByTestId("chartMock");
    fireEvent.click(screen.getByRole("button", { name: /ai insights/i }));
    expect(generate).not.toHaveBeenCalled();
    expect(screen.getByText(/~30 s of metrics/i)).toBeInTheDocument();
  });
});
