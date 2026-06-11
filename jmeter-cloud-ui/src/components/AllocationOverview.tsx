import type { FleetAllocationEntry } from "../api/runs";
import type { RegionCapacity } from "../api/regions";

/**
 * Track G (Step 30) — right-sidebar summary of the current allocation.
 * Mirrors the screenshot reference's "Overview" pane: total pods,
 * regions covered, per-region utilization bars, and a small legend
 * explaining the load-generator iconography.
 */
export interface AllocationOverviewProps {
  regions: RegionCapacity[];
  allocation: FleetAllocationEntry[];
}

export function AllocationOverview({ regions, allocation }: AllocationOverviewProps) {
  const totalClaimed = allocation.reduce((acc, e) => acc + e.count, 0);
  const regionsCovered = allocation.filter((e) => e.count > 0).length;

  // Per-region rows: claim count + idle capacity bar.
  const claimsByRegion = new Map<string, number>();
  for (const e of allocation) {
    claimsByRegion.set(e.region, (claimsByRegion.get(e.region) ?? 0) + e.count);
  }

  return (
    <aside className="overview">
      <h3 className="overview__title">Overview</h3>

      <dl className="overview__stats">
        <dt>Total workers</dt>
        <dd>{totalClaimed}</dd>
        <dt>Regions covered</dt>
        <dd>{regionsCovered} / {regions.length}</dd>
      </dl>

      <h4 className="overview__sectionHead">Capacity</h4>
      <ul className="overview__regionList">
        {regions.length === 0 && (
          <li className="ink-soft">no regions registered</li>
        )}
        {regions.map((r) => {
          const claimed = claimsByRegion.get(r.region) ?? 0;
          const denom = Math.max(1, r.idlePods);
          const claimedPct = Math.min(100, Math.round((claimed / denom) * 100));
          return (
            <li key={r.region} className="overview__regionRow">
              <div className="overview__regionLine">
                <span className="overview__regionName mono">{r.region}</span>
                <span className="ink-soft">{claimed}/{r.idlePods}</span>
              </div>
              <div className="overview__bar" aria-hidden="true">
                <span style={{ width: `${claimedPct}%` }} />
              </div>
            </li>
          );
        })}
      </ul>
    </aside>
  );
}
