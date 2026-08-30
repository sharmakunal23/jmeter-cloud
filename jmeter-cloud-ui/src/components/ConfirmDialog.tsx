import { type ReactNode } from "react";

import { Modal } from "./Modal";

/**
 * Generic confirmation modal for critical / irreversible actions (delete, skip,
 * fire-now…). Replaces ad-hoc `window.confirm` so confirmations look consistent
 * across the app and can carry extra controls (e.g. an "also skip next" checkbox)
 * via `children`. `danger` styles the confirm button as destructive.
 *
 * Esc + the overlay cancel (blocked while `busy` — a purge in flight must not
 * be dismissed); the confirm button auto-focuses so Enter confirms.
 */
export interface ConfirmDialogProps {
  title: string;
  body?: ReactNode;
  /** Optional ≤1-sentence description behind the ⓘ icon beside the title. */
  infoTip?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  busy?: boolean;
  /** Disables the confirm button without the "Working…" busy state — e.g. a
   *  type-to-confirm guard where the typed text doesn't match yet. */
  confirmDisabled?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children?: ReactNode;
}

export function ConfirmDialog({
  title, body, infoTip, confirmLabel = "Confirm", cancelLabel = "Cancel",
  danger = false, busy = false, confirmDisabled = false, onConfirm, onCancel, children,
}: ConfirmDialogProps) {
  return (
    <Modal
      title={title}
      infoTip={infoTip}
      width="confirm"
      onClose={onCancel}
      closeDisabled={busy}
      footer={
        <>
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>{cancelLabel}</button>
          <button
            type="button"
            className={`btn ${danger ? "btn--danger" : "btn--primary"}`}
            onClick={onConfirm}
            disabled={busy || confirmDisabled}
            aria-busy={busy}
            autoFocus
          >
            {busy ? "Working…" : confirmLabel}
          </button>
        </>
      }
    >
      {body && <div className="confirmDialog__body">{body}</div>}
      {children}
    </Modal>
  );
}
