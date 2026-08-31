import { useMemo, useState } from "react";

import {
  runsApi,
  type MetricsGranularity, type MetricsTimeseries, type RunSummary, type RunSummaryStats,
} from "../../api/runs";
import type { WorkflowTask } from "../../api/workflows";
import { colorForKey } from "../../lib/chartColors";
import {
  PERCENTILE_LABELS, errorPctByBucket, foldStats, percentileSeries, sumByBucket,
  type Percentile,
} from "../../lib/workflowMetrics";
import { usePanelQuery } from "../../hooks/usePanelQuery";
import { useRefreshTick } from "../../hooks/useRefreshTick";
import { statsFor } from "../metrics/KeyMetrics";
import { MetricsSection, useSectionOpen } from "../metrics/MetricsSection";
import { TimeseriesChart, type TimeseriesSeries } from "../charts/TimeseriesChart";

/**
 * The execution's load tests as one dashboard, laid out like the hosted
 * "Servicing MQ" live board — key metrics, a summary by application, then
 * throughput, response time, error rate and error codes — but split by
 * application across the runs the workflow launched rather than by label
 * within one run.
 *
 * <p>Every load test in a workflow is its own run, so this fans out one summary
 * and one timeseries read per run and folds them; the application name is the
 * only key that means the same thing across them. Reads happen only while the
 * tab is open and the browser tab is visible, and stop once the execution ends.
 */
const REFRESH_MS = 15_000;
const CHART_HEIGHT = 260;
const GRANULARITIES: MetricsGranularity[] = [15, 30, 60];

export interface WorkflowMetricsPanelProps {
  tasks: WorkflowTask[];
  /** Live executions poll; a finished one is read once. */
  live: boolean;
}

interface RunSummaryRow { task: WorkflowTask; application: string; summary: RunSummary }
interface RunSeriesRow { task: WorkflowTask; application: string; series: MetricsTimeseries }

/**
 * Read every run in parallel, dropping the ones whose metrics have not landed
 * yet — one run without rows must not blank the whole board.
 */
async function collect<T extends object>(
  loadTests: WorkflowTask[],
  read: (task: WorkflowTask) => Promise<T>,
): Promise<T[]> {
  const results: Array<T | null> = await Promise.all(
    loadTests.map((t) => read(t).catch(() => null)),
  );
  return results.filter((r): r is T => r !== null);
}

export function WorkflowMetricsPanel({ tasks, live }: WorkflowMetricsPanelProps) {
  const [granularity, setGranularity] = useState<MetricsGranularity>(60);
  const [percentile, setPercentile] = useState<Percentile>("avg");

  const [keyOpen, toggleKey] = useSectionOpen("wfKeyMetrics");
  const [summaryOpen, toggleSummary] = useSectionOpen("wfSummary");
  const [throughputOpen, toggleThroughput] = useSectionOpen("wfThroughput");
  const [errorsOpen, toggleErrors] = useSectionOpen("wfErrors");

  const loadTests = useMemo(
    () => tasks.filter((t) => t.type === "LOAD_TEST" && t.runId),
    [tasks],
  );
  const runKey = loadTests.map((t) => t.runId).join(",");
  const { tick, isPaused } = useRefreshTick(live ? REFRESH_MS : null, "workflowMetrics");

  // Two reads, each following what is open: a collapsed section fetches
  // nothing, so a board with only the numbers showing never pulls a timeseries.
  const needsSummary = keyOpen || summaryOpen;
  const needsSeries = throughputOpen || errorsOpen;

  const summaries = usePanelQuery<RunSummaryRow[]>(
    async (signal) => collect(loadTests, (task) =>
      runsApi.summary(task.runId!, signal).then((summary) => ({
        task, application: task.applicationName ?? task.name, summary,
      }))),
    [runKey],
    tick,
    needsSummary && loadTests.length > 0,
  );

  const seriesRows = usePanelQuery<RunSeriesRow[]>(
    async (signal) => collect(loadTests, (task) =>
      runsApi.timeseries(task.runId!, signal, { granularity, window: "all" }).then((series) => ({
        task, application: task.applicationName ?? task.name, series,
      }))),
    [runKey, granularity],
    tick,
    needsSeries && loadTests.length > 0,
  );

  const summaryRows = useMemo(() => summaries.data ?? [], [summaries.data]);
  const chartRows = useMemo(() => seriesRows.data ?? [], [seriesRows.data]);
  const folded = useMemo(
    () => foldStats(
      summaryRows.map((r) => r.summary.total),
      // The windows make throughput a wall-clock rate rather than the sum of
      // rates that, for sequential load tests, never coexisted.
      summaryRows.map((r) => ({ fromSecond: r.summary.fromSecond, toSecond: r.summary.toSecond })),
    ),
    [summaryRows],
  );

  if (loadTests.length === 0) {
    return (
      <div className="emptyState emptyState--compact">
        <p className="ink-soft">No load test has started yet — charts appear once one does.</p>
      </div>
    );
  }

  const seriesPerApplication = (pick: (r: RunSeriesRow) => ReadonlyArray<{ sec: number; v: number }>)
    : TimeseriesSeries[] =>
    chartRows
      .map((r, i) => ({ label: r.application, color: colorForKey(r.application, i), data: pick(r) }))
      .filter((s) => s.data.length > 0);

  const throughput = seriesPerApplication((r) => r.series.series.tps);
  const responseTime = seriesPerApplication((r) => percentileSeries(r.series.series, percentile));
  const errorRate = seriesPerApplication((r) => r.series.series.errorPct);

  // Error codes are the execution's, not each application's: five 4xx lines and
  // five 5xx lines is ten lines nobody reads.
  const codeSeries: TimeseriesSeries[] = [
    { label: "4xx", color: "#f59e0b", data: sumByBucket(chartRows.map((r) => r.series.series.statusCodes["4xx"] ?? [])) },
    { label: "5xx", color: "#dc2626", data: sumByBucket(chartRows.map((r) => r.series.series.statusCodes["5xx"] ?? [])) },
  ].filter((s) => s.data.length > 0);
  const overallErrorPct: TimeseriesSeries[] = chartRows.length > 1
    ? [{ label: "all applications", color: "#64748b", data: errorPctByBucket(chartRows.map((r) => r.series.series)) }]
    : [];

  const rowCount = Math.max(summaryRows.length, chartRows.length, loadTests.length);
  const summaryLoading = needsSummary && summaries.status.kind === "loading";
  const seriesLoading = needsSeries && seriesRows.status.kind === "loading";

  return (
    <div className="workflowMetrics">
      <div className="workflowMetrics__toolbar">
        <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
          {loadTests.length} run{loadTests.length === 1 ? "" : "s"}, split by application
          {live && (isPaused ? " · paused (tab hidden)" : " · live")}
        </span>
        <span className="workflowMetrics__controls">
          <label className="inlineField">
            <span>Granularity</span>
            <select
              value={granularity}
              onChange={(e) => setGranularity(Number(e.target.value) as MetricsGranularity)}
            >
              {GRANULARITIES.map((g) => <option key={g} value={g}>{g}s</option>)}
            </select>
          </label>
        </span>
      </div>

      {/* The sections always render. An empty state that replaced the board
          would take the headers with it, and with every section collapsed
          there is nothing that needs data — so there would be no way back to
          an open one. Each section reports its own emptiness instead. */}
      <>
          <MetricsSection
            id="wfKeyMetrics" title="Key metrics" open={keyOpen} onToggle={toggleKey}
            meta={`${rowCount} application${rowCount === 1 ? "" : "s"}`}
          >
            {folded === null ? (
              <p className="ink-soft">{summaryLoading ? "Loading metrics…" : "No samples yet."}</p>
            ) : (
              <dl className="statRow" aria-label="Key metrics across every application">
                {statsFor(folded).map((st) => (
                  <div key={st.label} className={`statCard ${st.tone ? `statCard--${st.tone}` : ""}`} title={st.title}>
                    <dt className="statCard__label">{st.label}</dt>
                    <dd className="statCard__value">{st.value}</dd>
                  </div>
                ))}
              </dl>
            )}
          </MetricsSection>

          <MetricsSection
            id="wfSummary" title="Summary by application" open={summaryOpen} onToggle={toggleSummary}
          >
          {summaryRows.length === 0 ? (
            <p className="ink-soft">{summaryLoading ? "Loading metrics…" : "No samples yet."}</p>
          ) : (
          <table className="miniTable workflowMetrics__summary">
            <thead>
              <tr>
                <th scope="col">Application</th>
                <th scope="col" className="num">TPS</th>
                <th scope="col" className="num">Avg</th>
                <th scope="col" className="num">P90</th>
                <th scope="col" className="num">P95</th>
                <th scope="col" className="num">P99</th>
                <th scope="col" className="num">Error %</th>
              </tr>
            </thead>
            <tbody>
              {summaryRows.map((r) => (
                <SummaryRow key={r.task.taskId} label={r.application} s={r.summary.total} />
              ))}
              {summaryRows.length > 1 && folded && (
                <SummaryRow label="All applications" s={folded} total />
              )}
            </tbody>
          </table>
          )}
          </MetricsSection>

          <MetricsSection
            id="wfThroughput" title="Throughput and response time"
            open={throughputOpen} onToggle={toggleThroughput}
            // In the section header, not above the chart: a picker sitting on
            // one chart made that column taller than its neighbour, so two
            // charts of the same height stopped looking like it. MetricsSection
            // renders controls only while the section is open, so the buttons
            // cannot be reached when the charts they steer are collapsed.
            controls={
              <div className="percentilePicker" role="group" aria-label="Response time percentile">
                {(Object.keys(PERCENTILE_LABELS) as Percentile[]).map((p) => (
                  <button
                    key={p}
                    type="button"
                    className={`btn btn--ghost btn--sm${percentile === p ? " isActive" : ""}`}
                    aria-pressed={percentile === p}
                    onClick={() => setPercentile(p)}
                  >
                    {PERCENTILE_LABELS[p]}
                  </button>
                ))}
              </div>
            }
          >
          {chartRows.length === 0 ? (
            <p className="ink-soft">{seriesLoading ? "Loading metrics…" : "No samples yet."}</p>
          ) : (
          <div className="workflowMetrics__charts">
            <TimeseriesChart title="Throughput by application (req/s)" series={throughput} height={CHART_HEIGHT} />
            <TimeseriesChart
              title={`Response time by application — ${PERCENTILE_LABELS[percentile]} (ms)`}
              series={responseTime}
              height={CHART_HEIGHT}
            />
          </div>
          )}
          </MetricsSection>

          <MetricsSection id="wfErrors" title="Errors" open={errorsOpen} onToggle={toggleErrors}>
          {chartRows.length === 0 ? (
            <p className="ink-soft">{seriesLoading ? "Loading metrics…" : "No samples yet."}</p>
          ) : (
          <div className="workflowMetrics__charts">
            <TimeseriesChart
              title="Error % by application"
              series={[...errorRate, ...overallErrorPct]}
              height={CHART_HEIGHT}
            />
            <TimeseriesChart title="Error codes across the execution (per second)" series={codeSeries} height={CHART_HEIGHT} />
          </div>
          )}
          </MetricsSection>
      </>
    </div>
  );
}

function SummaryRow({ label, s, total }: { label: string; s: RunSummaryStats; total?: boolean }) {
  return (
    <tr className={total ? "isTotal" : undefined}>
      <td>{label}</td>
      <td className="num mono" title={`${s.samples.toLocaleString()} samples`}>{s.tps.toFixed(2)}</td>
      <td className="num mono">{Math.round(s.avgMs)}ms</td>
      <td className="num mono">{Math.round(s.p90Ms)}ms</td>
      <td className="num mono">{Math.round(s.p95Ms)}ms</td>
      <td className="num mono">{Math.round(s.p99Ms)}ms</td>
      <td className={`num mono${s.errorPct >= 5 ? " ink-warn" : ""}`}>{s.errorPct.toFixed(1)}%</td>
    </tr>
  );
}
