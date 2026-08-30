import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";

import { parseMetricsView, writeMetricsView, type MetricsViewState } from "../lib/metricsView";

/**
 * The Metrics tab's view, read from and written to the page URL (replace, not
 * push — the browser's Back goes to the previous page, not the previous range).
 */
export function useMetricsView(isTerminal: boolean): [MetricsViewState, (patch: Partial<MetricsViewState>) => void] {
  const [params, setParams] = useSearchParams();
  const view = useMemo(() => parseMetricsView(params, isTerminal), [params, isTerminal]);
  const update = useCallback((patch: Partial<MetricsViewState>) => {
    setParams((prev) => writeMetricsView(prev, { ...parseMetricsView(prev, isTerminal), ...patch }, isTerminal),
      { replace: true });
  }, [setParams, isTerminal]);
  return [view, update];
}
