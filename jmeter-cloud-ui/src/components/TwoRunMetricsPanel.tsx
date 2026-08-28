import { useCallback, useEffect, useMemo, useState } from "react";

import type { MetricsTimeseries, RunState, TimeseriesPoint } from "../api/runs";
import { useTwoRunTimeseries } from "../hooks/useTwoRunTimeseries";
import { useAiStatus } from "../hooks/useAiStatus";
import { useCompareInsights } from "../hooks/useCompareInsights";
import { CompareInsightsPanel } from "./CompareInsightsPanel";
import {
  TimeseriesChart,
  type TimeseriesSeries,
  STACKED_CHART_HEIGHT,
  formatCompactNumber,
  formatCompactDuration,
  formatPercent,
} from "./charts/TimeseriesChart";

/**
 * Two-run comparison panel for {@code RunsComparePage}. Renders
 * three native uPlot charts (Total TPS, Response Time, Error %)
 * with both runs overlaid as separate series. Replaces the side-by-side
 * side-by-side iframes — operators can finally read the delta directly
 * instead of eyeballing two embeds.
 *
 * <p><b>Time-axis alignment.</b> Default = elapsed seconds from each
 * run's first metric ({@code fromSecond}). Two runs that started
 * minutes apart (or yesterday vs today) share a meaningful x-axis:
 * "at +30s into each run, how were they doing?" The "Absolute UTC"
 * toggle exposes the raw wall-clock view for side-by-side production-
 * incident analysis where both runs ran simultaneously.
 *
 * <p><b>Status-codes chart deliberately omitted.</b> Overlaying two
 * runs' code mixes adds visual clutter for marginal extra signal —
 * Error % already conveys the failure-rate delta, which is what most
 * comparisons reduce to (decision logged 2026-05-10).
 */
export interface TwoRunMetricsPanelProps {
  runIdA: string;
  runIdB: string;
  /** Optional terminal-state hint per run; lets the polling loop stop early. */
  runStateA?: RunState | null;
  runStateB?: RunState | null;
}

const RUN_A_COLOR = "#2563eb"; // brand blue — matches single-run TPS color
const RUN_B_COLOR = "#f59e0b"; // amber — high-contrast against blue
const CHART_HEIGHT = STACKED_CHART_HEIGHT;  // shared with the run-detail "Stacked" layout for consistency

const AXIS_STORAGE_KEY = "jmeterCloud.compareAxisMode";
type AxisMode = "elapsed" | "absolute";

function readStoredAxisMode(): AxisMode {
  try {
    const v = window.localStorage.getItem(AXIS_STORAGE_KEY);
    return v === "absolute" ? "absolute" : "elapsed";
  } catch { return "elapsed"; }
}

export function TwoRunMetricsPanel({
  runIdA, runIdB,
  runStateA = null, runStateB = null,
}: TwoRunMetricsPanelProps) {
  const { status, data, lastUpdated, isPaused, pauseReason } =
    useTwoRunTimeseries(runIdA, runIdB, runStateA, runStateB);

  const [axisMode, setAxisMode] = useState<AxisMode>(readStoredAxisMode);
  useEffect(() => {
    try { window.localStorage.setItem(AXIS_STORAGE_KEY, axisMode); }
    catch { /* private mode — no-op */ }
  }, [axisMode]);

  // Sync zoom across all three charts so dragging on TPS also zooms
  // Avg RT + Error %. Keyed by both runIds + the axis mode so a mode
  // switch starts a fresh sync group (the underlying x-coordinates
  // change, so leftover sub state from the prior mode would be wrong).
  const syncKey = `compare:${runIdA}:${runIdB}:${axisMode}`;
  const [resetVersion, setResetVersion] = useState(0);

  // Anchor each run's elapsed timeline at its own first metric. Falls
  // back to 0 (no shift) if fromSecond is missing — happens when the
  // run has no metrics yet, in which case there's nothing to plot
  // anyway.
  const tsA = data?.runs[runIdA] ?? null;
  const tsB = data?.runs[runIdB] ?? null;
  const anchorA = tsA?.fromSecond ?? 0;
  const anchorB = tsB?.fromSecond ?? 0;

  // X-axis formatter for elapsed mode: render ticks like "0s", "+30s",
  // "+2m" — uPlot's tick splitter still picks round-second / minute
  // boundaries because we keep `time: true` on the scale.
  const elapsedAxisFormatter = useMemo(
    () => (sec: number) => formatElapsedSeconds(sec),
    [],
  );
  const xAxisFormatter = axisMode === "elapsed" ? elapsedAxisFormatter : undefined;

  // Hover-legend formatter for the elapsed mode — the chart's built-in
  // legend draws each series's value plus the cursor x; without this
  // override the cursor x would render as time-of-day (uPlot's
  // `time: true` default) which is meaningless for elapsed.
  // Note: the chart wrapper today doesn't expose a cursor-x formatter;
  // we accept the default (the visible x-axis labels show the
  // operator the elapsed time, and the per-series y values are what
  // matters in the hover legend). Revisit if operators ask.

  const tpsSeries = useMemo<TimeseriesSeries[]>(
    () => buildTwoRunSeries(tsA, tsB, anchorA, anchorB, axisMode, "tps"),
    [tsA, tsB, anchorA, anchorB, axisMode],
  );
  const avgRtSeries = useMemo<TimeseriesSeries[]>(
    () => buildTwoRunSeries(tsA, tsB, anchorA, anchorB, axisMode, "avgRtMs"),
    [tsA, tsB, anchorA, anchorB, axisMode],
  );
  const errPctSeries = useMemo<TimeseriesSeries[]>(
    () => buildTwoRunSeries(tsA, tsB, anchorA, anchorB, axisMode, "errorPct"),
    [tsA, tsB, anchorA, anchorB, axisMode],
  );

  const onResetZoom = useCallback(() => setResetVersion((v) => v + 1), []);

  const hasAnyData = (tsA?.series.tps.length ?? 0) > 0
                  || (tsB?.series.tps.length ?? 0) > 0;
  const isInitialLoading = status.kind === "loading" && data === null;
  const errorMessage = status.kind === "error" ? status.message : null;
  const missingIds = data?.missing ?? [];

  // AI "Explain the delta" — a right-hand column toggled from the header (next
  // to "Reset zoom"), so it sits beside the charts with no scroll. Mirrors the
  // single-run Metrics tab. Ready once BOTH runs have some metric data.
  const { enabled: aiEnabled } = useAiStatus();
  const [showInsights, setShowInsights] = useState(false);
  const insights = useCompareInsights(runIdA, runIdB);
  const insightsReady = (tsA?.series.tps.length ?? 0) > 0 && (tsB?.series.tps.length ?? 0) > 0;
  const insightsOpen = aiEnabled && showInsights;

  // Auto-generate when the column opens (one click → insights). Fires once;
  // reopening a terminal pair shows the cached result (no re-bill).
  const insightsGenerate = insights.generate;
  const insightsKind = insights.status.kind;
  const hasInsights = insights.data !== null;
  useEffect(() => {
    if (insightsOpen && insightsReady && insightsKind === "idle" && !hasInsights) {
      insightsGenerate();
    }
  }, [insightsOpen, insightsReady, insightsKind, hasInsights, insightsGenerate]);

  return (
    <section className="twoRunMetricsPanel" aria-label="Two-run metrics comparison">
      <header className="twoRunMetricsPanel__header">
        <div className="twoRunMetricsPanel__headerInfo">
          <p className="twoRunMetricsPanel__status">
            {isInitialLoading
              ? "loading…"
              : errorMessage
                ? <span className="text--error">error: {errorMessage}</span>
                : <>
                    {hasAnyData
                      ? `${secondsCovered(tsA, tsB)} second${secondsCovered(tsA, tsB) === 1 ? "" : "s"} of overlap`
                      : "no metrics yet"}
                    {lastUpdated && <> · last fetch {lastUpdated.toLocaleTimeString()}</>}
                    {isPaused && <> · <span className="badge badge--warn" title={pauseTooltip(pauseReason)}>{pauseLabel(pauseReason)}</span></>}
                    {missingIds.length > 0 && (
                      <> · <span className="badge badge--err" title="One or both runs were not found in the global-orchestrator. Check the runId — the run may have been purged.">missing {missingIds.length}</span></>
                    )}
                  </>}
          </p>
          {/* Color legend: small chips matching the chart series colors,
              labelled with each run's truncated id. The full id sits in
              the page header above. */}
          <ul className="twoRunMetricsPanel__legend" aria-label="Run color legend">
            <li>
              <span className="twoRunMetricsPanel__swatch" style={{ background: RUN_A_COLOR }} aria-hidden="true" />
              <span className="mono">{shortId(runIdA)}</span>
            </li>
            <li>
              <span className="twoRunMetricsPanel__swatch" style={{ background: RUN_B_COLOR }} aria-hidden="true" />
              <span className="mono">{shortId(runIdB)}</span>
            </li>
          </ul>
        </div>
        <div className="twoRunMetricsPanel__headerActions">
          <div
            className="metricsPanel__layoutToggle"
            role="tablist"
            aria-label="Time axis"
          >
            <button
              type="button"
              role="tab"
              aria-selected={axisMode === "elapsed"}
              className={`btn btn--ghost ${axisMode === "elapsed" ? "btn--active" : ""}`}
              onClick={() => setAxisMode("elapsed")}
              title="Anchor each run's timeline at its own start (compare 'at +30s into each run, how were both doing?')"
            >
              Elapsed
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={axisMode === "absolute"}
              className={`btn btn--ghost ${axisMode === "absolute" ? "btn--active" : ""}`}
              onClick={() => setAxisMode("absolute")}
              title="Show each run at its actual wall-clock time (useful when both ran simultaneously)"
            >
              Absolute
            </button>
          </div>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onResetZoom}
            title="Reset zoom on all three charts back to the full data range"
            disabled={!hasAnyData}
          >
            ⟲ Reset zoom
          </button>
          {aiEnabled && (
            <button
              type="button"
              className={`btn btn--ghost ${insightsOpen ? "btn--active" : ""}`}
              aria-pressed={insightsOpen}
              onClick={() => setShowInsights((v) => !v)}
              title="Show Claude's read of the delta in a panel beside the charts"
            >
              ✨ Explain the delta
            </button>
          )}
        </div>
      </header>

      <div className="twoRunMetricsPanel__body">
        <div className="twoRunMetricsPanel__charts">
      {hasAnyData ? (
        <div className="twoRunMetricsPanel__chartStack">
          <TimeseriesChart
            title="Total TPS"
            series={tpsSeries}
            yLabel="requests / s"
            height={CHART_HEIGHT}
            formatValue={(v) => v.toFixed(1)}
            formatAxisValue={formatCompactNumber}
            xAxisFormatter={xAxisFormatter}
            syncKey={syncKey}
            resetVersion={resetVersion}
          />
          <TimeseriesChart
            title="Response Time"
            series={avgRtSeries}
            yLabel="ms"
            height={CHART_HEIGHT}
            formatValue={(v) => v.toFixed(1)}
            formatAxisValue={formatCompactDuration}
            xAxisFormatter={xAxisFormatter}
            syncKey={syncKey}
            resetVersion={resetVersion}
          />
          <TimeseriesChart
            title="Error %"
            series={errPctSeries}
            yLabel="%"
            height={CHART_HEIGHT}
            formatValue={(v) => v.toFixed(2)}
            formatAxisValue={formatPercent}
            xAxisFormatter={xAxisFormatter}
            syncKey={syncKey}
            resetVersion={resetVersion}
          />
        </div>
      ) : !isInitialLoading && !errorMessage ? (
        <p className="runDetail__embedHint twoRunMetricsPanel__emptyHint">
          {missingIds.length === 2
            ? <>Neither <code className="mono">{shortId(runIdA)}</code> nor <code className="mono">{shortId(runIdB)}</code> resolved to a known run. Double-check the run ids — they may have been purged.</>
            : missingIds.length === 1
              ? <>One of the two runs (<code className="mono">{shortId(missingIds[0]!)}</code>) wasn't found, and the other has no metrics yet. The comparison view needs both sides.</>
              : <>No metrics for either run yet — if both runs are <code className="mono">PREPARING</code> this is expected; otherwise check the metrics-consumer logs.</>}
        </p>
      ) : null}
        </div>
        {insightsOpen && (
          <CompareInsightsPanel
            status={insights.status}
            data={insights.data}
            ready={insightsReady}
            onRegenerate={insights.regenerate}
            onClose={() => setShowInsights(false)}
          />
        )}
      </div>
    </section>
  );
}

// ── helpers ────────────────────────────────────────────────────────────

function shortId(runId: string): string {
  // ULIDs are 26 chars; the last 8 are usually distinct enough to
  // tell two runs apart in a short legend chip.
  return runId.length <= 12 ? runId : `…${runId.slice(-8)}`;
}

function secondsCovered(a: MetricsTimeseries | null, b: MetricsTimeseries | null): number {
  return Math.max(a?.series.tps.length ?? 0, b?.series.tps.length ?? 0);
}

type SeriesKey = "tps" | "avgRtMs" | "errorPct";

function buildTwoRunSeries(
  tsA: MetricsTimeseries | null,
  tsB: MetricsTimeseries | null,
  anchorA: number,
  anchorB: number,
  mode: AxisMode,
  metric: SeriesKey,
): TimeseriesSeries[] {
  const out: TimeseriesSeries[] = [];
  if (tsA) {
    out.push({
      label: shortId(tsA.runId),
      color: RUN_A_COLOR,
      data: shiftPoints(tsA.series[metric], mode === "elapsed" ? anchorA : 0),
    });
  }
  if (tsB) {
    out.push({
      label: shortId(tsB.runId),
      color: RUN_B_COLOR,
      data: shiftPoints(tsB.series[metric], mode === "elapsed" ? anchorB : 0),
    });
  }
  return out;
}

function shiftPoints(points: TimeseriesPoint[], anchor: number): TimeseriesPoint[] {
  if (anchor === 0) return points;
  return points.map((p) => ({ sec: p.sec - anchor, v: p.v }));
}

/**
 * Render an elapsed second count as a compact axis label:
 * 0 → "0s", 30 → "+30s", 90 → "+1m30s", 7200 → "+2h".
 */
function formatElapsedSeconds(sec: number): string {
  if (sec === 0) return "0s";
  const sign = sec < 0 ? "-" : "+";
  const s = Math.abs(Math.round(sec));
  if (s < 60) return `${sign}${s}s`;
  if (s < 3600) {
    const m = Math.floor(s / 60);
    const rem = s % 60;
    return rem === 0 ? `${sign}${m}m` : `${sign}${m}m${rem}s`;
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return m === 0 ? `${sign}${h}h` : `${sign}${h}h${m}m`;
}

function pauseLabel(reason: PauseReasonOrNull): string {
  switch (reason) {
    case "manual":         return "paused";
    case "delayNull":      return "paused — both terminal";
    case "documentHidden": return "paused — tab hidden";
    case "offscreen":      return "paused — off-screen";
    case null:             return "paused";
  }
}

function pauseTooltip(reason: PauseReasonOrNull): string {
  switch (reason) {
    case "manual":         return "Manual pause.";
    case "delayNull":      return "Both runs are terminal — nothing new to fetch.";
    case "documentHidden": return "Browser tab is in the background. Polling resumes when the tab returns.";
    case "offscreen":      return "Panel is scrolled off-screen. Polling resumes on scroll back.";
    case null:             return "Polling is paused.";
  }
}

type PauseReasonOrNull = ReturnType<typeof useTwoRunTimeseries>["pauseReason"];

// Re-export so tests + callers can introspect the panel's color choices
// without re-declaring them.
export const TWO_RUN_PALETTE = {
  runA: RUN_A_COLOR,
  runB: RUN_B_COLOR,
} as const;
