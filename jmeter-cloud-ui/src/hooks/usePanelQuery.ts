import { useEffect, useRef, useState, type DependencyList } from "react";

/**
 * One dashboard panel's data: fetched when the panel mounts, again whenever
 * its inputs (`deps`) or the shared refresh `tick` change, and not at all while
 * `enabled` is false — a collapsed section costs nothing. The last good
 * snapshot survives a failed refresh so a chart never flashes blank.
 */
export type PanelStatus =
  | { kind: "loading" }
  | { kind: "ok" }
  | { kind: "error"; message: string };

export interface PanelQuery<T> {
  status: PanelStatus;
  /** The last successful result; stays populated across errors. */
  data: T | null;
  lastUpdated: Date | null;
}

export function usePanelQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: DependencyList,
  tick: number,
  enabled = true,
): PanelQuery<T> {
  const [status, setStatus] = useState<PanelStatus>({ kind: "loading" });
  const [data, setData] = useState<T | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  // Always the latest fetcher, without making it a dependency — callers pass inline lambdas.
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (!enabled) return;
    const ctl = new AbortController();
    fetcherRef.current(ctl.signal)
      .then((next) => {
        if (ctl.signal.aborted) return;
        setData(next);
        setLastUpdated(new Date());
        setStatus({ kind: "ok" });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setStatus({ kind: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, enabled]);

  return { status, data, lastUpdated };
}
