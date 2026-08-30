import { useState } from "react";

import type { PodView } from "../api/capacity";
import { Modal } from "./Modal";

/**
 * Phase 5b — confirmation modal for bulk Restart / Drain on selected
 * workers. Shows the operator EXACTLY what will happen up-front:
 * which workers proceed (READY) vs which get skipped (IN_USE, with the
 * blocker run cited). Cuts the foot-gun of clicking through 5 single
 * 409s after a careless multi-select.
 *
 * <p>Caller passes the full set of selected workers; the dialog
 * partitions them into "willProceed" + "willSkip" client-side using
 * the snapshot's `state` + `blockedBy` data (already fetched). No
 * extra round-trips for the preview.
 */

export type BulkAction = "drain" | "restart";

export interface BulkActionConfirmProps {
  action: BulkAction;
  selected: PodView[];
  /** Called with the subset that should actually be acted on (skips IN_USE). */
  onConfirm: (toAct: PodView[]) => void | Promise<void>;
  onCancel: () => void;
  /** True while the network request is in flight; disables the buttons. */
  busy?: boolean;
}

export function BulkActionConfirmDialog({
  action, selected, onConfirm, onCancel, busy = false,
}: BulkActionConfirmProps) {
  const [confirmed, setConfirmed] = useState(false);

  // IN_USE workers are skipped for both Drain and Restart — restart on a
  // pod that's mid-run would interrupt the test, which is at best surprising
  // and at worst destroys data. Operator has to abort the run first.
  const willSkip   = selected.filter((p) => p.state === "IN_USE");
  const willProceed = selected.filter((p) => p.state !== "IN_USE");

  const verb = action === "drain" ? "Drain" : "Restart";
  const verbing = action === "drain" ? "Draining" : "Restarting";

  async function handleConfirm() {
    if (busy || willProceed.length === 0) return;
    setConfirmed(true);
    await onConfirm(willProceed);
  }

  return (
    <Modal
      title={`${verb} ${selected.length} worker${selected.length === 1 ? "" : "s"}?`}
      infoTip="Workers held by an active run are skipped automatically — only idle workers are acted on."
      width="confirm"
      onClose={onCancel}
      closeDisabled={busy}
      footer={
        <>
          <button type="button" className="btn" onClick={onCancel} disabled={busy && confirmed}>
            Cancel
          </button>
          <button
            type="button"
            className={`btn ${action === "drain" ? "btn--danger" : "btn--primary"}`}
            onClick={handleConfirm}
            disabled={busy || willProceed.length === 0}
            aria-busy={busy}
            autoFocus
          >
            {busy ? `${verbing}…` : `${verb} ${willProceed.length}`}
          </button>
        </>
      }
    >
      {willProceed.length > 0 && (
        <section className="bulkActionList">
          <h4 className="bulkActionList__title">
            Will {action} ({willProceed.length})
          </h4>
          <ul className="bulkActionList__items">
            {willProceed.map((p) => (
              <li key={p.podName}>
                <span className="mono">{p.podName}</span>
                <span className={`chip chip--${p.state === "LOST" ? "err" : "ok"}`}>
                  {p.state}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {willSkip.length > 0 && (
        <section className="bulkActionList bulkActionList--skip">
          <h4 className="bulkActionList__title">
            Skipped ({willSkip.length})
          </h4>
          <ul className="bulkActionList__items">
            {willSkip.map((p) => (
              <li key={p.podName}>
                <span className="mono">{p.podName}</span>
                <span className="chip chip--warn">IN_USE</span>
                {p.blockedBy && (
                  <small className="ink-soft mono">
                    run {p.blockedBy.runId.slice(0, 12)}… ({p.blockedBy.state})
                  </small>
                )}
              </li>
            ))}
          </ul>
          <small className="ink-soft" style={{ display: "block", marginTop: "0.4rem" }}>
            Abort these runs first if you really need to {action} their workers.
          </small>
        </section>
      )}

      {willProceed.length === 0 && (
        <p className="text--error" role="alert" style={{ marginTop: "0.6rem" }}>
          All selected workers are in use. Nothing to {action}.
        </p>
      )}
    </Modal>
  );
}
