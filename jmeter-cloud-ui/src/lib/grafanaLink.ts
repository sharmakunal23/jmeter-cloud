import { isTerminalRunState, type MetricsGranularity, type MetricsWindow, type RunState } from "../api/runs";

/**
 * Builds the "Open in Grafana" deep link for a run: the application's (or its
 * group's) dashboard URL with the run's time range, the Metrics tab's window,
 * and the dashboard variables the hosted dashboards expose
 * (`var-application`, `var-granularity`).
 *
 * Live run → `from=now-<window>&to=now&refresh=15s` (whole test →
 * `from=<startedAt ms>`); terminal run → `from=<startedAt ms>&to=<completedAt ms>`
 * with no auto-refresh. A terminal run older than the group's hot days opens
 * the history dashboard when one is configured.
 */
/** The dashboards a run can open — the group's URLs and hot days, the app's metrics name. */
export interface GrafanaDashboards {
  liveUrl?: string | null;
  historyUrl?: string | null;
  /** Days the live dashboard covers; older runs open `historyUrl`. Default 7. */
  hotDays?: number | null;
  /** `LABEL.APPLICATION` for the run's app — set as `var-application` unless the URL already pins one. */
  metricsApplication?: string | null;
}

export interface GrafanaLinkInput extends GrafanaDashboards {
  run: { state: RunState; startedAt?: string | null; completedAt?: string | null };
  /** The Metrics tab's selection. */
  window: MetricsWindow;
  granularity?: MetricsGranularity | "auto";
  /** Injectable clock for tests (epoch ms). */
  now?: number;
}

const LIVE_REFRESH = "15s";
const DAY_MS = 86_400_000;

function epochMs(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? t : null;
}

/** Splits a URL into `[beforeQuery, params, hash]` without needing an absolute base. */
function splitUrl(url: string): { head: string; params: URLSearchParams; hash: string } {
  const hashAt = url.indexOf("#");
  const hash = hashAt >= 0 ? url.slice(hashAt) : "";
  const noHash = hashAt >= 0 ? url.slice(0, hashAt) : url;
  const qAt = noHash.indexOf("?");
  const head = qAt >= 0 ? noHash.slice(0, qAt) : noHash;
  const params = new URLSearchParams(qAt >= 0 ? noHash.slice(qAt + 1) : "");
  return { head, params, hash };
}

/** The dashboard to open, or `null` when the application has none. */
export function grafanaLinkFor(input: GrafanaLinkInput): string | null {
  const now = input.now ?? Date.now();
  const terminal = isTerminalRunState(input.run.state);
  const started = epochMs(input.run.startedAt);
  const completed = epochMs(input.run.completedAt);
  const hotDays = input.hotDays && input.hotDays > 0 ? input.hotDays : 7;

  const useHistory =
    terminal && !!input.historyUrl && completed !== null && now - completed > hotDays * DAY_MS;
  const base = (useHistory ? input.historyUrl : input.liveUrl) || input.liveUrl;
  if (!base || !base.trim()) return null;

  const { head, params, hash } = splitUrl(base.trim());
  if (terminal) {
    params.set("from", started !== null ? String(started) : "now-1h");
    params.set("to", completed !== null ? String(completed) : "now");
    params.delete("refresh");
  } else {
    params.set("from", input.window === "all"
      ? (started !== null ? String(started) : "now-30m")
      : `now-${input.window}`);
    params.set("to", "now");
    params.set("refresh", LIVE_REFRESH);
  }
  if (input.metricsApplication && !params.has("var-application")) {
    params.set("var-application", input.metricsApplication);
  }
  if (input.granularity && input.granularity !== "auto" && !params.has("var-granularity")) {
    params.set("var-granularity", String(input.granularity));
  }
  return `${head}?${params.toString()}${hash}`;
}

/** Whether a stored dashboard URL is usable: absolute http(s). Mirrors the server's rule. */
export function isDashboardUrl(value: string): boolean {
  try {
    const u = new URL(value);
    return (u.protocol === "http:" || u.protocol === "https:") && u.host !== "";
  } catch {
    return false;
  }
}
