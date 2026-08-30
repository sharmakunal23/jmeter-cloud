import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type uPlot from "uplot";

// ── Mock uPlot ──────────────────────────────────────────────────────
// The real lib is canvas-based and jsdom doesn't paint canvas
// meaningfully. We mock the constructor to record the props it
// received and to expose `destroy` / `setData` so the wrapper's
// lifecycle is testable.
//
// vi.mock hoists to the top of the file, so `vi.hoisted` is the right
// way to share spies between the factory and the test bodies.
const mocks = vi.hoisted(() => ({
  uplotConstructor: vi.fn(),
  uplotDestroy: vi.fn(),
  uplotSetData: vi.fn(),
  uplotSetSize: vi.fn(),
  uplotSetScale: vi.fn(),
  // Tracks every constructed mock chart instance (vi.fn().mock.instances
  // doesn't reliably capture ES-class `new` calls).
  instances: [] as Array<{ scales: { x: { min?: number; max?: number } }; data: unknown }>,
}));

vi.mock("uplot", () => {
  class MockUPlot {
    // Real uPlot exposes a mutable `data` field. The Reset Zoom code in
    // TimeseriesChart reads chart.data[0] to find the current x-extent,
    // so the mock has to store it too — otherwise resetVersion would
    // always short-circuit in tests and the assertion would never fire.
    data: unknown;
    // scales.x.{min,max} — the destroy+recreate-on-height path captures
    // these from the prior chart to restore zoom on the new chart.
    // Tests can mutate this directly to simulate "operator zoomed in".
    scales: { x: { min?: number; max?: number } } = { x: {} };
    destroy = mocks.uplotDestroy;
    setData = (next: unknown) => {
      this.data = next;
      mocks.uplotSetData(next);
    };
    setSize = mocks.uplotSetSize;
    setScale = (key: string, opts: { min: number; max: number }) => {
      // Mirror the prod call into our scales object so a subsequent
      // capture/restore cycle (e.g. height change after zoom) sees
      // the latest min/max.
      if (key === "x") this.scales.x = { min: opts.min, max: opts.max };
      mocks.uplotSetScale(key, opts);
    };
    constructor(opts: unknown, data: unknown, target: unknown) {
      this.data = data;
      mocks.uplotConstructor(opts, data, target);
      mocks.instances.push(this);
    }
  }
  return { default: MockUPlot };
});

const { uplotConstructor, uplotDestroy, uplotSetData, uplotSetSize, uplotSetScale } = mocks;

/** Returns the most-recently-constructed mock uPlot instance — for tests that need to spoof its `scales` field. */
function lastConstructedChart(): { scales: { x: { min?: number; max?: number } } } {
  const inst = mocks.instances.at(-1);
  if (!inst) throw new Error("no chart constructed yet");
  return inst;
}

import {
  TimeseriesChart,
  legendClickPlugin,
  type TimeseriesSeries,
  formatCompactNumber,
  formatCompactDuration,
  formatPercent,
} from "../TimeseriesChart";

beforeEach(() => {
  uplotConstructor.mockClear();
  uplotDestroy.mockClear();
  uplotSetData.mockClear();
  uplotSetSize.mockClear();
  uplotSetScale.mockClear();
  mocks.instances.length = 0;
});

afterEach(() => {
  vi.restoreAllMocks();
});

const tps: TimeseriesSeries = {
  label: "TPS",
  color: "#2563eb",
  data: [
    { sec: 100, v: 5 },
    { sec: 101, v: 12 },
    { sec: 102, v: 8 },
  ],
};

describe("TimeseriesChart — happy path", () => {
  it("renders without throwing on empty series and constructs a uPlot instance", () => {
    render(<TimeseriesChart title="Empty" series={[]} />);
    expect(uplotConstructor).toHaveBeenCalledTimes(1);
    // Aligned data for empty series is [[]] — just the empty xs.
    const [, data] = uplotConstructor.mock.calls[0]!;
    expect(data).toEqual([[]]);
  });

  it("constructs the uPlot instance with the right title + series labels + colors", () => {
    render(<TimeseriesChart title="TPS over time" series={[tps]} />);
    expect(uplotConstructor).toHaveBeenCalledTimes(1);
    const [opts] = uplotConstructor.mock.calls[0]!;
    expect((opts as { title: string }).title).toBe("TPS over time");
    const passedSeries = (opts as { series: Array<{ label?: string; stroke?: string }> }).series;
    expect(passedSeries[0]).toEqual({}); // x-axis placeholder
    expect(passedSeries[1]?.label).toBe("TPS");
    expect(passedSeries[1]?.stroke).toBe("#2563eb");
  });

  it("converts the series prop to uPlot's aligned `[xs, ys]` shape", () => {
    render(<TimeseriesChart title="t" series={[tps]} />);
    const [, data] = uplotConstructor.mock.calls[0]!;
    expect(data).toEqual([
      [100, 101, 102],
      [5, 12, 8],
    ]);
  });

  it("handles two sparse series — fills missing seconds with null so the line breaks", () => {
    const codes200: TimeseriesSeries = {
      label: "200", color: "#10b981",
      data: [{ sec: 100, v: 5 }, { sec: 102, v: 7 }],
    };
    const codes500: TimeseriesSeries = {
      label: "500", color: "#dc2626",
      data: [{ sec: 101, v: 1 }],
    };
    render(<TimeseriesChart title="t" series={[codes200, codes500]} />);
    const [, data] = uplotConstructor.mock.calls[0]!;
    expect(data).toEqual([
      [100, 101, 102],
      [5, null, 7],     // 200 — gap at 101
      [null, 1, null],  // 500 — only at 101
    ]);
  });
});

describe("TimeseriesChart — accessibility", () => {
  it("container has role=img with a descriptive aria-label naming the title + series summary", () => {
    render(<TimeseriesChart title="TPS over time" series={[tps]} />);
    const fig = screen.getByRole("img");
    const label = fig.getAttribute("aria-label") ?? "";
    expect(label).toContain("TPS over time");
    expect(label).toContain("TPS");
    // Summary should mention range + count
    expect(label).toContain("3 points");
  });

  it("renders a visually-hidden table with first / last / min / max / count for every series", () => {
    const codes500: TimeseriesSeries = {
      label: "500", color: "#dc2626",
      data: [{ sec: 100, v: 1 }, { sec: 101, v: 4 }, { sec: 102, v: 2 }],
    };
    render(<TimeseriesChart title="Status codes" series={[tps, codes500]} />);
    const table = screen.getByRole("table");
    expect(within(table).getByText(/text summary for assistive technology/i)).toBeInTheDocument();
    // Headers
    ["Series", "First", "Last", "Min", "Max", "Points"].forEach((h) => {
      expect(within(table).getByText(h)).toBeInTheDocument();
    });

    // TPS row: first=5, last=8, min=5, max=12, count=3 (default formatter is toFixed(2))
    // Cells are positional — use row.children[i] rather than text match
    // because some values repeat (first=5 and min=5 both render as "5.00").
    const tpsRow = within(table).getByText("TPS").closest("tr")!;
    expect(tpsRow.children[0]?.textContent).toBe("TPS");
    expect(tpsRow.children[1]?.textContent).toBe("5.00");   // first
    expect(tpsRow.children[2]?.textContent).toBe("8.00");   // last
    expect(tpsRow.children[3]?.textContent).toBe("5.00");   // min
    expect(tpsRow.children[4]?.textContent).toBe("12.00");  // max
    expect(tpsRow.children[5]?.textContent).toBe("3");      // count

    // 500 row: first=1, last=2, min=1, max=4
    const codesRow = within(table).getByText("500").closest("tr")!;
    expect(codesRow.children[1]?.textContent).toBe("1.00"); // first
    expect(codesRow.children[2]?.textContent).toBe("2.00"); // last
    expect(codesRow.children[3]?.textContent).toBe("1.00"); // min
    expect(codesRow.children[4]?.textContent).toBe("4.00"); // max
  });

  it("uses formatValue for the a11y table when provided", () => {
    render(
      <TimeseriesChart
        title="Latency"
        series={[tps]}
        formatValue={(v) => `${v.toFixed(0)} ms`}
      />,
    );
    const table = screen.getByRole("table");
    const tpsRow = within(table).getByText("TPS").closest("tr")!;
    expect(tpsRow.children[1]?.textContent).toBe("5 ms");   // first
    expect(tpsRow.children[4]?.textContent).toBe("12 ms");  // max
  });

  it("empty series renders an aria-label saying 'no data' (not garbage)", () => {
    render(<TimeseriesChart title="Errors" series={[]} />);
    const fig = screen.getByRole("img");
    expect(fig.getAttribute("aria-label")).toBe("Errors: no data");
  });
});

describe("TimeseriesChart — lifecycle", () => {
  it("destroys the uPlot instance on unmount — no leak across mount/unmount cycles", () => {
    const { unmount } = render(<TimeseriesChart title="t" series={[tps]} />);
    expect(uplotDestroy).not.toHaveBeenCalled();
    unmount();
    expect(uplotDestroy).toHaveBeenCalledTimes(1);
  });

  it("re-uses the existing instance + calls setData when only series VALUES change", () => {
    const { rerender } = render(<TimeseriesChart title="t" series={[tps]} />);
    expect(uplotConstructor).toHaveBeenCalledTimes(1);
    // setData is also called once on initial mount (the data-update effect
    // runs after the construct effect — harmless redundancy with the
    // data already passed to the constructor). Capture the baseline.
    const initialSetDataCalls = uplotSetData.mock.calls.length;

    const tps2: TimeseriesSeries = { ...tps, data: [{ sec: 100, v: 99 }] };
    rerender(<TimeseriesChart title="t" series={[tps2]} />);

    // Constructor NOT called again; setData called once MORE.
    expect(uplotConstructor).toHaveBeenCalledTimes(1);
    expect(uplotSetData.mock.calls.length).toBe(initialSetDataCalls + 1);
    expect(uplotSetData).toHaveBeenLastCalledWith([[100], [99]]);
  });

  it("changing height (layout toggle) destroys + recreates the chart cleanly", () => {
    // We tried setSize-only (no recreate) but uPlot's canvas got
    // wedged at the wrong dimensions on rapid Grid → Stacked → Grid
    // toggles. Destroy + recreate always lays out cleanly because the
    // constructor reads the live container.clientWidth + the new
    // height directly.
    const { rerender } = render(<TimeseriesChart title="t" series={[tps]} height={220} />);
    expect(uplotConstructor).toHaveBeenCalledTimes(1);

    rerender(<TimeseriesChart title="t" series={[tps]} height={320} />);
    expect(uplotConstructor).toHaveBeenCalledTimes(2);
    expect(uplotDestroy).toHaveBeenCalledTimes(1);
  });

  it("recreate on height change PRESERVES the operator's zoom (captures prior x-scale, applies after construct)", () => {
    const { rerender } = render(<TimeseriesChart title="t" series={[tps]} height={220} />);
    // Spoof a strict zoom on the first chart instance — pretend the
    // operator drag-zoomed into [100.5, 101.5] (a subset of the data
    // extent [100, 102]).
    const firstInstance = lastConstructedChart();
    firstInstance.scales.x = { min: 100.5, max: 101.5 };

    // Trigger the layout toggle (height change → recreate).
    uplotSetScale.mockClear();
    rerender(<TimeseriesChart title="t" series={[tps]} height={320} />);

    // The new chart should have setScale called with the captured
    // bounds, NOT the data-extent default — that's the zoom restore.
    expect(uplotSetScale).toHaveBeenCalledTimes(1);
    expect(uplotSetScale).toHaveBeenLastCalledWith("x", { min: 100.5, max: 101.5 });
  });

  it("recreate on height change does NOT call setScale when the prior was at full data extent (no zoom to restore)", () => {
    const { rerender } = render(<TimeseriesChart title="t" series={[tps]} height={220} />);
    // No zoom applied — scales.x stays unset (auto-fit).
    uplotSetScale.mockClear();
    rerender(<TimeseriesChart title="t" series={[tps]} height={320} />);
    // No restore — the new chart's auto-fit handles the full extent.
    expect(uplotSetScale).not.toHaveBeenCalled();
  });

  it("changing the formatter prop (new lambda identity) does NOT recreate — chart survives every poll", () => {
    // The parent typically passes formatValue={(v) => v.toFixed(1)} —
    // a new function reference every render. Including it in the
    // construct-effect deps used to destroy the chart on every poll;
    // the fix removes it from the deps.
    const { rerender } = render(
      <TimeseriesChart title="t" series={[tps]} formatValue={(v) => v.toFixed(2)} />,
    );
    expect(uplotConstructor).toHaveBeenCalledTimes(1);

    rerender(<TimeseriesChart title="t" series={[tps]} formatValue={(v) => v.toFixed(3)} />);
    rerender(<TimeseriesChart title="t" series={[tps]} formatValue={(v) => v.toFixed(4)} />);

    expect(uplotConstructor).toHaveBeenCalledTimes(1); // still ONE construction
    expect(uplotDestroy).not.toHaveBeenCalled();
  });

  it("recreates the chart when the series STRUCTURE (label) changes", () => {
    const { rerender } = render(<TimeseriesChart title="t" series={[tps]} />);
    expect(uplotConstructor).toHaveBeenCalledTimes(1);

    const renamed: TimeseriesSeries = { ...tps, label: "Throughput" };
    rerender(<TimeseriesChart title="t" series={[renamed]} />);

    expect(uplotDestroy).toHaveBeenCalledTimes(1);   // old chart torn down
    expect(uplotConstructor).toHaveBeenCalledTimes(2); // new chart built
  });
});

describe("sync zoom across charts (uPlot built-in cursor.sync)", () => {
  it("when syncKey is omitted, opts.cursor.sync is NOT set (no group join)", () => {
    render(<TimeseriesChart title="t" series={[tps]} />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    expect((opts as { cursor: { sync?: unknown } }).cursor.sync).toBeUndefined();
  });

  it("when syncKey is provided, opts.cursor.sync carries the key + x-scale binding", () => {
    render(<TimeseriesChart title="t" series={[tps]} syncKey="run-42" />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    const sync = (opts as { cursor: { sync?: { key: string; scales: unknown } } }).cursor.sync;
    expect(sync).toBeDefined();
    expect(sync!.key).toBe("run-42");
    // Scales bound x-only — y values differ across charts in the group
    // (TPS in req/s, latency in ms, etc.) so y-sync would be nonsense.
    expect(sync!.scales).toEqual(["x", null]);
  });

  it("does NOT sync series index across the group (setSeries:false) — isolating a status code can't blank sibling charts", () => {
    // Regression: with setSeries:true, toggling/focusing a status-code
    // series (index 2 = "404") propagated that index to the TPS/RT/Error
    // charts, which have only index 1 — blanking their lines. The sync
    // must couple scales only, never series.
    render(<TimeseriesChart title="t" series={[tps]} syncKey="run-42" />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    const sync = (opts as { cursor: { sync?: { setSeries?: boolean } } }).cursor.sync;
    expect(sync!.setSeries).toBe(false);
  });
});

describe("legend and tooltip — the Grafana shape", () => {
  it("the legend is static (names + markers, no live values) and a tooltip plugin is registered", () => {
    render(<TimeseriesChart title="t" series={[tps]} />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    const o = opts as { legend: { show: boolean; live: boolean; markers: { fill: (u: unknown, i: number) => string } }; plugins: Array<{ hooks: Record<string, unknown> }> };
    expect(o.legend).toMatchObject({ show: true, live: false });
    expect(o.legend).not.toHaveProperty("isolate");   // clicks are legendClickPlugin's, not uPlot's
    expect(o.legend.markers.fill({ series: [{}, { stroke: "#2563eb" }] }, 1)).toBe("#2563eb");
    // Once built, uPlot hands the stroke back as a function — the marker must still be the colour.
    expect(o.legend.markers.fill({ series: [{}, { stroke: () => "#db2777" }] }, 1)).toBe("#db2777");
    expect(o.plugins).toHaveLength(2);
    expect(Object.keys(o.plugins[0]!.hooks)).toEqual(expect.arrayContaining(["init", "setCursor", "destroy"]));
    expect(Object.keys(o.plugins[1]!.hooks)).toEqual(["init"]);
  });

  it("the tooltip shows the hovered bucket's time to the second and every visible series' value, only while the mouse is over the plot", () => {
    render(<TimeseriesChart title="t" series={[tps, { label: "P95", color: "#7c3aed", data: [{ sec: 1_700_000_000, v: 300 }, { sec: 1_700_000_015, v: 310 }] }]}
      formatValue={(v) => v.toFixed(1)} />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    const plugin = (opts as { plugins: Array<{ hooks: { init: (u: unknown) => void; setCursor: (u: unknown) => void } }> }).plugins[0]!;
    const over = document.createElement("div");
    Object.defineProperty(over, "clientWidth", { value: 600 });
    Object.defineProperty(over, "clientHeight", { value: 200 });
    const u = {
      over,
      cursor: { idx: 1, left: 100, top: 50 },
      data: [[1_700_000_000, 1_700_000_015], [5, 6], [300, 310]],
      series: [{}, { label: "TPS", stroke: "#2563eb", show: true }, { label: "P95", stroke: "#7c3aed", show: false }],
    };
    plugin.hooks.init(u);
    const box = over.querySelector(".uTooltip") as HTMLDivElement;
    expect(box).not.toBeNull();
    // Not hovering: a synced sibling's cursor moves draw no tooltip here.
    plugin.hooks.setCursor(u);
    expect(box.style.display).toBe("none");

    over.dispatchEvent(new Event("mouseenter"));
    plugin.hooks.setCursor(u);
    expect(box.style.display).toBe("block");
    const expectedTime = new Date(1_700_000_015 * 1000);
    const hh = String(expectedTime.getHours()).padStart(2, "0");
    const mm = String(expectedTime.getMinutes()).padStart(2, "0");
    const ss = String(expectedTime.getSeconds()).padStart(2, "0");
    expect(box.querySelector(".uTooltip__time")!.textContent).toBe(`${hh}:${mm}:${ss}`);
    const rows = Array.from(box.querySelectorAll(".uTooltip__row")).map((r) => r.textContent);
    expect(rows).toEqual(["TPS6.0"]);   // P95 is hidden (legend toggled off) so it is not listed

    over.dispatchEvent(new Event("mouseleave"));
    expect(box.style.display).toBe("none");
  });
});

describe("legend clicks — isolate, then add, double-click restores", () => {
  function fakeChart(n: number) {
    const root = document.createElement("div");
    const legend = document.createElement("table");
    legend.className = "u-legend";
    for (let i = 0; i < n; i++) {
      const row = document.createElement("tr");
      row.className = "u-series";
      const th = document.createElement("th");
      th.textContent = `s${i + 1}`;
      row.appendChild(th);
      legend.appendChild(row);
    }
    root.appendChild(legend);
    const series = [{}, ...Array.from({ length: n }, () => ({ show: true }))];
    const u = {
      root, series,
      setSeries: vi.fn((i: number, opts: { show: boolean }) => { (series[i] as { show: boolean }).show = opts.show; }),
    };
    const init = legendClickPlugin().hooks.init as (self: uPlot, opts: uPlot.Options, data: uPlot.AlignedData) => void;
    init(u as unknown as uPlot, {} as uPlot.Options, [[]] as unknown as uPlot.AlignedData);
    const click = (i: number) => legend.querySelectorAll("th")[i - 1]!.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    const dblclick = (i: number) => legend.querySelectorAll("th")[i - 1]!.dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));
    const shown = () => series.slice(1).map((s) => (s as { show: boolean }).show);
    return { click, dblclick, shown, legend };
  }

  it("first click isolates, the next clicks add and remove, hiding the last one shows all", () => {
    const c = fakeChart(4);
    c.click(2);
    expect(c.shown()).toEqual([false, true, false, false]);
    c.click(4);
    expect(c.shown()).toEqual([false, true, false, true]);
    c.click(2);
    expect(c.shown()).toEqual([false, false, false, true]);
    c.click(4);                                    // the last visible one → everything back
    expect(c.shown()).toEqual([true, true, true, true]);
  });

  it("a double-click shows every series again, and uPlot's own toggle never runs", () => {
    const c = fakeChart(3);
    const uplotHandler = vi.fn();
    c.legend.querySelector("th")!.addEventListener("click", uplotHandler);   // stands in for uPlot's listener on the row
    c.click(1);
    expect(c.shown()).toEqual([true, false, false]);
    expect(uplotHandler).not.toHaveBeenCalled();
    c.dblclick(1);
    expect(c.shown()).toEqual([true, true, true]);
  });
});

describe("showTitle", () => {
  it("omits uPlot's title when showTitle is false (the modal header carries it) and keeps it by default", () => {
    render(<TimeseriesChart title="Error codes" series={[tps]} showTitle={false} />);
    expect((uplotConstructor.mock.calls[0]![0] as { title?: string }).title).toBeUndefined();
    render(<TimeseriesChart title="Error codes" series={[tps]} />);
    expect((uplotConstructor.mock.calls[1]![0] as { title?: string }).title).toBe("Error codes");
  });
});

describe("x-axis tick density (fixed even-division pass)", () => {
  // 2026-05-31 — the x-axis moved from an increment ladder (incrs/space,
  // which floored ticks to 60s→5m→… and let uPlot pick a rung) to a fixed
  // even division: a custom `splits` fn that always returns the same number
  // of evenly-spaced ticks across the visible range, so the label *count*
  // stays steady at any duration/zoom (the ladder used to snap the count as
  // a run crossed a boundary). The axis therefore no longer sets incrs/space.
  it("uses a `splits` fn that yields a fixed count of evenly-spaced ticks (steady label count at any zoom)", () => {
    render(<TimeseriesChart title="t" series={[tps]} />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    const xAxis = (opts as {
      axes: Array<{ splits?: (u: unknown, axisIdx: number, min: number, max: number) => number[] }>;
    }).axes[0]!;
    expect(typeof xAxis.splits).toBe("function");

    // A 10-minute window → 6 ticks (5 intervals), endpoints included.
    const min = 1_000_000;
    const max = min + 600;
    const ticks = xAxis.splits!(null, 0, min, max);
    expect(ticks).toHaveLength(6);
    expect(ticks[0]).toBe(min);
    expect(ticks[ticks.length - 1]).toBe(max);
    // Evenly spaced — every gap equals (max-min)/5.
    const step = (max - min) / 5;
    for (let i = 1; i < ticks.length; i++) {
      expect(ticks[i]! - ticks[i - 1]!).toBeCloseTo(step);
    }
  });

  it("renders x-axis tick labels as HH:MM with no seconds component", () => {
    render(<TimeseriesChart title="t" series={[tps]} />);
    const [opts] = uplotConstructor.mock.calls[0]!;
    const xAxis = (opts as {
      axes: Array<{ values: (u: unknown, splits: number[]) => string[] }>;
    }).axes[0]!;
    // 2026-05-30 13:45:30 local — a non-round-minute instant. Old code
    // would have rendered "13:45:30"; the formatter now drops seconds.
    const sec = Math.floor(new Date(2026, 4, 30, 13, 45, 30).getTime() / 1000);
    const [label] = xAxis.values(null, [sec]);
    expect(label).toMatch(/^\d{2}:\d{2}$/);
    expect(label).not.toContain(":30");
  });
});

describe("resetVersion — imperative zoom reset", () => {
  it("initial value 0 does NOT trigger setScale (the mount already auto-fits)", () => {
    render(<TimeseriesChart title="t" series={[tps]} resetVersion={0} />);
    // Only the constructor ran; no scale-reset call.
    expect(uplotSetScale).not.toHaveBeenCalled();
  });

  it("bumping resetVersion calls setScale('x', {min, max}) using the data's actual extent", () => {
    // Earlier impl tried {min:null, max:null} which uPlot interprets as
    // "no range" and renders blank. The reset must explicitly pass the
    // first/last x values from the data so the chart redraws against
    // the full window.
    const { rerender } = render(<TimeseriesChart title="t" series={[tps]} resetVersion={0} />);
    expect(uplotSetScale).not.toHaveBeenCalled();

    rerender(<TimeseriesChart title="t" series={[tps]} resetVersion={1} />);
    expect(uplotSetScale).toHaveBeenCalledTimes(1);
    // tps data: sec=100, 101, 102 → reset to {min:100, max:102}
    expect(uplotSetScale).toHaveBeenLastCalledWith("x", { min: 100, max: 102 });

    rerender(<TimeseriesChart title="t" series={[tps]} resetVersion={2} />);
    expect(uplotSetScale).toHaveBeenCalledTimes(2);
  });

  it("bumping resetVersion when there's no data is a safe no-op (no setScale call)", () => {
    const { rerender } = render(<TimeseriesChart title="t" series={[]} resetVersion={0} />);
    rerender(<TimeseriesChart title="t" series={[]} resetVersion={1} />);
    // empty series → chart.data[0] is [] → effect short-circuits
    expect(uplotSetScale).not.toHaveBeenCalled();
  });
});

describe("axis formatters", () => {
  describe("formatCompactNumber", () => {
    it.each([
      [0,        "0"],
      [42,       "42"],
      [999,      "999"],
      [1_000,    "1.0k"],
      [1_234,    "1.2k"],
      [9_999,    "10.0k"],
      [10_000,   "10k"],
      [123_456,  "123k"],
      [1_000_000,    "1.0M"],
      [10_500_000,   "11M"],
    ])("%s → %s", (input, expected) => {
      expect(formatCompactNumber(input)).toBe(expected);
    });
  });

  describe("formatCompactDuration", () => {
    it.each([
      [0.5,    "0.5ms"],
      [1.2,    "1.2ms"],
      [10,     "10ms"],
      [999,    "999ms"],
      [1_000,  "1.0s"],
      [1_500,  "1.5s"],
      [12_345, "12.3s"],
    ])("%s → %s", (input, expected) => {
      expect(formatCompactDuration(input)).toBe(expected);
    });
  });

  describe("formatPercent", () => {
    it.each([
      [0,    "0%"],
      [0.05, "0.1%"],   // tiny value rounds to 1 decimal
      [0.5,  "0.5%"],
      [1,    "1%"],
      [12.5, "13%"],    // ≥1 drops decimal
      [100,  "100%"],
    ])("%s → %s", (input, expected) => {
      expect(formatPercent(input)).toBe(expected);
    });
  });
});
