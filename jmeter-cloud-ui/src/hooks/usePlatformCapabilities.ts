import { useEffect, useState } from "react";

import {
  DEFAULT_CAPABILITIES,
  platformApi,
  type PlatformCapabilities,
} from "../api/platform";

/**
 * One-shot probe of the deployment's capabilities,
 * consulted by every surface that only applies to one provisioning mode.
 *
 * <p>Module-memoized exactly like {@link useAiStatus}: the posture is fixed
 * for the life of the server process, so the first mount fetches and every
 * later mount reuses the resolved promise. Any failure resolves to
 * {@link DEFAULT_CAPABILITIES} — the historical, everything-visible
 * behaviour — so an older backend or a transient blip degrades to "looks
 * like it always did" rather than an empty shell. That is safe because the
 * server, not this hook, is what actually refuses.
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
  /** True when workers are operator-managed (`PROVISIONING_MODE=STATIC`). */
  isStaticFleet: boolean;
  /**
   * "Data center" / "data centers" / "Region" / … — the noun this
   * deployment uses for the placement axis. The API and schema always say
   * "region"; only user-facing copy changes.
   */
  regionNoun: (opts?: { plural?: boolean; capitalize?: boolean }) => string;
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
    isStaticFleet: resolved.provisioningMode === "STATIC",
    regionNoun: ({ plural = false, capitalize = false } = {}) => {
      const isDc = resolved.regionLabel === "dataCenter";
      const word = isDc
        ? plural ? "data centers" : "data center"
        : plural ? "regions" : "region";
      return capitalize ? word.charAt(0).toUpperCase() + word.slice(1) : word;
    },
  };
}
