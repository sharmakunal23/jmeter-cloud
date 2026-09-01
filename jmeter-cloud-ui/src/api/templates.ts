/**
 * D5 — Templates feature. A template is a saved snapshot of the New Run
 * form state, stored in document-service as a blob with
 * {@code X-Type: template} (no new backend endpoint needed). The blob
 * body is the JSON-serialised {@link TemplateBody}.
 *
 * <p>Listing templates uses {@code GET /api/v1/blob?type=template} and
 * returns a thin metadata view; "Use template" downloads the blob body
 * to hydrate the launcher form.
 */

import { TEMPLATES_CACHE, cached, invalidate } from "../lib/resourceCache";

import type { FleetAllocationEntry } from "./runs";
import type { BlobMetadata } from "./blobs";

/**
 * Snapshot of the launcher form. Versioned via {@code v} so we can
 * evolve the schema (extra fields, renamed properties) without breaking
 * stored templates — older bodies fall back to defaults for missing
 * fields at hydrate time.
 */
export interface TemplateBody {
  /** Schema version. v1 = D5 initial. */
  v: number;
  application: string;
  testPlanBlobId: string;
  dataFilesBlobId?: string;
  /**
   * Per-region allocation. Each entry's {@code perNodeProperties[i]} carries
   * the i-th worker's full JMeter {@code -J} property snapshot, so saving a
   * template captures every worker's parameters; hydration restores them
   * verbatim. (Older v1 templates created before the snapshot feature have no
   * {@code perNodeProperties} — those workers fall back to the global set.)
   */
  fleetAllocation: FleetAllocationEntry[];
  globalProperties?: Record<string, string>;
  /**
   * v1 bodies only — the label filter was removed platform-wide (nothing
   * ever read it). Ignored on hydrate, never written by the v2 writer.
   */
  labelFilter?: string;
  /** v2 — global-library plugin ids selected for the run (UX-DYNAMICS T3). */
  pluginIds?: string[];
  /** Save Results — restore the launcher's "upload JTLs on completion" toggle. */
  saveResults?: boolean;
  initiatedBy?: string;
}

export interface TemplateSummary {
  blobId: string;
  /** Operator-supplied template name (X-Name on upload). */
  name: string;
  /** Application this template targets (X-Application on upload). */
  application: string;
  description?: string | null;
  uploadedAt: string;
  sizeBytes: number;
}

export class TemplateApiError extends Error {
  readonly httpStatus: number;
  constructor(httpStatus: number, message: string) {
    super(message);
    this.httpStatus = httpStatus;
  }
}

export const templatesApi = {
  /**
   * All templates, newest-first. Cached: the templates page, the launcher and
   * the schedule dialog each read this whole list on mount, and it only moves
   * when someone saves or deletes one — both of which invalidate below.
   */
  list: (signal?: AbortSignal, opts?: { fresh?: boolean }): Promise<TemplateSummary[]> =>
    cached(`${TEMPLATES_CACHE}:list`, async () => {
      const resp = await fetch("/api/v1/blob?type=template&limit=200");
      if (!resp.ok) throw new TemplateApiError(resp.status, await resp.text());
      const payload = (await resp.json()) as { items: BlobMetadata[] };
      return payload.items.map((b) => ({
        blobId: b.blobId,
        name: b.name ?? "(untitled template)",
        application: b.application ?? "",
        description: b.description ?? null,
        uploadedAt: b.uploadedAt,
        sizeBytes: b.sizeBytes,
      }));
    }, { signal, fresh: opts?.fresh }),

  /** Save a new template. Returns the persisted blob's ID. */
  save: async (
    body: TemplateBody,
    opts: { name: string; description?: string },
    signal?: AbortSignal,
  ): Promise<string> => {
    const json = JSON.stringify(body);
    const resp = await fetch("/api/v1/blob", {
      method: "POST",
      signal,
      headers: {
        "Content-Type": "application/json",
        "X-Type": "template",
        "X-Name": opts.name,
        "X-Application": body.application,
        ...(opts.description ? { "X-Description": opts.description } : {}),
      },
      body: json,
    });
    if (!resp.ok) throw new TemplateApiError(resp.status, await resp.text());
    const meta = (await resp.json()) as BlobMetadata;
    invalidate(TEMPLATES_CACHE);
    return meta.blobId;
  },

  /** Hydrate a template body for the launcher pre-fill flow. */
  load: async (blobId: string, signal?: AbortSignal): Promise<TemplateBody> => {
    const resp = await fetch(`/api/v1/blob/${encodeURIComponent(blobId)}`, { signal });
    if (!resp.ok) throw new TemplateApiError(resp.status, await resp.text());
    const text = await resp.text();
    return JSON.parse(text) as TemplateBody;
  },

  delete: async (blobId: string, signal?: AbortSignal): Promise<void> => {
    const resp = await fetch(`/api/v1/blob/${encodeURIComponent(blobId)}`, {
      method: "DELETE",
      signal,
    });
    if (!resp.ok && resp.status !== 204) {
      throw new TemplateApiError(resp.status, await resp.text());
    }
    invalidate(TEMPLATES_CACHE);
  },
};
