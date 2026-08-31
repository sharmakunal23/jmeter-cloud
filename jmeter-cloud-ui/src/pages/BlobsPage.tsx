import { useCallback, useEffect, useRef, useState, type DragEvent, type ChangeEvent } from "react";
import { Link } from "react-router-dom";

import {
  blobsApi,
  describeBlobError,
  DocumentServiceError,
  type BlobListing,
  type BlobMetadata,
  type BlobType,
} from "../api/blobs";
import { applicationsApi, type Application } from "../api/applications";
import { DeleteBlobsConfirmDialog } from "../components/DeleteBlobsConfirmDialog";
import { Paginator } from "../components/Paginator";
import { ToastView, useToast } from "../components/Toast";
import { persistPageSize, readStoredPageSize } from "../hooks/useClientPagination";
import { formatBytes, inferDownloadFilename } from "../lib/blobFile";

/**
 * Blob library — Step 18 centerpiece. Drag-and-drop file uploader with
 * a live progress bar (XHR-backed; fetch() can't surface upload-side
 * progress), a paginated list of stored blobs, and per-row delete.
 *
 * <p>The {@code /runs/new} launcher's blob-id dropdowns read from the
 * same listing endpoint, so an artifact uploaded here becomes
 * immediately discoverable in the launcher.
 */
type ListState =
  | { status: "loading" }
  | { status: "ok"; listing: BlobListing }
  | { status: "error"; message: string };

interface UploadInFlight {
  fileName: string;
  totalBytes: number;
  sentBytes: number;
  controller: AbortController;
}

// Newest-first — the document-service already returns rows in `uploadedAt
// DESC` order. Rows per page is the operator's shared list preference
// (UX-SIMPLIFY), which also bounds how many documents one bulk action covers.
const TYPES: BlobType[] = ["testPlan", "dataFiles", "result", "other"];

export interface BlobsPageProps {
  /**
   * When set, locks the listing + the upload type-picker to a
   * single type (so {@code <DocumentsPage>} can drive each Documents
   * tab through this page). Hides the Type column + the Type dropdown
   * in the toolbar; defaults the next upload's type to the same value.
   * Unset → legacy behavior (Type column visible, free-form filter).
   */
  pinnedType?: BlobType;
  /**
   * Phase IA-Documents (2026-05-12) — when set, locks the listing + the
   * upload application-picker to a single application name. Hides the
   * Application column + the Application dropdown in the toolbar.
   * Mirrors {@link pinnedType}. Used by {@code <DocumentsDetailPage>}
   * to scope every blob action to one app.
   */
  pinnedApplication?: string;
  /** UI-D2 — hide the page header so {@code <DocumentsPage>} can render its own. */
  hideHeader?: boolean;
}

export function BlobsPage({ pinnedType, pinnedApplication, hideHeader }: BlobsPageProps = {}) {
  const [list, setList] = useState<ListState>({ status: "loading" });
  const [typeFilter, setTypeFilter] = useState<string>(pinnedType ?? "");
  // D-Documents polish — filter by application. "" = all apps. The
  // dropdown is populated from the registered-apps registry.
  const [appFilter, setAppFilter] = useState<string>(pinnedApplication ?? "");
  const [offset, setOffset] = useState(0);
  const [pageSize, setPageSizeState] = useState<number>(() => readStoredPageSize());

  // Bulk operations — the selection is a Map, not a Set of ids, so the
  // confirm dialog can name every picked file even after the operator paged
  // past it (paging refetches; the row objects would otherwise be gone).
  const [selected, setSelected] = useState<Map<string, BlobMetadata>>(new Map());
  /** The delete dialog's subject: the whole selection, or one row's file. */
  const [pendingDelete, setPendingDelete] = useState<BlobMetadata[] | null>(null);
  const { toast, showToast, dismiss } = useToast();

  // Form fields for the next upload. Name is no longer operator-supplied —
  // we keep the file's own name (with extension) since that's what the
  // operator already recognizes. Type is conveyed by the active Documents
  // tab (pinnedType); the standalone Type input is gone for the pinned
  // case. Application is the new field — when set, the launcher's blob
  // pickers can filter to this app.
  const [uploadDescription, setUploadDescription] = useState("");
  const [uploadType, setUploadType] = useState<BlobType>(pinnedType ?? "testPlan");
  const [uploadApplication, setUploadApplication] = useState<string>(pinnedApplication ?? "");
  const [applications, setApplications] = useState<Application[]>([]);
  const [inFlight, setInFlight] = useState<UploadInFlight | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // When the parent (DocumentsPage) toggles tabs, sync local state.
  useEffect(() => {
    if (pinnedType) {
      setTypeFilter(pinnedType);
      setUploadType(pinnedType);
      setOffset(0);
    }
  }, [pinnedType]);

  // Phase IA-Documents — when the parent (DocumentsDetailPage) routes to
  // a different app, sync the filter + upload-target so the listing
  // always matches the URL.
  useEffect(() => {
    if (pinnedApplication !== undefined) {
      setAppFilter(pinnedApplication);
      setUploadApplication(pinnedApplication);
      setOffset(0);
    }
  }, [pinnedApplication]);

  // Fetch the registered apps once for the upload form's Application select.
  useEffect(() => {
    const ctl = new AbortController();
    applicationsApi.list(ctl.signal)
      .then((apps) => setApplications(apps))
      .catch(() => { /* surface via the upload error if it bites later */ });
    return () => ctl.abort();
  }, []);

  const refresh = useCallback(() => {
    const ctl = new AbortController();
    setList((prev) => (prev.status === "ok" ? prev : { status: "loading" }));
    blobsApi
      .list({
          type: typeFilter || undefined,
          application: appFilter || undefined,
          offset, limit: pageSize,
        }, ctl.signal)
      .then((listing) => {
        setList({ status: "ok", listing });
        // Deleting the last rows of the last page would otherwise leave the
        // operator staring at an empty table; step back instead. The offset
        // strictly decreases, so the re-fetch this triggers terminates.
        if (listing.items.length === 0 && listing.offset > 0) {
          setOffset(Math.max(0, listing.offset - listing.limit));
        }
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setList({
          status: "error",
          message: err instanceof Error ? err.message : String(err),
        });
      });
    return ctl;
  }, [typeFilter, appFilter, offset, pageSize]);

  useEffect(() => {
    const ctl = refresh();
    return () => ctl.abort();
  }, [refresh]);

  // A selection means "these files", so it survives paging — but not a change
  // of scope: rows the operator can no longer see must not stay armed for a
  // bulk delete.
  useEffect(() => {
    setSelected(new Map());
  }, [typeFilter, appFilter]);

  function setPageSize(next: number) {
    setPageSizeState(next);
    persistPageSize(next);
    setOffset(0);
  }

  // ── Upload handlers ──────────────────────────────────────────────

  const startUpload = useCallback(
    (file: File) => {
      setUploadError(null);
      const controller = new AbortController();
      // Use the file's own name (with extension) — operator already
      // recognizes it; no point asking them to retype.
      const fileName = file.name;
      setInFlight({
        fileName,
        totalBytes: file.size,
        sentBytes: 0,
        controller,
      });
      blobsApi
        .upload(file, {
          contentType: file.type || "application/octet-stream",
          name: fileName,
          description: uploadDescription.trim() || undefined,
          type: uploadType,
          application: uploadApplication.trim() || undefined,
          signal: controller.signal,
          onProgress: (sent, total) => {
            setInFlight((prev) =>
              prev ? { ...prev, sentBytes: sent, totalBytes: total } : prev,
            );
          },
        })
        .then(() => {
          setInFlight(null);
          // Keep the operator's app + description selection so the next
          // upload can land in the same context without re-picking.
          setOffset(0);
          refresh();
        })
        .catch((err: unknown) => {
          setInFlight(null);
          if (err instanceof DocumentServiceError && err.code === "ABORTED") return;
          setUploadError(describeBlobError(err));
        });
    },
    [refresh, uploadDescription, uploadType, uploadApplication],
  );

  const onFilesPicked = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!uploadApplication.trim()) {
      setUploadError("Pick an application before uploading — every document must be tagged with an app.");
      if (fileInputRef.current) fileInputRef.current.value = "";
      return;
    }
    startUpload(file);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (!file) return;
    if (!uploadApplication.trim()) {
      setUploadError("Pick an application before uploading — every document must be tagged with an app.");
      return;
    }
    startUpload(file);
  };

  const cancelUpload = () => {
    inFlight?.controller.abort();
  };

  // ── Selection + delete handlers ──────────────────────────────────

  const pageItems = list.status === "ok" ? list.listing.items : [];
  const pageAllSelected = pageItems.length > 0 && pageItems.every((b) => selected.has(b.blobId));
  const pageAnySelected = pageItems.some((b) => selected.has(b.blobId));

  const toggleSelected = (blob: BlobMetadata) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (next.has(blob.blobId)) next.delete(blob.blobId);
      else next.set(blob.blobId, blob);
      return next;
    });
  };

  /** Header checkbox — covers this page only; picks on other pages stand. */
  const togglePageSelected = () => {
    setSelected((prev) => {
      const next = new Map(prev);
      for (const b of pageItems) {
        if (pageAllSelected) next.delete(b.blobId);
        else next.set(b.blobId, b);
      }
      return next;
    });
  };

  // The dialog reports what it actually deleted, so a partial failure still
  // clears the rows that went and leaves the rest selected to retry.
  const onDeleted = (deletedBlobIds: string[]) => {
    setSelected((prev) => {
      const next = new Map(prev);
      for (const id of deletedBlobIds) next.delete(id);
      return next;
    });
    refresh();
    showToast({
      variant: "ok",
      text: `${deletedBlobIds.length} document${deletedBlobIds.length === 1 ? "" : "s"} deleted.`,
    });
  };

  // ── Render ───────────────────────────────────────────────────────

  return (
    <section>
      {!hideHeader && (
        <header className="pageHeader">
          <h1>Blob library</h1>
          <Link to="/templates/new" className="btn">+ New run</Link>
        </header>
      )}

      <div className="uploadRow">
        <div
          className={`dropZone uploadRow__drop ${dragOver ? "dropZone--over" : ""} ${inFlight ? "dropZone--uploading" : ""} ${!uploadApplication ? "dropZone--gated" : ""}`}
          onDragOver={(e) => {
            e.preventDefault();
            if (!uploadApplication) return;
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          role="button"
          tabIndex={0}
          aria-disabled={!uploadApplication}
          onClick={() => {
            if (!uploadApplication) {
              setUploadError("Pick an application before uploading — every document must be tagged with an app.");
              return;
            }
            fileInputRef.current?.click();
          }}
          onKeyDown={(e) => {
            if ((e.key === "Enter" || e.key === " ") && uploadApplication) fileInputRef.current?.click();
          }}
        >
          <input
            ref={fileInputRef}
            type="file"
            onChange={onFilesPicked}
            style={{ display: "none" }}
          />
          {inFlight ? (
            <UploadInProgress upload={inFlight} onCancel={cancelUpload} />
          ) : !uploadApplication ? (
            <div className="dropZone__hint dropZone__hint--gated">
              <strong>Pick an application first →</strong>
              <small className="ink-soft">
                Every document must be tagged with an application so it can
                be discovered from the launcher.
              </small>
            </div>
          ) : (
            <div className="dropZone__hint">
              <strong>Drag a file here</strong> — or click to choose. Test plans
              (<code>.jmx</code>), data zips, JTL results, anything up to 1&nbsp;GB.
            </div>
          )}
        </div>

        {/* Phase IA-Documents 2026-05-12 — fieldset chrome restored
            with the legend renamed to "Description" (per user
            direction). On the per-app drill-in (both Application + Type
            pinned) the body is a single textarea filling the column
            height to match the left dropZone. The cross-app legacy
            view (no pin) still shows Application + Type pickers above
            the textarea. */}
        <fieldset className="dropZone__meta uploadRow__meta uploadRow__meta--description" disabled={!!inFlight}>
          <legend>Description</legend>
          {!pinnedApplication && (
            <label>
              Application *
              <select
                value={uploadApplication}
                onChange={(e) => setUploadApplication(e.target.value)}
                required
                aria-required="true"
              >
                <option value="">— pick an application —</option>
                {applications.map((a) => (
                  <option key={a.applicationId} value={a.name}>
                    {a.name}{a.sealId ? ` · ${a.sealId}` : ""}
                  </option>
                ))}
              </select>
              <small className="ink-soft">
                Required — tags the document so it surfaces in the launcher's
                blob pickers when you run a test for this app.
              </small>
            </label>
          )}
          <textarea
            className="uploadRow__descriptionInput"
            value={uploadDescription}
            onChange={(e) => setUploadDescription(e.target.value)}
            placeholder="Optional — shown in the launcher dropdown so the right file is easy to find."
            aria-label="Description for the next upload"
            rows={4}
            /* SECURITY S-7 — keep descriptions descriptions, not payloads.
               React already escapes them on render; this caps the length. The
               cap is enforced again server-side (document-service), since this
               client-side one is trivially bypassable. */
            maxLength={200}
          />
          {!pinnedType && (
            <label>
              Type
              <select
                value={uploadType}
                onChange={(e) => setUploadType(e.target.value as BlobType)}
              >
                {TYPES.map((t) => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </label>
          )}
        </fieldset>
      </div>

      {uploadError && (
        <p className="text--error" role="alert">{uploadError}</p>
      )}

      <hr className="hr" />

      <div className="runsToolbar">
        {!pinnedType && (
          // The Type dropdown is redundant on the Documents tabs;
          // keep it as a fallback when BlobsPage is consumed without
          // pinnedType (e.g. from a future surface).
          <label className="filterToggle">
            Type:&nbsp;
            <select
              value={typeFilter}
              onChange={(e) => {
                setTypeFilter(e.target.value);
                setOffset(0);
              }}
            >
              <option value="">All</option>
              {TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </label>
        )}
        {/* Application filter — only when not already scoped via pinnedApplication
            (Phase IA-Documents 2026-05-12 — the URL conveys the app on
            the Documents detail page, so the dropdown is redundant). */}
        {!pinnedApplication && (
          <label className="filterToggle">
            Application:&nbsp;
            <select
              value={appFilter}
              onChange={(e) => {
                setAppFilter(e.target.value);
                setOffset(0);
              }}
            >
              <option value="">All</option>
              {applications.map((a) => (
                <option key={a.applicationId} value={a.name}>{a.name}</option>
              ))}
            </select>
          </label>
        )}
        <span className="runsToolbar__spacer" />
      </div>

      {selected.size > 0 && (
        <div className="bulkToolbar" role="toolbar" aria-label="Bulk actions for selected documents">
          <span className="bulkToolbar__count">
            {selected.size} selected
          </span>
          <button type="button" className="btn btn--ghost" onClick={() => setSelected(new Map())}>
            Clear
          </button>
          <span className="bulkToolbar__spacer" />
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => setPendingDelete([...selected.values()])}
          >
            Delete selected
          </button>
        </div>
      )}

      {list.status === "loading" && <p>loading…</p>}
      {list.status === "error" && <p className="text--error">{list.message}</p>}
      {list.status === "ok" && list.listing.items.length === 0 && (
        <p>No blobs yet. Drop one in above ☝️.</p>
      )}
      {list.status === "ok" && list.listing.items.length > 0 && (
        <>
          <table className="runsTable">
            <thead>
              <tr>
                <th className="runsTable__check">
                  <input
                    type="checkbox"
                    aria-label="Select all documents on this page"
                    checked={pageAllSelected}
                    ref={(el) => {
                      // "Some but not all" is the third checkbox state, and it
                      // is only settable from JS.
                      if (el) el.indeterminate = !pageAllSelected && pageAnySelected;
                    }}
                    onChange={togglePageSelected}
                  />
                </th>
                <th>File</th>
                {!pinnedType && <th>Type</th>}
                {!pinnedApplication && <th>Application</th>}
                <th>Size</th>
                <th>Uploaded</th>
                <th>blobId</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {list.listing.items.map((b) => (
                <BlobRow
                  key={b.blobId}
                  blob={b}
                  selected={selected.has(b.blobId)}
                  onToggleSelected={toggleSelected}
                  onDelete={(blob) => setPendingDelete([blob])}
                  showType={!pinnedType}
                  showApplication={!pinnedApplication}
                />
              ))}
            </tbody>
          </table>
          <Paginator
            page={Math.floor(list.listing.offset / list.listing.limit) + 1}
            pageSize={list.listing.limit}
            total={list.listing.total}
            label="documents"
            onChange={(nextPage) => setOffset((nextPage - 1) * list.listing.limit)}
            onPageSizeChange={setPageSize}
          />
        </>
      )}

      {pendingDelete && pendingDelete.length > 0 && (
        <DeleteBlobsConfirmDialog
          selected={pendingDelete}
          onDeleted={onDeleted}
          onClose={() => setPendingDelete(null)}
        />
      )}

      <ToastView toast={toast} onDismiss={dismiss} />
    </section>
  );
}

function BlobRow({
  blob,
  selected,
  onToggleSelected,
  onDelete,
  showType,
  showApplication,
}: {
  blob: BlobMetadata;
  selected: boolean;
  onToggleSelected: (blob: BlobMetadata) => void;
  onDelete: (blob: BlobMetadata) => void;
  showType: boolean;
  showApplication: boolean;
}) {
  // Phase IA-Documents (2026-05-12) — drop the operator-supplied "name"
  // line; just show the filename. The two were almost always
  // duplicates (e.g. "smoketest-checkout" vs "smoketest-checkout.jmx")
  // and ate horizontal space without adding signal.
  const filename = inferDownloadFilename(blob);
  return (
    <tr className={selected ? "blobRow--selected" : undefined}>
      <td className="runsTable__check">
        <input
          type="checkbox"
          aria-label={`Select ${filename} (${blob.blobId})`}
          checked={selected}
          onChange={() => onToggleSelected(blob)}
        />
      </td>
      <td>
        <div className="mono blobRow__filename"><strong>{filename}</strong></div>
        {blob.description && (
          <div className="text--muted blobRow__desc">{blob.description}</div>
        )}
      </td>
      {showType && (
        <td>
          {blob.type ? (
            <span className={`badge badge--${blob.type === "testPlan" ? "info" : "warn"}`}>
              {blob.type}
            </span>
          ) : (
            <span className="text--muted">—</span>
          )}
        </td>
      )}
      {showApplication && (
        <td>
          {blob.application ? (
            <Link
              to={`/applications/${encodeURIComponent(blob.application)}`}
              className="mono"
              title={`Open ${blob.application} detail`}
            >
              {blob.application}
            </Link>
          ) : (
            <span className="text--muted">—</span>
          )}
        </td>
      )}
      <td>{formatBytes(blob.sizeBytes)}</td>
      <td>{new Date(blob.uploadedAt).toLocaleString()}</td>
      <td>
        <div className="mono blobRow__id">{blob.blobId}</div>
      </td>
      <td>
        <a
          href={`/api/v1/blob/${encodeURIComponent(blob.blobId)}?download=true`}
          download={filename}
          className="btn btn--ghost"
        >
          Download
        </a>
        <button
          type="button"
          className="btn btn--ghost text--error"
          onClick={() => onDelete(blob)}
        >
          Delete
        </button>
      </td>
    </tr>
  );
}

function UploadInProgress({
  upload,
  onCancel,
}: {
  upload: UploadInFlight;
  onCancel: () => void;
}) {
  const pct = upload.totalBytes > 0
    ? Math.min(100, Math.round((upload.sentBytes / upload.totalBytes) * 100))
    : 0;
  return (
    <div className="uploadProgress" onClick={(e) => e.stopPropagation()}>
      <div className="uploadProgress__name">
        {upload.fileName}
        <span className="text--muted">
          &nbsp;{formatBytes(upload.sentBytes)} / {formatBytes(upload.totalBytes)}
        </span>
      </div>
      <div className="progressBar" aria-label="upload progress">
        <div className="progressBar__fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="uploadProgress__footer">
        <span>{pct}%</span>
        <button type="button" className="btn btn--ghost" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </div>
  );
}
