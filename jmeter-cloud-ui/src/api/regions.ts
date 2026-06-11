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

export const regionsApi = {
  list: async (signal?: AbortSignal): Promise<RegionCapacity[]> => {
    const resp = await fetch("/api/v1/regions", { method: "GET", signal });
    const text = await resp.text();
    if (!resp.ok) {
      throw new Error(text || `request failed: HTTP ${resp.status}`);
    }
    return JSON.parse(text) as RegionCapacity[];
  },
};
