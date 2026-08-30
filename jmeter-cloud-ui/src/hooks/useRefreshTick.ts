import { useCallback, useState } from "react";

import { useVisiblePolling, type PauseReason } from "./useVisiblePolling";

/**
 * A dashboard's refresh clock: `tick` advances every `intervalMs` while the
 * page is visible (null = never — a finished run), and every panel that reads
 * it refetches on the same beat, like Grafana's panels on one refresh. The
 * gates and their reasons come from {@link useVisiblePolling}.
 */
export interface RefreshTick {
  tick: number;
  /** Advance now — the manual refresh. */
  refresh: () => void;
  isPaused: boolean;
  pauseReason: PauseReason;
}

export function useRefreshTick(intervalMs: number | null, name = "refreshTick"): RefreshTick {
  const [tick, setTick] = useState(0);
  const refresh = useCallback(() => setTick((t) => t + 1), []);
  const { isPaused, pauseReason } = useVisiblePolling(refresh, intervalMs, { name });
  return { tick, refresh, isPaused, pauseReason };
}
