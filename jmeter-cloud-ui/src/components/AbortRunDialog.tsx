import { useState } from "react";

import {
  GlobalOrchestratorError,
  runsApi,
  type Run,
} from "../api/runs";
import { Modal } from "./Modal";

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
    <Modal
      title="Abort run?"
      infoTip="Force-stops every worker immediately and marks the run ABORTED — in-flight samplers are lost."
      width="confirm"
      onClose={onClose}
      closeDisabled={submitting}
      footer={
        <>
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
            autoFocus
          >
            {submitting ? "Aborting…" : "Abort run"}
          </button>
        </>
      }
    >
      <p>
        <strong>{activeWorkerCount} active worker{activeWorkerCount === 1 ? "" : "s"}</strong>{" "}
        will be hard-killed and released so their pods free up for re-use.
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
    </Modal>
  );
}
