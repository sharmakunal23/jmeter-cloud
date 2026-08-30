import { useEffect, useMemo, useState } from "react";

import {
  isTerminalRunState, runsApi,
  type MetricsSplit, type MetricsTimeseries, type MetricsTimeseriesSeries, type RunState, type TimeseriesPoint,
} from "../api/runs";
import { CATEGORICAL_PALETTE, SERIES_COLOR, colorForKey } from "../lib/chartColors";
import { grafanaLinkFor, type GrafanaDashboards } from "../lib/grafanaLink";
import {
  GRANULARITY_OPTIONS, LABEL_LIMIT_OPTIONS, RANGE_OPTIONS, SPLIT_OPTIONS,
  type GranularityChoice, type LabelLimit, type LabelSelection, type MetricsViewState,
} from "../lib/metricsView";
import { useAiStatus } from "../hooks/useAiStatus";
import { useMetricsView } from "../hooks/useMetricsView";
import { usePanelQuery } from "../hooks/usePanelQuery";
import { useRefreshTick } from "../hooks/useRefreshTick";
import { useRunInsights } from "../hooks/useRunInsights";
import { RunInsightsPanel } from "./RunInsightsPanel";
import { AggregateReport, aggregateReportCsv } from "./metrics/AggregateReport";
import { downloadCsv } from "../lib/download";
import { ChartModal, type ChartSpec } from "./charts/ChartModal";
import { KeyMetrics } from "./metrics/KeyMetrics";
import { MetricsSection, useSectionOpen } from "./metrics/MetricsSection";
import {
  TimeseriesChart, type TimeseriesSeries, formatCompactDuration, formatCompactNumber, formatPercent,
} from "./charts/TimeseriesChart";

/**
 * The run's metrics as a dashboard — the hosted Grafana layout, scoped to one
 * run: a toolbar (the live/paused badge, AI insights, reset zoom, granularity,
 * split by region, range, the group's Grafana link), then five collapsible sections over
 * four queries — the Per label section and the Aggregate report each carry
 * their own label filter and Top-N:
 *
 * <ul>
 *   <li><b>Key metrics</b> — TPS · Avg · P90 · P95 · P99 · Error % + summary by application (`/summary`, one statement).</li>
 *   <li><b>Throughput and response time</b> and <b>Errors</b> (error %, 4xx / 5xx) — one `/timeseries` read, shared while either is open.</li>
 *   <li><b>Per label</b> — throughput and response time per label (`/timeseries?byLabel`).</li>
 *   <li><b>Aggregate report</b> — one row per label (`/metrics`).</li>
 * </ul>
 *
 * A collapsed section fetches nothing. Open sections refresh together every
 * {@link METRICS_REFRESH_MS} while the run is live and the page is open (one
 * flush window — the rows change no faster), never once it is terminal. The
 * view lives in the URL. Error % everywhere is HTTP 4xx + 5xx over samples.
 */
export interface MetricsTabPanelProps {
  runId: string;
  runState: RunState;
  /** The run's timestamps, for the Grafana link's absolute range on a finished run. */
  run?: { startedAt?: string | null; completedAt?: string | null };
  /** The group's dashboards (and the app's metrics name); absent = no "Open in Grafana". */
  dashboards?: GrafanaDashboards | null;
}

/** One flush window: the workers publish 15-second windows, so the rows change every 15 s. */
export const METRICS_REFRESH_MS = 15_000;
const CHART_HEIGHT = 280;
const LABEL_DEBOUNCE_MS = 300;

export function MetricsTabPanel({ runId, runState, run, dashboards }: MetricsTabPanelProps) {
  const isTerminal = isTerminalRunState(runState);
  const [view, updateView] = useMetricsView(isTerminal);
  const granularity = view.granularity;

  // One clock for every open section.
  const { tick, isPaused, pauseReason } = useRefreshTick(isTerminal ? null : METRICS_REFRESH_MS, `metrics:${runId}`);

  const [keyOpen, toggleKey] = useSectionOpen("keyMetrics");
  const [throughputOpen, toggleThroughput] = useSectionOpen("throughput");
  const [errorsOpen, toggleErrors] = useSectionOpen("errors");
  const [labelsOpen, toggleLabels] = useSectionOpen("perLabel");
  const [reportOpen, toggleReport] = useSectionOpen("aggregateReport");
  // Both chart sections read the same statement — one query, enabled while either is open.
  const chartsOpen = throughputOpen || errorsOpen;

  // ── The four queries — each only while its section is open ──────────
  const summary = usePanelQuery(
    (signal) => runsApi.summary(runId, signal, { window: view.range }),
    [runId, view.range], tick, keyOpen,
  );
  const charts = usePanelQuery(
    (signal) => runsApi.timeseries(runId, signal, {
      byApplication: view.split === "application", byRegion: view.split === "region",
      granularity, window: view.range,
    }),
    [runId, view.range, granularity, view.split], tick, chartsOpen,
  );
  const labels = usePanelQuery(
    (signal) => runsApi.timeseries(runId, signal, {
      byLabel: true, labelPrefix: view.labels.prefix, labelLimit: view.labels.limit, granularity, window: view.range,
    }),
    [runId, view.range, granularity, view.labels.prefix, view.labels.limit], tick, labelsOpen,
  );
  const report = usePanelQuery(
    (signal) => runsApi.metrics(runId, signal, { window: view.range, labelPrefix: view.report.prefix, labelLimit: view.report.limit }),
    [runId, view.range, view.report.prefix, view.report.limit], tick, reportOpen,
  );

  // Zoom is shared across every chart on the tab (cursor sync), and "Reset zoom" clears it everywhere.
  const syncKey = `metrics:${runId}`;
  const [resetVersion, setResetVersion] = useState(0);
  // Any chart card can be opened enlarged in a modal (there is no stacked layout).
  const [enlarged, setEnlarged] = useState<ChartSpec | null>(null);

  const data = charts.data;
  const hasChartData = data !== null && data.series.tps.length > 0;

  // ── AI insights — a side column, gated on ≥ 30 s of chart data ──────
  const { enabled: aiEnabled } = useAiStatus();
  const [showInsights, setShowInsights] = useState(false);
  const insights = useRunInsights(runId);
  const secondsOfData = data ? data.series.tps.length * Math.max(1, data.bucketSize) : 0;
  const insightsReady = secondsOfData >= 30;
  const insightsOpen = aiEnabled && showInsights;
  const insightsGenerate = insights.generate;
  const insightsKind = insights.status.kind;
  const hasInsights = insights.data !== null;
  useEffect(() => {
    if (insightsOpen && insightsReady && insightsKind === "idle" && !hasInsights) insightsGenerate();
  }, [insightsOpen, insightsReady, insightsKind, hasInsights, insightsGenerate]);

  const grafanaHref = dashboards
    ? grafanaLinkFor({
        ...dashboards,
        run: { state: runState, startedAt: run?.startedAt, completedAt: run?.completedAt },
        window: view.range, granularity: view.granularity,
      })
    : null;

  const firstError = [summary, charts, labels, report].map((q) => q.status).find((s) => s.kind === "error");

  return (
    <div className="metricsPanel">
      <MetricsToolbar
        view={view}
        onChange={updateView}
        isPaused={isPaused}
        pauseLabel={isPaused ? pauseLabel(pauseReason) : null}
        pauseTitle={isPaused ? pauseTooltip(pauseReason) : null}
        onResetZoom={() => setResetVersion((v) => v + 1)}
        canResetZoom={hasChartData}
        error={firstError && firstError.kind === "error" ? firstError.message : null}
        aiEnabled={aiEnabled}
        insightsOpen={insightsOpen}
        onToggleInsights={() => setShowInsights((v) => !v)}
        grafanaHref={grafanaHref}
        grafanaTitle={isTerminal
          ? "Open the group's Grafana dashboard for this run's exact time range"
          : "Open the group's Grafana dashboard on this range, auto-refreshing"}
      />

      <div className="metricsPanel__body">
        <div className="metricsPanel__charts">
          <MetricsSection id="keyMetrics" title="Key metrics" open={keyOpen} onToggle={toggleKey}>
            <KeyMetrics summary={summary.data} loading={summary.status.kind === "loading"} />
          </MetricsSection>

          <MetricsSection id="throughput" title="Throughput and response time" open={throughputOpen} onToggle={toggleThroughput}
            meta={throughputOpen ? splitMeta(view.split) : undefined}>
            {hasChartData ? (
              <ChartGrid data={data} split={view.split} panels="throughput" syncKey={syncKey} resetVersion={resetVersion} onEnlarge={setEnlarged} />
            ) : (
              <EmptyCharts loading={charts.status.kind === "loading" && data === null} runState={runState} />
            )}
          </MetricsSection>

          <MetricsSection id="errors" title="Errors" open={errorsOpen} onToggle={toggleErrors}
            meta={errorsOpen ? splitMeta(view.split) : undefined}>
            {hasChartData ? (
              <ChartGrid data={data} split={view.split} panels="errors" syncKey={syncKey} resetVersion={resetVersion} onEnlarge={setEnlarged} />
            ) : (
              <EmptyCharts loading={charts.status.kind === "loading" && data === null} runState={runState} />
            )}
          </MetricsSection>

          <MetricsSection id="perLabel" title="Per label" open={labelsOpen} onToggle={toggleLabels}
            meta={labelsOpen ? labelsMeta(labels.data) : undefined}
            controls={<LabelControls id="perLabel" selection={view.labels} onChange={(labels) => updateView({ labels })} />}>
            {labels.data && labels.data.labels && Object.keys(labels.data.labels).length > 0 ? (
              <LabelCharts data={labels.data} syncKey={syncKey} resetVersion={resetVersion} onEnlarge={setEnlarged} />
            ) : (
              <p className="metricsPanel__status" data-testid="perLabelEmpty">
                {labels.status.kind === "loading" && labels.data === null ? "loading…"
                  : view.labels.prefix ? `No labels start with "${view.labels.prefix}" in this range.`
                  : "No samples in this range yet."}
              </p>
            )}
          </MetricsSection>

          <MetricsSection id="aggregateReport" title="Aggregate report" open={reportOpen} onToggle={toggleReport}
            meta={reportOpen && report.data && report.data.byLabel.length > 0
              ? `${report.data.byLabel.length} label${report.data.byLabel.length === 1 ? "" : "s"}` : undefined}
            controls={<>
              <button
                type="button"
                className="btn btn--ghost"
                disabled={!report.data || report.data.byLabel.length === 0}
                onClick={() => downloadCsv(aggregateReportCsv(report.data?.byLabel ?? []), `aggregateReport-${runId}-${view.range}.csv`)}
                title="Download the rows below as CSV"
              >
                ⭳ Export CSV
              </button>
              <LabelControls id="report" selection={view.report} onChange={(report) => updateView({ report })} />
            </>}>
            <AggregateReport rows={report.data?.byLabel ?? null} loading={report.status.kind === "loading"} labelPrefix={view.report.prefix} />
          </MetricsSection>
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
      <ChartModal chart={enlarged} onClose={() => setEnlarged(null)} />
    </div>
  );
}

// ── Toolbar ────────────────────────────────────────────────────────────

interface MetricsToolbarProps {
  view: MetricsViewState;
  onChange: (patch: Partial<MetricsViewState>) => void;
  isPaused: boolean;
  pauseLabel: string | null;
  pauseTitle: string | null;
  onResetZoom: () => void;
  canResetZoom: boolean;
  error: string | null;
  aiEnabled: boolean;
  insightsOpen: boolean;
  onToggleInsights: () => void;
  grafanaHref: string | null;
  grafanaTitle: string;
}

function MetricsToolbar(p: MetricsToolbarProps) {
  return (
    <header className="metricsPanel__header metricsToolbar">
      <div className="metricsPanel__headerInfo metricsToolbar__state">
        {p.isPaused
          ? <span className="badge badge--warn" title={p.pauseTitle ?? undefined}>{p.pauseLabel}</span>
          : <span className="badge badge--info" title={`Every open section refreshes every ${METRICS_REFRESH_MS / 1000} s while this page is open — one flush window`}>live · {METRICS_REFRESH_MS / 1000} s</span>}
        {p.error && <span className="text--error metricsToolbar__error">error: {p.error}</span>}
      </div>
      <div className="metricsPanel__headerActions">
        {p.aiEnabled && (
          <button
            type="button"
            className={`btn btn--ghost ${p.insightsOpen ? "btn--active" : ""}`}
            aria-pressed={p.insightsOpen}
            onClick={p.onToggleInsights}
            title="Show Claude's read of this run in a panel beside the charts"
          >
            ✨ AI insights
          </button>
        )}
        <button
          type="button"
          className="btn btn--ghost"
          onClick={p.onResetZoom}
          title="Reset zoom on all charts back to the full range"
          disabled={!p.canResetZoom}
        >
          ⟲ Reset zoom
        </button>
        <label className="metricsPanel__windowPicker">
          <span className="visuallyHidden">Granularity</span>
          <select
            className="formSelect"
            value={String(p.view.granularity)}
            onChange={(e) => p.onChange({ granularity: Number(e.target.value) as GranularityChoice })}
            title="Seconds per point — 15 s is the workers' window, the finest there is"
          >
            {GRANULARITY_OPTIONS.map((o) => <option key={String(o.value)} value={String(o.value)}>{o.label}</option>)}
          </select>
        </label>
        <label className="metricsPanel__windowPicker">
          <span className="visuallyHidden">Split</span>
          <select
            className="formSelect"
            value={p.view.split}
            onChange={(e) => p.onChange({ split: e.target.value as MetricsSplit })}
            title="One line per region"
          >
            {SPLIT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
        <label className="metricsPanel__windowPicker">
          <span className="visuallyHidden">Time range</span>
          <select
            className="formSelect"
            value={p.view.range}
            onChange={(e) => p.onChange({ range: e.target.value as MetricsViewState["range"] })}
            title="The most recent slice of the run, or the whole test"
          >
            {RANGE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
        {p.grafanaHref && (
          <a className="btn btn--ghost" href={p.grafanaHref} target="_blank" rel="noreferrer" title={p.grafanaTitle}>
            ↗ Open in Grafana
          </a>
        )}
      </div>
    </header>
  );
}

/** A section's own label controls: an exact label prefix (debounced into the URL) and the Top-N / all. */
function LabelControls({ id, selection, onChange }: {
  id: string; selection: LabelSelection; onChange: (next: LabelSelection) => void;
}) {
  const [labelDraft, setLabelDraft] = useState(selection.prefix);
  useEffect(() => { setLabelDraft(selection.prefix); }, [selection.prefix]);
  useEffect(() => {
    if (labelDraft === selection.prefix) return;
    const timer = window.setTimeout(() => onChange({ ...selection, prefix: labelDraft }), LABEL_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [labelDraft]);

  return (
    <>
      <label className="metricsPanel__windowPicker metricsToolbar__label">
        <span className="visuallyHidden">Label filter</span>
        <input
          type="search"
          className="formInput metricsToolbar__labelInput"
          data-testid={`${id}LabelPrefix`}
          value={labelDraft}
          maxLength={100}
          placeholder="Label starts with…"
          onChange={(e) => setLabelDraft(e.target.value)}
          title="Only labels starting with this text"
        />
      </label>
      <label className="metricsPanel__windowPicker">
        <span className="visuallyHidden">Labels shown</span>
        <select
          className="formSelect"
          data-testid={`${id}LabelLimit`}
          value={String(selection.limit)}
          onChange={(e) => onChange({ ...selection, limit: e.target.value === "all" ? "all" : Number(e.target.value) as LabelLimit })}
          title="How many of the busiest labels to show"
        >
          {LABEL_LIMIT_OPTIONS.map((o) => <option key={String(o.value)} value={String(o.value)}>{o.label}</option>)}
        </select>
      </label>
    </>
  );
}

// ── Charts ─────────────────────────────────────────────────────────────

interface ChartGridProps {
  data: MetricsTimeseries; split: MetricsSplit; panels: "throughput" | "errors";
  syncKey: string; resetVersion: number; onEnlarge: (chart: ChartSpec) => void;
}

function ChartGrid({ data, split, panels, syncKey, resetVersion, onEnlarge }: ChartGridProps) {
  const groups = split === "application" ? data.applications : split === "region" ? data.regions : undefined;
  const keys = useMemo(() => (groups ? Object.keys(groups).sort() : []), [groups]);
  // The split is applied only when the payload carries it — never flash empty on the first refresh after switching.
  const splitMode = split !== "none" && keys.length > 0 && groups !== undefined;
  const by = split === "application" ? "application" : "region";

  const throughput = useMemo<TimeseriesSeries[]>(() => splitMode
    ? splitSeries(groups!, keys, "tps")
    : [{ label: "TPS", color: SERIES_COLOR.tps, data: data.series.tps }], [data, groups, keys, splitMode]);
  const responseTime = useMemo<TimeseriesSeries[]>(() => {
    if (splitMode) return splitSeries(groups!, keys, "avgRtMs");
    const s: TimeseriesSeries[] = [{ label: "Avg", color: SERIES_COLOR.avg, data: data.series.avgRtMs }];
    if (data.series.p90Ms?.length) s.push({ label: "P90", color: SERIES_COLOR.p90, data: data.series.p90Ms });
    if (data.series.p95Ms?.length) s.push({ label: "P95", color: SERIES_COLOR.p95, data: data.series.p95Ms });
    if (data.series.p99Ms?.length) s.push({ label: "P99", color: SERIES_COLOR.p99, data: data.series.p99Ms });
    return s;
  }, [data, groups, keys, splitMode]);
  const errorPct = useMemo<TimeseriesSeries[]>(() => splitMode
    ? splitSeries(groups!, keys, "errorPct")
    : [{ label: "Error %", color: SERIES_COLOR.error, data: data.series.errorPct }], [data, groups, keys, splitMode]);
  const errorCodes = useMemo<TimeseriesSeries[]>(() => errorCodeSeries(data.series), [data]);

  const one = (v: number) => v.toFixed(1);
  const two = (v: number) => v.toFixed(2);
  const cards: ChartSpec[] = panels === "throughput"
    ? [
        { title: splitMode ? `Throughput by ${by}` : "Throughput", series: throughput, yLabel: "requests / s",
          formatValue: one, formatAxisValue: formatCompactNumber },
        { title: splitMode ? `Response time (avg) by ${by}` : "Response time", series: responseTime, yLabel: "ms",
          formatValue: one, formatAxisValue: formatCompactDuration },
      ]
    : [
        { title: splitMode ? `Error % by ${by}` : "Error %", series: errorPct, yLabel: "% of samples",
          formatValue: two, formatAxisValue: formatPercent },
        { title: "Error codes", series: errorCodes, yLabel: "% of samples",
          formatValue: two, formatAxisValue: formatPercent },
      ];
  return (
    <div className="metricsPanel__chartGrid">
      {cards.map((c) => (
        <ChartCard key={c.title} chart={c} syncKey={syncKey} resetVersion={resetVersion} onEnlarge={onEnlarge} />
      ))}
    </div>
  );
}

/** A chart in its card, with the enlarge control in the corner. */
function ChartCard({ chart, syncKey, resetVersion, onEnlarge }: {
  chart: ChartSpec; syncKey: string; resetVersion: number; onEnlarge: (chart: ChartSpec) => void;
}) {
  return (
    <div className="chartCard">
      <button
        type="button"
        className="chartCard__enlarge"
        onClick={() => onEnlarge(chart)}
        title="Enlarge"
        aria-label={`Enlarge ${chart.title}`}
      >
        ⤢
      </button>
      <TimeseriesChart {...chart} height={CHART_HEIGHT} syncKey={syncKey} resetVersion={resetVersion} />
    </div>
  );
}

function LabelCharts({ data, syncKey, resetVersion, onEnlarge }: {
  data: MetricsTimeseries; syncKey: string; resetVersion: number; onEnlarge: (chart: ChartSpec) => void;
}) {
  const labels = data.labels!;
  const keys = useMemo(() => Object.keys(labels), [labels]);   // busiest first, as the server orders them
  const throughput = useMemo(() => keys.map((k, i) => ({
    label: k, color: CATEGORICAL_PALETTE[i % CATEGORICAL_PALETTE.length]!, data: labels[k]!.tps,
  })), [labels, keys]);
  const responseTime = useMemo(() => keys.map((k, i) => ({
    label: k, color: CATEGORICAL_PALETTE[i % CATEGORICAL_PALETTE.length]!, data: labels[k]!.avgRtMs,
  })), [labels, keys]);
  const one = (v: number) => v.toFixed(1);
  const cards: ChartSpec[] = [
    { title: "Throughput per label", series: throughput, yLabel: "requests / s", formatValue: one, formatAxisValue: formatCompactNumber },
    { title: "Response time per label (avg)", series: responseTime, yLabel: "ms", formatValue: one, formatAxisValue: formatCompactDuration },
  ];
  return (
    <div className="metricsPanel__chartGrid">
      {cards.map((c) => (
        <ChartCard key={c.title} chart={c} syncKey={syncKey} resetVersion={resetVersion} onEnlarge={onEnlarge} />
      ))}
    </div>
  );
}

function EmptyCharts({ loading, runState }: { loading: boolean; runState: RunState }) {
  if (loading) return <p className="metricsPanel__status">loading…</p>;
  return (
    <p className="runDetail__embedHint metricsPanel__emptyHint">
      No metrics in this range yet — the consumer writes each 15-second window as the workers close it.
      {runState === "RUNNING" && " If this persists past the first minute of a running test, check the metrics-consumer logs."}
    </p>
  );
}

// ── helpers ────────────────────────────────────────────────────────────

function splitSeries(
  groups: Record<string, MetricsTimeseriesSeries>, keys: string[], metric: "tps" | "avgRtMs" | "errorPct",
): TimeseriesSeries[] {
  return keys.map((k, i) => ({ label: k, color: colorForKey(k, i), data: groups[k]![metric] }));
}

/**
 * The hosted "Error Codes" panel: 4xx and 5xx as a percentage of the bucket's
 * samples — the server's counts per second over its TPS, both over the same
 * bucket so the ratio is exact. Non-HTTP markers are not charted.
 */
export function errorCodeSeries(series: MetricsTimeseriesSeries): TimeseriesSeries[] {
  const tpsBySec = new Map<number, number>();
  for (const p of series.tps) tpsBySec.set(p.sec, p.v);
  const pct = (points: TimeseriesPoint[] | undefined): TimeseriesPoint[] =>
    (points ?? []).map((p) => {
      const tps = tpsBySec.get(p.sec) ?? 0;
      return { sec: p.sec, v: tps > 0 ? 100 * p.v / tps : 0 };
    });
  return [
    { label: "4xx", color: SERIES_COLOR.http4xx, data: pct(series.statusCodes["4xx"]) },
    { label: "5xx", color: SERIES_COLOR.http5xx, data: pct(series.statusCodes["5xx"]) },
  ];
}

/** "by region" beside a chart section's title when a split is on; nothing otherwise. */
function splitMeta(split: MetricsSplit): string | undefined {
  return split === "none" ? undefined : SPLIT_OPTIONS.find((o) => o.value === split)?.label.toLowerCase() ?? split;
}

function labelsMeta(data: MetricsTimeseries | null): string | undefined {
  if (!data || !data.labels) return undefined;
  const shown = Object.keys(data.labels).length;
  if (shown === 0) return undefined;   // the body already says there is nothing to show
  const total = data.labelsTotal ?? shown;
  return total > shown ? `busiest ${shown} of ${total} labels` : `${shown} label${shown === 1 ? "" : "s"}`;
}

type PauseReasonOrNull = ReturnType<typeof useRefreshTick>["pauseReason"];

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
    case "manual":         return "Refresh is paused.";
    case "delayNull":      return "Run is terminal (COMPLETED / FAILED / ABORTED) — nothing new to fetch.";
    case "documentHidden": return "Browser tab is in the background. Refresh resumes when the tab returns to the foreground.";
    case "offscreen":      return "Panel is scrolled off-screen. Refresh resumes on scroll back.";
    case null:             return "Refresh is paused.";
  }
}
