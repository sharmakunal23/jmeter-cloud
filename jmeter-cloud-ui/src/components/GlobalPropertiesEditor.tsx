import { useMemo, useState } from "react";

/**
 * Fleet-wide JMeter property defaults.
 *
 * <p>Defaults snapshotted into every newly-added worker at click time.
 * Changing globals after a worker exists does NOT mutate that worker's
 * snapshot — the operator can rely on each worker's properties being
 * immutable from the moment of creation. The per-worker drawer
 * provides further per-instance overrides on top of the snapshot.
 *
 * <p>Validation mirrors the drawer (and the local-orchestrator's
 * server-side rules): keys match {@code [A-Za-z_][A-Za-z0-9_.]{0,63}};
 * values ≤ 256 chars with no control characters.
 */

const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_.]{0,63}$/;
const MAX_VALUE_LENGTH = 256;

export interface GlobalPropertiesEditorProps {
  value: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  /** Number of workers whose snapshot diverges from the *current*
   *  globals (older workers + drawer-edited workers). Surfaces as a
   *  reminder that changing globals here only affects future workers. */
  divergedCount?: number;
}

interface Row {
  key: string;
  value: string;
}

export function GlobalPropertiesEditor({
  value, onChange, divergedCount = 0,
}: GlobalPropertiesEditorProps) {
  // Local row state — keeps an entry for an in-flight blank row that
  // the operator hasn't filled in yet. We sync upward only when the
  // row is valid + complete.
  const initialRows: Row[] = useMemo(
    () => Object.entries(value).map(([k, v]) => ({ key: k, value: v })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const [rows, setRows] = useState<Row[]>(initialRows);

  const seen = new Set<string>();
  const rowErrors = rows.map((r) => {
    if (!r.key.trim() && !r.value) return null; // blank row in progress
    if (!r.key.trim()) return "key is required";
    if (!KEY_PATTERN.test(r.key)) return "key must match [A-Za-z_][A-Za-z0-9_.]{0,63}";
    if (seen.has(r.key)) return `duplicate key: ${r.key}`;
    seen.add(r.key);
    if (r.value.length > MAX_VALUE_LENGTH) return `value > ${MAX_VALUE_LENGTH} chars`;
    for (let i = 0; i < r.value.length; i++) {
      const code = r.value.charCodeAt(i);
      if (code < 0x20 || code === 0x7f) return "value contains control character";
    }
    return null;
  });

  function syncUp(nextRows: Row[]) {
    const props: Record<string, string> = {};
    const seenKeys = new Set<string>();
    for (let i = 0; i < nextRows.length; i++) {
      const r = nextRows[i];
      if (!r.key.trim() || rowErrors[i] != null) continue;
      if (seenKeys.has(r.key)) continue;
      seenKeys.add(r.key);
      props[r.key] = r.value;
    }
    onChange(props);
  }

  function setKey(idx: number, key: string) {
    setRows((prev) => {
      const next = prev.map((r, i) => (i === idx ? { ...r, key } : r));
      queueMicrotask(() => syncUp(next));
      return next;
    });
  }
  function setValue(idx: number, value: string) {
    setRows((prev) => {
      const next = prev.map((r, i) => (i === idx ? { ...r, value } : r));
      queueMicrotask(() => syncUp(next));
      return next;
    });
  }
  function remove(idx: number) {
    setRows((prev) => {
      const next = prev.filter((_, i) => i !== idx);
      queueMicrotask(() => syncUp(next));
      return next;
    });
  }
  function add() {
    setRows((prev) => [...prev, { key: "", value: "" }]);
  }

  return (
    <section className="globalProps" aria-label="Global properties">
      {/* UX22 — "+ Add property" moved from below the table to the right
          side of the header so the section's primary action lives at
          the top-right where operators expect it. Title-left, action-
          right mirrors the standard section-header pattern across the
          rest of the app. */}
      <header className="globalProps__head">
        <div className="globalProps__headText">
          <h3 className="globalProps__title">Global properties</h3>
          <small className="ink-soft">
            Snapshotted into each new worker — existing workers keep their original values.
          </small>
        </div>
        <button
          type="button"
          className="btn btn--ghost globalProps__addBtn"
          onClick={add}
        >+ Add property</button>
      </header>

      <table className="propsEditor">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th><span className="visuallyHidden">remove</span></th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={3} className="ink-soft globalProps__empty">
                No global properties for new workers.
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
                  aria-label={`global property ${idx + 1} key`}
                />
              </td>
              <td>
                <input
                  type="text"
                  value={r.value}
                  onChange={(e) => setValue(idx, e.target.value)}
                  placeholder="0"
                  maxLength={MAX_VALUE_LENGTH}
                  aria-label={`global property ${idx + 1} value`}
                />
              </td>
              <td>
                <button
                  type="button"
                  className="btn btn--ghost"
                  onClick={() => remove(idx)}
                  aria-label={`remove global property ${idx + 1}`}
                >
                  ×
                </button>
              </td>
              {rowErrors[idx] && (
                <td colSpan={3} className="text--error globalProps__rowError">
                  {rowErrors[idx]}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      {/* UX22 — bottom "+ Add property" button removed; it now lives
          in the section header (top-right). */}

      {divergedCount > 0 && (
        <p className="ink-soft globalProps__overrideHint">
          {divergedCount} existing worker{divergedCount === 1 ? "" : "s"} keep a
          different snapshot — your changes here only apply to workers added
          from now on.
        </p>
      )}
    </section>
  );
}
