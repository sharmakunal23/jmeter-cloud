import { useEffect, useState } from "react";

import { aiApi, type AiStatus } from "../api/ai";

/**
 * One-shot probe of `GET /api/v1/ai/status` so AI surfaces can
 * decide whether to render their buttons at all (hidden when no API key is
 * configured, so they never 503 on click — the local-dev concern).
 *
 * <p>Module-memoized: the status is the same for the whole session, so the
 * first mount fetches and every later mount reuses the resolved promise — no
 * refetch storm when several panels mount. Resilient by construction: any
 * failure (or absent `fetch`, as in the jsdom test env) resolves to
 * {@code enabled:false}, so a panel that consults this simply renders nothing
 * rather than erroring.
 */
const DISABLED: AiStatus = { enabled: false, model: "" };

let cached: Promise<AiStatus> | null = null;

function loadStatus(): Promise<AiStatus> {
  if (cached) return cached;
  if (typeof fetch === "undefined") {
    cached = Promise.resolve(DISABLED);
    return cached;
  }
  cached = aiApi.status().catch(() => DISABLED);
  return cached;
}

/** Test-only: drop the memoized probe so each test starts clean. */
export function __resetAiStatusCache(): void {
  cached = null;
}

export interface UseAiStatusResult {
  /** True once the probe resolved AND a key is configured. */
  enabled: boolean;
  /** Model id (empty until resolved / when disabled). */
  model: string;
  /** True until the probe resolves. */
  loading: boolean;
}

export function useAiStatus(): UseAiStatusResult {
  const [status, setStatus] = useState<AiStatus | null>(null);

  useEffect(() => {
    let active = true;
    loadStatus().then((s) => {
      if (active) setStatus(s);
    });
    return () => {
      active = false;
    };
  }, []);

  return {
    enabled: status?.enabled ?? false,
    model: status?.model ?? "",
    loading: status === null,
  };
}
