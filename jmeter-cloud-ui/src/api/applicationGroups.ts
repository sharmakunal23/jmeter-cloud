/**
 * Typed client for the application-group registry. A group is a team's set of
 * applications; its `groupId` is what the group's workers send as `?groupId=`
 * on every metrics batch and, upper-cased, the prefix of the group's tables
 * (`cps` → `CPS_METRICS`), so it equals the metrics schema's
 * `GROUP_REGISTRY.GROUP_ID` (e.g. `cps` for "Servicing MQ").
 */

import { requestJson } from "./applications";

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
}

export interface CreateApplicationGroupRequest {
  groupId: string;
  name: string;
  description?: string | null;
  grafanaLiveUrl?: string | null;
  grafanaHistoryUrl?: string | null;
  hotDays?: number | null;
}

export interface UpdateApplicationGroupRequest {
  name: string;
  description?: string | null;
  grafanaLiveUrl?: string | null;
  grafanaHistoryUrl?: string | null;
  hotDays?: number | null;
}

/** Mirrors the server's rule: an identifier stem — lowercase letter first, then letters / digits / _, max 30. */
export const GROUP_ID_PATTERN = /^[a-z][a-z0-9_]{0,29}$/;

export const applicationGroupsApi = {
  list: (signal?: AbortSignal) =>
    requestJson<ApplicationGroup[]>("GET", "/api/v1/applicationGroups", undefined, signal),

  get: (groupId: string, signal?: AbortSignal) =>
    requestJson<ApplicationGroup>("GET", `/api/v1/applicationGroups/${encodeURIComponent(groupId)}`,
      undefined, signal),

  create: (body: CreateApplicationGroupRequest, signal?: AbortSignal) =>
    requestJson<ApplicationGroup>("POST", "/api/v1/applicationGroups", body, signal),

  update: (groupId: string, body: UpdateApplicationGroupRequest, signal?: AbortSignal) =>
    requestJson<ApplicationGroup>("PUT", `/api/v1/applicationGroups/${encodeURIComponent(groupId)}`,
      body, signal),

  /** 409 `APPLICATION_GROUP_HAS_APPLICATIONS` while any application is in the group. */
  delete: (groupId: string, signal?: AbortSignal) =>
    requestJson<void>("DELETE", `/api/v1/applicationGroups/${encodeURIComponent(groupId)}`,
      undefined, signal),
};

/** Stable "grouped first, ungrouped last" ordering shared by the Apps page and the Home checklist. */
export function sortByGroup<T extends { name: string; metricsGroupId?: string | null }>(
  items: T[], groups: ApplicationGroup[],
): T[] {
  const nameOf = new Map(groups.map((g) => [g.groupId, g.name]));
  const key = (a: T) => (a.metricsGroupId ? (nameOf.get(a.metricsGroupId) ?? a.metricsGroupId) : "￿");
  return [...items].sort((a, b) => key(a).localeCompare(key(b)) || a.name.localeCompare(b.name));
}
