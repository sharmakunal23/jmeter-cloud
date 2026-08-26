/**
 * Typed client for `GET /api/v1/platform/capabilities` — what this
 * deployment can do.
 *
 * <p>STATIC-FLEET Phase 7. The posture lives on the server, not in a
 * build-time `VITE_` flag: a browser flag cannot enforce anything (hiding
 * the Capacity tab would leave the spin endpoints live) and would force a
 * UI image rebuild per environment, breaking the one-image-serves-docker-
 * and-Kubernetes property. The server decides; this file reflects.
 */

export type ProvisioningMode = "DYNAMIC" | "STATIC";

/** What the UI should call the placement axis. See `regionNoun`. */
export type RegionLabel = "region" | "dataCenter";

export interface PlatformCapabilities {
  provisioningMode: ProvisioningMode;
  /** Whether the control plane may create / destroy workers. */
  dynamicScalingEnabled: boolean;
  /** Whether the recycler runs — gates the recycle-policy editor. */
  podRecyclingEnabled: boolean;
  /** Region ids this deployment uses; empty means "no override". */
  regions: string[];
  regionLabel: RegionLabel;
}

/**
 * Assumed posture when the endpoint can't be reached (older backend, or
 * the jsdom test env with no `fetch`). Deliberately the historical
 * behaviour: everything visible, nothing hidden. Failing open on the UI is
 * right because the server still refuses what it must — a stale browser can
 * show a Spin button, but pressing it gets a clean `409`.
 */
export const DEFAULT_CAPABILITIES: PlatformCapabilities = {
  provisioningMode: "DYNAMIC",
  dynamicScalingEnabled: true,
  podRecyclingEnabled: true,
  regions: [],
  regionLabel: "region",
};

export const platformApi = {
  async capabilities(signal?: AbortSignal): Promise<PlatformCapabilities> {
    const resp = await fetch("/api/v1/platform/capabilities", { signal });
    if (!resp.ok) {
      throw new Error(`capabilities failed: HTTP ${resp.status}`);
    }
    const body = (await resp.json()) as Partial<PlatformCapabilities>;
    // Tolerant read: a field the backend hasn't shipped yet falls back to the
    // historical behaviour rather than rendering an empty UI.
    return {
      ...DEFAULT_CAPABILITIES,
      ...body,
      regions: Array.isArray(body.regions) ? body.regions : [],
    };
  },
};
