import { useCallback, useEffect, useState } from "react";

import { runsApi, type MetricsTimeseriesBatch, type RunState } from "../api/runs";
import { useVisiblePolling, type PauseReason } from "./useVisiblePolling";

/**
 * Fetches the batched timeseries for two runs and re-polls
 * every {@link POLL_INTERVAL_MS} as long as <i>at least one</i> of the
 * two runs is non-terminal. Once both runs have settled the polling
 * stops — comparing two finished runs is a static view.
 *
 * <p>Mirrors {@link useMetricsTimeseries} in shape so the comparison
 * panel surfaces the same loading / error / paused affordances as the
 * single-run panel. Keeps the last-good {@link MetricsTimeseriesBatch}
 * snapshot in {@code data} across loading transitions so the chart
 * doesn't blank between polls.
 */
export const POLL_INTERVAL_MS = 5_000;

export type UseTwoRunTimeseriesStatus =
  | { kind: "loading" }
  | { kind: "ok" }
  | { kind: "error"; message: string };

export interface UseTwoRunTimeseriesResult {
  status: UseTwoRunTimeseriesStatus;
  /** Last successful snapshot. Stays populated across error transitions. */
  data: MetricsTimeseriesBatch | null;
  lastUpdated: Date | null;
  isPaused: boolean;
  pauseReason: PauseReason;
}

export function useTwoRunTimeseries(
  runIdA: string,
  runIdB: string,
  runStateA: RunState | null,
  runStateB: RunState | null,
): UseTwoRunTimeseriesResult {
  const [status, setStatus]           = useState<UseTwoRunTimeseriesStatus>({ kind: "loading" });
  const [data, setData]               = useState<MetricsTimeseriesBatch | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  const fetchOnce = useCallback(() => {
    if (!runIdA || !runIdB) return new AbortController();
    const ctl = new AbortController();
    runsApi
      .timeseriesBatch(runIdA, runIdB, ctl.signal)
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
  }, [runIdA, runIdB]);

  // Re-fetch whenever either id changes (user picked different runs).
  useEffect(() => {
    const ctl = fetchOnce();
    return () => ctl.abort();
  }, [fetchOnce]);

  // Background refresh — gated by terminal-state of BOTH runs (if
  // either is still moving, keep polling) + tab visibility.
  const aTerminal = runStateA !== null && isTerminalRunState(runStateA);
  const bTerminal = runStateB !== null && isTerminalRunState(runStateB);
  const bothTerminal = aTerminal && bTerminal;
  const delayMs = bothTerminal ? null : POLL_INTERVAL_MS;
  const { isPaused, pauseReason } = useVisiblePolling(fetchOnce, delayMs, {
    name: `twoRunTimeseries:${runIdA}:${runIdB}`,
  });

  return { status, data, lastUpdated, isPaused, pauseReason };
}

function isTerminalRunState(state: RunState): boolean {
  return state === "COMPLETED" || state === "FAILED" || state === "ABORTED";
}
