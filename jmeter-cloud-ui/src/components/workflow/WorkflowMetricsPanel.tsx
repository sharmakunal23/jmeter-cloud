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

interface RunMetrics {
  task: WorkflowTask;
  application: string;
  summary: RunSummary;
  series: MetricsTimeseries;
}

export function WorkflowMetricsPanel({ tasks, live }: WorkflowMetricsPanelProps) {
  const [granularity, setGranularity] = useState<MetricsGranularity>(60);
  const [percentile, setPercentile] = useState<Percentile>("avg");

  const loadTests = useMemo(
    () => tasks.filter((t) => t.type === "LOAD_TEST" && t.runId),
    [tasks],
  );
  const runKey = loadTests.map((t) => t.runId).join(",");
  const { tick, isPaused } = useRefreshTick(live ? REFRESH_MS : null, "workflowMetrics");

  const query = usePanelQuery<RunMetrics[]>(
    async (signal) => {
      // One summary + one timeseries per run, in parallel. A run whose metrics
      // have not landed yet is dropped rather than failing the whole board.
      const results = await Promise.all(loadTests.map(async (task) => {
        try {
          const [summary, series] = await Promise.all([
            runsApi.summary(task.runId!, signal),
            runsApi.timeseries(task.runId!, signal, { granularity, window: "all" }),
          ]);
          return { task, application: task.applicationName ?? task.name, summary, series };
        } catch {
          return null;
        }
      }));
      return results.filter((r): r is RunMetrics => r !== null);
    },
    [runKey, granularity],
    tick,
    loadTests.length > 0,
  );

  const rows = useMemo(() => query.data ?? [], [query.data]);
  const folded = useMemo(() => foldStats(rows.map((r) => r.summary.total)), [rows]);

  if (loadTests.length === 0) {
    return (
      <div className="emptyState emptyState--compact">
        <p className="ink-soft">No load test has started yet — charts appear once one does.</p>
      </div>
    );
  }

  const seriesPerApplication = (pick: (r: RunMetrics) => ReadonlyArray<{ sec: number; v: number }>)
    : TimeseriesSeries[] =>
    rows
      .map((r, i) => ({ label: r.application, color: colorForKey(r.application, i), data: pick(r) }))
      .filter((s) => s.data.length > 0);

  const throughput = seriesPerApplication((r) => r.series.series.tps);
  const responseTime = seriesPerApplication((r) => percentileSeries(r.series.series, percentile));
  const errorRate = seriesPerApplication((r) => r.series.series.errorPct);

  // Error codes are the execution's, not each application's: five 4xx lines and
  // five 5xx lines is ten lines nobody reads.
  const codeSeries: TimeseriesSeries[] = [
    { label: "4xx", color: "#f59e0b", data: sumByBucket(rows.map((r) => r.series.series.statusCodes["4xx"] ?? [])) },
    { label: "5xx", color: "#dc2626", data: sumByBucket(rows.map((r) => r.series.series.statusCodes["5xx"] ?? [])) },
  ].filter((s) => s.data.length > 0);
  const overallErrorPct: TimeseriesSeries[] = rows.length > 1
    ? [{ label: "all applications", color: "#64748b", data: errorPctByBucket(rows.map((r) => r.series.series)) }]
    : [];

  const loading = query.status.kind === "loading" && rows.length === 0;

  return (
    <div className="workflowMetrics">
      <div className="workflowMetrics__toolbar">
        <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
          {rows.length} run{rows.length === 1 ? "" : "s"}, split by application
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

      {loading ? (
        <p className="ink-soft">Loading metrics…</p>
      ) : folded === null ? (
        <p className="ink-soft">No samples yet.</p>
      ) : (
        <>
          <h3 className="metricsHeading">Key metrics</h3>
          <dl className="statRow" aria-label="Key metrics across every application">
            {statsFor(folded).map((st) => (
              <div key={st.label} className={`statCard ${st.tone ? `statCard--${st.tone}` : ""}`} title={st.title}>
                <dt className="statCard__label">{st.label}</dt>
                <dd className="statCard__value">{st.value}</dd>
              </div>
            ))}
          </dl>

          <h3 className="metricsHeading">Summary by application</h3>
          <table className="miniTable workflowMetrics__summary">
            <thead>
              <tr>
                <th scope="col">Application</th>
                <th scope="col" className="num">Samples</th>
                <th scope="col" className="num">TPS</th>
                <th scope="col" className="num">Avg</th>
                <th scope="col" className="num">P90</th>
                <th scope="col" className="num">P95</th>
                <th scope="col" className="num">P99</th>
                <th scope="col" className="num">Error %</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => <SummaryRow key={r.task.taskId} label={r.application} s={r.summary.total} />)}
              {rows.length > 1 && <SummaryRow label="All applications" s={folded} total />}
            </tbody>
          </table>

          <div className="workflowMetrics__charts">
            <TimeseriesChart title="Throughput by application (req/s)" series={throughput} height={CHART_HEIGHT} />
            <div className="workflowMetrics__chartWithPicker">
              <div className="workflowMetrics__pickerRow">
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
              <TimeseriesChart
                title={`Response time by application — ${PERCENTILE_LABELS[percentile]} (ms)`}
                series={responseTime}
                height={CHART_HEIGHT}
              />
            </div>
            <TimeseriesChart
              title="Error % by application"
              series={[...errorRate, ...overallErrorPct]}
              height={CHART_HEIGHT}
            />
            <TimeseriesChart title="Error codes across the execution (per second)" series={codeSeries} height={CHART_HEIGHT} />
          </div>
        </>
      )}
    </div>
  );
}

function SummaryRow({ label, s, total }: { label: string; s: RunSummaryStats; total?: boolean }) {
  return (
    <tr className={total ? "isTotal" : undefined}>
      <td>{label}</td>
      <td className="num mono">{s.samples.toLocaleString()}</td>
      <td className="num mono">{s.tps.toFixed(2)}</td>
      <td className="num mono">{Math.round(s.avgMs)}ms</td>
      <td className="num mono">{Math.round(s.p90Ms)}ms</td>
      <td className="num mono">{Math.round(s.p95Ms)}ms</td>
      <td className="num mono">{Math.round(s.p99Ms)}ms</td>
      <td className={`num mono${s.errorPct >= 5 ? " ink-warn" : ""}`}>{s.errorPct.toFixed(1)}%</td>
    </tr>
  );
}
