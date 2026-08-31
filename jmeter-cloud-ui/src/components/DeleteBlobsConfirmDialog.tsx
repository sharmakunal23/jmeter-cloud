import { useState } from "react";

import { blobsApi, type BlobMetadata, type BulkDeleteResult } from "../api/blobs";
import { formatBytes, inferDownloadFilename } from "../lib/blobFile";
import { Modal } from "./Modal";

/**
 * Confirmation modal for deleting one or many documents — a true delete, not
 * the soft archive a run gets, so nothing is recoverable afterwards.
 *
 * <p>On a partial failure it stays open and narrows itself to the blobs that
 * survived, so confirming again retries those and only those.
 */

/** Files listed by name before the list collapses to a "+N more" line. */
const NAMES_SHOWN = 12;

export interface DeleteBlobsConfirmProps {
  selected: BlobMetadata[];
  /** Called with the blobIds actually deleted — including on a partial failure. */
  onDeleted: (deletedBlobIds: string[]) => void;
  onClose: () => void;
}

export function DeleteBlobsConfirmDialog({ selected, onDeleted, onClose }: DeleteBlobsConfirmProps) {
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<BulkDeleteResult["failed"]>([]);
  // Shrinks to the survivors after a partial failure — re-confirming must not
  // re-issue deletes for blobs that are already gone.
  const [remaining, setRemaining] = useState<BlobMetadata[]>(selected);

  const shown = remaining.slice(0, NAMES_SHOWN);
  const hidden = remaining.length - shown.length;
  const totalBytes = remaining.reduce((sum, b) => sum + (b.sizeBytes ?? 0), 0);
  // A `result` blob is a member of its run's saved-results zip
  // (`GET /api/v1/blob/run/{runId}/archive` gathers every result blob named
  // `results-<runId>-*`), so deleting one silently shrinks that download.
  const resultCount = remaining.filter((b) => b.type === "result").length;

  async function handleConfirm() {
    if (busy || remaining.length === 0) return;
    setBusy(true);
    setErrors([]);
    const { deleted, failed } = await blobsApi.deleteMany(remaining.map((b) => b.blobId));
    setBusy(false);
    if (deleted.length > 0) onDeleted(deleted);
    if (failed.length === 0) {
      onClose();
      return;
    }
    const stillThere = new Set(failed.map((f) => f.blobId));
    setRemaining(remaining.filter((b) => stillThere.has(b.blobId)));
    setErrors(failed);
  }

  const noun = remaining.length === 1 ? "document" : "documents";

  return (
    <Modal
      title={`Delete ${remaining.length} ${noun}?`}
      infoTip="Deleting removes the stored bytes — there is no archive tab and no undo."
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
            disabled={busy || remaining.length === 0}
            aria-busy={busy}
          >
            {busy ? "Deleting…" : `Delete ${remaining.length}`}
          </button>
        </>
      }
    >
      <section className="bulkActionList">
        <h4 className="bulkActionList__title">
          Will delete ({remaining.length}) · {formatBytes(totalBytes)}
        </h4>
        <ul className="bulkActionList__items">
          {shown.map((b) => (
            <li key={b.blobId}>
              <span className="mono">{inferDownloadFilename(b)}</span>
              <span className="ink-soft">{formatBytes(b.sizeBytes)}</span>
            </li>
          ))}
          {hidden > 0 && <li className="ink-soft">…and {hidden} more</li>}
        </ul>
      </section>

      {resultCount > 0 && (
        <p className="ink-soft" style={{ marginTop: "0.6rem" }}>
          {resultCount === 1
            ? 'One selected file is a run result — deleting it also removes it from that run\'s "Download results" zip.'
            : `${resultCount} selected files are run results — deleting them also removes them from their runs' "Download results" zips.`}
        </p>
      )}

      {errors.length > 0 && (
        <div className="formError" role="alert" style={{ marginTop: "0.6rem" }}>
          <strong>
            {errors.length} {errors.length === 1 ? "document" : "documents"} could not be deleted:
          </strong>
          <ul style={{ margin: "0.3rem 0 0", paddingLeft: "1.2rem" }}>
            {errors.map((e) => (
              <li key={e.blobId}>
                <span className="mono">{e.blobId.slice(0, 12)}…</span> — {e.message}
              </li>
            ))}
          </ul>
        </div>
      )}
    </Modal>
  );
}
