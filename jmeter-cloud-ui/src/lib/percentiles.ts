import type { MetricsTimeseriesSeries, TimeseriesPoint } from "../api/runs";

/**
 * Which response-time series a chart is showing. Shared by both Metrics tabs —
 * a run's (split by region) and a workflow execution's (split by application) —
 * so the same four options mean the same thing in both places.
 *
 * <p>A split chart shows one line per group, so it can only show ONE percentile
 * at a time: four percentiles across five regions is twenty lines. The unsplit
 * chart has the opposite problem and draws all four at once.
 */
export type Percentile = "avg" | "p90" | "p95" | "p99";

export const PERCENTILE_LABELS: Record<Percentile, string> = {
  avg: "Average",
  p90: "P90",
  p95: "P95",
  p99: "P99",
};

/** Render order for the picker; `Object.keys` on the record is not guaranteed. */
export const PERCENTILE_ORDER: readonly Percentile[] = ["avg", "p90", "p95", "p99"];

/**
 * The chosen series out of a bucket set. The percentile arrays are optional on
 * the wire (an older payload carries only the average), so a missing one reads
 * as empty rather than throwing — the chart then draws nothing for that group.
 */
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
