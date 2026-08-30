import { describe, expect, it } from "vitest";
import { grafanaLinkFor, isDashboardUrl } from "../grafanaLink";

const LIVE = "https://grafana.example.com/d/cpsProductMetrics/servicing-mq?orgId=1";
const HISTORY = "https://grafana.example.com/d/cpsProductMetricsHistory/servicing-mq-history?orgId=1";
const NOW = Date.parse("2026-08-30T12:00:00Z");

function q(url: string | null): Record<string, string> {
  return Object.fromEntries(new URL(url!).searchParams.entries());
}

describe("grafanaLinkFor", () => {
  it("live run: the Metrics tab's window becomes a relative range with auto-refresh, plus the dashboard variables", () => {
    const url = grafanaLinkFor({
      liveUrl: LIVE, run: { state: "RUNNING", startedAt: "2026-08-30T11:50:00Z" },
      metricsApplication: "CPS-PCI", window: "15m", granularity: 30, now: NOW,
    });
    expect(url).toMatch(/^https:\/\/grafana\.example\.com\/d\/cpsProductMetrics\/servicing-mq\?/);
    expect(q(url)).toEqual({
      orgId: "1", from: "now-15m", to: "now", refresh: "15s", "var-application": "CPS-PCI", "var-granularity": "30",
    });
  });

  it("live run on 'whole test' anchors from at startedAt (epoch ms) and keeps refreshing", () => {
    const url = grafanaLinkFor({
      liveUrl: LIVE, run: { state: "RUNNING", startedAt: "2026-08-30T11:50:00Z" }, window: "all", now: NOW,
    });
    expect(q(url)).toMatchObject({ from: String(Date.parse("2026-08-30T11:50:00Z")), to: "now", refresh: "15s" });
    expect(q(url)["var-application"]).toBeUndefined();
  });

  it("terminal run: the exact range in epoch ms, no refresh, auto granularity adds nothing", () => {
    const url = grafanaLinkFor({
      liveUrl: LIVE + "&refresh=1m",
      run: { state: "COMPLETED", startedAt: "2026-08-30T11:00:00Z", completedAt: "2026-08-30T11:30:00Z" },
      metricsApplication: "CPS", window: "all", granularity: "auto", now: NOW,
    });
    expect(q(url)).toEqual({
      orgId: "1", from: String(Date.parse("2026-08-30T11:00:00Z")), to: String(Date.parse("2026-08-30T11:30:00Z")),
      "var-application": "CPS",
    });
  });

  it("a terminal run older than hotDays opens the history dashboard; a recent one stays on live", () => {
    const old = { state: "COMPLETED" as const, startedAt: "2026-08-10T11:00:00Z", completedAt: "2026-08-10T11:30:00Z" };
    expect(grafanaLinkFor({ liveUrl: LIVE, historyUrl: HISTORY, hotDays: 7, run: old, window: "all", now: NOW }))
      .toMatch(/cpsProductMetricsHistory/);
    expect(grafanaLinkFor({ liveUrl: LIVE, historyUrl: HISTORY, hotDays: 30, run: old, window: "all", now: NOW }))
      .toMatch(/\/cpsProductMetrics\//);
    // No history URL configured → live, whatever the age.
    expect(grafanaLinkFor({ liveUrl: LIVE, hotDays: 7, run: old, window: "all", now: NOW }))
      .toMatch(/\/cpsProductMetrics\//);
  });

  it("a stored var-application is not overridden; a hash survives; no URL → null", () => {
    const url = grafanaLinkFor({
      liveUrl: "https://g/d/x?var-application=CPP#panel-3",
      run: { state: "RUNNING" }, metricsApplication: "CPS", window: "5m", now: NOW,
    });
    expect(url).toBe("https://g/d/x?var-application=CPP&from=now-5m&to=now&refresh=15s#panel-3");
    expect(grafanaLinkFor({ liveUrl: null, run: { state: "RUNNING" }, window: "5m" })).toBeNull();
    expect(grafanaLinkFor({ liveUrl: "  ", run: { state: "RUNNING" }, window: "5m" })).toBeNull();
  });

  it("isDashboardUrl accepts absolute http(s) only", () => {
    expect(isDashboardUrl("https://grafana.example.com/d/abc")).toBe(true);
    expect(isDashboardUrl("http://localhost:3000/d/abc?orgId=1")).toBe(true);
    expect(isDashboardUrl("/d/abc")).toBe(false);
    expect(isDashboardUrl("ftp://x/y")).toBe(false);
    expect(isDashboardUrl("not a url")).toBe(false);
  });
});
