/**
 * Typed client for the per-(application, region) capacity surface.
 *
 *   PUT    /api/v1/applications/{id}/capacity/{region}                 → setMax
 *   GET    /api/v1/applications/{id}/capacity/{region}/pods            → listPods
 *   POST   /api/v1/applications/{id}/capacity/{region}/pods            → spinPod
 *   POST   /api/v1/applications/{id}/capacity/{region}/pods/{name}/restart
 *   DELETE /api/v1/applications/{id}/capacity/{region}/pods/{name}     → drainPod
 *
 * Wraps non-2xx responses in `CapacityApiError` carrying the structured
 * `code`, the HTTP status, and any `blockedBy` payload — the UI uses
 * those to render specific 409 toasts ("cannot drain — held by run X").
 */

export type PodState = "READY" | "IN_USE" | "LOST" | "UNKNOWN" | "RECYCLING";

export interface PodBlockedBy {
  runId: string;
  state: string;
  startedAt?: string | null;
  initiatedBy: string;
}

export interface PodView {
  podName: string;
  state: PodState;
  containerRunning: boolean;
  lastHeartbeat?: string | null;
  blockedBy?: PodBlockedBy | null;
  /** WORKER-HYGIENE Phase F1 — count of runs claimed against this pod. */
  runsServed?: number;
  /** WORKER-HYGIENE Phase F1 — sha256 ID of the image; null for legacy rows. */
  imageDigest?: string | null;
  /** WORKER-HYGIENE Phase F1 — container-create timestamp; null for legacy rows. */
  provisionedAt?: string | null;
}

export interface CapacitySnapshot {
  applicationId: string;
  region: string;
  maxAvailable: number;
  /** ready + inUse — pods that exist as containers right now. */
  provisioned: number;
  ready: number;
  inUse: number;
  /** maxAvailable - provisioned, clamped at 0. */
  spinnable: number;
  pods: PodView[];
}

export interface ApplicationCapacityRow {
  applicationId: string;
  region: string;
  maxAvailable: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface SpinPodResponse {
  podName: string;
  applicationId: string;
  region: string;
  baseUrl: string;
  provisioned: number;
  maxAvailable: number;
}

/**
 * Result of the registry-wide reconcile (`POST /api/v1/admin/reconcilePods`).
 * Each bucket is a list of worker (pod) names the sweep acted on.
 */
export interface ReconcileWorkersResult {
  /** Managed containers that had no registry row — a row was inserted. */
  adopted: string[];
  /** Managed containers found stopped — they were started. */
  started: string[];
  /** Registry rows whose container is gone — the row was deleted (the usual stuck-worker cleanup). */
  orphansDeleted: string[];
  /** Per-worker errors; the sweep continues past each. */
  errors: string[];
}

export class CapacityApiError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  readonly blockedBy?: PodBlockedBy;
  readonly extra?: Record<string, unknown>;
  constructor(
    httpStatus: number,
    code: string,
    message: string,
    blockedBy?: PodBlockedBy,
    extra?: Record<string, unknown>,
  ) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
    this.blockedBy = blockedBy;
    this.extra = extra;
  }
}

async function request<T>(
  method: "GET" | "POST" | "PUT" | "DELETE",
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const init: RequestInit = {
    method,
    signal,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  };
  const resp = await fetch(path, init);
  if (resp.status === 204) return undefined as unknown as T;
  const text = await resp.text();
  let parsed: unknown = undefined;
  if (text) {
    try { parsed = JSON.parse(text); } catch { /* leave undefined */ }
  }
  if (!resp.ok) {
    const err = parsed as
      | { code?: string; message?: string; blockedBy?: PodBlockedBy } & Record<string, unknown>
      | undefined;
    throw new CapacityApiError(
      resp.status,
      err?.code ?? `HTTP_${resp.status}`,
      err?.message ?? text ?? `request failed: HTTP ${resp.status}`,
      err?.blockedBy,
      err,
    );
  }
  return parsed as T;
}

const base = (applicationId: string, region: string) =>
  `/api/v1/applications/${encodeURIComponent(applicationId)}/capacity/${encodeURIComponent(region)}`;

export const capacityApi = {
  /** PUT /capacity/{region} — direct UPDATE of maxAvailable, no sponsor gate. */
  setMax: (applicationId: string, region: string, maxAvailable: number, signal?: AbortSignal) =>
    request<ApplicationCapacityRow>("PUT", base(applicationId, region), { maxAvailable }, signal),

  /** GET /capacity/{region}/pods — full per-(app, region) snapshot. */
  listPods: (applicationId: string, region: string, signal?: AbortSignal) =>
    request<CapacitySnapshot>("GET", `${base(applicationId, region)}/pods`, undefined, signal),

  /** POST /capacity/{region}/pods — spin one new Ready pod. 409 on cap-exceed. */
  spinPod: (applicationId: string, region: string, signal?: AbortSignal) =>
    request<SpinPodResponse>("POST", `${base(applicationId, region)}/pods`, undefined, signal),

  /** POST /capacity/{region}/pods/{name}/restart — recycle in place. */
  restartPod: (applicationId: string, region: string, podName: string, signal?: AbortSignal) =>
    request<{ podName: string; restarted: boolean }>(
      "POST",
      `${base(applicationId, region)}/pods/${encodeURIComponent(podName)}/restart`,
      undefined,
      signal,
    ),

  /** DELETE /capacity/{region}/pods/{name} — drain. 409 + blockedBy if in use. */
  drainPod: (applicationId: string, region: string, podName: string, signal?: AbortSignal) =>
    request<{ podName: string; drained: boolean }>(
      "DELETE",
      `${base(applicationId, region)}/pods/${encodeURIComponent(podName)}`,
      undefined,
      signal,
    ),

  /** PUT /capacity/{region} with max=0 — add a region to the app (upsert). */
  addRegion: (applicationId: string, region: string, signal?: AbortSignal) =>
    request<ApplicationCapacityRow>("PUT", base(applicationId, region), { maxAvailable: 0 }, signal),

  /** DELETE /capacity/{region} — remove a region. 409 REGION_NOT_EMPTY if workers exist. */
  removeRegion: (applicationId: string, region: string, signal?: AbortSignal) =>
    request<void>("DELETE", base(applicationId, region), undefined, signal),

  /**
   * POST /api/v1/admin/reconcilePods — registry-wide reconcile (NOT per-app).
   * Deletes registry rows whose container is gone (the usual fix for a worker
   * stuck after its container died), adopts managed containers missing a row,
   * and starts ones found stopped. Idempotent + safe — a healthy, heart-beating
   * worker is never touched. Returns the per-bucket worker-name lists.
   */
  reconcileWorkers: (signal?: AbortSignal) =>
    request<ReconcileWorkersResult>("POST", "/api/v1/admin/reconcilePods", undefined, signal),
};
