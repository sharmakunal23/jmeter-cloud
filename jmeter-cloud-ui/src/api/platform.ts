/**
 * Typed client for `GET /api/v1/platform/capabilities` — what this
 * deployment can do.
 *
 * <p>CLUSTER-CAPACITY: the deployment-wide STATIC/DYNAMIC posture is gone —
 * spun and declared workers coexist per pool. What remains deployment-wide is
 * the registered clusters' region ids (the runtime registry) and how many
 * clusters one group may reserve capacity on. Server-side on purpose: a
 * build-time `VITE_` flag cannot enforce anything and would break the
 * one-image-serves-docker-and-Kubernetes property.
 */

export interface PlatformCapabilities {
  /**
   * How many clusters one application group may reserve capacity on. The
   * cluster LIST is deliberately not here — it changes at runtime, so every
   * surface reads `clustersApi.status()` instead of a boot-time snapshot.
   */
  maxClustersPerGroup: number;
}

/**
 * Assumed shape when the endpoint can't be reached (older backend, or the
 * jsdom test env with no `fetch`). Failing open on the UI is right because
 * the server still refuses what it must.
 */
export const DEFAULT_CAPABILITIES: PlatformCapabilities = {
  maxClustersPerGroup: 2,
};

export const platformApi = {
  async capabilities(signal?: AbortSignal): Promise<PlatformCapabilities> {
    const resp = await fetch("/api/v1/platform/capabilities", { signal });
    if (!resp.ok) {
      throw new Error(`capabilities failed: HTTP ${resp.status}`);
    }
    const body = (await resp.json()) as Partial<PlatformCapabilities>;
    // Tolerant read: a field the backend hasn't shipped yet falls back rather
    // than rendering an empty UI.
    return { ...DEFAULT_CAPABILITIES, ...body };
  },
};
