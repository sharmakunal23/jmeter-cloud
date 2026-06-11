import type { Application, HealthStatus } from "../api/applications";
import { formatRelative } from "../lib/time";

/**
 * Reusable per-application health pill. Sourced from {@code lastHealthStatus}
 * (refreshed every ~30s by `ApplicationHealthPoller` server-side).
 *
 * <p>Originally inline on `<ApplicationsListPage>`; extracted in
 * Phase 5b so the Capacity list can render the same badge on each app
 * row without duplicating the styling + tooltip logic.
 */
export function HealthBadge({ app, compact = false }: { app: Application; compact?: boolean }) {
  const status: HealthStatus = app.lastHealthStatus ?? "UNKNOWN";
  const checked = app.lastHealthCheckedAt
    ? `last checked ${formatRelative(app.lastHealthCheckedAt)}`
    : "never checked";
  const failedDetail = (app.lastHealthDetails ?? [])
    .filter((d) => !d.ok)
    .map((d) => `${d.url} ${d.error ?? "fail"}`)
    .join("\n");
  const endpointCount = app.healthEndpoints.length;
  const title = `${status}: ${endpointCount} endpoint${endpointCount === 1 ? "" : "s"}, ${checked}${failedDetail ? "\n\n" + failedDetail : ""}`;
  return (
    <span
      className={`healthBadge healthBadge--${status.toLowerCase()} ${compact ? "healthBadge--compact" : ""}`}
      title={title}
      aria-label={`health: ${status.toLowerCase()}`}
    >
      <span className="healthBadge__dot" aria-hidden="true" />
      {status}
    </span>
  );
}
