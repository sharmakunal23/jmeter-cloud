/**
 * Typed client for the global-orchestrator's per-region capacity rollup
 * — `GET /api/v1/regions`. The launcher polls this every 5 s so the
 * allocation widget reflects capacity changes from concurrent runs
 * launching / finishing.
 */

export interface RegionCapacity {
  region: string;
  totalPods: number;
  idlePods: number;
  lostPods: number;
}

/**
 * One row of `GET /api/v1/regions/status`: a region from the hub's
 * `REGIONS`, and — when it is routed through a regional orchestrator —
 * whether the last probe reached it. `reachable` is null before the first
 * probe and always null for a direct region.
 */
export interface RegionStatus {
  region: string;
  url?: string | null;
  routed: boolean;
  reachable?: boolean | null;
  lastSeenAt?: string | null;
  lastError?: string | null;
}

export const regionsApi = {
  list: async (signal?: AbortSignal): Promise<RegionCapacity[]> => {
    const resp = await fetch("/api/v1/regions", { method: "GET", signal });
    const text = await resp.text();
    if (!resp.ok) {
      throw new Error(text || `request failed: HTTP ${resp.status}`);
    }
    return JSON.parse(text) as RegionCapacity[];
  },

  /** Tolerant read: an older backend without the endpoint yields no rows, never an error. */
  status: async (signal?: AbortSignal): Promise<RegionStatus[]> => {
    const resp = await fetch("/api/v1/regions/status", { method: "GET", signal });
    if (!resp.ok) return [];
    const body: unknown = await resp.json().catch(() => []);
    return Array.isArray(body) ? (body as RegionStatus[]) : [];
  },
};
