import { useEffect, useState } from "react";

import {
  DEFAULT_CAPABILITIES,
  platformApi,
  type PlatformCapabilities,
} from "../api/platform";

/**
 * One-shot probe of the deployment's capabilities — the registered clusters'
 * region ids and `maxClustersPerGroup` (CLUSTER-CAPACITY).
 *
 * <p>Module-memoized: the values move only when a cluster is registered, and
 * every surface that needs the live list polls `clustersApi.status()`
 * instead. Any failure resolves to {@link DEFAULT_CAPABILITIES} so an older
 * backend or a transient blip degrades gracefully — the server, not this
 * hook, is what actually refuses.
 */
let cached: Promise<PlatformCapabilities> | null = null;

function loadCapabilities(): Promise<PlatformCapabilities> {
  if (cached) return cached;
  if (typeof fetch === "undefined") {
    cached = Promise.resolve(DEFAULT_CAPABILITIES);
    return cached;
  }
  cached = platformApi.capabilities().catch(() => DEFAULT_CAPABILITIES);
  return cached;
}

/** Test-only: drop the memoized probe so each test starts clean. */
export function __resetPlatformCapabilitiesCache(): void {
  cached = null;
}

export interface UsePlatformCapabilitiesResult extends PlatformCapabilities {
  /** True until the probe resolves. */
  loading: boolean;
}

export function usePlatformCapabilities(): UsePlatformCapabilitiesResult {
  const [caps, setCaps] = useState<PlatformCapabilities | null>(null);

  useEffect(() => {
    let active = true;
    loadCapabilities().then((c) => {
      if (active) setCaps(c);
    });
    return () => {
      active = false;
    };
  }, []);

  const resolved = caps ?? DEFAULT_CAPABILITIES;
  return {
    ...resolved,
    loading: caps === null,
  };
}
