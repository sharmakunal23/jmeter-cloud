import { useCallback, useEffect, useRef, useState } from "react";

import { aiApi, type RunInsights } from "../api/ai";
import { GlobalOrchestratorError } from "../api/runs";

/**
 * On-demand (not polling) hook driving the single-run
 * insights panel. `generate()` fetches (cache-first on the server for terminal
 * runs); `regenerate()` sends `?fresh=true` to bypass the cache and re-bill.
 * A 429 surfaces as a friendly "daily limit reached" message via `quotaHit`.
 */
export type RunInsightsStatus =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ok" }
  | { kind: "error"; message: string; quotaHit: boolean };

export interface UseRunInsightsResult {
  status: RunInsightsStatus;
  data: RunInsights | null;
  generate: () => void;
  regenerate: () => void;
}

export function useRunInsights(runId: string): UseRunInsightsResult {
  const [status, setStatus] = useState<RunInsightsStatus>({ kind: "idle" });
  const [data, setData] = useState<RunInsights | null>(null);
  const ctlRef = useRef<AbortController | null>(null);

  const run = useCallback(
    (fresh: boolean) => {
      if (!runId) return;
      ctlRef.current?.abort();
      const ctl = new AbortController();
      ctlRef.current = ctl;
      setStatus({ kind: "loading" });
      aiApi
        .runInsights(runId, { fresh }, ctl.signal)
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
    [runId],
  );

  const generate = useCallback(() => run(false), [run]);
  const regenerate = useCallback(() => run(true), [run]);

  // Abort an in-flight request on unmount.
  useEffect(() => () => ctlRef.current?.abort(), []);

  return { status, data, generate, regenerate };
}

/** Map an API error to an operator-facing message + a quota flag. */
export function toFriendlyError(err: unknown): { message: string; quotaHit: boolean } {
  if (err instanceof GlobalOrchestratorError) {
    if (err.code === "AI_QUOTA_EXCEEDED" || err.httpStatus === 429) {
      return {
        message: "Daily AI limit reached — try again tomorrow (resets at UTC midnight).",
        quotaHit: true,
      };
    }
    if (err.code === "AI_DISABLED") {
      return { message: "AI analysis isn't configured on this server.", quotaHit: false };
    }
    if (err.code === "AI_UPSTREAM_ERROR") {
      return { message: "The AI provider call failed — please try again.", quotaHit: false };
    }
    return { message: err.message, quotaHit: false };
  }
  return { message: err instanceof Error ? err.message : String(err), quotaHit: false };
}
