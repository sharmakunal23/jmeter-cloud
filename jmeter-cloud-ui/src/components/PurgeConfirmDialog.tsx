import { useState, type ReactNode } from "react";
import { ConfirmDialog } from "./ConfirmDialog";

/**
 * HARD-DELETE / purge — the irreversible second tier ("empty trash"). A
 * type-to-confirm guard sits in front of the destructive call: the confirm
 * button stays disabled until the operator types the exact `confirmPhrase`
 * (the run id or the application name), so a permanent delete can't happen on a
 * stray click. Wraps the shared {@link ConfirmDialog} (danger styling, Esc /
 * overlay cancel) and owns the busy + error state around the async purge.
 *
 * The parent's `onConfirm` performs the API call; on success it should close
 * this dialog (and toast). On failure it throws — the message renders inline and
 * the dialog stays open so the operator can retry or cancel.
 */
export interface PurgeConfirmDialogProps {
  /** "run" | "application" — drives the copy. */
  kind: "run" | "application";
  /** The exact text the operator must type to enable the confirm button. */
  confirmPhrase: string;
  /** What will be destroyed — rendered as the warning body (e.g. a <ul>). */
  summary: ReactNode;
  /** Performs the purge. Resolves → parent closes; throws → message shown inline. */
  onConfirm: (reason?: string) => Promise<void>;
  onClose: () => void;
}

export function PurgeConfirmDialog({
  kind, confirmPhrase, summary, onConfirm, onClose,
}: PurgeConfirmDialogProps) {
  const [typed, setTyped] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const matches = typed.trim() === confirmPhrase;

  async function handleConfirm() {
    if (!matches) return;
    setBusy(true);
    setError(null);
    try {
      await onConfirm(reason.trim() || undefined);
      // Parent closes the dialog on success.
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  }

  return (
    <ConfirmDialog
      title={kind === "run" ? "Permanently delete this run?" : "Permanently delete this application?"}
      danger
      busy={busy}
      confirmDisabled={!matches}
      confirmLabel="Delete permanently"
      onConfirm={handleConfirm}
      onCancel={onClose}
      body={
        <>
          <p>
            This <strong>cannot be undone</strong>. The following will be permanently
            removed to reclaim storage:
          </p>
          {summary}
        </>
      }
    >
      <div className="formField">
        <label htmlFor="purgeConfirmInput">
          Type <span className="mono">{confirmPhrase}</span> to confirm
        </label>
        <input
          id="purgeConfirmInput"
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          disabled={busy}
          aria-label={`type ${confirmPhrase} to confirm permanent deletion`}
        />
      </div>
      <div className="formField">
        <label htmlFor="purgeReasonInput">Reason (optional)</label>
        <input
          id="purgeReasonInput"
          type="text"
          maxLength={256}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={busy}
          placeholder="recorded on the purge audit log"
        />
      </div>
      {error && <div className="formError" role="alert">{error}</div>}
    </ConfirmDialog>
  );
}
