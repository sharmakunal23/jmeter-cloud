/**
 * D-AppRegistry — typed client for the global-orchestrator's
 * registered-application surface (was: document-service's tag-derived
 * `/applications`). The registry stores operator-managed metadata
 * (sealId, description, healthEndpoints) and a health snapshot
 * polled every minute by ApplicationHealthPoller.
 */

import { getActor } from "../actor";
import {
  APPLICATIONS_CACHE, APPLICATION_GROUPS_CACHE, cached, invalidate,
} from "../lib/resourceCache";

export type HealthStatus = "HEALTHY" | "DEGRADED" | "UNHEALTHY" | "UNKNOWN";

export interface HealthEndpointResult {
  url: string;
  statusCode: number | null;
  latencyMs: number;
  ok: boolean;
  error?: string;
}

export interface Application {
  applicationId: string;
  name: string;
  sealId?: string | null;
  description?: string | null;
  healthEndpoints: string[];
  createdAt: string;
  lastHealthCheckedAt?: string | null;
  lastHealthStatus?: HealthStatus | null;
  lastHealthDetails?: HealthEndpointResult[] | null;
  /**
   * The application group this app belongs to — required. Its workers send it as
   * `?groupId=`, its metrics land in the group's tables, and its runs draw on the
   * group's worker pool (capacity and recycle policy live on the group).
   */
  metricsGroupId: string;
  /** The group classifier's value for this app's labels (`LABEL.APPLICATION`, e.g. `CPS-PCI`). */
  metricsApplication?: string | null;
}

export interface CreateApplicationRequest {
  name: string;
  sealId?: string | null;
  description?: string | null;
  healthEndpoints?: string[];
  /** An existing group's id — required (400 without it); PUT replaces wholesale. */
  metricsGroupId: string;
  /** Omitted/null = upper-cased name when grouped. */
  metricsApplication?: string | null;
}

export interface UpdateApplicationRequest extends CreateApplicationRequest {}

export class ApplicationApiError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  constructor(httpStatus: number, code: string, message: string) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
  }
}

/** Shared JSON fetch for the registry clients (applications, application groups). */
export async function requestJson<T>(
  method: "GET" | "POST" | "PUT" | "DELETE",
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  // Attribute state-changing calls (e.g. the purge tombstone).
  if (method === "POST" || method === "PUT" || method === "DELETE") {
    const actor = getActor();
    if (actor) headers["X-Actor"] = actor;
  }
  const init: RequestInit = {
    method,
    signal,
    headers: Object.keys(headers).length > 0 ? headers : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  };
  const resp = await fetch(path, init);
  if (resp.status === 204) return undefined as unknown as T;
  const text = await resp.text();
  let parsed: unknown = undefined;
  if (text) {
    try { parsed = JSON.parse(text); } catch { /* leave as text */ }
  }
  if (!resp.ok) {
    const err = parsed as { code?: string; message?: string } | undefined;
    throw new ApplicationApiError(
      resp.status,
      err?.code ?? `HTTP_${resp.status}`,
      err?.message ?? text ?? `request failed: HTTP ${resp.status}`,
    );
  }
  return parsed as T;
}

/** Response of `POST /api/v1/applications/{id}/purge` — what the hard delete reclaimed. */
export interface PurgeApplicationResult {
  applicationId: string;
  runsPurged: number;
  metricRowsDeleted: number;
  blobsDeleted: number;
  blobStepComplete: boolean;
}

/** Strip the `__deleted__<id>` archive suffix to recover an app's original display name. */
export function displayName(name: string): string {
  const i = name.indexOf("__deleted__");
  return i === -1 ? name : name.slice(0, i);
}

/**
 * Every application mutation clears the group cache too: a group carries the
 * `applicationCount` that "Manage groups" shows and gates its Delete button on,
 * and create / delete / purge — and an update that moves an app between groups
 * — all move it. Without this the dialog offers Delete on a group that still
 * has applications, and the server's 409 is the only thing that stops it.
 */
function invalidateApplications(): void {
  invalidate(APPLICATIONS_CACHE);
  invalidate(APPLICATION_GROUPS_CACHE);
}

export const applicationsApi = {
  /**
   * The registry, deduplicated and briefly cached — seven surfaces read this
   * list to resolve ONE application, so an uncached read means seven copies of
   * the whole registry per navigation. Pass `{fresh: true}` from a poller that
   * must see a change the moment it lands; every mutation below invalidates.
   */
  list: (signal?: AbortSignal, opts?: { fresh?: boolean }) =>
    cached(`${APPLICATIONS_CACHE}:list`,
      () => requestJson<Application[]>("GET", "/api/v1/applications"),
      { signal, fresh: opts?.fresh }),

  /** Archived view — only soft-deleted (hidden) apps, the hard-delete/purge surface. */
  listHidden: (signal?: AbortSignal) =>
    requestJson<Application[]>("GET", "/api/v1/applications?hidden=true", undefined, signal),

  get: (applicationId: string, signal?: AbortSignal) =>
    requestJson<Application>("GET", `/api/v1/applications/${encodeURIComponent(applicationId)}`,
      undefined, signal),

  create: async (body: CreateApplicationRequest, signal?: AbortSignal) => {
    const created = await requestJson<Application>("POST", "/api/v1/applications", body, signal);
    invalidateApplications();
    return created;
  },

  update: async (applicationId: string, body: UpdateApplicationRequest, signal?: AbortSignal) => {
    const updated = await requestJson<Application>(
      "PUT", `/api/v1/applications/${encodeURIComponent(applicationId)}`, body, signal);
    invalidateApplications();
    return updated;
  },

  delete: async (applicationId: string, signal?: AbortSignal) => {
    await requestJson<void>("DELETE", `/api/v1/applications/${encodeURIComponent(applicationId)}`,
      undefined, signal);
    invalidateApplications();
  },

  /**
   * HARD-DELETE / purge — PERMANENTLY deletes a HIDDEN application and its whole
   * footprint (its runs + blobs + metric rows, health history, the app row).
   * Workers and capacity belong to the group and are untouched. Irreversible.
   * Precondition: the app must already be hidden.
   *
   * <p>Throws {@link ApplicationApiError}:
   *   {@code APPLICATION_NOT_FOUND} (404) — unknown id;
   *   {@code APPLICATION_NOT_PURGEABLE} (409) — exists but not hidden first.
   */
  purge: async (applicationId: string, reason?: string, signal?: AbortSignal) => {
    const trimmed = reason?.trim();
    const body = trimmed ? { reason: trimmed } : undefined;
    const result = await requestJson<PurgeApplicationResult>(
      "POST",
      `/api/v1/applications/${encodeURIComponent(applicationId)}/purge`,
      body,
      signal,
    );
    invalidateApplications();
    return result;
  },
};
