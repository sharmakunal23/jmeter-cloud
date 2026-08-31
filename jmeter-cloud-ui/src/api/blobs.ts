/**
 * Typed client for the document-service's blob REST API. Mirrors
 * `document-service/api/openapi.yaml`. The UI's nginx proxy routes
 * `^~ /api/v1/blob` to document-service while everything else under
 * `/api/` continues to global-orchestrator (Step 18).
 */

// "template" added in D5 — saved New Run form snapshots are stored as
// blobs with X-Type: template and a JSON body. The blob body is the
// templatesApi.TemplateBody serialised; Documents tab hides the
// "template" type since it isn't a user-managed file artifact.
// "plugin" added in UX-DYNAMICS T3 — a JMeter plugin jar (or a zip bundle
// of jars) backing one hub ORCH_PLUGIN registry row; managed on the
// Plugins page, hidden from Documents like "template".
export type BlobType = "testPlan" | "dataFiles" | "result" | "other" | "template" | "plugin";

export interface BlobMetadata {
  blobId: string;
  sizeBytes: number;
  sha256: string;
  contentType?: string | null;
  uploadedAt: string;
  owner?: string | null;
  name?: string | null;
  description?: string | null;
  type?: string | null;
  /** Step 28 — application tag. Null for legacy uploads. */
  application?: string | null;
}

export interface BlobListing {
  items: BlobMetadata[];
  total: number;
  offset: number;
  limit: number;
}

export class DocumentServiceError extends Error {
  readonly code: string;
  readonly httpStatus: number;
  constructor(httpStatus: number, code: string, message: string) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

/** Human-readable one-liner for any error this module can reject with. */
export function describeBlobError(err: unknown): string {
  if (err instanceof DocumentServiceError) return `${err.code}: ${err.message}`;
  if (err instanceof Error) return err.message;
  return String(err);
}

/** Outcome of a bulk delete: it reports per-blob failures rather than throwing. */
export interface BulkDeleteResult {
  deleted: string[];
  failed: Array<{ blobId: string; message: string }>;
}

/**
 * Parallel DELETEs in flight. Browsers cap HTTP/1.1 sockets per origin at ~6,
 * so a wider fan-out only queues in the browser while making a cancel slower
 * to take effect.
 */
const DELETE_CONCURRENCY = 6;

export interface UploadOptions {
  contentType?: string;
  name?: string;
  description?: string;
  type?: BlobType | string;
  /** Step 28 — application tag (≤64 chars). */
  application?: string;
  /** Reports total bytes sent + total file size; called frequently during upload. */
  onProgress?: (sent: number, total: number) => void;
  signal?: AbortSignal;
}

/**
 * XHR-backed upload — `fetch()` doesn't expose request-body progress
 * events in any browser, so we use {@link XMLHttpRequest} where
 * {@code xhr.upload.onprogress} fires for each TCP-buffer flush. That's
 * what the {@code /blobs} page's progress bar binds to.
 */
export function uploadBlob(file: Blob | File, opts: UploadOptions = {}): Promise<BlobMetadata> {
  return new Promise<BlobMetadata>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/v1/blob");
    xhr.setRequestHeader(
      "Content-Type",
      opts.contentType ?? (file instanceof File ? file.type || "application/octet-stream" : "application/octet-stream"),
    );
    if (opts.name)        xhr.setRequestHeader("X-Name", opts.name);
    if (opts.description) xhr.setRequestHeader("X-Description", opts.description);
    if (opts.type)        xhr.setRequestHeader("X-Type", opts.type);
    if (opts.application) xhr.setRequestHeader("X-Application", opts.application);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && opts.onProgress) {
        opts.onProgress(e.loaded, e.total);
      }
    };

    xhr.onload = () => {
      const status = xhr.status;
      if (status >= 200 && status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as BlobMetadata);
        } catch (parseErr) {
          reject(new DocumentServiceError(status, "PARSE_ERROR", String(parseErr)));
        }
      } else {
        let code = `HTTP_${status}`;
        let message = xhr.responseText || `request failed: HTTP ${status}`;
        try {
          const parsed = JSON.parse(xhr.responseText) as { code?: string; message?: string };
          code = parsed.code ?? code;
          message = parsed.message ?? message;
        } catch {
          // leave fallbacks
        }
        reject(new DocumentServiceError(status, code, message));
      }
    };
    xhr.onerror = () => reject(new DocumentServiceError(0, "NETWORK_ERROR", "upload failed (network)"));
    xhr.onabort = () => reject(new DocumentServiceError(0, "ABORTED", "upload aborted"));

    if (opts.signal) {
      // Bridge AbortSignal → xhr.abort() so callers can cancel mid-upload.
      if (opts.signal.aborted) {
        xhr.abort();
      } else {
        opts.signal.addEventListener("abort", () => xhr.abort(), { once: true });
      }
    }

    xhr.send(file);
  });
}

async function jsonRequest<T>(
  method: "GET" | "DELETE",
  path: string,
  signal?: AbortSignal,
): Promise<T> {
  const resp = await fetch(path, { method, signal });
  const text = await resp.text();
  let parsed: unknown;
  if (text) {
    try { parsed = JSON.parse(text); } catch { /* leave undefined */ }
  }
  if (!resp.ok) {
    const e = parsed as { code?: string; message?: string } | undefined;
    throw new DocumentServiceError(
      resp.status,
      e?.code ?? `HTTP_${resp.status}`,
      e?.message ?? text ?? `request failed: HTTP ${resp.status}`,
    );
  }
  return parsed as T;
}

function deleteBlob(blobId: string, signal?: AbortSignal): Promise<void> {
  return jsonRequest<void>("DELETE", `/api/v1/blob/${encodeURIComponent(blobId)}`, signal);
}

/**
 * Deletes many blobs, at most {@link DELETE_CONCURRENCY} at a time, resolving
 * with what succeeded and what did not — one bad blobId never cancels the rest.
 *
 * <p>The document-service has no batch delete endpoint; this is the one seam to
 * swap if it ever gains one.
 */
async function deleteManyBlobs(blobIds: string[], signal?: AbortSignal): Promise<BulkDeleteResult> {
  const deleted: string[] = [];
  const failed: BulkDeleteResult["failed"] = [];
  let next = 0;
  async function worker(): Promise<void> {
    while (next < blobIds.length) {
      if (signal?.aborted) return;
      const blobId = blobIds[next++];
      try {
        await deleteBlob(blobId, signal);
        deleted.push(blobId);
      } catch (err: unknown) {
        failed.push({ blobId, message: describeBlobError(err) });
      }
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(DELETE_CONCURRENCY, blobIds.length) }, worker),
  );
  return { deleted, failed };
}

export const blobsApi = {
  upload: uploadBlob,

  list: (
    opts: { type?: BlobType | string; application?: string;
            offset?: number; limit?: number } = {},
    signal?: AbortSignal,
  ) => {
    const params = new URLSearchParams();
    if (opts.type)                  params.set("type", opts.type);
    if (opts.application !== undefined) params.set("application", opts.application);
    if (opts.offset !== undefined)  params.set("offset", String(opts.offset));
    if (opts.limit  !== undefined)  params.set("limit",  String(opts.limit));
    const qs = params.toString();
    return jsonRequest<BlobListing>("GET", `/api/v1/blob${qs ? `?${qs}` : ""}`, signal);
  },

  delete: deleteBlob,

  deleteMany: deleteManyBlobs,

  metadata: (blobId: string, signal?: AbortSignal) =>
    jsonRequest<BlobMetadata>("GET", `/api/v1/blob/${encodeURIComponent(blobId)}/metadata`, signal),
};
