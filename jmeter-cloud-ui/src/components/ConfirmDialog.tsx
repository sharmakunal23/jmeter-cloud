import { useEffect, type ReactNode } from "react";

/**
 * Generic confirmation modal for critical / irreversible actions (delete, skip,
 * fire-now…). Replaces ad-hoc `window.confirm` so confirmations look consistent
 * across the app and can carry extra controls (e.g. an "also skip next" checkbox)
 * via `children`. `danger` styles the confirm button as destructive.
 *
 * Esc + the overlay cancel; the confirm button auto-focuses so Enter confirms.
 */
export interface ConfirmDialogProps {
  title: string;
  body?: ReactNode;
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
  title, body, confirmLabel = "Confirm", cancelLabel = "Cancel",
  danger = false, busy = false, confirmDisabled = false, onConfirm, onCancel, children,
}: ConfirmDialogProps) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onCancel(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  return (
    <div className="modal__overlay" onClick={onCancel}>
      <div
        className="modal modal--confirm"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <h3>{title}</h3>
          <button type="button" className="btn btn--ghost" onClick={onCancel} aria-label="Close">×</button>
        </header>
        <div className="modal__body">
          {body && <div className="confirmDialog__body">{body}</div>}
          {children}
        </div>
        <footer className="modal__footer">
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
        </footer>
      </div>
    </div>
  );
}
