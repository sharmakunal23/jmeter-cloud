import { useEffect, useState } from "react";

import {
  GlobalOrchestratorError,
  runsApi,
  type Run,
} from "../api/runs";

/**
 * Run-abort confirmation dialog (SAVERESULTS BUG-3 UI). Force-terminates a run
 * via {@code POST /api/v1/runs/{runId}/abort} — the HARD stop, distinct from
 * the Worker-Fleet tab's "Stop test" (a graceful drain via scaleDown).
 *
 * <p>Use this to kill a run outright, or to clear a zombie run whose workers
 * are stuck/unreachable — a graceful drain can't end those (there's nothing
 * alive to drain). Abort marks the run + every non-terminal member ABORTED and
 * releases the pods regardless of worker reachability.
 *
 * <p>On success, {@code onSuccess(updatedRun)} lets the page replace its state
 * without waiting for the next poll.
 */
export interface AbortRunDialogProps {
  runId: string;
  /** Live (non-terminal) worker count — drives the confirmation copy. */
  activeWorkerCount: number;
  onClose: () => void;
  onSuccess: (run: Run) => void;
}

interface SubmitState {
  status: "idle" | "submitting" | "error";
  code?: string;
  message?: string;
}

export function AbortRunDialog({
  runId, activeWorkerCount, onClose, onSuccess,
}: AbortRunDialogProps) {
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  const [reason, setReason] = useState("");

  // ESC closes — same dismiss UX as the rest of the modal family.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function handleConfirm() {
    setSubmit({ status: "submitting" });
    try {
      const updated = await runsApi.abort(runId, reason);
      onSuccess(updated);
      onClose();
    } catch (err) {
      if (err instanceof GlobalOrchestratorError) {
        // RUN_NOT_ABORTABLE (409) lands here too — the run went terminal while
        // the dialog was open. The message is operator-readable; the page's
        // next poll picks up the real terminal state.
        setSubmit({ status: "error", code: err.code, message: err.message });
      } else {
        setSubmit({
          status: "error",
          code: "UNKNOWN",
          message: err instanceof Error ? err.message : String(err),
        });
      }
    }
  }

  const submitting = submit.status === "submitting";

  return (
    <div className="modal__overlay" role="presentation" onClick={onClose}>
      <div
        className="modal modal--application"
        role="dialog"
        aria-modal="true"
        aria-labelledby="abortRunDialogTitle"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3 id="abortRunDialogTitle">Abort run?</h3>
            <small className="ink-soft">
              {activeWorkerCount} active worker{activeWorkerCount === 1 ? "" : "s"}
            </small>
          </div>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onClose}
            aria-label="Close"
          >×</button>
        </header>

        <div className="modal__body">
          <p>
            Force-terminates the whole run. Each worker is hard-killed
            (<span className="mono">SIGKILL</span>) and the run rolls to{" "}
            <span className="badge badge--err">ABORTED</span> immediately —
            in-flight samplers do <strong>not</strong> complete. The run's
            workers are released so their pods free up for re-use.
          </p>
          <p className="ink-soft" style={{ fontSize: "0.85rem" }}>
            This is the hard stop. For a graceful end that lets in-flight
            requests finish, use <strong>Stop test</strong> on the Worker Fleet
            tab instead. Abort still works when workers are stuck or
            unreachable (a graceful drain can't end those).
          </p>
          <div className="formField">
            <label htmlFor="abortReason">Reason (optional)</label>
            <input
              id="abortReason"
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="optional — e.g. misconfigured test plan"
              maxLength={500}
              disabled={submitting}
            />
          </div>
          {submit.status === "error" && (
            <div className="formError" role="alert">
              <strong>{submit.code}</strong>: {submit.message}
            </div>
          )}
        </div>

        <footer className="modal__footer">
          <button
            type="button"
            className="btn"
            onClick={onClose}
            disabled={submitting}
          >Cancel</button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => { void handleConfirm(); }}
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? "Aborting…" : "Abort run"}
          </button>
        </footer>
      </div>
    </div>
  );
}
