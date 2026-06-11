/**
 * Typed client for the global-orchestrator's AI analysis API.
 * Mirrors the `ai` tag in `jmeter-global-orchestrator/api/openapi.yaml`.
 *
 * The API key lives only on the server; these endpoints are a thin proxy. AI
 * output is advisory, never authoritative — the UI copy says so next to every
 * surface. Reuses the shared {@link request} helper from `runs.ts` (same fetch /
 * error handling), so an AI error surfaces as a {@link GlobalOrchestratorError}
 * with codes `AI_DISABLED` / `AI_QUOTA_EXCEEDED` / `AI_UPSTREAM_ERROR`.
 */
import { request } from "./runs";

/** Whether AI analysis is configured on this server + which model it uses. */
export interface AiStatus {
  enabled: boolean;
  model: string;
}

/** One severity-tagged observation in a single-run insight. */
export interface RunInsightFinding {
  severity: "info" | "warn" | "crit";
  title: string;
  detail: string;
}

/** AI-1 — Claude's reading of a single run. `fromCache` ⇒ served without a new bill. */
export interface RunInsights {
  runId: string;
  model: string;
  promptVersion: string;
  summary: string;
  findings: RunInsightFinding[];
  tokensIn: number;
  tokensOut: number;
  cachedAt: string;
  fromCache: boolean;
}

/** One per-metric verdict in a comparison insight. */
export interface CompareInsightFinding {
  metric: string;
  verdict: "regression" | "improvement" | "no significant change" | string;
  delta: string;
}

/** AI-2 — Claude's reading of the delta between two runs (B relative to A). */
export interface CompareInsights {
  runIds: string[];
  model: string;
  promptVersion: string;
  summary: string;
  findings: CompareInsightFinding[];
  tokensIn: number;
  tokensOut: number;
  cachedAt: string;
  fromCache: boolean;
}

export const aiApi = {
  /** Feature probe — no quota consumed; the UI hides its buttons when `enabled=false`. */
  status: (signal?: AbortSignal): Promise<AiStatus> =>
    request<AiStatus>("GET", "/api/v1/ai/status", undefined, signal),

  /** AI-1 — generate (or fetch cached) insights for a single run. */
  runInsights: (
    runId: string,
    opts?: { fresh?: boolean },
    signal?: AbortSignal,
  ): Promise<RunInsights> =>
    request<RunInsights>(
      "POST",
      `/api/v1/runs/${encodeURIComponent(runId)}/insights${opts?.fresh ? "?fresh=true" : ""}`,
      undefined,
      signal,
    ),

  /** AI-2 — explain the delta between two runs (order preserved as A, B). */
  compareInsights: (
    idA: string,
    idB: string,
    opts?: { fresh?: boolean },
    signal?: AbortSignal,
  ): Promise<CompareInsights> => {
    const ids = `${encodeURIComponent(idA)},${encodeURIComponent(idB)}`;
    const fresh = opts?.fresh ? "&fresh=true" : "";
    return request<CompareInsights>(
      "POST",
      `/api/v1/runs/compare-insights?ids=${ids}${fresh}`,
      undefined,
      signal,
    );
  },
};
