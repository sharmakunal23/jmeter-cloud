import { useEffect, useState } from "react";

import { Modal } from "../Modal";
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
    const onResize = () => setHeight(modalHeight());
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [chart]);

  if (!chart) return null;

  return (
    <Modal title={chart.title} width="chart" onClose={onClose}>
      <div className="modal__body modal__body--chart">
        <TimeseriesChart {...chart} showTitle={false} height={height} />
      </div>
    </Modal>
  );
}

function modalHeight(): number {
  if (typeof window === "undefined") return MIN_HEIGHT;
  return Math.max(MIN_HEIGHT, Math.round(window.innerHeight * 0.6));
}
