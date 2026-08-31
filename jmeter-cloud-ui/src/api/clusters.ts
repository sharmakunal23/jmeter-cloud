/**
 * Typed client for the cluster registry (CLUSTER-CAPACITY) —
 * `/api/v1/regions/*`. The API keeps calling the axis "region"; every
 * user-facing word here is "cluster".
 */

export interface ClusterCheck {
  name: string;
  ok: boolean;
  detail: string;
  code?: string;
}

export interface ClusterCapabilities {
  region?: string;
  namespace?: string;
  headlessService?: string;
  image?: string;
  localOrchestratorPort?: number;
  version?: string;
  workersFree?: number | null;
  workerMemoryMb?: number | null;
  workerEphemeralStorage?: string | null;
}

export interface ClusterProbeVerdict {
  at: string;
  status: "PASS" | "FAIL";
  detail: string;
}

export interface ClusterStatus {
  region: string;
  label: string;
  regionalUrl: string;
  maxWorkers: number;
  /** SUM of every group's reservation on this cluster. */
  reservedWorkers: number;
  /** Worker rows (spun + declared) registered in this cluster. */
  provisionedWorkers: number;
  /** Last reachability probe; null/undefined before the first probe. */
  reachable?: boolean | null;
  lastSeenAt?: string | null;
  lastError?: string | null;
  capabilities?: ClusterCapabilities | null;
  lastValidatedAt?: string | null;
  lastProbe?: ClusterProbeVerdict | null;
  /** True while a test-provisioning probe is running right now. */
  probing: boolean;
}

export interface RegisterClusterRequest {
  region: string;
  label: string;
  regionalUrl: string;
  maxWorkers?: number;
}

export interface UpdateClusterRequest {
  label?: string;
  regionalUrl?: string;
  maxWorkers?: number;
}

export interface ClusterRegistrationResponse {
  cluster: ClusterStatus;
  checks?: ClusterCheck[];
}

export class ClusterApiError extends Error {
  constructor(
    readonly httpStatus: number,
    readonly code: string,
    message: string,
    /** The validation checklist — present on 422 registration failures. */
    readonly checks?: ClusterCheck[],
    /** The parsed body — carries maxWorkers/reserved/… extras on 409s. */
    readonly extra?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "ClusterApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, init);
  if (resp.status === 204) return undefined as T;
  const text = await resp.text();
  let body: Record<string, unknown> = {};
  try {
    body = text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    /* non-JSON body — fall through to the status check */
  }
  if (!resp.ok) {
    throw new ClusterApiError(
      resp.status,
      typeof body.code === "string" ? body.code : `HTTP_${resp.status}`,
      typeof body.message === "string" ? body.message : text || `HTTP ${resp.status}`,
      Array.isArray(body.checks) ? (body.checks as ClusterCheck[]) : undefined,
      body,
    );
  }
  return body as T;
}

function jsonInit(method: string, payload: unknown): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  };
}

export const clustersApi = {
  /** The Clusters page's single read — one row per registered cluster. */
  async status(signal?: AbortSignal): Promise<ClusterStatus[]> {
    const resp = await fetch("/api/v1/regions/status", { signal });
    if (!resp.ok) throw new ClusterApiError(resp.status, `HTTP_${resp.status}`, `status failed: HTTP ${resp.status}`);
    return (await resp.json()) as ClusterStatus[];
  },

  /** Registers a cluster — validated server-side; a 422 carries the ✓/✗ `checks`. */
  register(req: RegisterClusterRequest): Promise<ClusterRegistrationResponse> {
    return request<ClusterRegistrationResponse>("/api/v1/regions", jsonInit("POST", req));
  },

  /** Null/omitted fields keep their value; a changed URL re-validates (422 on failure). */
  update(region: string, req: UpdateClusterRequest): Promise<ClusterRegistrationResponse> {
    return request<ClusterRegistrationResponse>(
      `/api/v1/regions/${encodeURIComponent(region)}`, jsonInit("PUT", req));
  },

  /** 409 CLUSTER_IN_USE while reservations or workers reference it. */
  remove(region: string): Promise<void> {
    return request<void>(`/api/v1/regions/${encodeURIComponent(region)}`, { method: "DELETE" });
  },

  /** Async deep probe — 202; the verdict lands on the next status() poll. */
  testProvision(region: string): Promise<{ region: string; probing: boolean }> {
    return request<{ region: string; probing: boolean }>(
      `/api/v1/regions/${encodeURIComponent(region)}/testProvision`, { method: "POST" });
  },
};
