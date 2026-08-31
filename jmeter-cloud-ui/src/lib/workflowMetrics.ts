/**
 * The arithmetic behind the workflow Metrics tab.
 *
 * <p>Each load test in a workflow is its own run, so "split by application" is a
 * fan-out across runs rather than one query with a split. Folding those back
 * into one headline follows the platform's rule for combining metric rows:
 * **sum the components, never average the ratios**, and weight every percentile
 * by the samples behind it. Averaging five runs' `errorPct` would give the mean
 * of five percentages, not the error rate.
 */

import type { MetricsTimeseriesSeries, RunSummaryStats, TimeseriesPoint } from "../api/runs";

/** Which response-time series the chart is showing. */
export type Percentile = "avg" | "p90" | "p95" | "p99";

export const PERCENTILE_LABELS: Record<Percentile, string> = {
  avg: "Average",
  p90: "P90",
  p95: "P95",
  p99: "P99",
};

export function percentileSeries(
  series: MetricsTimeseriesSeries,
  which: Percentile,
): TimeseriesPoint[] {
  switch (which) {
    case "avg": return series.avgRtMs;
    case "p90": return series.p90Ms ?? [];
    case "p95": return series.p95Ms ?? [];
    case "p99": return series.p99Ms ?? [];
  }
}

/**
 * One headline across every run of an execution. Throughput adds up (each run
 * measured its own share of the load); response times are weighted by samples;
 * the error rate is recomputed from the totals rather than averaged.
 */
export function foldStats(rows: ReadonlyArray<RunSummaryStats>): RunSummaryStats | null {
  const present = rows.filter((r) => r.samples > 0);
  if (present.length === 0) return null;

  const samples = present.reduce((n, r) => n + r.samples, 0);
  const errors = present.reduce((n, r) => n + r.errors, 0);
  const weighted = (pick: (r: RunSummaryStats) => number) =>
    present.reduce((n, r) => n + pick(r) * r.samples, 0) / samples;

  return {
    samples,
    errors,
    tps: present.reduce((n, r) => n + r.tps, 0),
    errorPct: (100 * errors) / samples,
    avgMs: weighted((r) => r.avgMs),
    p90Ms: weighted((r) => r.p90Ms),
    p95Ms: weighted((r) => r.p95Ms),
    p99Ms: weighted((r) => r.p99Ms),
    maxMs: present.reduce((n, r) => Math.max(n, r.maxMs), 0),
    maxActiveThreads: present.reduce((n, r) => n + r.maxActiveThreads, 0),
  };
}

/**
 * Add several runs' series together bucket by bucket, keyed on the bucket's
 * second. Used for the error-code chart, where the interesting line is the
 * whole execution's 4xx and 5xx rather than one per application.
 */
export function sumByBucket(seriesList: ReadonlyArray<ReadonlyArray<TimeseriesPoint>>): TimeseriesPoint[] {
  const totals = new Map<number, number>();
  for (const series of seriesList) {
    for (const p of series) totals.set(p.sec, (totals.get(p.sec) ?? 0) + p.v);
  }
  return [...totals.entries()].sort((a, b) => a[0] - b[0]).map(([sec, v]) => ({ sec, v }));
}

/**
 * Error percentage per bucket across every run: the 4xx + 5xx counts over the
 * samples behind them. Recomputed from counts, never averaged from each run's
 * own percentage.
 */
export function errorPctByBucket(
  seriesList: ReadonlyArray<MetricsTimeseriesSeries>,
): TimeseriesPoint[] {
  const bad = new Map<number, number>();
  const all = new Map<number, number>();
  for (const s of seriesList) {
    for (const key of Object.keys(s.statusCodes)) {
      const isBad = key === "4xx" || key === "5xx";
      for (const p of s.statusCodes[key] ?? []) {
        all.set(p.sec, (all.get(p.sec) ?? 0) + p.v);
        if (isBad) bad.set(p.sec, (bad.get(p.sec) ?? 0) + p.v);
      }
    }
  }
  return [...all.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([sec, total]) => ({ sec, v: total === 0 ? 0 : (100 * (bad.get(sec) ?? 0)) / total }));
}
