/**
 * Typed client for the global-orchestrator's run-management REST API.
 * Mirrors `jmeter-global-orchestrator/api/openapi.yaml`. All requests
 * go through the nginx reverse-proxy at `/api/v1/*`, so URLs here are
 * relative — keeps the UI host-agnostic.
 */

import { getActor } from "../actor";

export type RunState =
  | "PREPARING"
  | "STARTING"
  | "RUNNING"
  | "DRAINING"
  | "COMPLETED"
  | "FAILED"
  | "ABORTED";

export type MemberState =
  | "PENDING"
  | "REQUESTED"
  | "ACCEPTED"
  | "RUNNING"
  // Operator drained via POST /runs/{runId}/scaleDown.
  | "DRAINING"
  | "COMPLETED"
  | "FAILED"
  | "ABORTED"
  // Successful terminal after graceful drain.
  | "DRAINED";

export interface RunFleetMember {
  runId: string;
  workerId: string;
  region: string;
  state: MemberState;
  stateReason?: string | null;
  fanoutStatusCode?: number | null;
  podBaseUrl?: string | null;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  /**
   * Null for original-fleet members; >= 0 for
   * mid-test scale-up joiners (seconds since `run.startedAt`). UI renders
   * a "joined +Xm" chip on rows with a non-null value.
   */
  joinedAtSecond?: number | null;
  /**
   * Joined from `pod.runsServed` server-side.
   * Null when the pod row is gone. UI uses this to flag workers whose
   * pod is near its recycle threshold.
   */
  runsServed?: number | null;
  /**
   * Per-worker JMeter `-J` properties snapshot taken at launch (the
   * fleet-wide globals plus any per-worker drawer overrides). The
   * scale-up modal recovers "the global properties set before the test"
   * from the original-fleet members' snapshots so new workers can
   * inherit + tweak them. May be an empty object.
   */
  properties?: Record<string, string>;
}

export interface Run {
  runId: string;
  originRegion: string;
  testPlanBlobId: string;
  dataFilesBlobId?: string | null;
  /** UI-D3 — application this run was launched against. NULL for legacy rows. */
  application?: string | null;
  /** Save Results — true → workers uploaded their JTLs; results downloadable as one zip. */
  saveResults?: boolean;
  initiatedBy: string;
  state: RunState;
  stateReason?: string | null;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  fleetMembers: RunFleetMember[];
}

/** AUDIT-TRAIL — kind of event on the run's audit timeline. */
export type RunEventType =
  | "RUN_START"
  | "SCALE_UP"
  | "SCALE_DOWN"
  | "DRAIN_WORKER"
  | "ABORT"
  | "STOP"
  // Platform-detected lifecycle events (actorSource = system).
  | "RESULTS_SAVED"
  | "RUN_COMPLETED"
  | "RUN_FAILED"
  | "RUN_ABORTED"
  | "WORKERS_RECYCLED";

/** AUDIT-TRAIL — how the server learned the actor identity. `system` = automated/platform-initiated. */
export type ActorSource = "anonymous" | "headerActor" | "oidcSubject" | "iamRole" | "system";

/**
 * One append-only audit event from
 * {@code GET /api/v1/runs/{runId}/events}. `result` is `ok` / `partial` /
 * `rejected:CODE`. `payload` is a per-eventType contract (see openapi.yaml).
 */
export interface RunEvent {
  eventId: string;
  runId: string;
  eventType: RunEventType;
  actor: string;
  actorSource: ActorSource;
  payload: Record<string, unknown>;
  result: string;
  occurredAt: string;
}

/** AUDIT-TRAIL — one page of events plus the total count (from `X-Total-Count`). */
export interface RunEventsListing {
  events: RunEvent[];
  total: number;
}

/**
 * Per-second timeseries returned by
 * {@code GET /api/v1/runs/{runId}/timeseries}. Drives the four native
 * charts in the run-detail Metrics tab.
 */
export interface TimeseriesPoint {
  /** Unix epoch second. */
  sec: number;
  /** Metric value (TPS / ms / percentage / count). */
  v: number;
}

/**
 * Time-window selector for the metrics charts — the last N of the run, or
 * the whole test. Mirrors the values the global-orchestrator accepts on
 * {@code GET /runs/{id}/timeseries?window=…}.
 */
export type MetricsWindow = "all" | "5m" | "10m" | "15m" | "30m" | "1h" | "2h" | "4h";

/** Bucket width in seconds — the Grafana granularity picker's values; omit for the server's automatic choice. */
export type MetricsGranularity = 15 | 30 | 60;

/** The series the charts consume — shared by the total and each region / application / label split. */
export interface MetricsTimeseriesSeries {
  tps:      TimeseriesPoint[];
  avgRtMs:  TimeseriesPoint[];
  /** 100 × (HTTP 4xx + 5xx) / samples per bucket. */
  errorPct: TimeseriesPoint[];
  /** Counts per second by HTTP class: keys `2xx`, `3xx`, `4xx`, `5xx`, `other` (the schema keeps no per-code detail). */
  statusCodes: Record<string, TimeseriesPoint[]>;
  /** Throughput-weighted p90 / p95 / p99 per bucket. */
  p90Ms?: TimeseriesPoint[];
  p95Ms?: TimeseriesPoint[];
  p99Ms?: TimeseriesPoint[];
}

/** How the charts split the run: one line, or one per application (`LABEL.APPLICATION`) or region (`WORKER.REGION`). */
export type MetricsSplit = "none" | "application" | "region";

/** True for the three states after which a run's rows stop changing. */
export function isTerminalRunState(state: RunState): boolean {
  return state === "COMPLETED" || state === "FAILED" || state === "ABORTED";
}

export interface MetricsTimeseries {
  runId: string;
  /** Seconds per point: 15 (the workers' window), 30 or 60 — the server's automatic choice or the requested granularity. */
  bucketSize: number;
  fromSecond: number | null;
  toSecond: number | null;
  series: MetricsTimeseriesSeries;
  /**
   * Per-region breakdown (region → the same four series), present only when
   * the request passed `byRegion=true` (the run-detail "Split by region"
   * toggle). The all-regions total in {@link series} is the exact fold of
   * these. Absent/empty when the breakdown wasn't requested.
   */
  regions?: Record<string, MetricsTimeseriesSeries>;
  /** Per-application split (`LABEL.APPLICATION`), present only with `byApplication=true`. */
  applications?: Record<string, MetricsTimeseriesSeries>;
  /** Per-label split (`LABEL.LABEL_KEY`), present only with `byLabel=true` — the `labelLimit` busiest labels (10 by default), busiest first. */
  labels?: Record<string, MetricsTimeseriesSeries>;
  /** With `byLabel=true`: how many labels matched before the cap. */
  labelsTotal?: number | null;
}

/**
 * One aggregate over a range — the hosted dashboard's "Key Metrics" formulas:
 * `tps` is samples over the span the rows cover, percentiles are
 * throughput-weighted. `application` is set on the per-application rows only.
 */
export interface RunSummaryStats {
  application?: string | null;
  samples: number;
  /** HTTP 4xx + 5xx samples. */
  errors: number;
  tps: number;
  /** 100 × errors / samples. */
  errorPct: number;
  avgMs: number;
  p90Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
  maxActiveThreads: number;
}

/** `GET /api/v1/runs/{runId}/summary` — the headline numbers and the per-application table. */
export interface RunSummary {
  runId: string;
  fromSecond: number | null;
  toSecond: number | null;
  total: RunSummaryStats;
  /** Busiest application first; empty until rows land. */
  byApplication: RunSummaryStats[];
}

/** One row of the aggregate report — `GET /api/v1/runs/{runId}/metrics`, busiest label first. */
export interface RunLabelRollup {
  label: string;
  application?: string | null;
  totalThroughput: number;
  /** JMeter's failed samples (the success flag). */
  totalErrors: number;
  /** totalErrors / samples — a fraction, not a percentage. */
  errorRate: number;
  /** HTTP 4xx + 5xx samples — what the dashboard calls errors. */
  httpErrors: number;
  /** httpErrors / samples — a fraction. */
  httpErrorRate: number;
  throughputRps: number;
  avgMs: number;
  avgP50Ms: number;
  avgP90Ms: number;
  avgP95Ms: number;
  avgP99Ms: number;
  maxMs: number;
  maxActiveThreads: number;
  firstSecond: number;
  lastSecond: number;
  rowCount: number;
}

export interface RunMetricsRollup {
  runId: string;
  state: RunState;
  byLabel: RunLabelRollup[];
}

/**
 * Batched per-run timeseries returned by
 * {@code GET /api/v1/runs/timeseries?ids=A,B}. Drives the two-run
 * comparison view. Partial-200 shape: if one of the
 * two ids has been purged, the other still surfaces in {@link runs}
 * and the missing id appears in {@link missing}.
 *
 * <p>{@link runs} preserves the order of the {@code ids} query
 * parameter so the UI can map the first id to its left column.
 */
export interface MetricsTimeseriesBatch {
  runs: Record<string, MetricsTimeseries>;
  missing: string[];
}

export interface FleetAllocationEntry {
  region: string;
  count: number;
  /**
   * Track G (Step 31) — per-pod JMeter `-J` properties. Index `i`
   * applies to the i-th pod claimed in this region. Length ≤ count;
   * missing or empty entries → no extra props for that pod.
   *
   * <p>UI-C2.b polish: each entry is now a *snapshot* of the global
   * properties taken at the moment the worker was added (plus any
   * subsequent drawer overrides). Changing globals after the fact
   * does NOT mutate existing snapshots — operators can rely on a
   * worker's properties being immutable from the moment of creation.
   */
  perNodeProperties?: Array<Record<string, string>>;
}

/**
 * Per-worker lifecycle status surfaced as a small icon on each tile in
 * the Flow view. Pre-launch all workers sit in {@code READY}; the
 * other states reflect future backend signals (heartbeat / drain /
 * stop) once the global-orchestrator exposes them.
 */
export type WorkerStatus =
  | "READY"        // pre-launch / freshly added; no JMeter process yet
  | "INITIATING"   // run started, JMeter spinning up
  | "HEALTHY"      // RUNNING, heartbeating
  | "UNHEALTHY"    // heartbeat stale or last sample errored
  | "DRAINING"     // marked for removal mid-test
  | "STOPPED";     // terminal — completed/failed/aborted

/**
 * Body of `POST /api/v1/runs/{runId}/scaleUp`.
 * The original run's testPlan + dataFiles blob IDs come from the persisted
 * run row; the request only carries the per-region allocation.
 */
export interface ScaleUpRunRequest {
  allocations: FleetAllocationEntry[];
}

/**
 * Response of `POST /api/v1/runs/{runId}/scaleUp`.
 * `run` is the post-scale snapshot (original + newly added members).
 * `partial` is true iff `granted < requested` (only with bestEffort=true).
 */
export interface ScaleUpRunResponse {
  run: Run;
  requested: number;
  granted: number;
  partial: boolean;
  stateReason?: string | null;
}

/**
 * Body of `POST /api/v1/runs/{runId}/scaleDown`.
 * Supply EXACTLY ONE of {@link workerIds} or {@link allocations}:
 *   - {@code workerIds} — explicit list of pod ids to drain.
 *   - {@code allocations} — per-region drain count; the service picks the
 *     N most-recently-created RUNNING members in that region (youngest-first).
 */
export interface ScaleDownRunRequest {
  workerIds?: string[];
  allocations?: FleetAllocationEntry[];
}

/**
 * Response of `POST /api/v1/runs/{runId}/scaleDown`.
 * {@link drained} lists workerIds that were targeted and accepted by their
 * local-orch. {@link skipped} lists targets that were already terminal,
 * already DRAINING, or whose drain RPC failed — each with a one-line reason.
 */
export interface ScaleDownRunResponse {
  run: Run;
  drained: string[];
  skipped: Array<{ workerId: string; reason: string }>;
}

export interface StartRunRequest {
  testPlanBlobId: string;
  dataFilesBlobId?: string;
  /** UI-D3 — application this run is launched against. */
  application?: string;
  /** Track F multi-region shape — wins over fleetSize/regions when present. */
  fleetAllocation?: FleetAllocationEntry[];
  /** Legacy — used when fleetAllocation is absent. */
  fleetSize?: number;
  regions?: string[];
  labelFilter?: string[];
  initiatedBy?: string;
  /**
   * When true, a shortfall during claim
   * triggers an on-the-fly spin (subject to the application group's capacity
   * ceiling). Default false. The launcher sets this only after the
   * operator confirms a shortfall dialog rendered from the 503 body.
   */
  spinShortfall?: boolean;
  /**
   * Save Results — when true, each worker uploads its JTL to the Document
   * Service on a clean COMPLETE, downloadable as one combined zip per run.
   * Default false.
   */
  saveResults?: boolean;
}

/**
 * Paginated listing result. {@code total} comes from the
 * {@code X-Total-Count} response header so the body wire format stays
 * a flat array.
 */
export interface RunListing {
  runs: Run[];
  total: number;
  offset: number;
  limit: number;
}

/** Response of `POST /api/v1/runs/{runId}/purge` — what the hard delete reclaimed. */
export interface PurgeRunResult {
  runId: string;
  metricRowsDeleted: number;
  blobsDeleted: number;
  /** False when document-service was unreachable; result blobs were left behind. */
  blobStepComplete: boolean;
}

export interface RegionShortfall {
  region: string;
  requested: number;
  claimed: number;
}

export interface ApiError {
  code: string;
  message: string;
  /** Present on INSUFFICIENT_CAPACITY responses. */
  shortfall?: RegionShortfall[];
  /** Present on UNKNOWN_REGION responses. */
  regions?: string[];
  /** Present on FLEET_SIZE_EXCEEDED responses. */
  requested?: number;
  max?: number;
}

export class GlobalOrchestratorError extends Error {
  readonly code: string;
  readonly httpStatus: number;
  readonly shortfall?: RegionShortfall[];
  readonly regions?: string[];
  readonly requested?: number;
  readonly max?: number;
  constructor(httpStatus: number, code: string, message: string, extras?: Partial<ApiError>) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.shortfall = extras?.shortfall;
    this.regions = extras?.regions;
    this.requested = extras?.requested;
    this.max = extras?.max;
  }
}

/**
 * Shared HTTP helper — also reused by sibling API clients (e.g. `api/ai.ts`)
 * so the fetch + `X-Actor` + `GlobalOrchestratorError` handling lives in one
 * place. Exported for that reuse; most callers go through `runsApi`.
 */
export async function request<T>(
  method: "GET" | "POST" | "DELETE",
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  // Attach the operator's self-attested identity on every
  // state-changing call so the global-orchestrator can record who did it.
  // Reads are unaffected. Absent actor → header omitted → server defaults
  // to "anonymous".
  if (method === "POST" || method === "DELETE") {
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
  const text = await resp.text();
  let parsed: unknown = undefined;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      // leave parsed undefined; we'll fall back to text
    }
  }
  if (!resp.ok) {
    const err = parsed as Partial<ApiError> | undefined;
    throw new GlobalOrchestratorError(
      resp.status,
      err?.code ?? `HTTP_${resp.status}`,
      err?.message ?? text ?? `request failed: HTTP ${resp.status}`,
      err,
    );
  }
  return parsed as T;
}

export const runsApi = {
  start: (req: StartRunRequest, opts: { bestEffort?: boolean } = {}, signal?: AbortSignal) => {
    const path = opts.bestEffort ? "/api/v1/runs?bestEffort=true" : "/api/v1/runs";
    return request<Run>("POST", path, req, signal);
  },

  /**
   * Adds workers to a RUNNING run, strictly by default — `bestEffort` accepts a
   * partial claim rather than rolling back on a per-region shortfall, and
   * `spinShortfall` provisions the missing pods up to the capacity ceiling and
   * retries, letting the operator go beyond the currently-IDLE count. Both
   * mirror `start()`.
   *
   * Throws {@link GlobalOrchestratorError} with:
   *   - `RUN_NOT_FOUND` (404)
   *   - `RUN_NOT_SCALABLE` (409) — run is not RUNNING
   *   - `RUN_NOT_SCALABLE_NO_APPLICATION` (409) — untagged run has no group whose capacity could gate it
   *   - `APPLICATION_CAPACITY_EXCEEDED` (409) — per-(app, region) ceiling hit
   *   - `INSUFFICIENT_CAPACITY` (503) — strict-mode shortfall; the body carries
   *     a structured {@link ApiError.shortfall}
   */
  scaleUp: (
    runId: string,
    req: ScaleUpRunRequest,
    opts: { bestEffort?: boolean; spinShortfall?: boolean } = {},
    signal?: AbortSignal,
  ) => {
    const params = new URLSearchParams();
    if (opts.bestEffort) params.set("bestEffort", "true");
    if (opts.spinShortfall) params.set("spinShortfall", "true");
    const qs = params.toString();
    return request<ScaleUpRunResponse>(
      "POST",
      `/api/v1/runs/${encodeURIComponent(runId)}/scaleUp${qs ? `?${qs}` : ""}`,
      req,
      signal,
    );
  },

  /**
   * Drains workers from a RUNNING run via
   * graceful drain (JMeter TCP shutdown port → in-flight samplers
   * complete → DRAINED on clean exit; drain timeout → ABORTED with
   * reason {@code drainTimeoutExpired}).
   *
   * <p>Body supplies exactly one of {@code workerIds} (explicit ids) or
   * {@code allocations} (per-region count, youngest-first).
   *
   * <p>May throw {@link GlobalOrchestratorError} with codes:
   *   <ul>
   *     <li>{@code RUN_NOT_FOUND} (404).</li>
   *     <li>{@code RUN_NOT_SCALABLE} (409) — run state ≠ RUNNING.</li>
   *     <li>{@code INVALID_REQUEST} (400) — missing/both paths, unknown
   *         workerId, etc.</li>
   *   </ul>
   */
  scaleDown: (
    runId: string,
    req: ScaleDownRunRequest,
    signal?: AbortSignal,
  ) => {
    return request<ScaleDownRunResponse>(
      "POST",
      `/api/v1/runs/${encodeURIComponent(runId)}/scaleDown`,
      req,
      signal,
    );
  },

  /**
   * Force-terminate a run — the hard-stop / zombie-run cleanup primitive
   * (distinct from {@link scaleDown}'s graceful drain). Rolls the WHOLE run to
   * ABORTED, best-effort hard-kills each live worker, and releases every
   * fleet-member binding so the run's pods free up. Works even when workers
   * are stuck or unreachable (a graceful drain can't end those). Optional
   * `reason` rides onto the run's ABORT audit event.
   *
   * <p>May throw {@link GlobalOrchestratorError} with codes:
   *   <ul>
   *     <li>{@code RUN_NOT_FOUND} (404).</li>
   *     <li>{@code RUN_NOT_ABORTABLE} (409) — the run is already terminal.</li>
   *   </ul>
   */
  abort: (runId: string, reason?: string, signal?: AbortSignal) => {
    const trimmed = reason?.trim();
    const body = trimmed ? { reason: trimmed } : undefined;
    return request<Run>(
      "POST",
      `/api/v1/runs/${encodeURIComponent(runId)}/abort`,
      body,
      signal,
    );
  },

  /**
   * Soft-delete ("hide") a run so it drops out of the default listing — the
   * declutter primitive. The run's data is RETAINED (reversible); only TERMINAL
   * runs can be hidden. Optional `reason` rides onto the run's DELETE audit
   * event.
   *
   * <p>May throw {@link GlobalOrchestratorError} with codes:
   *   <ul>
   *     <li>{@code RUN_NOT_FOUND} (404).</li>
   *     <li>{@code RUN_NOT_DELETABLE} (409) — the run is still active.</li>
   *   </ul>
   */
  delete: (runId: string, reason?: string, signal?: AbortSignal) => {
    const trimmed = reason?.trim();
    const body = trimmed ? { reason: trimmed } : undefined;
    return request<Run>(
      "DELETE",
      `/api/v1/runs/${encodeURIComponent(runId)}`,
      body,
      signal,
    );
  },

  /**
   * HARD-DELETE / purge — PERMANENTLY deletes a HIDDEN, terminal run and the
   * storage it occupies (result blobs, metric rows, run-state rows). Irreversible.
   * Precondition: the run must already be hidden (via {@link delete}).
   *
   * <p>May throw {@link GlobalOrchestratorError} with codes:
   *   <ul>
   *     <li>{@code RUN_NOT_FOUND} (404).</li>
   *     <li>{@code RUN_NOT_PURGEABLE} (409) — still active, or not hidden first.</li>
   *   </ul>
   */
  purge: (runId: string, reason?: string, signal?: AbortSignal) => {
    const trimmed = reason?.trim();
    const body = trimmed ? { reason: trimmed } : undefined;
    return request<PurgeRunResult>(
      "POST",
      `/api/v1/runs/${encodeURIComponent(runId)}/purge`,
      body,
      signal,
    );
  },

  list: (
    opts: { activeOnly?: boolean; limit?: number } = {},
    signal?: AbortSignal,
  ) => {
    const params = new URLSearchParams();
    if (opts.activeOnly) params.set("state", "active");
    if (opts.limit !== undefined) params.set("limit", String(opts.limit));
    const qs = params.toString();
    return request<Run[]>("GET", `/api/v1/runs${qs ? `?${qs}` : ""}`, undefined, signal);
  },

  /**
   * Paginated + application-filtered listing. Reads the total
   * count from the {@code X-Total-Count} response header (the body
   * itself is a flat array per the OpenAPI spec).
   */
  listPage: async (
    opts: { activeOnly?: boolean; application?: string; hidden?: boolean; offset?: number; limit?: number } = {},
    signal?: AbortSignal,
  ): Promise<RunListing> => {
    const params = new URLSearchParams();
    if (opts.activeOnly)            params.set("state", "active");
    if (opts.application)           params.set("application", opts.application);
    // hidden=true is the "Archived" view (only hidden runs — the purge surface).
    if (opts.hidden)                params.set("hidden", "true");
    if (opts.offset !== undefined)  params.set("offset", String(opts.offset));
    if (opts.limit !== undefined)   params.set("limit",  String(opts.limit));
    const qs = params.toString();
    const path = `/api/v1/runs${qs ? `?${qs}` : ""}`;
    const resp = await fetch(path, { method: "GET", signal });
    const text = await resp.text();
    if (!resp.ok) {
      let parsed: Partial<ApiError> | undefined;
      try { parsed = JSON.parse(text) as Partial<ApiError>; } catch { /* ignore */ }
      throw new GlobalOrchestratorError(
        resp.status,
        parsed?.code ?? `HTTP_${resp.status}`,
        parsed?.message ?? text ?? `request failed: HTTP ${resp.status}`,
        parsed,
      );
    }
    const runs = JSON.parse(text) as Run[];
    const totalHeader = resp.headers.get("X-Total-Count");
    const total = totalHeader != null ? Number.parseInt(totalHeader, 10) : runs.length;
    return {
      runs,
      total: Number.isFinite(total) ? total : runs.length,
      offset: opts.offset ?? 0,
      limit: opts.limit ?? runs.length,
    };
  },

  get: (runId: string, signal?: AbortSignal) =>
    request<Run>("GET", `/api/v1/runs/${encodeURIComponent(runId)}`, undefined, signal),

  /**
   * One page of the run's reverse-chronological audit timeline
   * (who started / scaled / drained, when, and with what result). A
   * long-running test can accumulate many events, so this is paginated:
   * `offset`/`limit` (default page size 25, newest first); the total rides on
   * the `X-Total-Count` header (body stays a flat array, mirroring `listPage`).
   */
  events: async (
    runId: string,
    opts: { offset?: number; limit?: number } = {},
    signal?: AbortSignal,
  ): Promise<RunEventsListing> => {
    const params = new URLSearchParams();
    if (opts.offset !== undefined) params.set("offset", String(opts.offset));
    if (opts.limit !== undefined)  params.set("limit",  String(opts.limit));
    const qs = params.toString();
    const path = `/api/v1/runs/${encodeURIComponent(runId)}/events${qs ? `?${qs}` : ""}`;
    const resp = await fetch(path, { method: "GET", signal });
    const text = await resp.text();
    if (!resp.ok) {
      let parsed: Partial<ApiError> | undefined;
      try { parsed = JSON.parse(text) as Partial<ApiError>; } catch { /* ignore */ }
      throw new GlobalOrchestratorError(
        resp.status,
        parsed?.code ?? `HTTP_${resp.status}`,
        parsed?.message ?? text ?? `request failed: HTTP ${resp.status}`,
        parsed,
      );
    }
    const events = JSON.parse(text) as RunEvent[];
    const totalHeader = resp.headers.get("X-Total-Count");
    const total = totalHeader != null ? Number.parseInt(totalHeader, 10) : events.length;
    return { events, total: Number.isFinite(total) ? total : events.length };
  },

  status: (runId: string, signal?: AbortSignal) =>
    request<{ runId: string; state: RunState; stateReason?: string | null;
              members: RunFleetMember[] }>(
      "GET",
      `/api/v1/runs/${encodeURIComponent(runId)}/status`,
      undefined,
      signal,
    ),

  /**
   * Per-second timeseries for the run-detail Metrics tab.
   * Empty arrays during PREPARING (200, not 404) so the polling UI
   * doesn't flash a red error before metrics-consumer's first write.
   *
   * <p>{@code byRegion: true} adds the per-region breakdown (`regions`
   * map) for the "Split by region" toggle. Default false keeps the
   * lighter aggregate-only payload.
   *
   * <p>{@code window} restricts the data to the last 5m/10m/30m/1h/2h/4h
   * of the run (the run-detail time-window selector); omit or pass
   * {@code "all"} for the whole test. On long live runs the window also
   * trims the per-poll server-side scan + aggregate.
   */
  timeseries: (
    runId: string,
    signal?: AbortSignal,
    opts: {
      byRegion?: boolean; byApplication?: boolean; byLabel?: boolean; labelPrefix?: string; labelLimit?: number | "all";
      granularity?: MetricsGranularity; window?: MetricsWindow;
    } = {},
  ): Promise<MetricsTimeseries> => {
    const params = new URLSearchParams();
    if (opts.byRegion) params.set("byRegion", "true");
    if (opts.byApplication) params.set("byApplication", "true");
    if (opts.byLabel) params.set("byLabel", "true");
    if (opts.byLabel && opts.labelPrefix?.trim()) params.set("labelPrefix", opts.labelPrefix.trim());
    if (opts.byLabel && opts.labelLimit) params.set("labelLimit", String(opts.labelLimit));
    if (opts.granularity) params.set("granularity", String(opts.granularity));
    if (opts.window && opts.window !== "all") params.set("window", opts.window);
    const qs = params.toString();
    return request<MetricsTimeseries>(
      "GET",
      `/api/v1/runs/${encodeURIComponent(runId)}/timeseries${qs ? `?${qs}` : ""}`,
      undefined,
      signal,
    );
  },

  /** The Metrics tab's headline numbers over the same window as the charts — one statement server-side. */
  summary: (
    runId: string,
    signal?: AbortSignal,
    opts: { window?: MetricsWindow } = {},
  ): Promise<RunSummary> => {
    const qs = opts.window && opts.window !== "all" ? `?window=${opts.window}` : "";
    return request<RunSummary>("GET", `/api/v1/runs/${encodeURIComponent(runId)}/summary${qs}`, undefined, signal);
  },

  /** The aggregate report: the busiest labels over the window, narrowed to an exact label prefix (`labelLimit` 1–50 or "all"; server default 10). */
  metrics: (
    runId: string,
    signal?: AbortSignal,
    opts: { window?: MetricsWindow; labelPrefix?: string; labelLimit?: number | "all" } = {},
  ): Promise<RunMetricsRollup> => {
    const params = new URLSearchParams();
    if (opts.window && opts.window !== "all") params.set("window", opts.window);
    if (opts.labelPrefix?.trim()) params.set("labelPrefix", opts.labelPrefix.trim());
    if (opts.labelLimit) params.set("labelLimit", String(opts.labelLimit));
    const qs = params.toString();
    return request<RunMetricsRollup>(
      "GET",
      `/api/v1/runs/${encodeURIComponent(runId)}/metrics${qs ? `?${qs}` : ""}`,
      undefined,
      signal,
    );
  },

  /**
   * Batch fetch the per-second timeseries for two runs in one
   * round-trip. Drives the two-run comparison view.
   * The backend enforces exactly two distinct ids; this client
   * reflects that in the signature so the constraint can't be
   * accidentally violated from app code.
   */
  timeseriesBatch: (
    idA: string,
    idB: string,
    signal?: AbortSignal,
  ): Promise<MetricsTimeseriesBatch> => {
    const ids = `${encodeURIComponent(idA)},${encodeURIComponent(idB)}`;
    return request<MetricsTimeseriesBatch>(
      "GET",
      `/api/v1/runs/timeseries?ids=${ids}`,
      undefined,
      signal,
    );
  },

  /**
   * Step 19 — fetches the per-pod log tail through the global-orchestrator's
   * proxy. Returns plain text; callers split on newlines for rendering.
   *
   * <p>UI-1 added the {@code stream} selector: {@code 'console'} (default —
   * orchestrator's in-memory ring of the JMeter child's stdout/stderr) or
   * {@code 'jmeter'} (tail of jmeter.log on disk).
   */
  podLogs: async (
    runId: string,
    workerId: string,
    opts: { tail?: number; stream?: "console" | "jmeter" } = {},
    signal?: AbortSignal,
  ): Promise<string> => {
    const params = new URLSearchParams();
    params.set("tail", String(opts.tail ?? 200));
    if (opts.stream) params.set("stream", opts.stream);
    const url = `/api/v1/runs/${encodeURIComponent(runId)}/members/${encodeURIComponent(workerId)}/logs?${params.toString()}`;
    const resp = await fetch(url, { method: "GET", signal });
    const text = await resp.text();
    if (!resp.ok) {
      throw new GlobalOrchestratorError(
        resp.status,
        `HTTP_${resp.status}`,
        text || `request failed: HTTP ${resp.status}`,
      );
    }
    return text;
  },
};
