import { useCallback, useEffect, useState } from "react";

import { runsApi, type MetricsGranularity, type MetricsTimeseries, type MetricsWindow, type RunState } from "../api/runs";
import { useVisiblePolling, type PauseReason } from "./useVisiblePolling";

/**
 * Fetches the per-second timeseries for a run and re-polls
 * every {@link POLL_INTERVAL_MS} while the run is non-terminal. The
 * 5-second cadence matches the run-status poll on the page; we don't
 * gain much by polling faster (metrics-consumer writes within ~1 s of
 * the first JMeter sample anyway).
 *
 * <p>Polling pauses automatically when:
 * <ul>
 *   <li>{@code runState} is terminal — finished runs don't change.</li>
 *   <li>The browser tab hides — operator isn't watching.</li>
 * </ul>
 *
 * <p>Aborted in-flight fetches are silently dropped (the next tick
 * re-issues). The hook keeps the last-good {@link MetricsTimeseries}
 * snapshot in {@code data} across loading transitions so the chart
 * doesn't flash blank between polls.
 */
export const POLL_INTERVAL_MS = 5_000;

export type UseMetricsTimeseriesStatus =
  | { kind: "loading" }
  | { kind: "ok" }
  | { kind: "error"; message: string };

export interface UseMetricsTimeseriesResult {
  status: UseMetricsTimeseriesStatus;
  /** Last successful snapshot. Stays populated across error transitions. */
  data: MetricsTimeseries | null;
  lastUpdated: Date | null;
  isPaused: boolean;
  pauseReason: PauseReason;
}

export function useMetricsTimeseries(
  runId: string,
  runState: RunState | null,
  byRegion = false,
  window: MetricsWindow = "all",
  granularity?: MetricsGranularity,
): UseMetricsTimeseriesResult {
  const [status, setStatus]           = useState<UseMetricsTimeseriesStatus>({ kind: "loading" });
  const [data, setData]               = useState<MetricsTimeseries | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const fetchOnce = useCallback(() => {
    if (!runId) return new AbortController();
    const ctl = new AbortController();
    runsApi
      .timeseries(runId, ctl.signal, { byRegion, window, granularity })
      .then((next) => {
        setData(next);
        setLastUpdated(new Date());
        setStatus({ kind: "ok" });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        const message = err instanceof Error ? err.message : String(err);
        setStatus({ kind: "error", message });
      });
    return ctl;
  }, [runId, byRegion, window, granularity]);

  // Initial fetch on mount + whenever the runId changes.
  useEffect(() => {
    const ctl = fetchOnce();
    return () => ctl.abort();
  }, [fetchOnce]);

  // Background refresh — gated by runState terminal + browser tab visibility.
  const isTerminal = runState !== null && isTerminalRunState(runState);
  const delayMs = isTerminal ? null : POLL_INTERVAL_MS;
  const { isPaused, pauseReason } = useVisiblePolling(fetchOnce, delayMs, {
    name: `metricsTimeseries:${runId}`,
  });

  return { status, data, lastUpdated, isPaused, pauseReason };
}

export function isTerminalRunState(state: RunState): boolean {
  return state === "COMPLETED" || state === "FAILED" || state === "ABORTED";
}
