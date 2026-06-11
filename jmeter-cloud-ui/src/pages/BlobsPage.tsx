import { useCallback, useEffect, useRef, useState, type DragEvent, type ChangeEvent } from "react";
import { Link } from "react-router-dom";

import {
  blobsApi,
  DocumentServiceError,
  type BlobListing,
  type BlobMetadata,
  type BlobType,
} from "../api/blobs";
import { applicationsApi, type Application } from "../api/applications";

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

// UI-D2 standardized page size — 25 docs per page, newest-first (the
// document-service already returns rows in `uploadedAt DESC` order).
const PAGE_SIZE = 25;
const TYPES: BlobType[] = ["testPlan", "dataFiles", "result", "other"];

export interface BlobsPageProps {
  /**
   * UI-D2 — when set, locks the listing + the upload type-picker to a
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
          offset, limit: PAGE_SIZE,
        }, ctl.signal)
      .then((listing) => setList({ status: "ok", listing }))
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setList({
          status: "error",
          message: err instanceof Error ? err.message : String(err),
        });
      });
    return ctl;
  }, [typeFilter, appFilter, offset]);

  useEffect(() => {
    const ctl = refresh();
    return () => ctl.abort();
  }, [refresh]);

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
          setUploadError(
            err instanceof DocumentServiceError
              ? `${err.code}: ${err.message}`
              : err instanceof Error
                ? err.message
                : String(err),
          );
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

  // ── Delete handler ───────────────────────────────────────────────

  const onDelete = async (blobId: string) => {
    if (!confirm(`Delete blob ${blobId}?`)) return;
    try {
      await blobsApi.delete(blobId);
      refresh();
    } catch (err) {
      alert(err instanceof Error ? err.message : String(err));
    }
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
          // The Type dropdown is redundant on the Documents tabs (UI-D2);
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
        {list.status === "ok" && (
          <span className="runsToolbar__refreshed">
            {list.listing.total} total
            {list.listing.total > 0 && (
              <>
                &nbsp;· showing {list.listing.offset + 1}–
                {Math.min(list.listing.offset + list.listing.items.length, list.listing.total)}
              </>
            )}
          </span>
        )}
      </div>

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
                  onDelete={onDelete}
                  showType={!pinnedType}
                  showApplication={!pinnedApplication}
                />
              ))}
            </tbody>
          </table>
          <Pagination
            total={list.listing.total}
            offset={list.listing.offset}
            limit={list.listing.limit}
            onChange={setOffset}
          />
        </>
      )}
    </section>
  );
}

function BlobRow({
  blob,
  onDelete,
  showType,
  showApplication,
}: {
  blob: BlobMetadata;
  onDelete: (blobId: string) => void;
  showType: boolean;
  showApplication: boolean;
}) {
  // Phase IA-Documents (2026-05-12) — drop the operator-supplied "name"
  // line; just show the filename. The two were almost always
  // duplicates (e.g. "smoketest-checkout" vs "smoketest-checkout.jmx")
  // and ate horizontal space without adding signal.
  const filename = inferDownloadFilename(blob);
  return (
    <tr>
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
          onClick={() => onDelete(blob.blobId)}
        >
          Delete
        </button>
      </td>
    </tr>
  );
}

/**
 * Mirrors the server-side {@code BlobController#inferDownloadFilename}
 * — keeps client + server in lockstep so the visible filename matches
 * what the browser actually saves. Conventions:
 * {@code testPlan → .jmx · dataFiles → .zip · result → .jtl.gz · other → .bin}.
 * If the operator-supplied name already carries an extension, trust it.
 */
export function inferDownloadFilename(blob: Pick<BlobMetadata, "name" | "type" | "blobId">): string {
  const base = blob.name && blob.name.trim() ? blob.name.trim() : blob.blobId;
  const safe = base.replace(/[-"\/]/g, "_");
  if (safe.includes(".")) return safe;
  switch (blob.type) {
    case "testPlan": return safe + ".jmx";
    case "dataFiles": return safe + ".zip";
    case "result": return safe + ".jtl.gz";
    default: return safe + ".bin";
  }
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

function Pagination({
  total,
  offset,
  limit,
  onChange,
}: {
  total: number;
  offset: number;
  limit: number;
  onChange: (next: number) => void;
}) {
  if (total <= limit) return null;
  const hasPrev = offset > 0;
  const hasNext = offset + limit < total;
  return (
    <div className="pagination">
      <button
        type="button"
        className="btn"
        disabled={!hasPrev}
        onClick={() => onChange(Math.max(0, offset - limit))}
      >
        ← Prev
      </button>
      <span className="text--muted">
        {Math.floor(offset / limit) + 1} / {Math.ceil(total / limit)}
      </span>
      <button
        type="button"
        className="btn"
        disabled={!hasNext}
        onClick={() => onChange(offset + limit)}
      >
        Next →
      </button>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}
