import { useMemo, useState } from "react";

import type { RunLabelRollup } from "../../api/runs";
import { formatErrorPct, formatMs } from "./KeyMetrics";

/**
 * The hosted dashboard's "Aggregate Report": one row per label over the
 * selected range, busiest first, sortable by any column in the browser
 * (the page is already bounded — the server returns every label at most once).
 * Error % is HTTP 4xx + 5xx, like every other Error % on the tab.
 */
export interface AggregateReportProps {
  rows: RunLabelRollup[] | null;
  loading: boolean;
  labelPrefix: string;
}

type SortKey = "label" | "totalThroughput" | "throughputRps" | "avgMs"
  | "avgP90Ms" | "avgP95Ms" | "avgP99Ms" | "httpErrorRate";

const COLUMNS: ReadonlyArray<{ key: SortKey; label: string; numeric: boolean }> = [
  { key: "label",           label: "Label",       numeric: false },
  { key: "totalThroughput", label: "Samples",     numeric: true },
  { key: "throughputRps",   label: "Throughput",  numeric: true },
  { key: "avgMs",           label: "Avg",         numeric: true },
  { key: "avgP90Ms",        label: "P90",         numeric: true },
  { key: "avgP95Ms",        label: "P95",         numeric: true },
  { key: "avgP99Ms",        label: "P99",         numeric: true },
  { key: "httpErrorRate",   label: "Error %",     numeric: true },
];

/** The report as CSV — the columns shown, busiest label first, RFC 4180 quoting. */
export function aggregateReportCsv(rows: RunLabelRollup[]): string {
  const header = ["label", "samples", "throughputRps", "avgMs", "p90Ms", "p95Ms", "p99Ms", "errorPct"];
  const lines = rows.map((r) => [
    r.label, r.totalThroughput, r.throughputRps.toFixed(2), r.avgMs.toFixed(1), r.avgP90Ms.toFixed(1),
    r.avgP95Ms.toFixed(1), r.avgP99Ms.toFixed(1), (100 * r.httpErrorRate).toFixed(2),
  ].map(csvCell).join(","));
  return [header.join(","), ...lines].join("\r\n") + "\r\n";
}

function csvCell(v: string | number): string {
  const text = String(v);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export function AggregateReport({ rows, loading, labelPrefix }: AggregateReportProps) {
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: "totalThroughput", desc: true });

  const sorted = useMemo(() => {
    if (!rows) return [];
    const copy = [...rows];
    copy.sort((a, b) => {
      const av = a[sort.key] ?? "";
      const bv = b[sort.key] ?? "";
      const cmp = typeof av === "number" && typeof bv === "number" ? av - bv : String(av).localeCompare(String(bv));
      return sort.desc ? -cmp : cmp;
    });
    return copy;
  }, [rows, sort]);

  if (!rows || rows.length === 0) {
    return (
      <p className="metricsPanel__status" data-testid="aggregateReportEmpty">
        {loading ? "loading…" : labelPrefix
          ? `No labels start with "${labelPrefix}" in this range.`
          : "No samples in this range yet."}
      </p>
    );
  }

  const toggle = (key: SortKey, numeric: boolean) =>
    setSort((prev) => prev.key === key ? { key, desc: !prev.desc } : { key, desc: numeric });

  return (
    <div className="metricsTable__scroll">
      <table className="runsTable metricsTable" aria-label="Aggregate report">
        <thead>
          <tr>
            {COLUMNS.map((c) => (
              <th
                key={c.key}
                className={c.numeric ? "metricsTable__num" : undefined}
                aria-sort={sort.key === c.key ? (sort.desc ? "descending" : "ascending") : "none"}
              >
                <button type="button" className="metricsTable__sort" onClick={() => toggle(c.key, c.numeric)}>
                  {c.label}{sort.key === c.key ? (sort.desc ? " ↓" : " ↑") : ""}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr key={r.label}>
              <td className="mono">{r.label}</td>
              <td className="metricsTable__num">{r.totalThroughput.toLocaleString()}</td>
              <td className="metricsTable__num">{r.throughputRps.toFixed(1)}</td>
              <td className="metricsTable__num">{formatMs(r.avgMs)}</td>
              <td className="metricsTable__num">{formatMs(r.avgP90Ms)}</td>
              <td className="metricsTable__num">{formatMs(r.avgP95Ms)}</td>
              <td className="metricsTable__num">{formatMs(r.avgP99Ms)}</td>
              <td className="metricsTable__num">{formatErrorPct(100 * r.httpErrorRate)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
