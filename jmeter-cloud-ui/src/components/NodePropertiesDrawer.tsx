import { useState } from "react";

import { Modal } from "./Modal";

/**
 * Per-node JMeter properties editor. Triggered
 * from {@code <NodeVisualizationPanel>} when the operator clicks a
 * node icon. Edits a single pod's property map; "Apply to all in
 * region" promotes the current rows to every pod within the region
 * for the common case where one set of properties covers the whole
 * region.
 *
 * <p>Validation mirrors the local-orchestrator's server-side rules:
 * keys match {@code [A-Za-z_][A-Za-z0-9_.]{0,63}}; values ≤ 256 chars
 * with no control characters. Surfacing the same constraint client-
 * side avoids a server round-trip for typos.
 */

const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_.]{0,63}$/;
const MAX_VALUE_LENGTH = 256;

export interface NodePropertiesDrawerProps {
  region: string;
  nodeIndex: number;
  totalNodesInRegion: number;
  /** Pre-resolved worker label, e.g. `checkout-svc-local-east-1-worker-1`.
   *  Passed in so the drawer doesn't have to know about app + format
   *  conventions — the page is the single source of truth. */
  workerName: string;
  initialProperties: Record<string, string>;
  onSave: (props: Record<string, string>, applyToAll: boolean) => void;
  onClose: () => void;
}

interface Row {
  key: string;
  value: string;
}

export function NodePropertiesDrawer({
  region,
  nodeIndex: _nodeIndex,  // kept on the props contract for callers; the drawer renders by workerName now
  totalNodesInRegion,
  workerName,
  initialProperties,
  onSave,
  onClose,
}: NodePropertiesDrawerProps) {
  const [rows, setRows] = useState<Row[]>(() =>
    Object.entries(initialProperties).map(([key, value]) => ({ key, value })));
  const [applyToAll, setApplyToAll] = useState(false);

  // Snapshot the initial map for the dirty-check below. The drawer is
  // remounted each open so this captures the value at open time.
  const initialKey = JSON.stringify(initialProperties);

  // Validate every row; per-row errors are rendered inline; the
  // "Save" button is disabled when any row is invalid or duplicates
  // exist.
  const seenKeys = new Set<string>();
  const rowErrors = rows.map((r) => {
    if (!r.key.trim()) return "key is required";
    if (!KEY_PATTERN.test(r.key)) return "key must match [A-Za-z_][A-Za-z0-9_.]{0,63}";
    if (seenKeys.has(r.key)) return `duplicate key: ${r.key}`;
    seenKeys.add(r.key);
    if (r.value.length > MAX_VALUE_LENGTH) return `value > ${MAX_VALUE_LENGTH} chars`;
    for (let i = 0; i < r.value.length; i++) {
      const code = r.value.charCodeAt(i);
      if (code < 0x20 || code === 0x7f) return "value contains control character";
    }
    return null;
  });
  const allRowsValid = rowErrors.every((e) => e === null);

  // Build the "if-saved-now" map and compare against the initial snapshot.
  // Save is only enabled when:
  //   1. every row is valid, AND
  //   2. the resulting map differs from initialProperties OR
  //      apply-to-all is checked (broadcasting unchanged values to siblings
  //      is still a meaningful save, so don't lock the operator out).
  const candidateProps: Record<string, string> = {};
  for (const r of rows) {
    if (!r.key.trim()) continue;
    candidateProps[r.key] = r.value;
  }
  const isDirty = JSON.stringify(candidateProps) !== initialKey;
  const canSave = allRowsValid && (isDirty || applyToAll);

  function handleSave() {
    if (!canSave) return;
    onSave(candidateProps, applyToAll);
  }

  function setKey(idx: number, key: string) {
    setRows((prev) => prev.map((r, i) => (i === idx ? { ...r, key } : r)));
  }
  function setValue(idx: number, value: string) {
    setRows((prev) => prev.map((r, i) => (i === idx ? { ...r, value } : r)));
  }
  function remove(idx: number) {
    setRows((prev) => prev.filter((_, i) => i !== idx));
  }
  function add() {
    setRows((prev) => [...prev, { key: "", value: "" }]);
  }

  return (
    <Modal
      title="Worker properties"
      infoTip="Passed to this worker's JMeter as -JKEY=VALUE at launch, overriding the run's global properties for this worker only."
      width="form"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={handleSave}
            disabled={!canSave}
          >
            Save
          </button>
        </>
      }
    >
      <p style={{ margin: 0 }}>
        <span className="mono">{workerName}</span> in <span className="mono">{region}</span>
      </p>

      <table className="propsEditor">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th aria-label="remove" />
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={3} className="ink-soft" style={{ textAlign: "center", padding: "0.6rem" }}>
                No properties yet. Click "+ Add property" to add one.
              </td>
            </tr>
          )}
          {rows.map((r, idx) => (
            <tr key={idx} className={rowErrors[idx] ? "propsEditor__row--invalid" : ""}>
              <td>
                <input
                  type="text"
                  value={r.key}
                  onChange={(e) => setKey(idx, e.target.value)}
                  placeholder="USER_OFFSET"
                  maxLength={64}
                  aria-label="property key"
                />
              </td>
              <td>
                <input
                  type="text"
                  value={r.value}
                  onChange={(e) => setValue(idx, e.target.value)}
                  placeholder="0"
                  maxLength={MAX_VALUE_LENGTH}
                  aria-label="property value"
                />
              </td>
              <td>
                <button
                  type="button"
                  className="btn btn--ghost"
                  onClick={() => remove(idx)}
                  aria-label="remove row"
                >
                  ×
                </button>
              </td>
              {rowErrors[idx] && (
                <td colSpan={3} className="text--error" style={{ fontSize: "0.78rem" }}>
                  {rowErrors[idx]}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      <button type="button" className="btn" onClick={add}>+ Add property</button>

      {totalNodesInRegion > 1 && (
        <label className="filterToggle">
          <input
            type="checkbox"
            checked={applyToAll}
            onChange={(e) => setApplyToAll(e.target.checked)}
          />
          Apply same properties to all {totalNodesInRegion} workers in {region}
        </label>
      )}
    </Modal>
  );
}
