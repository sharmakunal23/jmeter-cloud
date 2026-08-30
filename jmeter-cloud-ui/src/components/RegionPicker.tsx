import { useMemo, useState } from "react";

import {
  US_MAP_PATH,
  isCanonicalRegion,
  regionLabel,
  resolveRegionOptions,
} from "../regions";
import { usePlatformCapabilities } from "../hooks/usePlatformCapabilities";

/**
 * Placement picker — lets an operator choose which regions (or, on a
 * private cloud, which data centers) an application group's worker pool
 * uses, via a clickable US map + a synced checklist.
 *
 * <p>Toggling one on adds it; toggling off removes it. One that still has
 * provisioned workers is <em>locked</em> (can't be removed until drained).
 * At least one must stay selected. Any the group already has that aren't in
 * the deployment's list are surfaced separately so they can be cleaned up.
 *
 * <p>STATIC-FLEET Phase 7 — the option list comes from the deployment
 * (`GET /api/v1/platform/capabilities`) instead of the hardcoded four AWS
 * USA regions, and the map is dropped as soon as any option has no place on
 * it: `na-east` is not a point in Virginia, and a pin in the wrong spot is
 * worse than no map. The vocabulary follows the same signal — "Region" on
 * AWS, "Data center" on a private cloud.
 *
 * <p>The parent computes the add/remove diff from {@code current} vs the
 * submitted selection and applies it (PUT to add, DELETE to remove).
 */

export interface RegionPickerProps {
  /** The group whose pool is being placed — shown in the title. */
  groupName: string;
  /** Region ids currently configured for the group. */
  current: string[];
  /** Regions that can't be removed because they still have workers. */
  lockedRegions?: Set<string>;
  busy?: boolean;
  onSubmit: (selected: string[]) => void | Promise<void>;
  onCancel: () => void;
}

export function RegionPicker({
  groupName, current, lockedRegions = new Set(), busy = false, onSubmit, onCancel,
}: RegionPickerProps) {
  const [selected, setSelected] = useState<Set<string>>(() => new Set(current));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const { regions: deploymentRegions, regionNoun } = usePlatformCapabilities();

  const { options, showMap } = useMemo(
    () => resolveRegionOptions(deploymentRegions, current),
    [deploymentRegions, current],
  );

  /**
   * Ids the group carries that this deployment doesn't offer. With a
   * configured list that means "not in the list"; without one it falls back
   * to the AWS-canonical check, which is what it meant before Phase 7.
   */
  const legacyRegions = useMemo(
    () => (deploymentRegions.length > 0
      ? current.filter((r) => !deploymentRegions.includes(r))
      : current.filter((r) => !isCanonicalRegion(r))),
    [current, deploymentRegions],
  );
  /** Options rendered in the checklist — the deployment's, minus the legacy strays. */
  const listOptions = useMemo(
    () => options.filter((o) => !legacyRegions.includes(o.id)),
    [options, legacyRegions],
  );

  function toggle(id: string) {
    // A locked region (has workers) can't be deselected — drain first.
    if (lockedRegions.has(id) && selected.has(id)) return;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const added = useMemo(
    () => [...selected].filter((r) => !current.includes(r)).sort(),
    [selected, current],
  );
  const removed = useMemo(
    () => current.filter((r) => !selected.has(r)).sort(),
    [selected, current],
  );
  const changed = added.length > 0 || removed.length > 0;
  const tooFew = selected.size === 0;
  const canSave = changed && !tooFew && !busy && !saving;

  async function handleSave() {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit([...selected]);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="modal__overlay" role="presentation" onClick={onCancel}>
      <div
        className="modal modal--regions"
        role="dialog"
        aria-modal="true"
        aria-labelledby="regionPickerTitle"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <h3 id="regionPickerTitle">
            Manage {regionNoun({ plural: true })}{" "}
            <span className="modal__titleApp mono">{groupName}</span>
          </h3>
          <button type="button" className="btn btn--ghost" onClick={onCancel} aria-label="Close">×</button>
        </header>

        <div className="modal__body regionPicker">
          {/* US map — clickable pins. Rendered only when every option has a
              real place on it (see resolveRegionOptions). */}
          {showMap && (
            <svg
              className="regionMap"
              viewBox="0 0 960 600"
              role="group"
              aria-label="USA region map"
            >
              <path className="regionMap__land" d={US_MAP_PATH} />
              {listOptions.map((r) => {
                const isSel = selected.has(r.id);
                const isLocked = lockedRegions.has(r.id) && isSel;
                return (
                  <g
                    key={r.id}
                    className={`regionPin ${isSel ? "regionPin--on" : ""} ${isLocked ? "regionPin--locked" : ""}`}
                    transform={`translate(${r.x} ${r.y})`}
                    role="checkbox"
                    aria-checked={isSel}
                    aria-label={`${r.label} (${r.id})${isLocked ? " — locked, has workers" : ""}`}
                    tabIndex={0}
                    onClick={() => toggle(r.id)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(r.id); }
                    }}
                  >
                    <circle className="regionPin__halo" r={26} />
                    <circle className="regionPin__dot" r={13} />
                    {isLocked && <text className="regionPin__lock" y={5}>🔒</text>}
                    <text className="regionPin__label" y={-30}>{r.label}</text>
                    <text className="regionPin__id" y={44}>{r.id}</text>
                  </g>
                );
              })}
            </svg>
          )}

          {/* Checklist (form view) — synced with the map when there is one,
              and the sole control when there isn't. */}
          <ul className="regionChecklist" aria-label={`${regionNoun()} checklist`}>
            {listOptions.map((r) => {
              const isSel = selected.has(r.id);
              const isLocked = lockedRegions.has(r.id) && isSel;
              return (
                <li key={r.id} className={`regionChecklist__row ${isSel ? "is-selected" : ""}`}>
                  <label>
                    <input
                      type="checkbox"
                      checked={isSel}
                      disabled={isLocked}
                      onChange={() => toggle(r.id)}
                    />
                    <span className="regionChecklist__label">{r.label}</span>
                    <span className="regionChecklist__id mono ink-soft">{r.id}</span>
                  </label>
                  {isLocked && (
                    <span className="chip chip--warn" title="Has provisioned workers — drain them to remove">
                      has workers
                    </span>
                  )}
                </li>
              );
            })}
          </ul>

          {legacyRegions.length > 0 && (
            <div className="regionPicker__legacy">
              <small className="ink-soft">
                Not offered by this deployment — toggle off to remove:
              </small>
              <div className="regionPicker__legacyChips">
                {legacyRegions.map((r) => {
                  const isSel = selected.has(r);
                  const isLocked = lockedRegions.has(r) && isSel;
                  return (
                    <button
                      key={r}
                      type="button"
                      className={`chip ${isSel ? "chip--warn" : ""}`}
                      disabled={isLocked}
                      onClick={() => toggle(r)}
                      title={isLocked ? "Has workers — drain to remove" : (isSel ? "Click to remove" : "Removed")}
                      style={!isSel ? { textDecoration: "line-through", opacity: 0.55 } : undefined}
                    >
                      {regionLabel(r)} {isSel ? "×" : ""}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Change summary. */}
          <div className="regionPicker__summary" aria-live="polite">
            {!changed ? (
              <span className="ink-soft">No changes.</span>
            ) : (
              <>
                {added.length > 0 && (
                  <span className="chip chip--ok">+ {added.length} added</span>
                )}
                {removed.length > 0 && (
                  <span className="chip chip--err">− {removed.length} removed</span>
                )}
                <span className="ink-soft">
                  {[...added.map((r) => `+${regionLabel(r)}`), ...removed.map((r) => `−${regionLabel(r)}`)].join(", ")}
                </span>
              </>
            )}
            {tooFew && (
              <p className="text--error" style={{ fontSize: "0.78rem", margin: "0.3rem 0 0" }}>
                Keep at least one {regionNoun()}.
              </p>
            )}
          </div>

          {error && <div className="formError" role="alert">{error}</div>}
        </div>

        <footer className="modal__footer">
          <button type="button" className="btn" onClick={onCancel} disabled={saving}>Cancel</button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={handleSave}
            disabled={!canSave}
            aria-busy={saving}
          >
            {saving ? "Saving…" : `Save ${regionNoun({ plural: true })}`}
          </button>
        </footer>
      </div>
    </div>
  );
}
