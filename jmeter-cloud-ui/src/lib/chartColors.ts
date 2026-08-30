/**
 * One palette for every chart, so a series keeps its colour across panels and
 * pages: Avg is always sky, P99 always pink, 5xx always red. Splits (regions,
 * applications, labels) take the categorical palette by sorted position; the
 * four USA regions keep fixed hues so us-east-1 is blue on every chart.
 */
export const SERIES_COLOR = {
  tps:      "#2563eb", // blue
  avg:      "#0ea5e9", // sky
  p90:      "#6366f1", // indigo
  p95:      "#7c3aed", // violet
  p99:      "#db2777", // pink
  error:    "#dc2626", // red
  http4xx:  "#f59e0b", // amber
  http5xx:  "#dc2626", // red
} as const;

export const CATEGORICAL_PALETTE: ReadonlyArray<string> = [
  "#2563eb", "#f59e0b", "#0d9488", "#7c3aed", "#db2777",
  "#65a30d", "#ea580c", "#0891b2", "#9333ea", "#475569",
  "#16a34a", "#b45309", "#1d4ed8", "#be123c", "#0f766e",
  "#7e22ce", "#a16207", "#334155", "#c026d3", "#047857",
];

const REGION_COLORS: Record<string, string> = {
  "us-east-1": "#2563eb",
  "us-east-2": "#7c3aed",
  "us-west-1": "#0d9488",
  "us-west-2": "#f59e0b",
};

/** Colour for the i-th member of a split (`key` first, for the fixed region hues). */
export function colorForKey(key: string, index: number): string {
  return REGION_COLORS[key] ?? CATEGORICAL_PALETTE[index % CATEGORICAL_PALETTE.length]!;
}
