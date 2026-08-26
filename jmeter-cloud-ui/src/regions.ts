/**
 * Canonical AWS USA regions — the only regions this platform deploys to.
 * Single source of truth for the Capacity region picker (labels + map pins).
 * Region IDs match the AWS region codes used as `capacity.region` /
 * `pod.region` on the backend.
 */

export interface UsaRegion {
  /** AWS region code, e.g. "us-east-1". */
  id: string;
  /** AWS region name, e.g. "N. Virginia". */
  label: string;
  /** Short geographic hint for compact chips. */
  short: string;
  /** Marker position on the US-map SVG (viewBox `0 0 960 600`). */
  x: number;
  y: number;
}

export const USA_REGIONS: UsaRegion[] = [
  { id: "us-west-2", label: "Oregon",        short: "Oregon",      x: 120, y: 205 },
  { id: "us-west-1", label: "N. California", short: "California",  x: 150, y: 335 },
  { id: "us-east-2", label: "Ohio",          short: "Ohio",        x: 700, y: 245 },
  { id: "us-east-1", label: "N. Virginia",   short: "Virginia",    x: 805, y: 285 },
];

/** Max regions an application may use (the 4 USA regions). */
export const MAX_REGIONS = USA_REGIONS.length;

const BY_ID = new Map(USA_REGIONS.map((r) => [r.id, r]));

export function findRegion(id: string): UsaRegion | undefined {
  return BY_ID.get(id);
}

/** Human label for a region id, falling back to the raw id (legacy/dummy values). */
export function regionLabel(id: string): string {
  return BY_ID.get(id)?.label ?? id;
}

/** True when the id is one of the canonical 4 USA regions. */
export function isCanonicalRegion(id: string): boolean {
  return BY_ID.has(id);
}

/** One selectable placement option, map pin optional. */
export interface RegionOption {
  id: string;
  label: string;
  /** Map coordinates — present only for canonical AWS USA regions. */
  x?: number;
  y?: number;
}

export interface ResolvedRegionOptions {
  options: RegionOption[];
  /**
   * Whether to render the US map. False as soon as any option has no
   * geographic home on it — a pin in the wrong place is worse than no map,
   * and `na-east` is not a point in Virginia.
   */
  showMap: boolean;
}

/**
 * Resolves which placement options this deployment
 * offers.
 *
 * <p>The list used to be the hardcoded four AWS USA regions. A private
 * cloud names its data centers whatever it names them (`na-east`,
 * `na-west`, …), so the server supplies the list via
 * `GET /api/v1/platform/capabilities` and this turns it into options.
 *
 * @param configured deployment-supplied ids; empty means "no override",
 *                   which keeps the historical AWS-four behaviour
 * @param extra      ids the application already uses that may not be in the
 *                   configured list (legacy or hand-seeded rows) — surfaced
 *                   so they remain removable
 */
export function resolveRegionOptions(
  configured: string[],
  extra: string[] = [],
): ResolvedRegionOptions {
  const ids = configured.length > 0
    ? [...configured]
    : USA_REGIONS.map((r) => r.id);
  for (const id of extra) {
    if (!ids.includes(id)) ids.push(id);
  }
  const options: RegionOption[] = ids.map((id) => {
    const known = BY_ID.get(id);
    return known
      ? { id, label: known.label, x: known.x, y: known.y }
      : { id, label: id };
  });
  return { options, showMap: options.every((o) => o.x !== undefined) };
}

/**
 * Simplified continental-US silhouette for the picker map (viewBox
 * `0 0 960 600`). Not survey-grade — a recognizable outline (west coast,
 * Texas notch, Florida peninsula, Maine) good enough to place the 4 region
 * pins in their geographic quadrants.
 */
export const US_MAP_PATH =
  // Top / northern border (with the Great Lakes notch) → Maine.
  "M 80 130 L 150 110 L 250 100 L 360 96 L 470 96 L 520 100 " +
  "L 545 150 L 575 135 L 600 160 L 628 132 L 660 150 L 700 120 L 760 110 L 820 108 " +
  // Maine + the East coast down through the mid-Atlantic.
  "L 852 140 L 838 165 L 818 180 L 815 215 L 832 250 L 820 295 L 838 340 L 848 388 " +
  // Florida peninsula (down the Atlantic side, around the tip, up the Gulf side).
  "L 862 430 L 872 478 L 866 505 L 850 490 L 845 440 L 832 408 " +
  // Gulf coast → Texas → southern (Mexico) border.
  "L 790 432 L 720 452 L 660 468 L 632 452 L 618 492 L 588 535 L 556 512 L 545 470 " +
  "L 470 458 L 380 450 L 300 442 L 235 432 L 198 426 " +
  // West coast up through California / Oregon back to the NW.
  "L 165 392 L 140 350 L 122 300 L 110 240 L 96 180 L 84 150 Z";
