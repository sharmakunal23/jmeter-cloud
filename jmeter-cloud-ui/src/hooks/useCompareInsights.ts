import { useCallback, useEffect, useRef, useState } from "react";

import { aiApi, type CompareInsights } from "../api/ai";
import { toFriendlyError } from "./useRunInsights";

/**
 * On-demand hook driving the two-run comparison panel.
 * `generate()` fetches (server cache-first for terminal pairs); `regenerate()`
 * sends `?fresh=true`. Shares the error mapping with {@link useRunInsights}.
 */
export type CompareInsightsStatus =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ok" }
  | { kind: "error"; message: string; quotaHit: boolean };

export interface UseCompareInsightsResult {
  status: CompareInsightsStatus;
  data: CompareInsights | null;
  generate: () => void;
  regenerate: () => void;
}

export function useCompareInsights(idA: string, idB: string): UseCompareInsightsResult {
  const [status, setStatus] = useState<CompareInsightsStatus>({ kind: "idle" });
  const [data, setData] = useState<CompareInsights | null>(null);
  const ctlRef = useRef<AbortController | null>(null);

  const run = useCallback(
    (fresh: boolean) => {
      if (!idA || !idB) return;
      ctlRef.current?.abort();
      const ctl = new AbortController();
      ctlRef.current = ctl;
      setStatus({ kind: "loading" });
      aiApi
        .compareInsights(idA, idB, { fresh }, ctl.signal)
        .then((next) => {
          if (ctl.signal.aborted) return;
          setData(next);
          setStatus({ kind: "ok" });
        })
        .catch((err: unknown) => {
          if (ctl.signal.aborted) return;
          setStatus({ kind: "error", ...toFriendlyError(err) });
        });
    },
    [idA, idB],
  );

  const generate = useCallback(() => run(false), [run]);
  const regenerate = useCallback(() => run(true), [run]);

  useEffect(() => () => ctlRef.current?.abort(), []);

  return { status, data, generate, regenerate };
}
