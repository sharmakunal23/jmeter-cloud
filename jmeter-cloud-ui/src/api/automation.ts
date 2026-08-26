/**
 * Automation API client — CRON schedules. Backend: the global-orchestrator's
 * `/api/v1/cronJobs` surface (AUTOMATION Phase A+B). A DB-claim scheduler fires
 * due schedules; this client drives the CRUD + lifecycle + manual-fire actions.
 *
 * <p>Contract matches `jmeter-global-orchestrator/api/openapi.yaml`
 * (`CronJobSummary` / `CronJobRequest`). Mutating calls carry the operator's
 * `X-Actor` header (same convention as `runsApi`).
 */

import { getActor } from "../actor";

export type CronJobFireStatus = "LAUNCHED" | "SKIPPED" | "FAILED" | "DISABLED";

/**
 * What a fire does:
 *   LAUNCH_RUN       fire a saved template;
 *   DRAIN_REGION     drain every IDLE worker in (app, region) (cost saving);
 *   PROVISION_REGION spin workers up to the configured cap.
 */
export type CronJobKind =
  | "LAUNCH_RUN"
  | "DRAIN_REGION"
  | "PROVISION_REGION"
  | "INFRA_READINESS"
  | "DAILY_REPORT";

/** Platform-wide report kinds carry no application/template/region — just a cron + recipients. */
export function isReportKind(kind: CronJobKind): boolean {
  return kind === "INFRA_READINESS" || kind === "DAILY_REPORT";
}

/**
 * One CRON schedule. Field names mirror the backend `CronJobSummary` record
 * exactly (ULIDs for ids; ISO timestamps for `*At`; `cronExpression` is the raw
 * operator string — server-side parsing surfaces validation via
 * {@link CronJobApiError}).
 */
export interface CronJobSummary {
  cronJobId: string;
  name: string;
  /** Null for platform-wide report kinds (INFRA_READINESS / DAILY_REPORT). */
  applicationName?: string | null;
  /** Null for DRAIN_REGION / PROVISION_REGION (only LAUNCH_RUN uses a template). */
  templateBlobId?: string | null;
  cronExpression: string;
  timeZone: string;
  enabled: boolean;
  createdBy?: string | null;
  createdAt: string;
  lastFiredAt?: string | null;
  lastFiredRunId?: string | null;
  lastFireStatus?: CronJobFireStatus | null;
  nextFireAt?: string | null;
  /** AUTOMATION Phase C — defaults to LAUNCH_RUN for legacy rows. */
  kind: CronJobKind;
  /** Required for DRAIN_REGION / PROVISION_REGION; null for LAUNCH_RUN. */
  region?: string | null;
  /** Comma-separated recipients for report kinds; null otherwise. */
  recipients?: string | null;
  /** Optional custom email subject for report kinds; null → composer default. */
  customSubject?: string | null;
  /** Optional intro/note rendered above the report body for report kinds. */
  customIntro?: string | null;
}

/** Body for create + update. */
export interface CronJobRequest {
  name: string;
  applicationName: string;
  /** Required for kind=LAUNCH_RUN. */
  templateBlobId?: string;
  cronExpression: string;
  timeZone?: string;
  /** Defaults to LAUNCH_RUN server-side when omitted. */
  kind?: CronJobKind;
  /** Required for kind=DRAIN_REGION / PROVISION_REGION. */
  region?: string;
  /** Comma-separated recipients for report kinds (INFRA_READINESS / DAILY_REPORT). */
  recipients?: string;
  /** Optional custom email subject for report kinds; blank → composer default. */
  customSubject?: string;
  /** Optional intro/note rendered above the report body for report kinds. */
  customIntro?: string;
}

/** Result of a manual `fireNow`. */
export interface CronJobFireResult {
  outcome: CronJobFireStatus;
  runId?: string | null;
  error?: string | null;
}

/** One row of a schedule's fire history. */
export interface CronJobFire {
  fireId: string;
  cronJobId: string;
  firedAt: string;
  outcome: CronJobFireStatus;
  runId?: string | null;
  errorReason?: string | null;
}

export class CronJobApiError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  constructor(httpStatus: number, code: string, message: string) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
  }
}

export interface CronJobListFilter {
  application?: string;
  enabled?: boolean;
}

async function request<T>(
  method: "GET" | "POST" | "PUT" | "DELETE",
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  // Self-attested operator identity → audit trail (same as runsApi).
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
  let parsed: unknown = undefined;
  if (text) {
    try { parsed = JSON.parse(text); } catch { /* leave as text */ }
  }
  if (!resp.ok) {
    const err = parsed as { code?: string; message?: string } | undefined;
    throw new CronJobApiError(
      resp.status,
      err?.code ?? `HTTP_${resp.status}`,
      err?.message ?? text ?? `request failed: HTTP ${resp.status}`,
    );
  }
  return parsed as T;
}

export const cronJobsApi = {
  /** List schedules, optionally filtered by application. */
  list: async (filter?: CronJobListFilter, signal?: AbortSignal): Promise<CronJobSummary[]> => {
    const qs = filter?.application
      ? `?application=${encodeURIComponent(filter.application)}`
      : "";
    const payload = await request<{ items: CronJobSummary[] }>("GET", `/api/v1/cronJobs${qs}`, undefined, signal);
    const items = payload.items ?? [];
    return filter?.enabled === undefined ? items : items.filter((j) => j.enabled === filter.enabled);
  },

  get: (cronJobId: string, signal?: AbortSignal): Promise<CronJobSummary> =>
    request<CronJobSummary>("GET", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}`, undefined, signal),

  create: (body: CronJobRequest, signal?: AbortSignal): Promise<CronJobSummary> =>
    request<CronJobSummary>("POST", "/api/v1/cronJobs", body, signal),

  update: (cronJobId: string, body: CronJobRequest, signal?: AbortSignal): Promise<CronJobSummary> =>
    request<CronJobSummary>("PUT", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}`, body, signal),

  delete: (cronJobId: string, signal?: AbortSignal): Promise<void> =>
    request<void>("DELETE", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}`, undefined, signal),

  enable: (cronJobId: string, signal?: AbortSignal): Promise<CronJobSummary> =>
    request<CronJobSummary>("POST", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}/enable`, undefined, signal),

  disable: (cronJobId: string, signal?: AbortSignal): Promise<CronJobSummary> =>
    request<CronJobSummary>("POST", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}/disable`, undefined, signal),

  fireNow: (cronJobId: string, signal?: AbortSignal): Promise<CronJobFireResult> =>
    request<CronJobFireResult>("POST", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}/fireNow`, undefined, signal),

  /** Advance the next scheduled fire by one occurrence without firing (a one-off
   *  "not this time"). 409 NOTHING_TO_SKIP if the schedule is disabled. */
  skipNext: (cronJobId: string, signal?: AbortSignal): Promise<CronJobSummary> =>
    request<CronJobSummary>("POST", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}/skipNext`, undefined, signal),

  history: async (cronJobId: string, signal?: AbortSignal): Promise<CronJobFire[]> => {
    const payload = await request<{ items: CronJobFire[] }>(
      "GET", `/api/v1/cronJobs/${encodeURIComponent(cronJobId)}/history`, undefined, signal);
    return payload.items ?? [];
  },
};

/** The rendered report email as the scheduled fire would send it. */
export interface ReportPreview {
  subject: string;
  html: string;
}

/** Report-email previews — the same content a report fire would email, so the
 *  operator can see (and tailor) it before saving/sending. `customSubject` /
 *  `customIntro` preview unsaved tailoring exactly as it will send. */
export const automationReportsApi = {
  preview: (
    kind: CronJobKind,
    opts: { customSubject?: string; customIntro?: string } = {},
    signal?: AbortSignal,
  ): Promise<ReportPreview> => {
    const path = kind === "DAILY_REPORT" ? "daily" : "infraReadiness";
    const qs = new URLSearchParams();
    if (opts.customSubject?.trim()) qs.set("customSubject", opts.customSubject.trim());
    if (opts.customIntro?.trim()) qs.set("customIntro", opts.customIntro.trim());
    const q = qs.toString();
    return request<ReportPreview>("GET", `/api/v1/automation/reports/${path}${q ? `?${q}` : ""}`, undefined, signal);
  },
};
