import { useState } from "react";

/**
 * Phase 5b — "Request Capacity" modal. Replaces the old sponsor-gate
 * "Request more" stub flow (deleted in Phase 3 of the capacity rework).
 * Operator provides desired Max + optional reason; the dialog directly
 * `PUT`s the new ceiling via `capacityApi.setMax`. Reason is captured
 * for operator UX (audit trail belongs in a future log).
 *
 * <p>The dialog is a thin shell over the existing inline Max editor so
 * operators have a discoverable, deliberate action (button on the
 * detail page) in addition to the click-the-chip editor for power users.
 */

export interface RequestCapacityDialogProps {
  applicationName: string;
  region: string;
  current: number;
  /** Submit handler — receives the new max. Throws on backend failure (404/409). */
  onSubmit: (newMax: number, reason: string) => Promise<void>;
  onCancel: () => void;
}

const MAX_BUDGET = 1000;

export function RequestCapacityDialog({
  applicationName, region, current, onSubmit, onCancel,
}: RequestCapacityDialogProps) {
  // Default the requested max to "what would let me Provision Worker once
  // beyond the current ceiling." Most common case is +1; saves a click.
  const [draftMax, setDraftMax] = useState(String(Math.min(MAX_BUDGET, current + 1)));
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const parsed = Number.parseInt(draftMax.trim(), 10);
  const inputError =
    Number.isNaN(parsed) ? "must be a number"
    : parsed < 0 ? "must be ≥ 0"
    : parsed > MAX_BUDGET ? `must be ≤ ${MAX_BUDGET}`
    : null;
  const canSubmit = !busy && inputError === null && parsed !== current;

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      await onSubmit(parsed, reason.trim());
      onCancel(); // close on success
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal__overlay" role="presentation" onClick={onCancel}>
      <div
        className="modal modal--application"
        role="dialog"
        aria-modal="true"
        aria-labelledby="reqCapTitle"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3 id="reqCapTitle">Request capacity</h3>
            <small className="ink-soft">
              <span className="mono">{applicationName}</span> ·{" "}
              <span className="mono">{region}</span> · current cap{" "}
              <span className="mono">{current}</span>
            </small>
          </div>
          <button type="button" className="btn btn--ghost" onClick={onCancel} aria-label="Close">×</button>
        </header>
        <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
          <div className="formField">
            <label htmlFor="reqCapMax">New maximum</label>
            <input
              id="reqCapMax"
              type="number"
              min={0}
              max={MAX_BUDGET}
              value={draftMax}
              onChange={(e) => setDraftMax(e.target.value)}
              autoFocus
              required
            />
            {inputError && (
              <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>
                {inputError}
              </p>
            )}
            {!inputError && parsed < current && (
              <p className="ink-soft" style={{ fontSize: "0.78rem" }}>
                Shrinking — will be refused if {current - parsed} or more pod
                {current - parsed === 1 ? " is" : "s are"} provisioned. Drain first.
              </p>
            )}
          </div>
          <div className="formField">
            <label htmlFor="reqCapReason">Reason (optional, for your own records)</label>
            <textarea
              id="reqCapReason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. black-friday load test, expected 5x baseline"
              rows={3}
              maxLength={512}
            />
          </div>
          {error && <div className="formError" role="alert">{error}</div>}
          <footer className="modal__footer">
            <button type="button" className="btn" onClick={onCancel}>Cancel</button>
            <button type="submit" className="btn btn--primary" disabled={!canSubmit} aria-busy={busy}>
              {busy ? "Saving…" : `Set max to ${Number.isNaN(parsed) ? "?" : parsed}`}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
