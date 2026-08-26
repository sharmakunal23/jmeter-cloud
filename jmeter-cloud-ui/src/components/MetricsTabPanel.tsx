import { useEffect, useMemo, useState } from "react";

import type { MetricsTimeseries, MetricsTimeseriesSeries, MetricsWindow, RunState, TimeseriesPoint } from "../api/runs";
import { isTerminalRunState, useMetricsTimeseries } from "../hooks/useMetricsTimeseries";
import { useAiStatus } from "../hooks/useAiStatus";
import { useRunInsights } from "../hooks/useRunInsights";
import { RunInsightsPanel } from "./RunInsightsPanel";
import {
  TimeseriesChart,
  type TimeseriesSeries,
  STACKED_CHART_HEIGHT,
  formatCompactNumber,
  formatCompactDuration,
  formatPercent,
} from "./charts/TimeseriesChart";

/**
 * Historical metrics for the run-detail Metrics tab: four native uPlot charts
 * driven by `GET /api/v1/runs/{runId}/timeseries`, in place of a Grafana
 * iframe.
 *
 * **The reason it is native is historical correctness.** Grafana embeds default
 * to `now-3h`, so a run that finished yesterday renders empty; this query is
 * keyed by runId and any run renders. Keeping the data in this app's memory as
 * plain arrays is also what lets the compare page and the AI panels reuse it —
 * a cross-origin iframe is opaque.
 *
 * Full Grafana drill-down is still one click away via the header deep-link,
 * which passes explicit `from` / `to` bounds derived from the run's own window
 * so old runs render correctly there too.
 */
export interface MetricsTabPanelProps {
  runId: string;
  runState: RunState;
}

const HEIGHT_GRID    = 220;                  // 2-column compact view
const HEIGHT_STACKED = STACKED_CHART_HEIGHT;  // 1-column detailed view — matches the two-run comparison height

const TPS_COLOR      = "#2563eb"; // brand blue
const AVG_RT_COLOR   = "#0ea5e9"; // info blue
const ERROR_COLOR    = "#dc2626"; // err red

const LAYOUT_STORAGE_KEY = "jmeterCloud.metricsLayout";
const REGION_STORAGE_KEY = "jmeterCloud.metricsSplitByRegion";
const WINDOW_STORAGE_KEY = "jmeterCloud.metricsWindow";
type Layout = "grid" | "stacked";

/**
 * Time-window options for the metrics charts. The default depends on the
 * run's state — see {@link initialTimeWindow}: terminal runs open on
 * "Whole test" (served from the orchestrator's Redis cache), live runs
 * open on {@link LIVE_DEFAULT_WINDOW}.
 */
const WINDOW_OPTIONS: ReadonlyArray<{ value: MetricsWindow; label: string }> = [
  { value: "all", label: "Whole test" },
  { value: "5m",  label: "Last 5 min" },
  { value: "10m", label: "Last 10 min" },
  { value: "30m", label: "Last 30 min" },
  { value: "1h",  label: "Last 1 hour" },
  { value: "2h",  label: "Last 2 hours" },
  { value: "4h",  label: "Last 4 hours" },
];

/** Stored window preference, or null when nothing valid was ever picked. */
function readStoredWindow(): MetricsWindow | null {
  try {
    const v = window.localStorage.getItem(WINDOW_STORAGE_KEY);
    return WINDOW_OPTIONS.some((o) => o.value === v) ? (v as MetricsWindow) : null;
  } catch { return null; }
}

/**
 * Default window for a run that is still producing data. "Whole test" on a
 * long LIVE run re-aggregates every raw row of the run on each 5 s poll —
 * measured (2026-07-24, 34M-row bench): that shape can't keep up with ~10
 * concurrent watchers, while bounded windows have >10× headroom. Terminal
 * runs are exempt: their whole-test snapshot is computed once and served
 * from the Redis cache.
 */
const LIVE_DEFAULT_WINDOW: MetricsWindow = "30m";

/**
 * Initial window for the picker: the operator's stored preference, else
 * "Whole test" — except that a LIVE run never *starts* on "Whole test"
 * (bounded to {@link LIVE_DEFAULT_WINDOW}, see above). Explicitly picking
 * "Whole test" mid-run still works and persists; only the initial state
 * is bounded, so the expensive shape is a deliberate act, not a default.
 */
function initialTimeWindow(isTerminal: boolean): MetricsWindow {
  const preferred = readStoredWindow() ?? "all";
  return !isTerminal && preferred === "all" ? LIVE_DEFAULT_WINDOW : preferred;
}

function readStoredLayout(): Layout {
  try {
    const v = window.localStorage.getItem(LAYOUT_STORAGE_KEY);
    return v === "stacked" ? "stacked" : "grid";
  } catch { return "grid"; }
}

function readStoredSplitByRegion(): boolean {
  try {
    return window.localStorage.getItem(REGION_STORAGE_KEY) === "true";
  } catch { return false; }
}

export function MetricsTabPanel({ runId, runState }: MetricsTabPanelProps) {
  // "Split by region" — off by default (the aggregate view is the
  // default the operator asked to keep). When on, the hook fetches the
  // per-region breakdown (one extra GROUP BY server-side) and the charts
  // render one line per region.
  const [splitByRegion, setSplitByRegion] = useState<boolean>(readStoredSplitByRegion);
  useEffect(() => {
    try { window.localStorage.setItem(REGION_STORAGE_KEY, String(splitByRegion)); }
    catch { /* private mode */ }
  }, [splitByRegion]);

  // Time window — stored preference, bounded to "Last 30 min" while the
  // run is live (see initialTimeWindow). Persisted only on an explicit
  // pick, so the live-run bound never overwrites the stored preference
  // and terminal runs keep defaulting to "Whole test".
  const [timeWindow, setTimeWindow] = useState<MetricsWindow>(
    () => initialTimeWindow(isTerminalRunState(runState)),
  );
  const pickTimeWindow = (w: MetricsWindow) => {
    setTimeWindow(w);
    try { window.localStorage.setItem(WINDOW_STORAGE_KEY, w); }
    catch { /* private mode */ }
  };

  const { status, data, lastUpdated, isPaused, pauseReason } =
    useMetricsTimeseries(runId, runState, splitByRegion, timeWindow);

  const [layout, setLayout] = useState<Layout>(readStoredLayout);
  useEffect(() => {
    try { window.localStorage.setItem(LAYOUT_STORAGE_KEY, layout); }
    catch { /* private mode */ }
  }, [layout]);
  const chartHeight = layout === "stacked" ? HEIGHT_STACKED : HEIGHT_GRID;

  // Region mode is active only when the toggle is on AND the payload
  // actually carries a region breakdown (it won't during the first poll
  // after flipping the toggle, or for a run with no metrics yet) — so we
  // fall back to the aggregate charts rather than flashing empty.
  const regionKeys = useMemo(
    () => (data?.regions ? Object.keys(data.regions).sort() : []),
    [data],
  );
  const regionMode = splitByRegion && regionKeys.length > 0;

  // Bumping `resetVersion` tells every chart to call setScale("x",
  // {min:null, max:null}) — auto-fit back to the full data range.
  // Each TimeseriesChart instance subscribes to it via its
  // `resetVersion` prop. Independent of layout / data updates.
  const [resetVersion, setResetVersion] = useState(0);
  // Sync key — all four charts share this so uPlot's built-in cursor
  // sync propagates hover crosshairs AND drag-zoom across them.
  // Keyed by runId so navigating to a different run starts a fresh
  // sync group (no leftover sub from the previous chart instances).
  const syncKey = `metrics:${runId}`;

  // Project the response shape into the chart's input shape. Memoise on
  // `data` + `regionMode` identity so child charts get stable references
  // between polls when nothing changed.
  //
  // Aggregate mode: one series per chart (the all-regions total).
  // Region mode: one color-coded series per region, the region name as
  // the legend label so the operator reads "us-east-1 vs us-west-2".
  const tpsSeries = useMemo<TimeseriesSeries[]>(
    () => {
      if (!data) return [];
      if (regionMode) return regionSeries(data.regions!, regionKeys, "tps");
      return [{ label: "TPS", color: TPS_COLOR, data: data.series.tps }];
    },
    [data, regionMode, regionKeys],
  );
  const avgRtSeries = useMemo<TimeseriesSeries[]>(
    () => {
      if (!data) return [];
      if (regionMode) return regionSeries(data.regions!, regionKeys, "avgRtMs");
      return [{ label: "Avg RT (ms)", color: AVG_RT_COLOR, data: data.series.avgRtMs }];
    },
    [data, regionMode, regionKeys],
  );
  const errorPctSeries = useMemo<TimeseriesSeries[]>(
    () => {
      if (!data) return [];
      if (regionMode) return regionSeries(data.regions!, regionKeys, "errorPct");
      return [{ label: "Error %", color: ERROR_COLOR, data: data.series.errorPct }];
    },
    [data, regionMode, regionKeys],
  );
  // Aggregate mode → one Status-codes chart (one series per code).
  // Region mode → small multiples: one Status-codes chart per region.
  const statusCodeSeries = useMemo<TimeseriesSeries[]>(
    () => data && !regionMode ? buildStatusCodeSeries(data.series.statusCodes) : [],
    [data, regionMode],
  );
  const statusByRegion = useMemo<Array<{ region: string; series: TimeseriesSeries[] }>>(
    () => data && regionMode
      ? regionKeys.map((r) => ({ region: r, series: buildStatusCodeSeries(data.regions![r]!.statusCodes) }))
      : [],
    [data, regionMode, regionKeys],
  );

  const hasAnyData = data !== null && data.series.tps.length > 0;
  const isInitialLoading = status.kind === "loading" && data === null;
  const errorMessage = status.kind === "error" ? status.message : null;

  // AI insights — a right-hand column toggled from the header (next to "Split
  // by region"), so the summary sits beside the charts with no scroll. Gated
  // on ≥ 30 s of data (earlier, the summary is just "the run started");
  // bucketSize accounts for downsampled long runs.
  const { enabled: aiEnabled } = useAiStatus();
  const [showInsights, setShowInsights] = useState(false);
  const insights = useRunInsights(runId);
  const secondsOfData = data ? data.series.tps.length * Math.max(1, data.bucketSize) : 0;
  const insightsReady = secondsOfData >= 30;
  const insightsOpen = aiEnabled && showInsights;

  // Auto-generate when the column opens (one click → insights). Fires once:
  // generate() flips status to "loading", so the guard closes. If the run
  // wasn't ready at open time, it fires as soon as the data crosses 30 s.
  // Reopening a run that already has data shows the cached result — no re-bill.
  const insightsGenerate = insights.generate;
  const insightsKind = insights.status.kind;
  const hasInsights = insights.data !== null;
  useEffect(() => {
    if (insightsOpen && insightsReady && insightsKind === "idle" && !hasInsights) {
      insightsGenerate();
    }
  }, [insightsOpen, insightsReady, insightsKind, hasInsights, insightsGenerate]);

  return (
    <div className="metricsPanel">
      <header className="metricsPanel__header">
        <div className="metricsPanel__headerInfo">
          <p className="metricsPanel__status">
            {isInitialLoading
              ? "loading…"
              : errorMessage
                ? <span className="text--error">error: {errorMessage}</span>
                : <>
                    {hasAnyData
                      ? `${data!.series.tps.length} second${data!.series.tps.length === 1 ? "" : "s"} of data`
                      : "no metrics yet"}
                    {data && data.bucketSize > 1 && <> · bucketed at {data.bucketSize}-s windows</>}
                    {lastUpdated && <> · last fetch {lastUpdated.toLocaleTimeString()}</>}
                    {isPaused && <> · <span className="badge badge--warn" title={pauseTooltip(pauseReason)}>{pauseLabel(pauseReason)}</span></>}
                  </>}
          </p>
        </div>
        <div className="metricsPanel__headerActions">
          {aiEnabled && (
            <button
              type="button"
              className={`btn btn--ghost ${insightsOpen ? "btn--active" : ""}`}
              aria-pressed={insightsOpen}
              onClick={() => setShowInsights((v) => !v)}
              title="Show Claude's read of this run in a panel beside the charts"
            >
              ✨ AI insights
            </button>
          )}
          <button
            type="button"
            className={`btn btn--ghost metricsPanel__regionToggle ${splitByRegion ? "metricsPanel__regionToggle--active" : ""}`}
            aria-pressed={splitByRegion}
            onClick={() => setSplitByRegion((v) => !v)}
            title="Split each metric into one line per region (compare us-east vs us-west). Default off."
          >
            ⊞ Split by region
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => setResetVersion((v) => v + 1)}
            title="Reset zoom on all charts back to the full run window"
            disabled={!hasAnyData}
          >
            ⟲ Reset zoom
          </button>
          <label className="metricsPanel__windowPicker">
            <span className="visuallyHidden">Time window</span>
            <select
              className="formSelect"
              value={timeWindow}
              onChange={(e) => pickTimeWindow(e.target.value as MetricsWindow)}
              title="Show only the most recent slice of the run — faster to pull on long (e.g. 10-hour) tests"
            >
              {WINDOW_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </label>
          <div
            className="metricsPanel__layoutToggle"
            role="tablist"
            aria-label="Chart layout"
          >
            <button
              type="button"
              role="tab"
              aria-selected={layout === "grid"}
              className={`btn btn--ghost ${layout === "grid" ? "btn--active" : ""}`}
              onClick={() => setLayout("grid")}
              title="2-column compact grid"
            >
              ▦ Grid
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={layout === "stacked"}
              className={`btn btn--ghost ${layout === "stacked" ? "btn--active" : ""}`}
              onClick={() => setLayout("stacked")}
              title="Single-column stacked — larger charts, more detail"
            >
              ▤ Stacked
            </button>
          </div>
        </div>
      </header>

      <div className="metricsPanel__body">
        <div className="metricsPanel__charts">
      {hasAnyData ? (
        <div className={`metricsPanel__chartGrid metricsPanel__chartGrid--${layout}`}>
          <TimeseriesChart
            title={regionMode ? "TPS by region" : "Total TPS"}
            series={tpsSeries}
            yLabel="requests / s"
            height={chartHeight}
            formatValue={(v) => v.toFixed(1)}
            formatAxisValue={formatCompactNumber}
            syncKey={syncKey}
            resetVersion={resetVersion}
          />
          <TimeseriesChart
            title={regionMode ? "Response Time by region" : "Response Time"}
            series={avgRtSeries}
            yLabel="ms"
            height={chartHeight}
            formatValue={(v) => v.toFixed(1)}
            formatAxisValue={formatCompactDuration}
            syncKey={syncKey}
            resetVersion={resetVersion}
          />
          <TimeseriesChart
            title={regionMode ? "Error % by region" : "Error %"}
            series={errorPctSeries}
            yLabel="%"
            height={chartHeight}
            formatValue={(v) => v.toFixed(2)}
            formatAxisValue={formatPercent}
            syncKey={syncKey}
            resetVersion={resetVersion}
          />
          {regionMode ? (
            // Status codes is a 2-D metric (code × region); to avoid an
            // overcrowded single chart we render small multiples — one
            // Status-codes chart per region, each with that region's codes.
            statusByRegion.map(({ region, series }) => (
              <TimeseriesChart
                key={region}
                title={`Status codes — ${region}`}
                series={series}
                yLabel="count / s"
                height={chartHeight}
                formatValue={(v) => v.toFixed(0)}
                formatAxisValue={formatCompactNumber}
                syncKey={syncKey}
                resetVersion={resetVersion}
              />
            ))
          ) : (
            <TimeseriesChart
              title="Status codes"
              series={statusCodeSeries}
              yLabel="count / s"
              height={chartHeight}
              formatValue={(v) => v.toFixed(0)}
              formatAxisValue={formatCompactNumber}
              syncKey={syncKey}
              resetVersion={resetVersion}
            />
          )}
        </div>
      ) : !isInitialLoading && !errorMessage ? (
        <p className="runDetail__embedHint metricsPanel__emptyHint">
          No metrics yet — the consumer writes within ~1 s of the first
          JMeter sample. If the run is in <code className="mono">PREPARING</code>{" "}
          this is expected; if it's <code className="mono">RUNNING</code>{" "}
          and this hint persists past the first few seconds, check the
          metrics-consumer logs.
        </p>
      ) : null}
        </div>
        {insightsOpen && (
          <RunInsightsPanel
            status={insights.status}
            data={insights.data}
            ready={insightsReady}
            onRegenerate={insights.regenerate}
            onClose={() => setShowInsights(false)}
          />
        )}
      </div>
    </div>
  );
}

// ── helpers ────────────────────────────────────────────────────────────

/**
 * Build the per-status-code series with stable colors. Bucketed by
 * 2xx/3xx/4xx/5xx because the JMeter response code keys are usually
 * canonical HTTP statuses but JMeter also emits non-HTTP markers
 * ({@code "Non HTTP response code: …"}); those land in the "other"
 * bucket with a neutral grey.
 */
function buildStatusCodeSeries(
  codes: MetricsTimeseries["series"]["statusCodes"],
): TimeseriesSeries[] {
  const entries = Object.entries(codes);
  // Stable sort: numeric codes ascending, non-numeric last.
  entries.sort(([a], [b]) => compareCodes(a, b));
  return entries.map(([code, points]) => ({
    label: code,
    color: colorForCode(code),
    data: points as TimeseriesPoint[],
  }));
}

/**
 * Stable region → stroke color. The four USA regions get fixed hues so
 * a region keeps the same color across charts and across runs (us-east-1
 * is always blue, us-west-2 always amber); anything else falls back to a
 * palette cycled by the region's sorted position so two unknown regions
 * never collide.
 */
const REGION_COLORS: Record<string, string> = {
  "us-east-1": "#2563eb", // blue
  "us-east-2": "#7c3aed", // violet
  "us-west-1": "#0d9488", // teal
  "us-west-2": "#f59e0b", // amber
};
const REGION_FALLBACK_PALETTE = ["#2563eb", "#f59e0b", "#0d9488", "#7c3aed", "#db2777", "#65a30d"];

function colorForRegion(region: string, index: number): string {
  return REGION_COLORS[region] ?? REGION_FALLBACK_PALETTE[index % REGION_FALLBACK_PALETTE.length]!;
}

/**
 * Build one chart series per region for a single numeric metric — the
 * region name is the legend label, the color is stable per region.
 */
function regionSeries(
  regions: Record<string, MetricsTimeseriesSeries>,
  regionKeys: string[],
  metric: "tps" | "avgRtMs" | "errorPct",
): TimeseriesSeries[] {
  return regionKeys.map((r, i) => ({
    label: r,
    color: colorForRegion(r, i),
    data: regions[r]![metric],
  }));
}

function compareCodes(a: string, b: string): number {
  const an = parseInt(a, 10), bn = parseInt(b, 10);
  const aIsNum = !isNaN(an), bIsNum = !isNaN(bn);
  if (aIsNum && bIsNum) return an - bn;
  if (aIsNum) return -1;
  if (bIsNum) return 1;
  return a.localeCompare(b);
}

function colorForCode(code: string): string {
  const n = parseInt(code, 10);
  if (isNaN(n))           return "#94a3b8"; // slate-400 — non-HTTP marker
  if (n >= 200 && n < 300) return "#10b981"; // ok green
  if (n >= 300 && n < 400) return "#0ea5e9"; // info blue
  if (n >= 400 && n < 500) return "#f59e0b"; // warn amber
  if (n >= 500)            return "#dc2626"; // err red
  return "#94a3b8";
}

function pauseLabel(reason: PauseReasonOrNull): string {
  switch (reason) {
    case "manual":         return "paused";
    case "delayNull":      return "paused — terminal";
    case "documentHidden": return "paused — tab hidden";
    case "offscreen":      return "paused — off-screen";
    case null:             return "paused";
  }
}

function pauseTooltip(reason: PauseReasonOrNull): string {
  switch (reason) {
    case "manual":         return "Manual pause — click Refresh to fetch on demand.";
    case "delayNull":      return "Run is terminal (COMPLETED / FAILED / ABORTED) — nothing new to fetch.";
    case "documentHidden": return "Browser tab is in the background. Polling resumes when the tab returns to the foreground.";
    case "offscreen":      return "Panel is scrolled off-screen. Polling resumes on scroll back.";
    case null:             return "Polling is paused.";
  }
}

// Local alias to keep the imports tight — we only use the type for the
// label/tooltip switch above, not as a runtime value.
type PauseReasonOrNull = ReturnType<typeof useMetricsTimeseries>["pauseReason"];
