import { useMemo } from "react";

import { runsApi, type MetricsTimeseries } from "../../api/runs";
import type { WorkflowTask } from "../../api/workflows";
import { colorForKey } from "../../lib/chartColors";
import { usePanelQuery } from "../../hooks/usePanelQuery";
import { useRefreshTick } from "../../hooks/useRefreshTick";
import { TimeseriesChart, type TimeseriesSeries } from "../charts/TimeseriesChart";

/**
 * The execution's load tests as one picture, one line per application.
 *
 * <p>Every load-test task in a workflow is its own run, so "split by
 * application" here is a fan-out over those runs rather than one query with a
 * split — which is also why the series key is the task's application: it is the
 * only name that means the same thing across them.
 *
 * <p>Refreshes on the workers' own cadence while the execution is live and the
 * tab is visible, and stops entirely once it is not.
 */
const REFRESH_MS = 15_000;
const CHART_HEIGHT = 240;

export interface WorkflowMetricsPanelProps {
  tasks: WorkflowTask[];
  /** Live executions poll; a finished one is read once. */
  live: boolean;
}

export function WorkflowMetricsPanel({ tasks, live }: WorkflowMetricsPanelProps) {
  const loadTests = useMemo(
    () => tasks.filter((t) => t.type === "LOAD_TEST" && t.runId),
    [tasks],
  );
  const runKey = loadTests.map((t) => t.runId).join(",");
  const { tick, isPaused } = useRefreshTick(live ? REFRESH_MS : null, "workflowMetrics");

  const query = usePanelQuery<Array<{ task: WorkflowTask; series: MetricsTimeseries }>>(
    async (signal) => {
      // One read per run, in parallel; a run whose metrics are not up yet is
      // dropped rather than failing the whole panel.
      const results = await Promise.all(loadTests.map((task) =>
        runsApi.timeseries(task.runId!, signal, { window: "all" })
          .then((series) => ({ task, series }))
          .catch(() => null)));
      return results.filter((r): r is { task: WorkflowTask; series: MetricsTimeseries } => r !== null);
    },
    [runKey],
    tick,
    loadTests.length > 0,
  );

  if (loadTests.length === 0) {
    return (
      <div className="emptyState emptyState--compact">
        <p className="ink-soft">No load test has started yet — charts appear once one does.</p>
      </div>
    );
  }

  const rows = query.data ?? [];
  const nameOf = (task: WorkflowTask) => task.applicationName ?? task.name;

  function seriesFor(pick: (s: MetricsTimeseries) => ReadonlyArray<{ sec: number; v: number }> | undefined)
    : TimeseriesSeries[] {
    return rows.map(({ task, series }, i) => ({
      label: nameOf(task),
      color: colorForKey(nameOf(task), i),
      data: pick(series) ?? [],
    })).filter((s) => s.data.length > 0);
  }

  const tps = seriesFor((s) => s.series.tps);
  const rt = seriesFor((s) => s.series.avgRtMs);
  const err = seriesFor((s) => s.series.errorPct);

  return (
    <div className="workflowMetrics">
      <div className="workflowMetrics__head">
        <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
          {rows.length} run{rows.length === 1 ? "" : "s"}, split by application
          {live && (isPaused ? " · paused (tab hidden)" : " · live")}
        </span>
      </div>

      {query.status.kind === "loading" && rows.length === 0 ? (
        <p className="ink-soft">Loading metrics…</p>
      ) : tps.length === 0 ? (
        <p className="ink-soft">No samples yet.</p>
      ) : (
        <div className="workflowMetrics__charts">
          <TimeseriesChart title="Throughput (req/s)" series={tps} height={CHART_HEIGHT} />
          <TimeseriesChart title="Average response time (ms)" series={rt} height={CHART_HEIGHT} />
          <TimeseriesChart title="Error %" series={err} height={CHART_HEIGHT} />
        </div>
      )}

      <table className="miniTable">
        <thead>
          <tr>
            <th scope="col">Application</th>
            <th scope="col">Task</th>
            <th scope="col">Run</th>
            <th scope="col">State</th>
          </tr>
        </thead>
        <tbody>
          {loadTests.map((t) => (
            <tr key={t.taskId}>
              <td>{t.applicationName ?? "—"}</td>
              <td>{t.name}</td>
              <td className="mono" style={{ fontSize: "0.8rem" }}>
                <a href={`/applications/${encodeURIComponent(t.applicationName ?? "")}/runs/${t.runId}`}>
                  {t.runId?.slice(-8)}
                </a>
              </td>
              <td>{t.state}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
