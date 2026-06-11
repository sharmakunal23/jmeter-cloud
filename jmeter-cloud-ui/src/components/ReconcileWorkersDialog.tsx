import { useEffect, useState } from "react";

import {
  capacityApi,
  CapacityApiError,
  type ReconcileWorkersResult,
} from "../api/capacity";

/**
 * Registry-wide "Reconcile workers" confirmation dialog (SAVERESULTS
 * follow-up). Forces an immediate run of the background reconcile sweep via
 * {@code POST /api/v1/admin/reconcilePods} — the operator-facing cleanup for a
 * worker that's stuck after its container died (no heartbeat to flip it).
 *
 * <p>The reconcile is registry-WIDE (all applications + regions), not scoped to
 * one app, so it lives on the Capacity list page rather than a detail page. It
 * is safe + idempotent — a healthy, heart-beating worker is never touched.
 *
 * <p>On success, {@code onSuccess(result)} hands the per-bucket summary back so
 * the page can toast it and refresh.
 */
export interface ReconcileWorkersDialogProps {
  onClose: () => void;
  onSuccess: (result: ReconcileWorkersResult) => void;
}

interface SubmitState {
  status: "idle" | "submitting" | "error";
  code?: string;
  message?: string;
}

export function ReconcileWorkersDialog({ onClose, onSuccess }: ReconcileWorkersDialogProps) {
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // ESC closes — same dismiss UX as the rest of the modal family.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function handleConfirm() {
    setSubmit({ status: "submitting" });
    try {
      const result = await capacityApi.reconcileWorkers();
      onSuccess(result);
      onClose();
    } catch (err) {
      if (err instanceof CapacityApiError) {
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
        aria-labelledby="reconcileWorkersTitle"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3 id="reconcileWorkersTitle">Reconcile workers?</h3>
            <small className="ink-soft">registry-wide — all applications &amp; regions</small>
          </div>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onClose}
            aria-label="Close"
          >×</button>
        </header>

        <div className="modal__body">
          <p>Reconciles the worker registry against the actual containers:</p>
          <ul className="ink-soft" style={{ fontSize: "0.85rem", margin: "0.4rem 0 0 1.1rem", padding: 0 }}>
            <li>
              removes registry rows whose container is gone — the usual fix for a
              worker stuck after its container died;
            </li>
            <li>adopts managed containers that have no registry row;</li>
            <li>starts managed containers found stopped.</li>
          </ul>
          <p className="ink-soft" style={{ fontSize: "0.85rem", marginTop: "0.6rem" }}>
            Safe &amp; idempotent — a healthy, heart-beating worker is never
            touched. This sweep runs automatically in the background; this button
            just forces it now.
          </p>
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
            className="btn btn--primary"
            onClick={() => { void handleConfirm(); }}
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? "Reconciling…" : "Reconcile workers"}
          </button>
        </footer>
      </div>
    </div>
  );
}
