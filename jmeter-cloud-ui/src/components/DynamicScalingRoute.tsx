import type { ReactElement } from "react";
import { Navigate } from "react-router-dom";

import { usePlatformCapabilities } from "../hooks/usePlatformCapabilities";

/**
 * Guards routes that only apply when the control
 * plane provisions its own workers.
 *
 * <p>Redirects to `/applications` rather than rendering `<NotFoundPage>`:
 * the Capacity tab is not a broken URL, it is a real feature that this
 * deployment does not use, and a 404 would read as "the platform is
 * misconfigured". A bookmark or a shared link therefore lands somewhere
 * useful — on a static fleet, worker management lives under the
 * application's Data centers section.
 *
 * <p>Renders nothing while the capability probe is in flight, so a bookmark
 * never flashes the Capacity page before redirecting.
 */
export function DynamicScalingRoute({ children }: { children: ReactElement }) {
  const { dynamicScalingEnabled, loading } = usePlatformCapabilities();
  if (loading) return null;
  return dynamicScalingEnabled ? children : <Navigate to="/applications" replace />;
}
