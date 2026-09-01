import { describe, expect, it } from "vitest";

import { compareInsightsMarkdown, runInsightsMarkdown } from "../aiInsightText";
import type { CompareInsights, RunInsights } from "../../api/ai";

const run: RunInsights = {
  runId: "01M1DBCWGHSPXTG7D1N0K13TW1",
  model: "claude-opus-5",
  promptVersion: "v5",
  summary: "Throughput held flat near 18.4 tps; a quarter of requests failed.",
  findings: [
    {
      severity: "crit",
      title: "Quarter of requests fail throughout",
      detail: "Errors are constant from the first minute to the last.",
      evidence: "httpErrorPct 25.18 (4,113 of 16,333)",
    },
    { severity: "info", title: "Load capped by test plan", detail: "", evidence: "maxThreads 5" },
  ],
  tokensIn: 2778,
  tokensOut: 1170,
  cachedAt: "2026-09-01T03:10:39.204Z",
  fromCache: false,
};

const compare: CompareInsights = {
  runIds: ["01J0A", "01J0B"],
  model: "claude-opus-5",
  promptVersion: "v5",
  summary: "B is slower under the same load.",
  findings: [
    {
      metric: "p95Ms",
      verdict: "regression",
      delta: "+47%",
      detail: "Tail widened.",
      evidence: "604 → 891 ms",
    },
  ],
  tokensIn: 5000,
  tokensOut: 900,
  cachedAt: "2026-09-01T03:20:00.000Z",
  fromCache: true,
};

describe("runInsightsMarkdown", () => {
  it("carries the summary, numbered findings and each finding's evidence", () => {
    const md = runInsightsMarkdown(run);
    expect(md).toContain("# AI insights — run 01M1DBCWGHSPXTG7D1N0K13TW1");
    expect(md).toContain("Throughput held flat near 18.4 tps");
    expect(md).toContain("1. **[crit] Quarter of requests fail throughout**");
    expect(md).toContain("   Evidence: httpErrorPct 25.18 (4,113 of 16,333)");
    expect(md).toContain("2. **[info] Load capped by test plan**");
  });

  it("keeps scope, provenance and the disclaimer — the text outlives the panel", () => {
    const md = runInsightsMarkdown(run);
    expect(md).toContain("Whole run, every label.");
    expect(md).toContain("claude-opus-5 (prompt v5)");
    expect(md).toContain("2778+1170 tokens");
    // ISO, not a locale string: a pasted analysis is read in other timezones.
    expect(md).toContain("2026-09-01T03:10:39.204Z");
    expect(md).toContain("Advisory only");
  });

  it("omits an empty detail line rather than emitting a blank one", () => {
    expect(runInsightsMarkdown(run)).not.toMatch(/\n {3}\n/);
  });

  it("drops the Findings section when there are none", () => {
    const md = runInsightsMarkdown({ ...run, findings: [] });
    expect(md).not.toContain("## Findings");
    expect(md).toContain("Advisory only");
  });
});

describe("compareInsightsMarkdown", () => {
  it("names both runs in the operator's A/B order and renders the verdict + delta", () => {
    const md = compareInsightsMarkdown(compare);
    expect(md).toContain("# AI comparison — run A 01J0A vs run B 01J0B");
    expect(md).toContain("1. **p95Ms — regression** (+47%)");
    expect(md).toContain("   Evidence: 604 → 891 ms");
    expect(md).toContain("Whole run on both sides.");
  });

  it("survives a finding with no delta", () => {
    const md = compareInsightsMarkdown({
      ...compare,
      findings: [{ metric: "tps", verdict: "no significant change", delta: "", detail: "", evidence: "" }],
    });
    expect(md).toContain("1. **tps — no significant change**");
    expect(md).not.toContain("()");
  });
});
