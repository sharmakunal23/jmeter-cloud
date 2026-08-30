import type { MetricsTimeseries, MetricsTimeseriesSeries, RunLabelRollup, RunMetricsRollup, RunSummary } from "../../api/runs";

/** Shared fixtures for the Metrics tab tests — one shape per endpoint. */
export function series(seconds = 4, bucketSize = 15, base = 1_700_000_000): MetricsTimeseriesSeries {
  const pts = (v: number) => Array.from({ length: seconds }, (_, i) => ({ sec: base + i * bucketSize, v }));
  return {
    tps: pts(10), avgRtMs: pts(120), errorPct: pts(1), p90Ms: pts(200), p95Ms: pts(300), p99Ms: pts(900),
    statusCodes: { "2xx": pts(9.8), "4xx": pts(0.1), "5xx": pts(0.1) },
  };
}

export function timeseries(overrides: Partial<MetricsTimeseries> = {}, seconds = 4): MetricsTimeseries {
  return {
    runId: "01J000RUN", bucketSize: 15, fromSecond: 1_700_000_000, toSecond: 1_700_000_000 + (seconds - 1) * 15,
    series: series(seconds), ...overrides,
  };
}

export function emptyTimeseries(): MetricsTimeseries {
  return { runId: "01J000RUN", bucketSize: 15, fromSecond: null, toSecond: null,
    series: { tps: [], avgRtMs: [], errorPct: [], statusCodes: {} } };
}

export function summary(apps: string[] = ["CPS"]): RunSummary {
  const stats = (application: string | null, samples: number) => ({
    application, samples, errors: Math.round(samples / 100), tps: samples / 60, errorPct: 1,
    avgMs: 120, p90Ms: 200, p95Ms: 300, p99Ms: 900, maxMs: 1500, maxActiveThreads: 10,
  });
  return {
    runId: "01J000RUN", fromSecond: 1_700_000_000, toSecond: 1_700_000_045,
    total: stats(null, 1320),
    byApplication: apps.map((a, i) => stats(a, 1000 - i * 300)),
  };
}

export function emptySummary(): RunSummary {
  return { runId: "01J000RUN", fromSecond: null, toSecond: null,
    total: { samples: 0, errors: 0, tps: 0, errorPct: 0, avgMs: 0, p90Ms: 0, p95Ms: 0, p99Ms: 0, maxMs: 0, maxActiveThreads: 0 },
    byApplication: [] };
}

export function rollupRow(label: string, samples: number, application = "CPS"): RunLabelRollup {
  return {
    label, application, totalThroughput: samples, totalErrors: 1, errorRate: 1 / samples,
    httpErrors: 2, httpErrorRate: 2 / samples, throughputRps: samples / 60,
    avgMs: 120, avgP50Ms: 100, avgP90Ms: 200, avgP95Ms: 300, avgP99Ms: 900, maxMs: 1500, maxActiveThreads: 10,
    firstSecond: 1_700_000_000, lastSecond: 1_700_000_045, rowCount: 8,
  };
}

export function rollup(rows: RunLabelRollup[] = [rollupRow("TG1 login", 1000), rollupRow("TG5 pay", 320, "CPS-PCI")]): RunMetricsRollup {
  return { runId: "01J000RUN", state: "COMPLETED", byLabel: rows };
}
