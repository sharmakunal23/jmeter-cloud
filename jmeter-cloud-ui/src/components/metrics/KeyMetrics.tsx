import type { RunSummary, RunSummaryStats } from "../../api/runs";
import { formatCompactDuration } from "../charts/TimeseriesChart";

/**
 * The hosted dashboard's "Key Metrics" stat row (TPS · Avg · P90 · P95 · P99 ·
 * Error %, where errors are HTTP 4xx + 5xx) and, when the run's labels classify
 * to more than one application, its "Summary by Application" table.
 */
export interface KeyMetricsProps {
  summary: RunSummary | null;
  loading: boolean;
}

interface Stat { label: string; value: string; title: string; tone?: "warn" | "err" }

export function statsFor(s: RunSummaryStats): Stat[] {
  return [
    { label: "TPS",     value: s.tps.toFixed(1),   title: `${s.samples.toLocaleString()} samples over the range the rows cover` },
    { label: "Avg",     value: formatMs(s.avgMs),  title: "Throughput-weighted mean response time" },
    { label: "P90",     value: formatMs(s.p90Ms),  title: "Throughput-weighted p90" },
    { label: "P95",     value: formatMs(s.p95Ms),  title: "Throughput-weighted p95" },
    { label: "P99",     value: formatMs(s.p99Ms),  title: "Throughput-weighted p99" },
    { label: "Error %", value: formatErrorPct(s.errorPct), title: `${s.errors.toLocaleString()} HTTP 4xx + 5xx responses`,
      tone: s.errorPct >= 5 ? "err" : s.errorPct >= 1 ? "warn" : undefined },
  ];
}

export function KeyMetrics({ summary, loading }: KeyMetricsProps) {
  if (!summary || summary.total.samples === 0) {
    return (
      <p className="metricsPanel__status" data-testid="keyMetricsEmpty">
        {loading ? "loading…" : "No samples in this range yet."}
      </p>
    );
  }
  const stats = statsFor(summary.total);
  return (
    <div className="keyMetrics">
      <dl className="statRow" aria-label="Key metrics">
        {stats.map((st) => (
          <div key={st.label} className={`statCard ${st.tone ? `statCard--${st.tone}` : ""}`} title={st.title}>
            <dt className="statCard__label">{st.label}</dt>
            <dd className="statCard__value">{st.value}</dd>
          </div>
        ))}
      </dl>
      {summary.byApplication.length > 1 && (
        <table className="runsTable metricsTable" aria-label="Summary by application">
          <thead>
            <tr>
              <th>Application</th>
              <th className="metricsTable__num">Samples</th>
              <th className="metricsTable__num">TPS</th>
              <th className="metricsTable__num">Avg</th>
              <th className="metricsTable__num">P90</th>
              <th className="metricsTable__num">P95</th>
              <th className="metricsTable__num">P99</th>
              <th className="metricsTable__num">Error %</th>
            </tr>
          </thead>
          <tbody>
            {summary.byApplication.map((a) => (
              <tr key={a.application ?? "(none)"}>
                <td>{a.application ?? "(none)"}</td>
                <td className="metricsTable__num">{a.samples.toLocaleString()}</td>
                <td className="metricsTable__num">{a.tps.toFixed(1)}</td>
                <td className="metricsTable__num">{formatMs(a.avgMs)}</td>
                <td className="metricsTable__num">{formatMs(a.p90Ms)}</td>
                <td className="metricsTable__num">{formatMs(a.p95Ms)}</td>
                <td className="metricsTable__num">{formatMs(a.p99Ms)}</td>
                <td className="metricsTable__num">{formatErrorPct(a.errorPct)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export function formatMs(v: number): string {
  return formatCompactDuration(v);
}

export function formatErrorPct(v: number): string {
  if (v === 0) return "0%";
  return `${v < 10 ? v.toFixed(2) : v.toFixed(1)}%`;
}
