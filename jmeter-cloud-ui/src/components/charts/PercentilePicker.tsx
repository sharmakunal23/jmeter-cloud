import { PERCENTILE_LABELS, PERCENTILE_ORDER, type Percentile } from "../../lib/percentiles";

/**
 * Average / P90 / P95 / P99 for a split response-time chart, as one toggle
 * group. Belongs in the section header (`MetricsSection`'s `controls`), not
 * above the chart: a picker sitting on one chart makes that column taller than
 * its neighbour, and `MetricsSection` renders controls only while the section
 * is open — so the buttons cannot be reached when the charts they steer are
 * collapsed.
 */
export function PercentilePicker({ value, onChange, label = "Response time percentile" }: {
  value: Percentile;
  onChange: (next: Percentile) => void;
  /** Accessible name; distinguishes the two Metrics tabs' pickers in a test. */
  label?: string;
}) {
  return (
    <div className="percentilePicker" role="group" aria-label={label}>
      {PERCENTILE_ORDER.map((p) => (
        <button
          key={p}
          type="button"
          className={`btn btn--ghost btn--sm${value === p ? " isActive" : ""}`}
          aria-pressed={value === p}
          onClick={() => onChange(p)}
        >
          {PERCENTILE_LABELS[p]}
        </button>
      ))}
    </div>
  );
}
