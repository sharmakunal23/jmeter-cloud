import { useState } from "react";

import { runsApi, GlobalOrchestratorError, type Run } from "../api/runs";
import { Modal } from "./Modal";

/**
 * Confirmation modal for archiving one or more runs so they drop out of the
 * run lists — the declutter primitive. Archiving is a soft delete: the run's
 * row, fleet members, audit trail, and any saved results are RETAINED and the
 * run moves to the Archived tab, so the copy says "archive", never "delete".
 *
 * <p>Mirrors {@link BulkActionConfirmDialog}: the dialog partitions the
 * selection client-side into "will archive" (terminal runs) vs "skipped"
 * (active runs can't be archived — they pin live pods), so the
 * operator sees exactly what will happen before confirming. Fires the deletes
 * in parallel and reports any per-run failures inline rather than closing on a
 * partial success.
 */

const TERMINAL_STATES: ReadonlySet<Run["state"]> = new Set<Run["state"]>([
  "COMPLETED",
  "FAILED",
  "ABORTED",
]);

export interface DeleteRunsConfirmProps {
  selected: Run[];
  /** Called after the delete pass with the runIds that were successfully hidden. */
  onDeleted: (deletedRunIds: string[]) => void;
  onClose: () => void;
}

export function DeleteRunsConfirmDialog({ selected, onDeleted, onClose }: DeleteRunsConfirmProps) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<{ runId: string; message: string }[]>([]);

  const willHide = selected.filter((r) => TERMINAL_STATES.has(r.state));
  const willSkip = selected.filter((r) => !TERMINAL_STATES.has(r.state));

  async function handleConfirm() {
    if (busy || willHide.length === 0) return;
    setBusy(true);
    setErrors([]);
    const trimmed = reason.trim() || undefined;
    const settled = await Promise.allSettled(
      willHide.map((r) => runsApi.delete(r.runId, trimmed).then(() => r.runId)),
    );
    const deleted: string[] = [];
    const failed: { runId: string; message: string }[] = [];
    settled.forEach((s, i) => {
      const runId = willHide[i].runId;
      if (s.status === "fulfilled") {
        deleted.push(runId);
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
    // Always surface the successes so the parent clears selection + refreshes
    // for the runs that were hidden, even on a partial failure.
    if (deleted.length > 0) onDeleted(deleted);
    if (failed.length > 0) setErrors(failed);
    else onClose();
  }

  return (
    <Modal
      title={`Archive ${selected.length} run${selected.length === 1 ? "" : "s"}?`}
      infoTip="The runs move to the Archived tab — their results and metrics are kept."
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
            disabled={busy || willHide.length === 0}
            aria-busy={busy}
          >
            {busy ? "Archiving…" : `Archive ${willHide.length}`}
          </button>
        </>
      }
    >
      {willHide.length > 0 && (
        <section className="bulkActionList">
          <h4 className="bulkActionList__title">Will archive ({willHide.length})</h4>
          <ul className="bulkActionList__items">
            {willHide.map((r) => (
              <li key={r.runId}>
                <span className="mono">{r.runId}</span>
                <span className={`chip chip--${r.state === "COMPLETED" ? "ok" : "err"}`}>
                  {r.state}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {willSkip.length > 0 && (
        <section className="bulkActionList bulkActionList--skip">
          <h4 className="bulkActionList__title">Skipped ({willSkip.length})</h4>
          <ul className="bulkActionList__items">
            {willSkip.map((r) => (
              <li key={r.runId}>
                <span className="mono">{r.runId}</span>
                <span className="chip chip--warn">{r.state}</span>
              </li>
            ))}
          </ul>
          <small className="ink-soft" style={{ display: "block", marginTop: "0.4rem" }}>
            Active runs can't be archived — abort or let them finish first.
          </small>
        </section>
      )}

      {willHide.length === 0 && (
        <p className="text--error" role="alert" style={{ marginTop: "0.6rem" }}>
          All selected runs are still active. Nothing to archive.
        </p>
      )}

      {willHide.length > 0 && (
        <>
          <div className="formField" style={{ marginTop: "0.8rem" }}>
            <label htmlFor="deleteRunsReason">Reason (optional)</label>
            <input
              id="deleteRunsReason"
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. failed smoke runs, no longer needed"
              maxLength={256}
              disabled={busy}
            />
          </div>
        </>
      )}

      {errors.length > 0 && (
        <div className="formError" role="alert" style={{ marginTop: "0.6rem" }}>
          <strong>{errors.length} run{errors.length === 1 ? "" : "s"} could not be archived:</strong>
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
