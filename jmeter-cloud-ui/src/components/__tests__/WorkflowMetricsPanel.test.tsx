import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const mocks = vi.hoisted(() => ({ summary: vi.fn(), timeseries: vi.fn() }));
vi.mock("../../api/runs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/runs")>();
  return {
    ...actual,
    runsApi: { ...actual.runsApi, summary: mocks.summary, timeseries: mocks.timeseries },
  };
});

import { WorkflowMetricsPanel } from "../workflow/WorkflowMetricsPanel";
import type { WorkflowTask } from "../../api/workflows";

const SECTIONS = "jmeterCloud.metrics.sections";

function loadTest(id: string, application: string): WorkflowTask {
  return {
    taskId: `t-${id}`, executionId: "ex", nodeId: id, type: "LOAD_TEST",
    name: `Load · ${application}`, state: "SUCCEEDED", attempt: 1,
    applicationName: application, runId: `run-${id}`,
  };
}

/** 100 samples over 0..60 — self-consistent with the server's own rate, 100/75. */
function summaryFor(application: string) {
  return {
    runId: `run-${application}`, fromSecond: 0, toSecond: 60,
    total: {
      samples: 100, errors: 10, tps: 100 / 75, errorPct: 10, avgMs: 50,
      p90Ms: 80, p95Ms: 90, p99Ms: 120, maxMs: 300, maxActiveThreads: 5,
    },
    byApplication: [],
  };
}

beforeEach(() => {
  window.localStorage.clear();
  mocks.summary.mockReset();
  mocks.timeseries.mockReset();
  mocks.summary.mockImplementation((runId: string) => Promise.resolve(summaryFor(runId)));
  mocks.timeseries.mockResolvedValue({
    runId: "r", bucketSize: 60, fromSecond: 0, toSecond: 60,
    series: { tps: [{ sec: 0, v: 2 }], avgRtMs: [], errorPct: [], statusCodes: {} },
  });
});

const TASKS = [loadTest("load1", "card-auth"), loadTest("load2", "card-capture")];

describe("WorkflowMetricsPanel", () => {
  it("collapsing every section still leaves every section reachable", async () => {
    // The trap: with nothing open, neither query runs, so there is no data —
    // and an empty-state that replaces the whole board would hide the very
    // headers needed to open one again.
    window.localStorage.setItem(SECTIONS, JSON.stringify({
      wfKeyMetrics: false, wfSummary: false, wfThroughput: false, wfErrors: false,
    }));

    render(<WorkflowMetricsPanel tasks={TASKS} live={false} />);

    for (const title of ["Key metrics", "Summary by application", "Throughput and response time", "Errors"]) {
      expect(screen.getByRole("button", { name: new RegExp(title) })).toBeInTheDocument();
    }
    // And nothing was fetched, because nothing that needs data is open.
    expect(mocks.summary).not.toHaveBeenCalled();
    expect(mocks.timeseries).not.toHaveBeenCalled();
  });

  it("a collapsed chart section fetches no timeseries, and opening one does", async () => {
    window.localStorage.setItem(SECTIONS, JSON.stringify({
      wfKeyMetrics: true, wfSummary: false, wfThroughput: false, wfErrors: false,
    }));

    render(<WorkflowMetricsPanel tasks={TASKS} live={false} />);

    await waitFor(() => expect(mocks.summary).toHaveBeenCalledTimes(2));
    expect(mocks.timeseries).not.toHaveBeenCalled();
  });

  it("folds every run into one headline, over the wall clock they actually spanned", async () => {
    render(<WorkflowMetricsPanel tasks={TASKS} live={false} />);
    // Two runs, 100 samples each over the same 0..60 window: 200 samples across
    // a 75 s span is 2.7 req/s. Not one run's number, and not the sum of the
    // two rates either — that only holds when the runs overlap completely.
    await waitFor(() => expect(screen.getByText("2.7")).toBeInTheDocument());
    expect(screen.getByText("All applications")).toBeInTheDocument();
  });

  it("every chart can be enlarged, the same control the run's Metrics tab has", async () => {
    render(<WorkflowMetricsPanel tasks={TASKS} live={false} />);

    // Throughput + response time are open by default; Errors is not.
    const enlarge = await screen.findAllByRole("button", { name: /^Enlarge / });
    expect(enlarge.map((b) => b.getAttribute("aria-label"))).toEqual([
      "Enlarge Throughput by application (req/s)",
      "Enlarge Response time by application — Average (ms)",
    ]);

    fireEvent.click(enlarge[0]!);
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByText("Throughput by application (req/s)").length).toBeGreaterThan(0);
  });

  it("the percentile pick names the response-time chart, so the enlarged copy says which one", async () => {
    render(<WorkflowMetricsPanel tasks={TASKS} live={false} />);
    const picker = await screen.findByRole("group", { name: /response time percentile/i });
    fireEvent.click(within(picker).getByRole("button", { name: "P99" }));

    await waitFor(() => expect(
      screen.getByRole("button", { name: "Enlarge Response time by application — P99 (ms)" }),
    ).toBeInTheDocument());
  });

  it("with no load tests it says so instead of rendering an empty board", () => {
    render(<WorkflowMetricsPanel tasks={[]} live={false} />);
    expect(screen.getByText(/No load test has started yet/)).toBeInTheDocument();
  });
});
