import { useMemo } from "react";
import type { Run } from "../api/runs";

/**
 * Animated region pill, replaces the legacy "us-east-1 (1) + us-west-2 (1)"
 * text rendering. Visual shape matches the existing `.badge` (state badge) for
 * parity; adds a colored dot prefix whose color is deterministic per region
 * name (so the same region always gets the same hue across the app).
 *
 * <p>Drops the per-region count by design — operators care about which regions
 * a run touched, not the per-region pod count (which lives on the Fleet
 * column or the run-detail page already).
 */
export function RegionBadge({ name }: { name: string }) {
    const colorIdx = useMemo(() => hashRegionToColor(name), [name]);
    return (
        <span
            className={`regionBadge regionBadge--c${colorIdx}`}
            role="status"
            aria-label={`region: ${name}`}
            data-region={name}
        >
            <span className="regionBadge__dot" aria-hidden="true" />
            <span className="regionBadge__name">{name}</span>
        </span>
    );
}

/**
 * Multi-region wrapper. Walks the run's fleet members to find the
 * distinct regions that actually carried work; renders one pill per region.
 * Falls back to {@code originRegion} when no members exist yet (skeleton state).
 *
 * <p>Overflow: at &gt; {@code MAX_VISIBLE} (5 by default), shows the first
 * MAX_VISIBLE pills + a `+N more` summary pill carrying the overflow names
 * in its title attribute for hover / SR access.
 */
export function RegionBadgeList({ run, maxVisible = MAX_VISIBLE }: {
    run: Run;
    maxVisible?: number;
}) {
    const regions = useMemo(() => deriveRegions(run), [run]);

    if (regions.length === 0) {
        return <span className="ink-soft">—</span>;
    }

    const visible = regions.slice(0, maxVisible);
    const overflow = regions.slice(maxVisible);

    return (
        <span
            className="regionBadgeList"
            role="list"
            aria-label={`regions: ${regions.join(", ")}`}
        >
            {visible.map((r) => (
                <span role="listitem" key={r}>
                    <RegionBadge name={r} />
                </span>
            ))}
            {overflow.length > 0 && (
                <span
                    className="regionBadge regionBadge--overflow"
                    title={overflow.join(", ")}
                    aria-label={`+${overflow.length} more: ${overflow.join(", ")}`}
                    role="listitem"
                >
                    +{overflow.length} more
                </span>
            )}
        </span>
    );
}

/** Visible-region cap before overflow. Exported for tests. */
export const MAX_VISIBLE = 5;

/**
 * Deterministic hash → palette index. Uses sum-of-char-codes mod palette
 * length — stable, fast, no dependency. Same region always lands on the
 * same color across runs / browsers / sessions.
 */
export function hashRegionToColor(name: string): number {
    let sum = 0;
    for (let i = 0; i < name.length; i++) {
        sum = (sum + name.charCodeAt(i)) | 0;
    }
    return Math.abs(sum) % PALETTE_SIZE;
}

/** Palette size = 6 distinguishable hues. Defined here so tests can import it. */
export const PALETTE_SIZE = 6;

/**
 * Derive the distinct, sorted region list from a Run. Sorted alphabetically
 * so two adjacent rows with the same regions render identically (no jitter
 * across polls when fleetMembers' insertion order changes).
 */
export function deriveRegions(run: Run): string[] {
    const members = run.fleetMembers ?? [];
    if (members.length === 0) {
        return run.originRegion ? [run.originRegion] : [];
    }
    const set = new Set<string>();
    for (const m of members) set.add(m.region);
    return [...set].sort((a, b) => a.localeCompare(b));
}
