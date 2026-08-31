/**
 * Typed client for the application-group registry. A group is a team's set of
 * applications: its `groupId` is what the group's workers send as `?groupId=`
 * on every metrics batch and, upper-cased, the prefix of the group's tables
 * (`cps` → `CPS_METRICS`), so it equals the metrics schema's
 * `GROUP_REGISTRY.GROUP_ID` (e.g. `cps` for "Servicing MQ"). The group also
 * owns the worker pool — its per-region capacity and the pod recycle policy
 * (GROUP-CAPACITY, 2026-08-30) — which every application in it draws on.
 */

import { requestJson } from "./applications";
import { APPLICATION_GROUPS_CACHE, cached, invalidate } from "../lib/resourceCache";

/** Per-(group, region) worker budget. */
export interface ApplicationGroupCapacityEntry {
  region: string;
  maxAvailable: number;
}

export type RecyclePolicy = "REUSE" | "MAX_RUNS" | "MAX_AGE" | "BOTH" | "EVERY_RUN" | "DRAIN_AFTER_RUN";

export interface ApplicationGroup {
  /** Lowercase, ≤ 30 chars, immutable. */
  groupId: string;
  name: string;
  description?: string | null;
  createdAt: string;
  /** Applications in the group, archived ones included. */
  applicationCount?: number | null;
  /** The group's live Grafana dashboard (reads `<P>_METRICS`) — the "Open in Grafana" default for its apps. */
  grafanaLiveUrl?: string | null;
  /** The history dashboard (reads `<P>_METRICS_H`); optional, falls back to live. */
  grafanaHistoryUrl?: string | null;
  /** Days the live dashboard covers; a run older than this opens history. Default 7. */
  hotDays?: number | null;
  /** The pool's per-region budget; null when not hydrated. */
  capacity?: ApplicationGroupCapacityEntry[] | null;
  /** The pool's recycle policy (default REUSE). */
  recyclePolicy?: RecyclePolicy | null;
  maxRunsPerPod?: number | null;
  podMaxAgeHours?: number | null;
  /** When true, scheduled DRAIN_REGION jobs skip this group's workers. */
  alwaysOn?: boolean;
  /** The team that owns the group; display only. */
  teamName?: string | null;
  /**
   * Default recipients a workflow's email tasks inherit when they name none of
   * their own — so a group that changes owners needs no workflow edited.
   */
  notifyTo?: string[];
  notifyCc?: string[];
  notifyBcc?: string[];
}

/** The pod-policy half of a group; PUT replaces it wholesale with the rest of the record. */
export interface ApplicationGroupPolicy {
  recyclePolicy?: RecyclePolicy | null;
  maxRunsPerPod?: number | null;
  podMaxAgeHours?: number | null;
  alwaysOn?: boolean;
}

export interface CreateApplicationGroupRequest extends ApplicationGroupPolicy {
  groupId: string;
  name: string;
  description?: string | null;
  grafanaLiveUrl?: string | null;
  grafanaHistoryUrl?: string | null;
  hotDays?: number | null;
  /** Who owns the group; display only. */
  teamName?: string | null;
  /**
   * Default email recipients for the group's workflows. Each list is replaced
   * only when sent, so a caller that omits one keeps the stored value.
   */
  notifyTo?: string[];
  notifyCc?: string[];
  notifyBcc?: string[];
}

/** Replaced wholesale — send the full group (name, description, dashboards, hot days, policy). */
export interface UpdateApplicationGroupRequest extends ApplicationGroupPolicy {
  name: string;
  description?: string | null;
  grafanaLiveUrl?: string | null;
  grafanaHistoryUrl?: string | null;
  hotDays?: number | null;
  /** Who owns the group; display only. */
  teamName?: string | null;
  /**
   * Default email recipients for the group's workflows. Each list is replaced
   * only when sent, so a caller that omits one keeps the stored value.
   */
  notifyTo?: string[];
  notifyCc?: string[];
  notifyBcc?: string[];
}

/** Mirrors the server's rule: an identifier stem — lowercase letter first, then letters / digits / _, max 30. */
export const GROUP_ID_PATTERN = /^[a-z][a-z0-9_]{0,29}$/;

export const applicationGroupsApi = {
  list: (signal?: AbortSignal, opts?: { fresh?: boolean }) =>
    cached(`${APPLICATION_GROUPS_CACHE}:list`,
      () => requestJson<ApplicationGroup[]>("GET", "/api/v1/applicationGroups"),
      { signal, force: opts?.fresh }),

  /**
   * One group. Cached because the run launcher, the run detail page and the
   * capacity drill-in all resolve the same group on every mount — but a
   * reservation write goes through `capacityApi`, which invalidates this
   * namespace, so the grid a caller reads back is never its own stale copy.
   */
  get: (groupId: string, signal?: AbortSignal, opts?: { fresh?: boolean }) =>
    cached(`${APPLICATION_GROUPS_CACHE}:get:${groupId}`,
      () => requestJson<ApplicationGroup>(
        "GET", `/api/v1/applicationGroups/${encodeURIComponent(groupId)}`),
      { signal, force: opts?.fresh }),

  create: async (body: CreateApplicationGroupRequest, signal?: AbortSignal) => {
    const created = await requestJson<ApplicationGroup>("POST", "/api/v1/applicationGroups", body, signal);
    invalidate(APPLICATION_GROUPS_CACHE);
    return created;
  },

  update: async (groupId: string, body: UpdateApplicationGroupRequest, signal?: AbortSignal) => {
    const updated = await requestJson<ApplicationGroup>(
      "PUT", `/api/v1/applicationGroups/${encodeURIComponent(groupId)}`, body, signal);
    invalidate(APPLICATION_GROUPS_CACHE);
    return updated;
  },

  /**
   * 409 `APPLICATION_GROUP_HAS_APPLICATIONS` while any application is in the group,
   * 409 `APPLICATION_GROUP_HAS_WORKERS` while it still has workers or capacity rows.
   */
  delete: async (groupId: string, signal?: AbortSignal) => {
    await requestJson<void>("DELETE", `/api/v1/applicationGroups/${encodeURIComponent(groupId)}`,
      undefined, signal);
    invalidate(APPLICATION_GROUPS_CACHE);
  },
};

/** Stable ordering by group name then application name, shared by the Apps page and the Home checklist. */
export function sortByGroup<T extends { name: string; metricsGroupId: string }>(
  items: T[], groups: ApplicationGroup[],
): T[] {
  const nameOf = new Map(groups.map((g) => [g.groupId, g.name]));
  const key = (a: T) => nameOf.get(a.metricsGroupId) ?? a.metricsGroupId;
  return [...items].sort((a, b) => key(a).localeCompare(key(b)) || a.name.localeCompare(b.name));
}

/** The group an application belongs to, by its id — `undefined` only while the groups list is stale. */
export function groupOfApplication(
  groups: ApplicationGroup[], app: { metricsGroupId: string },
): ApplicationGroup | undefined {
  return groups.find((g) => g.groupId === app.metricsGroupId);
}
