import { useEffect, useMemo, useState } from "react";

import { clustersApi, type ClusterStatus } from "../api/clusters";
import { usePlatformCapabilities } from "../hooks/usePlatformCapabilities";
import { Modal } from "./Modal";

/**
 * Cluster picker (CLUSTER-CAPACITY) — attaches an application group's worker
 * pool to registered clusters. The option list is the runtime cluster
 * registry (`GET /api/v1/regions/status`), each option showing how many
 * workers other groups have left to reserve; a group holds at most
 * `maxClustersPerGroup` clusters, and one that still has workers is locked
 * until drained. The parent computes the add/remove diff from {@code current}
 * vs the submitted selection and applies it (PUT to add, DELETE to remove).
 */

export interface RegionPickerProps {
  /** The group whose pool is being placed — shown in the title. */
  groupName: string;
  /** Cluster (region) ids currently attached to the group. */
  current: string[];
  /** Clusters that can't be removed because they still have workers. */
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
  const [clusters, setClusters] = useState<ClusterStatus[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const { maxClustersPerGroup } = usePlatformCapabilities();

  useEffect(() => {
    const controller = new AbortController();
    clustersApi.status(controller.signal)
      .then((rows) => setClusters(rows))
      .catch((e) => {
        if (!controller.signal.aborted) {
          setLoadError(e instanceof Error ? e.message : String(e));
        }
      });
    return () => controller.abort();
  }, []);

  /** Registry options plus any attached id the registry no longer lists (defensive). */
  const options = useMemo(() => {
    const rows = clusters ?? [];
    const known = new Set(rows.map((c) => c.region));
    const strays: ClusterStatus[] = current
      .filter((id) => !known.has(id))
      .map((id) => ({
        region: id, label: id, regionalUrl: "", maxWorkers: 0,
        reservedWorkers: 0, provisionedWorkers: 0, probing: false,
      }));
    return [...rows, ...strays];
  }, [clusters, current]);

  const atLimit = selected.size >= maxClustersPerGroup;

  function toggle(id: string) {
    // A locked cluster (has workers) can't be deselected — drain first.
    if (lockedRegions.has(id) && selected.has(id)) return;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else if (next.size < maxClustersPerGroup) next.add(id);
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
  // Detaching the last cluster is allowed: a group starts with none, the
  // backend supports it (DELETE …/capacity/{region} once drained), and
  // forbidding it would strand a group that needs to move clusters while at
  // the attach limit. It simply cannot launch until it attaches one again.
  const canSave = changed && !busy && !saving;

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

  const labelOf = (id: string) => options.find((o) => o.region === id)?.label ?? id;

  return (
    <Modal
      title={
        <>
          Manage clusters{" "}
          <span className="modal__titleApp mono">{groupName}</span>
        </>
      }
      infoTip={`Pick the clusters this group's worker pool uses — at most ${maxClustersPerGroup}, so groups never fight for the same data centers; one that still has workers is locked until drained.`}
      width="regions"
      onClose={onCancel}
      closeDisabled={saving}
    >
      <div className="modal__body regionPicker">
        {clusters === null && loadError === null && (
          <p className="ink-soft">Loading the cluster registry…</p>
        )}
        {loadError !== null && (
          <div className="formError" role="alert">Could not load the cluster registry: {loadError}</div>
        )}
        {clusters !== null && options.length === 0 && (
          <div className="emptyState emptyState--compact">
            <p>No clusters registered yet.</p>
            <p className="ink-soft">Register one on the Clusters page first.</p>
          </div>
        )}

        <ul className="regionChecklist" aria-label="cluster checklist">
          {options.map((c) => {
            const isSel = selected.has(c.region);
            const isLocked = lockedRegions.has(c.region) && isSel;
            const reservable = Math.max(0, c.maxWorkers - c.reservedWorkers);
            const blockedByLimit = !isSel && atLimit;
            return (
              <li key={c.region} className={`regionChecklist__row ${isSel ? "is-selected" : ""}`}>
                <label>
                  <input
                    type="checkbox"
                    checked={isSel}
                    disabled={isLocked || blockedByLimit}
                    onChange={() => toggle(c.region)}
                  />
                  <span className="regionChecklist__label">{c.label}</span>
                  <span className="regionChecklist__id mono ink-soft">{c.region}</span>
                  {c.maxWorkers > 0 && (
                    <span className="ink-soft" style={{ fontSize: "0.78rem" }}>
                      {reservable} of {c.maxWorkers} workers reservable
                    </span>
                  )}
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

        {/* Change summary. */}
        <div className="regionPicker__summary" aria-live="polite">
          {!changed ? (
            <span className="ink-soft">
              No changes. {selected.size} of {maxClustersPerGroup} clusters attached.
            </span>
          ) : (
            <>
              {added.length > 0 && (
                <span className="chip chip--ok">+ {added.length} added</span>
              )}
              {removed.length > 0 && (
                <span className="chip chip--err">− {removed.length} removed</span>
              )}
              <span className="ink-soft">
                {[...added.map((r) => `+${labelOf(r)}`), ...removed.map((r) => `−${labelOf(r)}`)].join(", ")}
              </span>
            </>
          )}
          {atLimit && (
            <p className="ink-soft" style={{ fontSize: "0.78rem", margin: "0.3rem 0 0" }}>
              {selected.size} of {maxClustersPerGroup} clusters attached — detach one to pick another.
            </p>
          )}
          {selected.size === 0 && (
            <p className="ink-soft" style={{ fontSize: "0.78rem", margin: "0.3rem 0 0" }}>
              No clusters attached — this group cannot launch runs until one is.
            </p>
          )}
        </div>

        {error && <div className="formError" role="alert">{error}</div>}
      </div>
      <Modal.Footer>
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>Cancel</button>
        <button
          type="button"
          className="btn btn--primary"
          onClick={handleSave}
          disabled={!canSave}
          aria-busy={saving}
        >
          {saving ? "Saving…" : "Save clusters"}
        </button>
      </Modal.Footer>
    </Modal>
  );
}
