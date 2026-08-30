import type { MetricsGranularity, MetricsSplit, MetricsWindow } from "../api/runs";

/**
 * What the Metrics tab shows, carried in the page URL (`?range=30m&granularity=30
 * &split=application&label=TG1&top=20&reportLabel=TG5&reportTop=all`) so a link
 * is the view — the way a Grafana dashboard URL is. Only values that differ
 * from the defaults are written.
 *
 * Defaults: a live run opens on the last 30 minutes ("Whole test" on a long
 * live run re-aggregates every row of the run on each refresh — the bounded
 * default is what keeps ten watchers cheap); a finished run opens on the whole
 * test, served from the hub's cache. Granularity is 15 s — the workers'
 * window, the finest there is. The per-label section and the aggregate report
 * each show their busiest 10, with their own prefix filter.
 */
export type GranularityChoice = MetricsGranularity;

/** How many of the busiest labels a section shows — or every one. */
export type LabelLimit = 10 | 20 | 50 | "all";

/** A section's own label controls: an exact prefix ("" = every label) and a cap. */
export interface LabelSelection {
  prefix: string;
  limit: LabelLimit;
}

export interface MetricsViewState {
  range: MetricsWindow;
  granularity: GranularityChoice;
  split: MetricsSplit;
  /** The Per label section's controls (`label`, `top` in the URL). */
  labels: LabelSelection;
  /** The Aggregate report's controls (`reportLabel`, `reportTop`) — independent of the charts'. */
  report: LabelSelection;
}

export const LIVE_DEFAULT_RANGE: MetricsWindow = "30m";
export const TERMINAL_DEFAULT_RANGE: MetricsWindow = "all";

export const RANGE_OPTIONS: ReadonlyArray<{ value: MetricsWindow; label: string }> = [
  { value: "5m",  label: "Last 5 min" },
  { value: "15m", label: "Last 15 min" },
  { value: "30m", label: "Last 30 min" },
  { value: "1h",  label: "Last 1 hour" },
  { value: "2h",  label: "Last 2 hours" },
  { value: "4h",  label: "Last 4 hours" },
  { value: "all", label: "Whole test" },
];

export const DEFAULT_GRANULARITY: GranularityChoice = 15;
export const DEFAULT_LABEL_LIMIT: LabelLimit = 10;

export const GRANULARITY_OPTIONS: ReadonlyArray<{ value: GranularityChoice; label: string }> = [
  { value: 15, label: "15 s" },
  { value: 30, label: "30 s" },
  { value: 60, label: "60 s" },
];

export const LABEL_LIMIT_OPTIONS: ReadonlyArray<{ value: LabelLimit; label: string }> = [
  { value: 10,    label: "Top 10" },
  { value: 20,    label: "Top 20" },
  { value: 50,    label: "Top 50" },
  { value: "all", label: "All labels" },
];

/** The splits the tab offers — by region only; the API's `byApplication` stays available to other callers. */
export const SPLIT_OPTIONS: ReadonlyArray<{ value: MetricsSplit; label: string }> = [
  { value: "none",   label: "No split" },
  { value: "region", label: "By region" },
];

const PARAM_RANGE = "range";
const PARAM_GRANULARITY = "granularity";
const PARAM_SPLIT = "split";
const PARAM_LABEL = "label";
const PARAM_TOP = "top";
const PARAM_REPORT_LABEL = "reportLabel";
const PARAM_REPORT_TOP = "reportTop";
const LABEL_PREFIX_MAX = 100;

export function defaultRange(isTerminal: boolean): MetricsWindow {
  return isTerminal ? TERMINAL_DEFAULT_RANGE : LIVE_DEFAULT_RANGE;
}

export function defaultView(isTerminal: boolean): MetricsViewState {
  return {
    range: defaultRange(isTerminal), granularity: DEFAULT_GRANULARITY, split: "none",
    labels: { prefix: "", limit: DEFAULT_LABEL_LIMIT }, report: { prefix: "", limit: DEFAULT_LABEL_LIMIT },
  };
}

/** The view a URL describes; anything unrecognised falls back to the default for that field. */
export function parseMetricsView(params: URLSearchParams, isTerminal: boolean): MetricsViewState {
  const d = defaultView(isTerminal);
  const range = params.get(PARAM_RANGE);
  const granularity = params.get(PARAM_GRANULARITY);
  const split = params.get(PARAM_SPLIT);
  return {
    range: RANGE_OPTIONS.some((o) => o.value === range) ? (range as MetricsWindow) : d.range,
    granularity: granularity === "15" || granularity === "30" || granularity === "60"
      ? (Number(granularity) as MetricsGranularity) : d.granularity,
    split: SPLIT_OPTIONS.some((o) => o.value === split) ? (split as MetricsSplit) : d.split,
    labels: selection(params.get(PARAM_LABEL), params.get(PARAM_TOP)),
    report: selection(params.get(PARAM_REPORT_LABEL), params.get(PARAM_REPORT_TOP)),
  };
}

function selection(prefix: string | null, top: string | null): LabelSelection {
  return {
    prefix: (prefix ?? "").slice(0, LABEL_PREFIX_MAX),
    limit: top === "10" || top === "20" || top === "50" ? (Number(top) as LabelLimit) : top === "all" ? "all" : DEFAULT_LABEL_LIMIT,
  };
}

/** The URL for a view: the given params with the four metrics keys set, or removed when at their default. */
export function writeMetricsView(params: URLSearchParams, view: MetricsViewState, isTerminal: boolean): URLSearchParams {
  const next = new URLSearchParams(params);
  const d = defaultView(isTerminal);
  setOrDelete(next, PARAM_RANGE, view.range === d.range ? null : view.range);
  setOrDelete(next, PARAM_GRANULARITY, view.granularity === d.granularity ? null : String(view.granularity));
  setOrDelete(next, PARAM_SPLIT, view.split === "none" ? null : view.split);
  setOrDelete(next, PARAM_LABEL, view.labels.prefix.trim() || null);
  setOrDelete(next, PARAM_TOP, view.labels.limit === DEFAULT_LABEL_LIMIT ? null : String(view.labels.limit));
  setOrDelete(next, PARAM_REPORT_LABEL, view.report.prefix.trim() || null);
  setOrDelete(next, PARAM_REPORT_TOP, view.report.limit === DEFAULT_LABEL_LIMIT ? null : String(view.report.limit));
  return next;
}

function setOrDelete(params: URLSearchParams, key: string, value: string | null) {
  if (value === null) params.delete(key);
  else params.set(key, value);
}

/** "14:02:15" in the viewer's zone; the date lives in the run's metadata. */
export function formatClock(sec: number): string {
  const d = new Date(sec * 1000);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const ss = String(d.getSeconds()).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}
