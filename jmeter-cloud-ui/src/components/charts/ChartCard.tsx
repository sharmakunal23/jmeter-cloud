import { TimeseriesChart } from "./TimeseriesChart";
import type { ChartSpec } from "./ChartModal";

/**
 * A chart in its card with the enlarge control in the corner. Pair it with
 * {@link ChartModal}: the card hands the same {@link ChartSpec} up, so the
 * enlarged copy is the same series and formatters at viewport size.
 *
 * <p>`syncKey` is optional — pass it to join a cursor-sync group (a run's
 * Metrics tab zooms its four charts together); leave it off where the charts
 * come from different runs and a shared crosshair would be a lie.
 */
export function ChartCard({ chart, height, syncKey, resetVersion, onEnlarge }: {
  chart: ChartSpec;
  height: number;
  syncKey?: string;
  resetVersion?: number;
  onEnlarge: (chart: ChartSpec) => void;
}) {
  return (
    <div className="chartCard">
      <button
        type="button"
        className="chartCard__enlarge"
        onClick={() => onEnlarge(chart)}
        title="Enlarge"
        aria-label={`Enlarge ${chart.title}`}
      >
        ⤢
      </button>
      <TimeseriesChart {...chart} height={height} syncKey={syncKey} resetVersion={resetVersion} />
    </div>
  );
}
