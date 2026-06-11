import type { RunFleetMember } from "./api/runs";

/**
 * Browser-side configuration. In local-dev these are hardcoded to the
 * docker-compose port layout; cloud deployments inject the runtime
 * values via {@code VITE_*} env vars at build time.
 */

export const GRAFANA_BASE_URL: string =
  (import.meta.env.VITE_GRAFANA_URL as string | undefined) ?? "http://localhost:3000";

/**
 * Builds the URL the run-detail page embeds for live per-test metrics.
 * Targets the {@code perTestLiveMetrics} dashboard provisioned in
 * {@code grafana/dashboards/perTestLiveMetrics.json}.
 *
 * <p>{@code kiosk=tv} hides Grafana's chrome (header / sidebar) so the
 * iframe shows just the panels. {@code refresh=10s} matches the Grafana
 * dashboard's intrinsic refresh; bumping it down would just hammer
 * Postgres without buying us anything visible. {@code from=now-3h}
 * matches the dashboard's default time range so just-finished runs
 * appear in the timeseries panels without the operator widening the
 * range manually.
 */
export function grafanaPerTestEmbed(runId: string): string {
  const base = GRAFANA_BASE_URL.replace(/\/$/, "");
  const params = new URLSearchParams({
    "var-runId": runId,
    "kiosk":     "tv",
    "refresh":   "10s",
    "from":      "now-3h",
    "to":        "now",
  });
  return `${base}/d/perTestLiveMetrics/per-test-live-metrics?${params.toString()}`;
}

/**
 * HM-3 — full-Grafana deep link for a finished run. Same dashboard as
 * {@link grafanaPerTestEmbed}, but with {@code kiosk} omitted (so the
 * operator gets Grafana's full chrome for drill-down) and an EXPLICIT
 * {@code from} / {@code to} time range. The explicit range matters
 * because Grafana defaults to "now-Xh"; for a run that completed
 * yesterday, the embed's "now-3h" shows nothing useful, but the
 * deep-link with concrete millisecond bounds renders correctly.
 *
 * <p>Both bounds are Unix epoch milliseconds. When omitted (e.g. the
 * run is still PREPARING and we have no timestamp range yet), Grafana
 * falls back to its dashboard default — fine for live runs.
 */
export function grafanaPerTestDeepLink(
  runId: string,
  fromMs?: number | null,
  toMs?: number | null,
): string {
  const base = GRAFANA_BASE_URL.replace(/\/$/, "");
  const params = new URLSearchParams({ "var-runId": runId });
  if (fromMs != null && toMs != null) {
    params.set("from", String(fromMs));
    params.set("to",   String(toMs));
  }
  return `${base}/d/perTestLiveMetrics/per-test-live-metrics?${params.toString()}`;
}

/**
 * Best-effort URL that opens the local-orchestrator's
 * {@code /api/v1/logs?stream=jmeter&tail=200} endpoint for a given
 * fleet member. The {@code podBaseUrl} the global stores points at the
 * pod's in-cluster service name (e.g.,
 * {@code http://orchestrator-1:8080}); for a browser that lives on
 * the docker host, we rewrite container hosts to {@code localhost} +
 * the published port.
 *
 * <p>This is local-dev convenience only — production deployments
 * front per-pod logs through a centralized log aggregator (Loki /
 * CloudWatch); Step 19 will inline the tail in the UI itself.
 */
export function podLogTailUrl(member: RunFleetMember): string | null {
  if (!member.podBaseUrl) return null;
  let url = member.podBaseUrl;
  // Map docker-network hostnames → localhost host-ports.
  const replacements: Record<string, string> = {
    "http://orchestrator-1:8080": "http://localhost:8080",
    "http://orchestrator-2:8080": "http://localhost:8090",
  };
  if (replacements[url]) url = replacements[url];
  return `${url.replace(/\/$/, "")}/api/v1/logs?stream=jmeter&tail=200`;
}
