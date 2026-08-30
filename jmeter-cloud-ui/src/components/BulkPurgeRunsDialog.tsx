import { useState } from "react";

import { runsApi, GlobalOrchestratorError, type Run } from "../api/runs";
import { Modal } from "./Modal";

/**
 * HARD-DELETE / purge — bulk permanent deletion of one or more ARCHIVED (hidden)
 * runs. Irreversible: each run's result files, metric rows, and run-state
 * rows are physically removed. A type-to-confirm guard (type "delete") sits in
 * front of the action since the selection is too large to retype id-by-id.
 *
 * <p>Mirrors {@link DeleteRunsConfirmDialog}'s parallel + partial-failure
 * handling: fires the purges concurrently, reports any per-run failures inline,
 * and always surfaces the successes so the parent clears them from the selection
 * and refreshes. Every selected run is already hidden + terminal (the Archived
 * view only lists those), so there's no will-purge/skip partition.
 */

const CONFIRM_PHRASE = "delete";

export interface BulkPurgeRunsProps {
  selected: Run[];
  /** Called after the purge pass with the runIds that were permanently deleted. */
  onPurged: (purgedRunIds: string[]) => void;
  onClose: () => void;
}

export function BulkPurgeRunsDialog({ selected, onPurged, onClose }: BulkPurgeRunsProps) {
  const [typed, setTyped] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<{ runId: string; message: string }[]>([]);

  const matches = typed.trim() === CONFIRM_PHRASE;

  async function handleConfirm() {
    if (busy || !matches || selected.length === 0) return;
    setBusy(true);
    setErrors([]);
    const trimmed = reason.trim() || undefined;
    const settled = await Promise.allSettled(
      selected.map((r) => runsApi.purge(r.runId, trimmed).then(() => r.runId)),
    );
    const purged: string[] = [];
    const failed: { runId: string; message: string }[] = [];
    settled.forEach((s, i) => {
      const runId = selected[i].runId;
      if (s.status === "fulfilled") {
        purged.push(runId);
      } else {
        const message =
          s.reason instanceof GlobalOrchestratorError
            ? `${s.reason.code}: ${s.reason.message}`
            : s.reason instanceof Error
              ? s.reason.message
              : String(s.reason);
        failed.push({ runId, message });
      }
    });
    setBusy(false);
    if (purged.length > 0) onPurged(purged);
    if (failed.length > 0) setErrors(failed);
    else onClose();
  }

  return (
    <Modal
      title={`Permanently delete ${selected.length} run${selected.length === 1 ? "" : "s"}?`}
      infoTip="Permanently erases the selected runs' result files, metric rows, and records — this cannot be undone."
      width="confirm"
      onClose={onClose}
      closeDisabled={busy}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            {errors.length > 0 ? "Close" : "Cancel"}
          </button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={handleConfirm}
            disabled={busy || !matches}
            aria-busy={busy}
          >
            {busy ? "Deleting…" : `Delete ${selected.length} permanently`}
          </button>
        </>
      }
    >
      <section className="bulkActionList">
        <h4 className="bulkActionList__title">Will delete permanently ({selected.length})</h4>
        <ul className="bulkActionList__items">
          {selected.map((r) => (
            <li key={r.runId}>
              <span className="mono">{r.runId}</span>
              <span className={`chip chip--${r.state === "COMPLETED" ? "ok" : "err"}`}>
                {r.state}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <div className="formField" style={{ marginTop: "0.8rem" }}>
        <label htmlFor="purgeRunsConfirm">
          Type <span className="mono">{CONFIRM_PHRASE}</span> to confirm
        </label>
        <input
          id="purgeRunsConfirm"
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          disabled={busy}
          aria-label={`type ${CONFIRM_PHRASE} to confirm permanent deletion`}
        />
      </div>

      <div className="formField">
        <label htmlFor="purgeRunsReason">Reason (optional)</label>
        <input
          id="purgeRunsReason"
          type="text"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="recorded on the purge audit log"
          maxLength={256}
          disabled={busy}
        />
      </div>

      {errors.length > 0 && (
        <div className="formError" role="alert" style={{ marginTop: "0.6rem" }}>
          <strong>{errors.length} run{errors.length === 1 ? "" : "s"} could not be deleted:</strong>
          <ul style={{ margin: "0.3rem 0 0", paddingLeft: "1.2rem" }}>
            {errors.map((e) => (
              <li key={e.runId}>
                <span className="mono">{e.runId.slice(0, 12)}…</span> — {e.message}
              </li>
            ))}
          </ul>
        </div>
      )}
    </Modal>
  );
}
