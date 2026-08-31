import { describe, expect, it } from "vitest";

import type { MetricsTimeseriesSeries, RunSummaryStats } from "../../api/runs";
import { errorPctByBucket, foldStats, sumByBucket } from "../workflowMetrics";

function stats(over: Partial<RunSummaryStats>): RunSummaryStats {
  return {
    samples: 0, errors: 0, tps: 0, errorPct: 0, avgMs: 0,
    p90Ms: 0, p95Ms: 0, p99Ms: 0, maxMs: 0, maxActiveThreads: 0, ...over,
  };
}

describe("foldStats — one headline across an execution's runs", () => {
  it("adds throughput and recomputes the error rate from the counts, never averaging percentages", () => {
    // 100 samples at 50% errors and 900 at 10% is 14%, not the 30% an average gives.
    const folded = foldStats([
      stats({ samples: 100, errors: 50, errorPct: 50, tps: 2 }),
      stats({ samples: 900, errors: 90, errorPct: 10, tps: 8 }),
    ])!;

    expect(folded.samples).toBe(1000);
    expect(folded.errors).toBe(140);
    expect(folded.tps).toBe(10);
    expect(folded.errorPct).toBeCloseTo(14, 6);
  });

  it("sequential runs do not have their rates added — that throughput never coexisted", () => {
    // Two 50 req/s runs, back to back, 100 s apart: the execution pushed
    // 10,000 samples over ~200 s, which is ~50 req/s, not 100.
    const folded = foldStats(
      [stats({ samples: 5000, tps: 50 }), stats({ samples: 5000, tps: 50 })],
      [{ fromSecond: 0, toSecond: 100 }, { fromSecond: 100, toSecond: 200 }],
    )!;
    expect(folded.tps).toBeCloseTo(10000 / 215, 4);
    expect(folded.tps).toBeLessThan(60);
  });

  it("parallel runs still add up, because that throughput did coexist", () => {
    const folded = foldStats(
      [stats({ samples: 5000, tps: 50 }), stats({ samples: 5000, tps: 50 })],
      [{ fromSecond: 0, toSecond: 100 }, { fromSecond: 0, toSecond: 100 }],
    )!;
    expect(folded.tps).toBeCloseTo(10000 / 115, 4);
    expect(folded.tps).toBeGreaterThan(80);
  });

  it("without windows it falls back to the sum of per-run rates", () => {
    expect(foldStats([stats({ samples: 10, tps: 2 }), stats({ samples: 10, tps: 3 })])!.tps)
      .toBeCloseTo(5, 6);
  });

  it("weights response times by the samples behind them", () => {
    // 100 samples at 1000ms and 900 at 100ms is 190ms, not the 550ms of a plain mean.
    const folded = foldStats([
      stats({ samples: 100, avgMs: 1000, p95Ms: 1000 }),
      stats({ samples: 900, avgMs: 100, p95Ms: 100 }),
    ])!;

    expect(folded.avgMs).toBeCloseTo(190, 6);
    expect(folded.p95Ms).toBeCloseTo(190, 6);
  });

  it("keeps the worst max rather than averaging it, and ignores runs with no samples", () => {
    const folded = foldStats([
      stats({ samples: 10, maxMs: 50, maxActiveThreads: 5 }),
      stats({ samples: 10, maxMs: 9000, maxActiveThreads: 5 }),
      stats({ samples: 0, maxMs: 99999, maxActiveThreads: 99 }),
    ])!;

    expect(folded.maxMs).toBe(9000);
    expect(folded.maxActiveThreads).toBe(10);
    expect(folded.samples).toBe(20);
  });

  it("no samples anywhere is null, not a row of zeroes pretending to be data", () => {
    expect(foldStats([])).toBeNull();
    expect(foldStats([stats({ samples: 0 })])).toBeNull();
  });
});

describe("sumByBucket", () => {
  it("adds runs bucket by bucket and keeps the buckets in order", () => {
    expect(sumByBucket([
      [{ sec: 30, v: 1 }, { sec: 15, v: 2 }],
      [{ sec: 15, v: 5 }],
    ])).toEqual([{ sec: 15, v: 7 }, { sec: 30, v: 1 }]);
  });

  it("a run that has not reported a bucket simply does not contribute to it", () => {
    expect(sumByBucket([[{ sec: 15, v: 3 }], []])).toEqual([{ sec: 15, v: 3 }]);
  });
});

describe("errorPctByBucket", () => {
  function series(codes: Record<string, Array<{ sec: number; v: number }>>): MetricsTimeseriesSeries {
    return { tps: [], avgRtMs: [], errorPct: [], statusCodes: codes };
  }

  it("is computed from counts across runs, so a small noisy run cannot dominate", () => {
    const out = errorPctByBucket([
      series({ "2xx": [{ sec: 15, v: 90 }], "5xx": [{ sec: 15, v: 10 }] }),
      series({ "2xx": [{ sec: 15, v: 900 }], "4xx": [{ sec: 15, v: 0 }] }),
    ]);
    // 10 bad of 1000 total = 1%, not the 5% you get averaging 10% and 0%.
    expect(out).toEqual([{ sec: 15, v: 1 }]);
  });

  it("a bucket with no traffic is 0%, not a division by zero", () => {
    expect(errorPctByBucket([series({ "2xx": [{ sec: 15, v: 0 }] })])).toEqual([{ sec: 15, v: 0 }]);
  });
});
