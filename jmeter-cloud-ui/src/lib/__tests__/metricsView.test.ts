import { describe, expect, it } from "vitest";

import { defaultView, formatClock, parseMetricsView, writeMetricsView } from "../metricsView";

describe("metricsView — the URL is the view", () => {
  const top10 = { prefix: "", limit: 10 as const };

  it("defaults: a live run opens on the last 30 minutes, a finished run on the whole test; 15 s buckets, top 10 labels in both sections", () => {
    expect(parseMetricsView(new URLSearchParams(), false)).toEqual({ range: "30m", granularity: 15, split: "none", labels: top10, report: top10, percentile: "avg" });
    expect(parseMetricsView(new URLSearchParams(), true).range).toBe("all");
  });

  it("parses every key — the two sections' label controls independently — and ignores anything unrecognised", () => {
    const v = parseMetricsView(new URLSearchParams("range=1h&granularity=60&split=region&label=TG1&top=50&reportLabel=TG5&reportTop=all&rt=p95"), false);
    expect(v).toEqual({ range: "1h", granularity: 60, split: "region",
      labels: { prefix: "TG1", limit: 50 }, report: { prefix: "TG5", limit: "all" }, percentile: "p95" });
    const bad = parseMetricsView(new URLSearchParams("range=9d&granularity=7&split=application&top=13&reportTop=none&rt=p42"), true);   // by-application split is not offered
    expect(bad).toEqual(defaultView(true));
    expect(parseMetricsView(new URLSearchParams(`label=${"x".repeat(200)}`), true).labels.prefix).toHaveLength(100);
  });

  it("writes only what differs from the default and leaves other params alone", () => {
    const base = new URLSearchParams("tab=metrics");
    const written = writeMetricsView(base, { range: "30m", granularity: 15, split: "none", labels: { prefix: " ", limit: 10 }, report: top10, percentile: "avg" }, false);
    expect(written.toString()).toBe("tab=metrics");
    const changed = writeMetricsView(base, { range: "all", granularity: 30, split: "region",
      labels: { prefix: "TG5", limit: 20 }, report: { prefix: "", limit: "all" }, percentile: "avg" }, false);
    expect(Object.fromEntries(changed.entries())).toEqual({ tab: "metrics", range: "all", granularity: "30", split: "region", label: "TG5", top: "20", reportTop: "all" });
    // The percentile is a view choice like the rest — in the link when chosen,
    // absent at its default so a plain URL stays plain.
    const p95 = writeMetricsView(base, { ...defaultView(false), split: "region", percentile: "p95" }, false);
    expect(p95.get("rt")).toBe("p95");
    expect(writeMetricsView(base, { ...defaultView(false), percentile: "avg" }, false).has("rt")).toBe(false);
    // "all" is the default for a finished run, so it is not written there.
    expect(writeMetricsView(base, { range: "all", granularity: 15, split: "none", labels: top10, report: top10, percentile: "avg" }, true).has("range")).toBe(false);
    // Writing removes a key that went back to its default.
    expect(writeMetricsView(new URLSearchParams("range=4h"), defaultView(false), false).has("range")).toBe(false);
  });

  it("formatClock renders seconds", () => {
    const sec = Math.floor(new Date(2026, 4, 30, 13, 45, 7).getTime() / 1000);
    expect(formatClock(sec)).toBe("13:45:07");
  });
});
