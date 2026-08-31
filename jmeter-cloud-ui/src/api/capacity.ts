/**
 * Typed client for the per-(group, region) capacity surface — the worker pool
 * belongs to the application group, and every application in it draws on it.
 *
 *   PUT    /api/v1/applicationGroups/{groupId}/capacity/{region}                 → setMax
 *   GET    /api/v1/applicationGroups/{groupId}/capacity/{region}/pods            → listPods
 *   POST   /api/v1/applicationGroups/{groupId}/capacity/{region}/pods            → spinPod
 *   POST   /api/v1/applicationGroups/{groupId}/capacity/{region}/pods/{name}/restart
 *   DELETE /api/v1/applicationGroups/{groupId}/capacity/{region}/pods/{name}     → drainPod
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
  /** DYNAMIC = spun by the cluster's regional; STATIC = operator-declared (CLUSTER-CAPACITY). */
  source?: "DYNAMIC" | "STATIC";
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
  groupId: string;
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

/** STATIC-FLEET Phase 7 — response to declaring an operator-deployed worker. */
export interface DeclaredWorkerResponse {
  podName: string;
  groupId: string;
  region: string;
  baseUrl: string;
  source: "STATIC";
  /** False only when `force` was used — the worker did not answer. */
  reachable: boolean;
  /** Declared workers for this (group, region) after the call. */
  declared: number;
  /** Derived capacity written — always equal to `declared` in static mode. */
  maxAvailable: number;
}

export interface ApplicationGroupCapacityRow {
  groupId: string;
  region: string;
  maxAvailable: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface SpinPodResponse {
  podName: string;
  groupId: string;
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

const base = (groupId: string, region: string) =>
  `/api/v1/applicationGroups/${encodeURIComponent(groupId)}/capacity/${encodeURIComponent(region)}`;

/** The server's cap-exceeded code; both spellings are accepted. */
export function isCapacityExceeded(err: unknown): boolean {
  return err instanceof CapacityApiError
    && (err.code === "GROUP_CAPACITY_EXCEEDED" || err.code === "APPLICATION_CAPACITY_EXCEEDED");
}

/** The group a worker is already declared to, from a 409 `POD_BOUND_ELSEWHERE` body. */
export function boundGroupOf(err: CapacityApiError): string | null {
  const g = err.extra?.boundGroupId ?? err.extra?.boundApplicationId;
  return typeof g === "string" ? g : null;
}

export const capacityApi = {
  /** PUT /capacity/{region} — direct UPDATE of maxAvailable, no sponsor gate. */
  setMax: (groupId: string, region: string, maxAvailable: number, signal?: AbortSignal) =>
    request<ApplicationGroupCapacityRow>("PUT", base(groupId, region), { maxAvailable }, signal),

  /** GET /capacity/{region}/pods — full per-(group, region) snapshot. */
  listPods: (groupId: string, region: string, signal?: AbortSignal) =>
    request<CapacitySnapshot>("GET", `${base(groupId, region)}/pods`, undefined, signal),

  /** POST /capacity/{region}/pods — spin one new Ready pod. 409 on cap-exceed. */
  spinPod: (groupId: string, region: string, signal?: AbortSignal) =>
    request<SpinPodResponse>("POST", `${base(groupId, region)}/pods`, undefined, signal),

  /** POST /capacity/{region}/pods/{name}/restart — recycle in place. */
  restartPod: (groupId: string, region: string, podName: string, signal?: AbortSignal) =>
    request<{ podName: string; restarted: boolean }>(
      "POST",
      `${base(groupId, region)}/pods/${encodeURIComponent(podName)}/restart`,
      undefined,
      signal,
    ),

  /**
   * DELETE /capacity/{region}/pods/{name} — release a worker. 409 + blockedBy
   * if in use. In DYNAMIC mode this drains (container stopped + removed); in
   * STATIC mode it undeclares (registry row removed, the operator's worker
   * left running) — `containerStopped` in the response says which happened.
   */
  drainPod: (groupId: string, region: string, podName: string, signal?: AbortSignal) =>
    request<{ podName: string; drained: boolean; containerStopped?: boolean }>(
      "DELETE",
      `${base(groupId, region)}/pods/${encodeURIComponent(podName)}`,
      undefined,
      signal,
    ),

  /**
   * PUT /capacity/{region}/pods/{name} — STATIC-FLEET Phase 7: declare an
   * operator-deployed worker. Static-mode only (409 PROVISIONING_REQUIRES_STATIC
   * otherwise). Idempotent: re-declaring the same name updates its address.
   *
   * `force` skips the reachability probe, for a worker that is deployed but
   * not up yet; without it an unreachable address is refused 400
   * WORKER_UNREACHABLE so a typo fails here rather than at the next run.
   */
  declareWorker: (
    groupId: string,
    region: string,
    podName: string,
    baseUrl: string,
    force = false,
    signal?: AbortSignal,
  ) =>
    request<DeclaredWorkerResponse>(
      "PUT",
      `${base(groupId, region)}/pods/${encodeURIComponent(podName)}${force ? "?force=true" : ""}`,
      { baseUrl },
      signal,
    ),

  /** PUT /capacity/{region} with max=0 — add a region to the group (upsert). */
  addRegion: (groupId: string, region: string, signal?: AbortSignal) =>
    request<ApplicationGroupCapacityRow>("PUT", base(groupId, region), { maxAvailable: 0 }, signal),

  /** DELETE /capacity/{region} — remove a region. 409 REGION_NOT_EMPTY if workers exist. */
  removeRegion: (groupId: string, region: string, signal?: AbortSignal) =>
    request<void>("DELETE", base(groupId, region), undefined, signal),

  /**
   * POST /api/v1/admin/reconcilePods — registry-wide reconcile (NOT per-group).
   * Deletes registry rows whose container is gone (the usual fix for a worker
   * stuck after its container died), adopts managed containers missing a row,
   * and starts ones found stopped. Idempotent + safe — a healthy, heart-beating
   * worker is never touched. Returns the per-bucket worker-name lists.
   */
  reconcileWorkers: (signal?: AbortSignal) =>
    request<ReconcileWorkersResult>("POST", "/api/v1/admin/reconcilePods", undefined, signal),
};
