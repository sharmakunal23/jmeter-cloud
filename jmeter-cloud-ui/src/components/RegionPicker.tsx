import { useMemo, useState } from "react";

import {
  USA_REGIONS,
  US_MAP_PATH,
  isCanonicalRegion,
  regionLabel,
} from "../regions";

/**
 * Region picker — lets an operator choose which of the 4 USA AWS regions an
 * application uses (1–4), via a clickable US map + a synced checklist.
 *
 * <p>Toggling a region on adds it; toggling off removes it. A region that
 * still has provisioned workers is <em>locked</em> (can't be removed until
 * drained). At least one region must stay selected. Any non-canonical
 * "legacy" regions the app already has (dummy data) are listed separately so
 * they can be cleaned up, but they're not on the map.
 *
 * <p>The parent computes the add/remove diff from {@code current} vs the
 * submitted selection and applies it (PUT to add, DELETE to remove).
 */

export interface RegionPickerProps {
  appName: string;
  /** Region ids currently configured for the app. */
  current: string[];
  /** Regions that can't be removed because they still have workers. */
  lockedRegions?: Set<string>;
  busy?: boolean;
  onSubmit: (selected: string[]) => void | Promise<void>;
  onCancel: () => void;
}

export function RegionPicker({
  appName, current, lockedRegions = new Set(), busy = false, onSubmit, onCancel,
}: RegionPickerProps) {
  const [selected, setSelected] = useState<Set<string>>(() => new Set(current));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const legacyRegions = useMemo(
    () => current.filter((r) => !isCanonicalRegion(r)),
    [current],
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
            Manage regions <span className="modal__titleApp mono">{appName}</span>
          </h3>
          <button type="button" className="btn btn--ghost" onClick={onCancel} aria-label="Close">×</button>
        </header>

        <div className="modal__body regionPicker">
          {/* US map — clickable region pins. */}
          <svg
            className="regionMap"
            viewBox="0 0 960 600"
            role="group"
            aria-label="USA region map"
          >
            <path className="regionMap__land" d={US_MAP_PATH} />
            {USA_REGIONS.map((r) => {
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

          {/* Checklist (form view) — synced with the map. */}
          <ul className="regionChecklist" aria-label="region checklist">
            {USA_REGIONS.map((r) => {
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
              <small className="ink-soft">Legacy regions (not USA-canonical) — toggle off to remove:</small>
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
                Keep at least one region.
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
            {saving ? "Saving…" : "Save regions"}
          </button>
        </footer>
      </div>
    </div>
  );
}
