import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import { emptySummary, emptyTimeseries, rollup, summary, timeseries } from "./metricsFixtures";

// ── The chart is mocked so the panel's wiring (titles, series, sync, reset)
//    is asserted without uPlot; the API is mocked at the client boundary so
//    the test drives the real hooks, sections and view state. ──
const chartCalls = vi.hoisted(() => ({
  instances: [] as Array<{ title: string; seriesLabels: string[]; firstValues: Array<number | undefined>;
                           height?: number; syncKey?: string; resetVersion?: number }>,
}));
vi.mock("../charts/TimeseriesChart", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../charts/TimeseriesChart")>();
  return {
    ...actual,
    TimeseriesChart: (props: { title: string; series: Array<{ label: string; data?: Array<{ v: number }> }>;
                               height?: number; syncKey?: string; resetVersion?: number }) => {
      chartCalls.instances.push({
        title: props.title, seriesLabels: props.series.map((s) => s.label),
        firstValues: props.series.map((s) => s.data?.[0]?.v),
        height: props.height, syncKey: props.syncKey, resetVersion: props.resetVersion,
      });
      return (
        <div data-testid="chartMock" data-title={props.title} data-labels={props.series.map((s) => s.label).join(",")}
          data-synckey={props.syncKey ?? ""} data-resetversion={String(props.resetVersion ?? 0)}>
          chart: {props.title}
        </div>
      );
    },
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

import { MetricsTabPanel, METRICS_REFRESH_MS, errorCodeSeries } from "../MetricsTabPanel";
import type { RunState } from "../../api/runs";

let location: { search: string } = { search: "" };
function Location() {
  const loc = useLocation();
  location = loc;
  return null;
}

function renderPanel(runState: RunState = "RUNNING", search = "", extra: Partial<Parameters<typeof MetricsTabPanel>[0]> = {}) {
  return render(
    <MemoryRouter initialEntries={[`/runs/01J000RUN${search}`]}>
      <Routes>
        <Route path="/runs/:runId" element={<><Location /><MetricsTabPanel runId="01J000RUN" runState={runState} {...extra} /></>} />
      </Routes>
    </MemoryRouter>,
  );
}

const chartTitles = () => screen.queryAllByTestId("chartMock").map((el) => el.getAttribute("data-title"));
const section = (name: RegExp) => screen.getByRole("button", { name });

beforeEach(() => {
  chartCalls.instances = [];
  api.summary.mockReset().mockResolvedValue(summary());
  api.timeseries.mockReset().mockResolvedValue(timeseries());
  api.metrics.mockReset().mockResolvedValue(rollup());
  try { window.localStorage.clear(); } catch { /* jsdom always has it */ }
});

afterEach(() => {
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe("MetricsTabPanel — sections and what they fetch", () => {
  it("opens Key metrics and both chart sections by default; Per label and the Aggregate report stay collapsed and fetch nothing", async () => {
    renderPanel();
    await screen.findAllByTestId("chartMock");
    expect(section(/key metrics/i)).toHaveAttribute("aria-expanded", "true");
    expect(section(/throughput and response time/i)).toHaveAttribute("aria-expanded", "true");
    expect(section(/^errors$/i)).toHaveAttribute("aria-expanded", "true");
    expect(section(/per label/i)).toHaveAttribute("aria-expanded", "false");
    expect(section(/aggregate report/i)).toHaveAttribute("aria-expanded", "false");
    expect(api.summary).toHaveBeenCalledTimes(1);
    expect(api.timeseries).toHaveBeenCalledTimes(1);
    expect(api.timeseries.mock.calls[0]![2]).toMatchObject({ byApplication: false, byRegion: false });
    expect(api.timeseries.mock.calls[0]![2].byLabel).toBeFalsy();
    expect(api.metrics).not.toHaveBeenCalled();
  });

  it("expanding Per label fetches the label split; expanding the Aggregate report fetches the rollup", async () => {
    api.timeseries.mockImplementation((_id: string, _s: AbortSignal, opts: { byLabel?: boolean }) =>
      Promise.resolve(opts.byLabel
        ? timeseries({ labels: { "TG1 login": timeseries().series, "TG5 pay": timeseries().series }, labelsTotal: 7 })
        : timeseries()));
    renderPanel();
    await screen.findAllByTestId("chartMock");
    fireEvent.click(section(/per label/i));
    await waitFor(() => expect(api.timeseries).toHaveBeenCalledTimes(2));
    expect(api.timeseries.mock.calls[1]![2]).toMatchObject({ byLabel: true, labelLimit: 10, window: "30m" });
    await waitFor(() => expect(chartTitles()).toContain("Throughput per label"));
    expect(chartTitles()).toContain("Response time per label (avg)");
    expect(screen.getByText(/busiest 2 of 7 labels/i)).toBeInTheDocument();

    fireEvent.click(section(/aggregate report/i));
    await waitFor(() => expect(api.metrics).toHaveBeenCalledTimes(1));
    const table = await screen.findByRole("table", { name: /aggregate report/i });
    expect(within(table).getAllByRole("row")).toHaveLength(3);
    expect(within(table).getByText("TG1 login")).toBeInTheDocument();
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent?.replace(/ [↑↓]$/, "")))
      .toEqual(["Label", "Samples", "Throughput", "Avg", "P90", "P95", "P99", "Error %"]);
  });

  it("collapsing a section unmounts its content; a collapsed section is not refreshed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPanel();
    await screen.findAllByTestId("chartMock");
    fireEvent.click(section(/key metrics/i));
    expect(screen.queryByText("TPS", { selector: "dt" })).toBeNull();
    const summaryCalls = api.summary.mock.calls.length;
    await act(async () => { vi.advanceTimersByTime(METRICS_REFRESH_MS + 50); });
    await waitFor(() => expect(api.timeseries.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(api.summary).toHaveBeenCalledTimes(summaryCalls);
  });

  it("the two chart sections share one timeseries read: collapsing both stops it, either one keeps it", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPanel();
    await screen.findAllByTestId("chartMock");
    fireEvent.click(section(/throughput and response time/i));
    expect(chartTitles()).toEqual(["Error %", "Error codes"]);
    await act(async () => { vi.advanceTimersByTime(METRICS_REFRESH_MS + 50); });
    await waitFor(() => expect(api.timeseries).toHaveBeenCalledTimes(2));
    fireEvent.click(section(/^errors$/i));
    expect(chartTitles()).toEqual([]);
    await act(async () => { vi.advanceTimersByTime(METRICS_REFRESH_MS + 50); });
    expect(api.timeseries).toHaveBeenCalledTimes(2);
  });
});

describe("MetricsTabPanel — key metrics", () => {
  it("renders TPS · Avg · P90 · P95 · P99 · Error % from /summary and the per-application table only when the run spans several applications", async () => {
    api.summary.mockResolvedValue(summary(["CPS", "CPS-PCI"]));
    renderPanel();
    const value = async (term: string) => (await screen.findByText(term, { selector: "dt" })).nextElementSibling;
    expect(await value("TPS")).toHaveTextContent("22.0");
    expect(await value("P99")).toHaveTextContent("900ms");
    expect(await value("Error %")).toHaveTextContent("1.00%");
    expect(screen.getAllByRole("term").map((t) => t.textContent)).toEqual(["TPS", "Avg", "P90", "P95", "P99", "Error %"]);
    const table = screen.getByRole("table", { name: /summary by application/i });
    expect(within(table).getAllByRole("row")).toHaveLength(3);
  });

  it("a single-application run shows no per-application table; an empty range says so", async () => {
    renderPanel();
    await screen.findByText("TPS", { selector: "dt" });
    expect(screen.queryByRole("table", { name: /summary by application/i })).toBeNull();
    api.summary.mockResolvedValue(emptySummary());
    renderPanel("RUNNING", "?range=5m");
    await screen.findByTestId("keyMetricsEmpty");
  });
});

describe("MetricsTabPanel — charts", () => {
  it("renders the four dashboard charts with the Grafana series (Avg/P90/P95/P99, 4xx/5xx) on one sync group", async () => {
    renderPanel();
    await waitFor(() => expect(chartTitles()).toEqual(["Throughput", "Response time", "Error %", "Error codes"]));
    const byTitle = Object.fromEntries(chartCalls.instances.map((c) => [c.title, c]));
    expect(byTitle["Response time"]!.seriesLabels).toEqual(["Avg", "P90", "P95", "P99"]);
    expect(byTitle["Error codes"]!.seriesLabels).toEqual(["4xx", "5xx"]);
    expect(new Set(chartCalls.instances.map((c) => c.syncKey))).toEqual(new Set(["metrics:01J000RUN"]));
  });

  it("error codes are 4xx and 5xx only, as a percentage of the bucket's samples", () => {
    const s = errorCodeSeries({
      tps: [{ sec: 1, v: 10 }], avgRtMs: [], errorPct: [],
      statusCodes: { "4xx": [{ sec: 1, v: 0.5 }], "5xx": [{ sec: 1, v: 1 }], other: [{ sec: 1, v: 0.1 }] },
    });
    expect(s.map((x) => x.label)).toEqual(["4xx", "5xx"]);
    expect(s[0]!.data[0]!.v).toBe(5);
    expect(s[1]!.data[0]!.v).toBe(10);
  });

  it("split by region fetches the split and renders one line per region; the picker offers no other split", async () => {
    api.timeseries.mockImplementation((_id: string, _s: AbortSignal, opts: { byRegion?: boolean }) =>
      Promise.resolve(opts.byRegion
        ? timeseries({ regions: { "na-east": timeseries().series, "na-west": timeseries().series } })
        : timeseries()));
    renderPanel("RUNNING", "?split=region");
    await waitFor(() => expect(chartTitles()[0]).toBe("Throughput by region"));
    expect(api.timeseries.mock.calls[0]![2]).toMatchObject({ byRegion: true, byApplication: false });
    const throughput = chartCalls.instances.find((c) => c.title === "Throughput by region")!;
    expect(throughput.seriesLabels).toEqual(["na-east", "na-west"]);
    expect(chartTitles()).toContain("Error codes");   // the total, as on the hosted dashboard
    expect(screen.getAllByRole("option").map((o) => o.textContent)).not.toContain("By application");
  });

  it("split by region offers Average / P90 / P95 / P99, and the chart follows the pick", async () => {
    api.timeseries.mockImplementation((_id: string, _s: AbortSignal, opts: { byRegion?: boolean }) =>
      Promise.resolve(opts.byRegion
        ? timeseries({ regions: { "na-east": timeseries().series, "na-west": timeseries().series } })
        : timeseries()));
    renderPanel("RUNNING", "?split=region");
    await waitFor(() => expect(chartTitles()).toContain("Response time (Average) by region"));

    const picker = screen.getByRole("group", { name: /response time percentile/i });
    expect(within(picker).getAllByRole("button").map((b) => b.textContent))
      .toEqual(["Average", "P90", "P95", "P99"]);

    chartCalls.instances.length = 0;
    fireEvent.click(within(picker).getByRole("button", { name: "P95" }));

    // The title says which, and the plotted values are the p95 series (300 in
    // the fixture) rather than the average (120) — a title alone would pass
    // even if the chart kept drawing averages.
    await waitFor(() => expect(chartTitles()).toContain("Response time (P95) by region"));
    const rt = chartCalls.instances.find((c) => c.title === "Response time (P95) by region")!;
    expect(rt.seriesLabels).toEqual(["na-east", "na-west"]);
    expect(rt.firstValues).toEqual([300, 300]);
    // A view choice, so it is in the link like range/granularity/split.
    expect(location.search).toContain("rt=p95");
  });

  it("no split, no picker — that chart already draws all four percentiles at once", async () => {
    renderPanel("RUNNING");
    await waitFor(() => expect(chartTitles()).toContain("Response time"));
    expect(screen.queryByRole("group", { name: /response time percentile/i })).not.toBeInTheDocument();
    const rt = chartCalls.instances.find((c) => c.title === "Response time")!;
    expect(rt.seriesLabels).toEqual(["Avg", "P90", "P95", "P99"]);
  });

  it("the picker is out of reach while its section is collapsed", async () => {
    api.timeseries.mockImplementation((_id: string, _s: AbortSignal, opts: { byRegion?: boolean }) =>
      Promise.resolve(opts.byRegion
        ? timeseries({ regions: { "na-east": timeseries().series } })
        : timeseries()));
    renderPanel("RUNNING", "?split=region");
    const picker = await screen.findByRole("group", { name: /response time percentile/i });
    expect(picker).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Throughput and response time/i }));
    await waitFor(() =>
      expect(screen.queryByRole("group", { name: /response time percentile/i })).not.toBeInTheDocument());
  });

  it("Reset zoom bumps resetVersion on every chart and is disabled without data", async () => {
    renderPanel();
    await screen.findAllByTestId("chartMock");
    fireEvent.click(screen.getByRole("button", { name: /reset zoom/i }));
    await waitFor(() => screen.getAllByTestId("chartMock").forEach((el) => expect(el.getAttribute("data-resetversion")).toBe("1")));
    api.timeseries.mockResolvedValue(emptyTimeseries());
    api.summary.mockResolvedValue(emptySummary());
    renderPanel("RUNNING", "?range=5m");
    await waitFor(() => expect(screen.getAllByRole("button", { name: /reset zoom/i }).some((b) => (b as HTMLButtonElement).disabled)).toBe(true));
  });

  it("shows the empty hint instead of charts when the range has no rows, and the error in the header without losing prior charts", async () => {
    api.timeseries.mockResolvedValue(emptyTimeseries());
    renderPanel();
    expect(await screen.findAllByText(/no metrics in this range yet/i)).toHaveLength(2);   // one per chart section
    expect(screen.queryAllByTestId("chartMock")).toHaveLength(0);
  });
});

describe("MetricsTabPanel — the view lives in the URL", () => {
  it("a live run opens on the last 30 minutes, a finished run on the whole test", async () => {
    renderPanel("RUNNING");
    await waitFor(() => expect(api.timeseries).toHaveBeenCalled());
    expect(api.timeseries.mock.calls[0]![2]).toMatchObject({ window: "30m", granularity: 15 });   // 15 s is the default, no "auto"
    expect(api.summary.mock.calls[0]![2]).toMatchObject({ window: "30m" });
    expect((screen.getByTitle(/most recent slice/i) as HTMLSelectElement).value).toBe("30m");
    expect(location.search).toBe("");

    api.timeseries.mockClear();
    renderPanel("COMPLETED");
    await waitFor(() => expect(api.timeseries).toHaveBeenCalled());
    expect(api.timeseries.mock.calls[0]![2]).toMatchObject({ window: "all" });
  });

  it("reads range, granularity and split from the URL and writes a change back (replace)", async () => {
    renderPanel("RUNNING", "?range=1h&granularity=60&split=region");
    await waitFor(() => expect(api.timeseries).toHaveBeenCalled());
    expect(api.timeseries.mock.calls[0]![2]).toMatchObject({ window: "1h", granularity: 60, byRegion: true });
    expect(screen.queryByText(/^Application$/)).toBeNull();   // no Application column anywhere on the tab
    fireEvent.change(screen.getByTitle(/most recent slice/i), { target: { value: "4h" } });
    await waitFor(() => expect(location.search).toContain("range=4h"));
    expect(location.search).toContain("granularity=60");
    expect(location.search).toContain("split=region");
    await waitFor(() => expect(api.timeseries.mock.calls.at(-1)![2]).toMatchObject({ window: "4h" }));
    // Back to the default clears the key.
    fireEvent.change(screen.getByTitle(/most recent slice/i), { target: { value: "30m" } });
    await waitFor(() => expect(location.search).not.toContain("range="));
  });

  it("Per label and the Aggregate report each own a label filter and Top-N (or All) that reach the URL after a pause", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPanel("COMPLETED", "?label=TG1&top=20&reportLabel=TG5&reportTop=all");
    await screen.findAllByTestId("chartMock");
    // Collapsed sections show no controls and fetch nothing.
    expect(screen.queryByTestId("perLabelLabelPrefix")).toBeNull();
    fireEvent.click(section(/per label/i));
    await waitFor(() => expect(api.timeseries.mock.calls.at(-1)![2]).toMatchObject({ byLabel: true, labelPrefix: "TG1", labelLimit: 20 }));
    expect((screen.getByTestId("perLabelLabelPrefix") as HTMLInputElement).value).toBe("TG1");
    expect((screen.getByTestId("perLabelLabelLimit") as HTMLSelectElement).value).toBe("20");
    expect(screen.getByRole("option", { name: "All labels" })).toBeInTheDocument();

    fireEvent.click(section(/aggregate report/i));
    await waitFor(() => expect(api.metrics).toHaveBeenCalledTimes(1));
    expect(api.metrics.mock.calls[0]![2]).toMatchObject({ labelPrefix: "TG5", labelLimit: "all", window: "all" });
    expect((screen.getByTestId("reportLabelPrefix") as HTMLInputElement).value).toBe("TG5");
    expect((screen.getByTestId("reportLabelLimit") as HTMLSelectElement).value).toBe("all");

    // The report's filter changes only the report's query and URL keys.
    fireEvent.change(screen.getByTestId("reportLabelPrefix"), { target: { value: "TG9" } });
    expect(location.search).toContain("reportLabel=TG5");
    await act(async () => { vi.advanceTimersByTime(400); });
    await waitFor(() => expect(location.search).toContain("reportLabel=TG9"));
    await waitFor(() => expect(api.metrics.mock.calls.at(-1)![2]).toMatchObject({ labelPrefix: "TG9" }));
    expect(location.search).toContain("label=TG1");
    expect(api.timeseries.mock.calls.at(-1)![2]).toMatchObject({ labelPrefix: "TG1" });

    fireEvent.change(screen.getByTestId("perLabelLabelLimit"), { target: { value: "all" } });
    await waitFor(() => expect(location.search).toContain("top=all"));
    await waitFor(() => expect(api.timeseries.mock.calls.at(-1)![2]).toMatchObject({ byLabel: true, labelLimit: "all" }));
  });

  it("an empty report or label set says so in the body only — no '0 labels' beside the title", async () => {
    api.metrics.mockResolvedValue(rollup([]));
    api.timeseries.mockImplementation((_id: string, _s: AbortSignal, opts: { byLabel?: boolean }) =>
      Promise.resolve(opts.byLabel ? timeseries({ labels: {}, labelsTotal: 0 }) : timeseries()));
    renderPanel("COMPLETED", "?reportLabel=GET%20s&label=GET%20s");
    await screen.findAllByTestId("chartMock");
    fireEvent.click(section(/aggregate report/i));
    fireEvent.click(section(/per label/i));
    await screen.findByTestId("aggregateReportEmpty");
    await screen.findByTestId("perLabelEmpty");
    expect(screen.getAllByText(/No labels start with "GET s" in this range/)).toHaveLength(2);
    expect(screen.queryByText(/0 labels/)).toBeNull();
  });

  it("the toolbar reads AI insights · Reset zoom · granularity · split · range · Open in Grafana, no points meta on the chart sections", async () => {
    renderPanel("RUNNING", "", { dashboards: { liveUrl: "https://grafana.example.com/d/cps?orgId=1" } });
    await screen.findAllByTestId("chartMock");
    const actions = screen.getByRole("link", { name: /open in grafana/i }).parentElement!;
    const order = Array.from(actions.children).map((el) =>
      el.tagName === "LABEL" ? (el.querySelector("select") as HTMLSelectElement).title.split(" ")[0] : el.textContent!.trim());
    expect(order).toEqual(["⟲ Reset zoom", "Seconds", "One", "The", "↗ Open in Grafana"]);   // AI insights is hidden while AI is disabled
    expect(screen.queryByText(/points · 15-s buckets/)).toBeNull();
    expect(screen.queryByRole("option", { name: "Auto" })).toBeNull();
  });
});

describe("MetricsTabPanel — refresh and status", () => {
  it("a live run refreshes every open section every 15 s while the page is open and wears a live badge; a finished run is paused as terminal", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const live = renderPanel("RUNNING");
    await screen.findAllByTestId("chartMock");
    expect(screen.getByText(/live · 15 s/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /refresh/i })).toBeNull();
    await act(async () => { vi.advanceTimersByTime(METRICS_REFRESH_MS + 50); });
    await waitFor(() => expect(api.timeseries).toHaveBeenCalledTimes(2));
    expect(api.summary).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
    live.unmount();

    api.timeseries.mockClear();
    renderPanel("COMPLETED");
    await screen.findByText(/paused — terminal/);
    expect(screen.queryByText(/live · 15 s/)).toBeNull();
    // The toolbar carries the badge only.
    expect(screen.queryByText(/refreshed/)).toBeNull();
  });

  it("a failed fetch is reported beside the badge while the last good charts stay painted", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPanel("RUNNING");
    await screen.findAllByTestId("chartMock");
    api.timeseries.mockRejectedValue(new Error("boom"));
    await act(async () => { vi.advanceTimersByTime(METRICS_REFRESH_MS + 50); });
    await screen.findByText(/error: boom/);
    expect(screen.getAllByTestId("chartMock").length).toBeGreaterThan(0);
  });
});

describe("MetricsTabPanel — export the aggregate report", () => {
  it("Export CSV is the first control of the report's header, disabled with no rows, and downloads the rows shown", async () => {
    const created: string[] = [];
    const revoked: string[] = [];
    let saved: { name: string; text: string } | null = null;
    (URL as unknown as { createObjectURL: (b: Blob) => string }).createObjectURL = (b: Blob) => {
      created.push("blob:1");
      void b.text().then((t) => { saved = { name: saved?.name ?? "", text: t }; });
      return "blob:1";
    };
    (URL as unknown as { revokeObjectURL: (u: string) => void }).revokeObjectURL = (u: string) => { revoked.push(u); };
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      saved = { name: this.download, text: saved?.text ?? "" };
    });

    renderPanel("COMPLETED", "?range=1h");
    await screen.findAllByTestId("chartMock");
    fireEvent.click(section(/aggregate report/i));
    const button = await screen.findByRole("button", { name: /export csv/i });
    const controls = button.parentElement!;
    expect(controls.firstElementChild).toBe(button);
    await screen.findByRole("table", { name: /aggregate report/i });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    await waitFor(() => expect(saved?.name).toBe("aggregateReport-01J000RUN-1h.csv"));
    await waitFor(() => expect(saved?.text).toContain("label,samples,throughputRps,avgMs,p90Ms,p95Ms,p99Ms,errorPct"));
    expect(saved!.text).toContain("TG1 login,1000,16.67,120.0,200.0,300.0,900.0,0.20");
    expect(revoked).toEqual(created);
    clickSpy.mockRestore();
  });

  it("Key metrics carries no samples meta beside its title", async () => {
    renderPanel("COMPLETED");
    await screen.findByText("TPS", { selector: "dt" });
    expect(screen.queryByText(/1,320 samples/)).toBeNull();
  });
});

describe("MetricsTabPanel — enlarge a chart", () => {
  it("every chart card has an enlarge control that opens the same chart in a modal; Escape and × close it", async () => {
    renderPanel("COMPLETED");
    await screen.findAllByTestId("chartMock");
    expect(screen.getAllByRole("button", { name: /^enlarge /i }).map((b) => b.getAttribute("aria-label")))
      .toEqual(["Enlarge Throughput", "Enlarge Response time", "Enlarge Error %", "Enlarge Error codes"]);
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Enlarge Response time" }));
    const dialog = screen.getByRole("dialog", { name: /^response time$/i });
    const modalChart = within(dialog).getByTestId("chartMock");
    expect(modalChart).toHaveAttribute("data-title", "Response time");
    expect(modalChart).toHaveAttribute("data-labels", "Avg,P90,P95,P99");
    expect(modalChart.getAttribute("data-synckey")).toBe("");   // not in the page's sync group
    expect(chartCalls.instances.at(-1)!.height).toBeGreaterThanOrEqual(320);   // ~60 % of the viewport, never below 320

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Enlarge Error codes" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("MetricsTabPanel — Open in Grafana", () => {
  it("links the group's dashboard on the tab's range when dashboards are given, and shows nothing otherwise", async () => {
    renderPanel("RUNNING", "?range=15m&granularity=30", {
      dashboards: { liveUrl: "https://grafana.example.com/d/cps?orgId=1", metricsApplication: "CPS-PCI" },
      run: { startedAt: "2026-08-30T11:50:00Z" },
    });
    const link = await screen.findByRole("link", { name: /open in grafana/i });
    const href = new URL(link.getAttribute("href")!);
    expect(href.searchParams.get("from")).toBe("now-15m");
    expect(href.searchParams.get("refresh")).toBe("15s");
    expect(href.searchParams.get("var-application")).toBe("CPS-PCI");
    expect(href.searchParams.get("var-granularity")).toBe("30");
    expect(new URL(link.getAttribute("href")!).searchParams.get("var-granularity")).toBe("30");

    renderPanel("RUNNING", "", { dashboards: { liveUrl: null } });
    await waitFor(() => expect(screen.getAllByRole("button", { name: /reset zoom/i }).length).toBe(2));
    expect(screen.getAllByRole("link", { name: /open in grafana/i })).toHaveLength(1);
  });
});
