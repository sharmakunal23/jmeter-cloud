/**
 * Typed client for `GET /api/v1/platform/health` — the whole platform's health
 * as one tree, built by the hub (the only component that talks to every other
 * one): itself + its Oracle pools and cache, the metrics-consumer, the
 * document-service, and every data center (regional orchestrator + workers).
 * The UI never probes a regional or a data-plane service itself.
 */
export type PlatformStatus = "UP" | "DEGRADED" | "DOWN" | "UNKNOWN";

export type PlatformComponentKind =
  | "service" | "dependency" | "regions" | "region" | "regional-orchestrator" | "workers";

export interface PlatformHealthComponent {
  id: string;
  name: string;
  kind: PlatformComponentKind;
  status: PlatformStatus;
  /** One line: the reason when not UP, the interesting fact when UP. */
  detail?: string | null;
  url?: string | null;
  checkedAt?: string | null;
  latencyMs?: number | null;
  facts?: Record<string, unknown> | null;
  components?: PlatformHealthComponent[] | null;
}

export interface PlatformHealth {
  status: PlatformStatus;
  checkedAt: string;
  components: PlatformHealthComponent[];
}

export const platformHealthApi = {
  /** The hub's last snapshot; `refresh` asks it to probe now (bounded). */
  get: async (signal?: AbortSignal, refresh = false): Promise<PlatformHealth> => {
    const resp = await fetch(`/api/v1/platform/health${refresh ? "?refresh=true" : ""}`, {
      method: "GET", signal, headers: { Accept: "application/json" },
    });
    const text = await resp.text();
    if (!resp.ok) throw new Error(text || `request failed: HTTP ${resp.status}`);
    return JSON.parse(text) as PlatformHealth;
  },
};

/** What the page shows when the hub itself cannot be reached — the one case the tree cannot describe. */
export function hubUnreachable(message: string): PlatformHealth {
  return {
    status: "DOWN",
    checkedAt: new Date().toISOString(),
    components: [{
      id: "global-orchestrator", name: "Global orchestrator", kind: "service", status: "DOWN",
      detail: `unreachable from the browser: ${message}`,
    }],
  };
}

/** Every component in the tree that is not UP, depth-first — the "what needs attention" list. */
export function unhealthy(tree: PlatformHealth): PlatformHealthComponent[] {
  const out: PlatformHealthComponent[] = [];
  const walk = (c: PlatformHealthComponent) => {
    if (c.status !== "UP") out.push(c);
    c.components?.forEach(walk);
  };
  tree.components.forEach(walk);
  return out;
}
