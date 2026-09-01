/**
 * Typed client for the hub's global JMeter plugin library
 * (`/api/v1/plugins`, the `ORCH_PLUGIN` registry). One version per plugin
 * name — an upgrade is delete + re-upload. The jar bytes live in a
 * document-service blob (`X-Type: plugin`) uploaded first; registration
 * references its blobId, and on a 409 the hub deletes the orphan blob
 * itself (`orphanBlobDeleted`) — the UI never deletes blobs.
 */

import { PLUGINS_CACHE, cached, invalidate } from "../lib/resourceCache";

import { getActor } from "../actor";

export interface PluginSummary {
  pluginId: string;
  name: string;
  version: string;
  sizeBytes: number;
  sha256: string;
  description?: string | null;
  /** The uploaded file's name — `.jar` = single plugin, `.zip` = bundle of jars. */
  fileName: string;
  createdAt: string;
  createdBy?: string | null;
}

export interface CreatePluginRequest {
  name: string;
  version: string;
  blobId: string;
  description?: string;
}

/** A 409 carries the colliding registry row in {@link existing}. */
export class PluginApiError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  readonly existing?: { pluginId: string; name: string; version: string };
  constructor(
    httpStatus: number, code: string, message: string,
    existing?: { pluginId: string; name: string; version: string },
  ) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
    this.existing = existing;
  }
}

async function request<T>(
  method: "GET" | "POST" | "DELETE", path: string, body?: unknown, signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") {
    const actor = getActor();
    if (actor) headers["X-Actor"] = actor;
  }
  const resp = await fetch(path, {
    method,
    signal,
    headers: Object.keys(headers).length > 0 ? headers : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (resp.status === 204) return undefined as unknown as T;
  const text = await resp.text();
  let parsed: unknown;
  if (text) { try { parsed = JSON.parse(text); } catch { /* leave as text */ } }
  if (!resp.ok) {
    const err = parsed as {
      code?: string; message?: string;
      existing?: { pluginId: string; name: string; version: string };
    } | undefined;
    throw new PluginApiError(
      resp.status,
      err?.code ?? `HTTP_${resp.status}`,
      err?.message ?? (text || `request failed: HTTP ${resp.status}`),
      err?.existing,
    );
  }
  return parsed as T;
}

export const pluginsApi = {
  /** The library. Cached — the plugins page and the run launcher both read it on mount. */
  list: (signal?: AbortSignal, opts?: { fresh?: boolean }) =>
    cached(`${PLUGINS_CACHE}:list`,
      () => request<PluginSummary[]>("GET", "/api/v1/plugins"),
      { signal, fresh: opts?.fresh }),
  create: async (req: CreatePluginRequest, signal?: AbortSignal) => {
    const created = await request<PluginSummary>("POST", "/api/v1/plugins", req, signal);
    invalidate(PLUGINS_CACHE);
    return created;
  },
  /** Idempotent — an unknown id is already 204 on the hub. */
  delete: async (pluginId: string, signal?: AbortSignal) => {
    await request<void>("DELETE", `/api/v1/plugins/${encodeURIComponent(pluginId)}`, undefined, signal);
    invalidate(PLUGINS_CACHE);
  },
};
