/**
 * Three-tier capacity classification used by the
 * {@link import("../components/UtilizationLegend").UtilizationLegend}
 * AND the per-region card stripes in
 * {@link import("../components/NodeVisualizationPanel").NodeVisualizationPanel}.
 * Single source of truth so the legend's swatches always match the
 * card colors at a glance.
 *
 * Discrete tiers (not a smooth gradient) are deliberate: easier to
 * read at a distance, colorblind-friendly when paired with the
 * `(claimed / idle)` text the cards already render. Boundaries match
 * common operational rules of thumb — under 50 % is "plenty of room",
 * 80 %+ is "about to run out".
 *
 *   < 50 %       → "low"   (green, comfortable)
 *   50 % – 80 %  → "mid"   (amber, getting full)
 *   ≥ 80 %       → "high"  (red, near capacity)
 *
 * Edge cases:
 *   - claimed === 0 → always "low" (nothing taken from this region).
 *   - idle === 0 with claimed > 0 → "high" (we're claiming from a
 *     region with no available capacity — typically an over-allocation
 *     state that the launcher's validation should catch first).
 *   - idle === 0 with claimed === 0 → "low" (neutral; no signal either way).
 *   - claimed > idle → "high" (over-allocation).
 */
export type UtilizationTier = "low" | "mid" | "high";

export function utilizationTier(claimed: number, idle: number): UtilizationTier {
  if (claimed <= 0) return "low";
  if (idle <= 0)    return "high";
  const pct = claimed / idle;
  if (pct < 0.5) return "low";
  if (pct < 0.8) return "mid";
  return "high";
}
