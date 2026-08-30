import { useEffect, useState } from "react";

import { TimeseriesChart, type TimeseriesChartProps } from "./TimeseriesChart";

/**
 * One chart, enlarged: the same series and formatters as its card, drawn at
 * the viewport's width and ~60 % of its height in a modal. Escape, the ×
 * button and the backdrop close it. Not part of the page's cursor-sync group,
 * so hovering here moves nothing behind the modal.
 */
export type ChartSpec = Omit<TimeseriesChartProps, "height" | "syncKey" | "resetVersion">;

export interface ChartModalProps {
  chart: ChartSpec | null;
  onClose: () => void;
}

const MIN_HEIGHT = 320;

export function ChartModal({ chart, onClose }: ChartModalProps) {
  const [height, setHeight] = useState(() => modalHeight());

  useEffect(() => {
    if (!chart) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    const onResize = () => setHeight(modalHeight());
    window.addEventListener("keydown", onKey);
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", onResize);
    };
  }, [chart, onClose]);

  if (!chart) return null;

  return (
    <div className="modal__overlay" onClick={onClose}>
      <div
        className="modal modal--chart"
        role="dialog"
        aria-modal="true"
        aria-label={`${chart.title} — enlarged`}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <h3>{chart.title}</h3>
          <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close" autoFocus>×</button>
        </header>
        <div className="modal__body modal__body--chart">
          <TimeseriesChart {...chart} showTitle={false} height={height} />
        </div>
      </div>
    </div>
  );
}

function modalHeight(): number {
  if (typeof window === "undefined") return MIN_HEIGHT;
  return Math.max(MIN_HEIGHT, Math.round(window.innerHeight * 0.6));
}
