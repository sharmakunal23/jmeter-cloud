import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { axe } from "vitest-axe";

import { emptySummary, emptyTimeseries, rollup, summary, timeseries } from "./metricsFixtures";

// The chart mock keeps the role="img" + aria-label the real chart provides
// (canvas is opaque to axe and tested separately in TimeseriesChart.test.tsx).
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return {
    ...actual,
    TimeseriesChart: ({ title, series }: { title: string; series: Array<{ label: string }> }) => (
      <figure role="img" aria-label={`${title}: ${series.length} series`}>
        <figcaption className="visuallyHidden">{title}</figcaption>
      </figure>
    ),
  };
});
const api = vi.hoisted(() => ({ summary: vi.fn(), timeseries: vi.fn(), metrics: vi.fn() }));
vi.mock("../../api/runs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/runs")>();
  return { ...actual, runsApi: { ...actual.runsApi, ...api } };
});
vi.mock("../../hooks/useAiStatus", () => ({ useAiStatus: () => ({ enabled: false, model: "", loading: false }) }));
vi.mock("../../hooks/useRunInsights", () => ({
  useRunInsights: () => ({ status: { kind: "idle" }, data: null, generate: vi.fn(), regenerate: vi.fn() }),
}));

import { MetricsTabPanel } from "../MetricsTabPanel";

function renderPanel(runState: "RUNNING" | "COMPLETED" = "COMPLETED") {
  return render(
    <MemoryRouter initialEntries={["/runs/01J000RUN"]}>
      <MetricsTabPanel runId="01J000RUN" runState={runState} dashboards={{ liveUrl: "https://grafana.example.com/d/cps?orgId=1" }} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  api.summary.mockReset().mockResolvedValue(summary(["CPS", "CPS-PCI"]));
  api.timeseries.mockReset().mockResolvedValue(timeseries());
  api.metrics.mockReset().mockResolvedValue(rollup());
  try { window.localStorage.setItem("jmeterCloud.metrics.sections", JSON.stringify({ perLabel: true, aggregateReport: true })); } catch { /* */ }
});
afterEach(() => {
  vi.clearAllMocks();
  try { window.localStorage.clear(); } catch { /* */ }
});

describe("MetricsTabPanel — accessibility (vitest-axe)", () => {
  it("populated state — every section open, the stat row, both tables, the Grafana link — has no axe violations", async () => {
    const { container } = renderPanel();
    await screen.findByRole("table", { name: /aggregate report/i });
    await screen.findByRole("table", { name: /summary by application/i });
    expect(await axe(container)).toHaveNoViolations();
  });

  it("empty state has no axe violations", async () => {
    api.summary.mockResolvedValue(emptySummary());
    api.timeseries.mockResolvedValue(emptyTimeseries());
    api.metrics.mockResolvedValue(rollup([]));
    const { container } = renderPanel("RUNNING");
    await screen.findByTestId("keyMetricsEmpty");
    await screen.findByTestId("aggregateReportEmpty");
    expect(await axe(container)).toHaveNoViolations();
  });

  it("error state (fetch rejected, nothing painted) has no axe violations", async () => {
    api.summary.mockRejectedValue(new Error("db down"));
    api.timeseries.mockRejectedValue(new Error("db down"));
    api.metrics.mockRejectedValue(new Error("db down"));
    const { container } = renderPanel("RUNNING");
    await screen.findByText(/error: db down/);
    expect(await axe(container)).toHaveNoViolations();
  });
});
