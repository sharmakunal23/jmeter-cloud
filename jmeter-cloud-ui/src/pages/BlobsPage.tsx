import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type ChangeEvent } from "react";
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
import { DataList } from "../components/DataList";
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

  /**
   * The selection is stored as {@code blobId → blob} rather than as ids,
   * because the delete dialog needs the metadata and a pick made three pages
   * ago is no longer in {@code pageItems} to look up. DataList speaks ids, so
   * this translates: removals come from the id set, additions are resolved
   * against the page they were made on.
   */
  const selectedIds = useMemo(() => new Set(selected.keys()), [selected]);
  const onSelectionChange = (next: ReadonlySet<string>) => {
    setSelected((prev) => {
      const out = new Map(prev);
      for (const id of prev.keys()) if (!next.has(id)) out.delete(id);
      for (const id of next) {
        if (out.has(id)) continue;
        const blob = pageItems.find((b) => b.blobId === id);
        if (blob) out.set(id, blob);
      }
      return out;
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
          <div className="pageHeader__actions">
            <Link to="/templates/new" className="btn">+ New run</Link>
          </div>
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

      {list.status === "error" && <p className="text--error">{list.message}</p>}

      <DataList<BlobMetadata>
        toolbar={<div className="runsToolbar">
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
        </div>}
        label="Documents"
        loading={list.status === "loading"}
        rows={list.status === "ok" ? list.listing.items : []}
        rowKey={(b) => b.blobId}
        rowSelectionLabel={(b) => `Select ${inferDownloadFilename(b)} (${b.blobId})`}
        itemNoun="documents"
        empty={<>
          <strong>No documents yet.</strong>
          <div>Drop one in above ☝️</div>
        </>}
        // Server-paginated: the listing endpoint returns one page, so the list
        // must render it as-is rather than paging it again.
        pagination={list.status === "ok" ? {
          page: Math.floor(list.listing.offset / list.listing.limit) + 1,
          pageSize: list.listing.limit,
          total: list.listing.total,
          onPageChange: (nextPage) => setOffset((nextPage - 1) * list.listing.limit),
          onPageSizeChange: setPageSize,
        } : undefined}
        selectedIds={selectedIds}
        onSelectionChange={onSelectionChange}
        bulkActions={[
          { label: "Delete selected", danger: true,
            // Server-paged: the ids are the whole selection, `rows` only the
            // visible part of it. Resolve from the page's own store.
            onRun: (_rows, ids) =>
              setPendingDelete([...ids].map((id) => selected.get(id)).filter((b): b is BlobMetadata => !!b)) },
        ]}
        rowProps={(b) => (selected.has(b.blobId) ? { className: "blobRow--selected" } : {})}
        columns={[
          { key: "file", header: "File", cell: (b) => (
            <>
              <div className="mono blobRow__filename"><strong>{inferDownloadFilename(b)}</strong></div>
              {b.description && <div className="text--muted blobRow__desc">{b.description}</div>}
            </>
          ) },
          ...(!pinnedType ? [{ key: "type", header: "Type", cell: (b: BlobMetadata) => (
            b.type
              ? <span className={`badge badge--${b.type === "testPlan" ? "info" : "warn"}`}>{b.type}</span>
              : <span className="text--muted">—</span>
          ) }] : []),
          ...(!pinnedApplication ? [{ key: "application", header: "Application", cell: (b: BlobMetadata) => (
            b.application
              ? <Link to={`/applications/${encodeURIComponent(b.application)}`} className="mono"
                      title={`Open ${b.application} detail`}>{b.application}</Link>
              : <span className="text--muted">—</span>
          ) }] : []),
          { key: "size", header: "Size", className: "dataList__num",
            cell: (b) => formatBytes(b.sizeBytes) },
          { key: "uploaded", header: "Uploaded",
            cell: (b) => new Date(b.uploadedAt).toLocaleString() },
          { key: "blobId", header: "blobId",
            cell: (b) => <div className="mono blobRow__id">{b.blobId}</div> },
          { key: "actions", header: <span className="visuallyHidden">actions</span>,
            className: "runsTable__actions", cell: (b) => (
              <>
                <a href={`/api/v1/blob/${encodeURIComponent(b.blobId)}?download=true`}
                   download={inferDownloadFilename(b)} className="btn btn--ghost btn--sm">
                  Download
                </a>
                <button type="button" className="btn btn--ghost btn--sm text--error"
                        onClick={() => setPendingDelete([b])}>
                  Delete
                </button>
              </>
            ) },
        ]}
      />

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
