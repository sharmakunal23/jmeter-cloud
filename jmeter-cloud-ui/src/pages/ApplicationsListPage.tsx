import { Fragment, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { formatRelative } from "../lib/time";

import {
  applicationsApi,
  displayName,
  type Application,
} from "../api/applications";
import { runsApi, type Run } from "../api/runs";
import { applicationGroupsApi, groupOfApplication, sortByGroup, type ApplicationGroup } from "../api/applicationGroups";
import { CreateApplicationDialog } from "../components/CreateApplicationDialog";
import { ApplicationGroupsDialog } from "../components/ApplicationGroupsDialog";
import { PurgeConfirmDialog } from "../components/PurgeConfirmDialog";
import { useToast, ToastView } from "../components/Toast";
import { HealthBadge } from "../components/HealthBadge";
import { AppListToolbar } from "../components/AppListToolbar";
import { Paginator } from "../components/Paginator";
import { useClientPagination } from "../hooks/useClientPagination";
import {
  ViewModeToggle,
  type ListViewMode,
  persistViewMode,
  readPersistedViewMode,
} from "../components/ViewModeToggle";

/**
 * D-AppRegistry — Applications surface.
 *
 * <p>Operator-visible registry with two view modes (grid / list) — the
 * mode is persisted in localStorage so the operator's preference
 * survives reloads. Health badge per app is sourced from the
 * registry's {@code lastHealthStatus} field (updated every minute by
 * {@code ApplicationHealthPoller}). Per-app run aggregates are stitched
 * client-side from {@code GET /api/v1/runs?limit=200} so the cards /
 * rows show recent activity at a glance.
 *
 * <p>"Register application" opens {@link CreateApplicationDialog} —
 * was inline on {@code <NewRunPage>}, moved here per the
 * D-AppRegistry brief.
 */

// Standardization sweep (2026-05-13) — local ViewMode type/component +
// readStoredViewMode helper deleted; the page now uses the shared
// <ViewModeToggle> + readPersistedViewMode/persistViewMode from
// src/components/ViewModeToggle.tsx so List always renders LEFT of Grid
// (matching the four IA tabs).
const VIEW_MODE_STORAGE_KEY = "jmeterCloud.applications.viewMode";
const RUN_AGG_LIMIT = 200;
const ACTIVE_STATES = new Set(["PREPARING", "STARTING", "RUNNING", "DRAINING"]);

interface AppAggregates {
  totalRuns: number;
  activeRuns: number;
  lastRun?: Run;
}

type State =
  | { status: "loading" }
  | { status: "ok"; apps: Application[]; groups: ApplicationGroup[]; aggregates: Record<string, AppAggregates>; refreshedAt: Date }
  | { status: "error"; message: string };

/** Heading for a run of apps in the same group (every app has one; the id stands in until the groups list catches up). */
function groupHeading(app: Application, groups: ApplicationGroup[]): { key: string; name: string; id: string } {
  const g = groupOfApplication(groups, app);
  return { key: app.metricsGroupId, name: g?.name ?? app.metricsGroupId, id: app.metricsGroupId };
}

export function ApplicationsListPage() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [viewMode, setViewMode] = useState<ListViewMode>(() => readPersistedViewMode(VIEW_MODE_STORAGE_KEY));
  const [search, setSearch] = useState("");
  const [showCreate, setShowCreate] = useState(false);
  const [showGroups, setShowGroups] = useState(false);
  const [refreshSeq, setRefreshSeq] = useState(0);
  // HARD-DELETE — the Archived view lists HIDDEN (soft-deleted) apps so the
  // operator can permanently purge a retired app and reclaim its storage.
  const [archived, setArchived] = useState(false);

  function changeViewMode(next: ListViewMode) {
    setViewMode(next);
    persistViewMode(VIEW_MODE_STORAGE_KEY, next);
  }

  useEffect(() => {
    const ctl = new AbortController();
    Promise.all([
      applicationsApi.list(ctl.signal),
      runsApi.listPage({ limit: RUN_AGG_LIMIT }, ctl.signal),
      // Groups only decorate the page; a failed fetch renders the flat list.
      applicationGroupsApi.list(ctl.signal).catch(() => [] as ApplicationGroup[]),
    ])
      .then(([apps, runListing, groups]) => {
        const aggregates = aggregateByApp(runListing.runs);
        setState({ status: "ok", apps: sortByGroup(apps, groups), groups, aggregates, refreshedAt: new Date() });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setState({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [refreshSeq]);

  function onCreated() {
    setShowCreate(false);
    setRefreshSeq((n) => n + 1);
  }

  // Apply the search filter once so the toolbar count + the grid + the
  // list view all stay in sync (avoid three independent narrowings).
  const filteredApps = useMemo<Application[]>(() => {
    if (state.status !== "ok") return [];
    const needle = search.trim().toLowerCase();
    if (!needle) return state.apps;
    return state.apps.filter((a) => {
      if (a.name.toLowerCase().includes(needle)) return true;
      const g = groupOfApplication(state.groups, a);
      return g != null && (g.name.toLowerCase().includes(needle) || g.groupId.includes(needle));
    });
  }, [state, search]);
  const groups = state.status === "ok" ? state.groups : [];

  const { page, setPage, pageItems, total, pageSize } = useClientPagination(filteredApps, search);

  return (
    <section className="applicationsListPage">
      {/* Standardized header — same shape as Capacity / Documents / Templates /
          Automation: a titleGroup (h1 + "Refreshed" line) on the left and a
          shared .pageHeader__actions group (primary action + view toggle) on
          the right, so spacing + structure match across every list tab. */}
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Applications</h1>
          <small className="ink-soft" aria-live="polite">
            {state.status === "loading"
              ? "Loading…"
              : state.status === "ok"
                ? `Refreshed ${formatRelative(state.refreshedAt.toISOString())}`
                : ""}
          </small>
        </div>
        <div className="pageHeader__actions">
          <button
            type="button"
            className="btn btn--primary"
            onClick={() => setShowCreate(true)}
          >
            + Register application
          </button>
          {!archived && (
            <button
              type="button"
              className="btn"
              onClick={() => setShowGroups(true)}
              title="Application groups — each owns its apps' worker pool and routes their metrics to its own tables"
            >
              Manage groups
            </button>
          )}
          <div className="segmentedToggle" role="tablist" aria-label="application view">
            <button
              type="button"
              role="tab"
              aria-selected={!archived}
              className={`btn ${archived ? "btn--ghost" : "btn--primary"}`}
              onClick={() => setArchived(false)}
            >
              Active
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={archived}
              className={`btn ${archived ? "btn--primary" : "btn--ghost"}`}
              onClick={() => setArchived(true)}
              title="Hidden (soft-deleted) applications — permanently delete them here to reclaim storage"
            >
              Archived
            </button>
          </div>
          {!archived && <ViewModeToggle viewMode={viewMode} onChange={changeViewMode} />}
        </div>
      </header>

      {archived && <ArchivedApplicationsView />}

      {!archived && (
        <>
      <AppListToolbar
        search={search}
        onSearchChange={setSearch}
        count={filteredApps.length}
        total={state.status === "ok" ? state.apps.length : 0}
        loading={state.status === "loading"}
      />

      {state.status === "loading" && <p className="ink-soft">Loading applications…</p>}
      {state.status === "error" && <p className="text--error">{state.message}</p>}

      {state.status === "ok" && state.apps.length === 0 && (
        <div className="emptyState">
          <p>No applications registered yet.</p>
          <p className="ink-soft">
            Register one to launch runs against it. Health-check endpoints
            (optional) will be polled every minute.
          </p>
          <button type="button" className="btn btn--primary"
                  onClick={() => setShowCreate(true)}>
            + Register your first application
          </button>
        </div>
      )}

      {state.status === "ok" && state.apps.length > 0 && filteredApps.length === 0 && (
        <div className="emptyState">
          <p className="ink-soft">No applications match "{search}".</p>
        </div>
      )}

      {state.status === "ok" && filteredApps.length > 0 && viewMode === "grid" && (
        <ul className="appCardGrid" aria-label="application cards">
          {pageItems.map((app, i) => {
            const heading = groupHeading(app, groups);
            const first = i === 0 || groupHeading(pageItems[i - 1], groups).key !== heading.key;
            return (
              <Fragment key={app.applicationId}>
                {first && (
                  <li className="appCardGrid__groupHeading" role="presentation">
                    <GroupHeading name={heading.name} id={heading.id} />
                  </li>
                )}
                <ApplicationCard app={app} agg={state.aggregates[app.name]} />
              </Fragment>
            );
          })}
        </ul>
      )}

      {state.status === "ok" && filteredApps.length > 0 && viewMode === "list" && (
        <ApplicationListView apps={pageItems} groups={groups} aggregates={state.aggregates} />
      )}

      {state.status === "ok" && filteredApps.length > 0 && (
        <Paginator page={page} pageSize={pageSize} total={total} label="applications" onChange={setPage} />
      )}
        </>
      )}

      {showCreate && (
        <CreateApplicationDialog
          onCreated={onCreated}
          onClose={() => setShowCreate(false)}
        />
      )}

      {showGroups && (
        <ApplicationGroupsDialog
          onClose={() => setShowGroups(false)}
          onChanged={() => setRefreshSeq((n) => n + 1)}
        />
      )}
    </section>
  );
}

/** One heading per run of applications in the same group. */
function GroupHeading({ name, id }: { name: string; id: string }) {
  return (
    <h2 className="appGroupHeading">
      {name}
      <span className="mono ink-soft appGroupHeading__id">{id}</span>
    </h2>
  );
}

/**
 * HARD-DELETE / purge Phase 3 — the "Archived applications" surface. Lists
 * HIDDEN (soft-deleted) apps and lets the operator PERMANENTLY purge one to
 * reclaim its storage (runs, blobs, metrics, the app row). Self-contained: it
 * owns its own fetch, purge dialog, and toast so the main list stays simple.
 */
function ArchivedApplicationsView() {
  type ArchState =
    | { status: "loading" }
    | { status: "ok"; apps: Application[] }
    | { status: "error"; message: string };
  const [state, setState] = useState<ArchState>({ status: "loading" });
  const [reloadSeq, setReloadSeq] = useState(0);
  const [purgeApp, setPurgeApp] = useState<Application | null>(null);
  const { toast, showToast, dismiss } = useToast();

  useEffect(() => {
    const ctl = new AbortController();
    setState({ status: "loading" });
    applicationsApi
      .listHidden(ctl.signal)
      .then((apps) => setState({ status: "ok", apps }))
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setState({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [reloadSeq]);

  return (
    <>
      <p className="ink-soft" style={{ margin: "0 0 0.75rem" }}>
        Soft-deleted applications. Permanently deleting one removes its runs,
        result files, metric data, and registry record — this cannot be undone.
      </p>

      {state.status === "loading" && <p className="ink-soft">Loading archived applications…</p>}
      {state.status === "error" && <p className="text--error">{state.message}</p>}

      {state.status === "ok" && state.apps.length === 0 && (
        <div className="emptyState">
          <p className="ink-soft">No archived applications.</p>
        </div>
      )}

      {state.status === "ok" && state.apps.length > 0 && (
        <table className="runsTable applicationListTable">
          <thead>
            <tr>
              <th>Name</th>
              <th>Seal ID</th>
              <th aria-label="actions"></th>
            </tr>
          </thead>
          <tbody>
            {state.apps.map((app) => (
              <tr key={app.applicationId}>
                <td>
                  <span className="mono">{displayName(app.name)}</span>{" "}
                  <span className="badge badge--info">archived</span>
                </td>
                <td className="mono ink-soft">{app.sealId ?? "—"}</td>
                <td className="runsTable__actions">
                  <button
                    type="button"
                    className="btn btn--sm btn--danger"
                    onClick={() => setPurgeApp(app)}
                    title="Permanently delete this application and all its data — cannot be undone"
                    aria-label={`permanently delete application ${displayName(app.name)}`}
                  >
                    Delete permanently
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {purgeApp && (
        <PurgeConfirmDialog
          kind="application"
          confirmPhrase={displayName(purgeApp.name)}
          summary={
            <ul>
              <li>every run of this app (result files + metric data + records)</li>
              <li>its health history (workers and capacity are the group's and stay)</li>
              <li>the application record itself</li>
            </ul>
          }
          onConfirm={async (reason) => {
            const result = await applicationsApi.purge(purgeApp.applicationId, reason);
            setPurgeApp(null);
            setReloadSeq((n) => n + 1);
            showToast({
              variant: "ok",
              text: "Application permanently deleted.",
              detail: `${result.runsPurged} run${result.runsPurged === 1 ? "" : "s"} purged.`,
            });
          }}
          onClose={() => setPurgeApp(null)}
        />
      )}

      <ToastView toast={toast} onDismiss={dismiss} />
    </>
  );
}

// ── Cards (grid view) ─────────────────────────────────────────────

function ApplicationCard({ app, agg }: { app: Application; agg?: AppAggregates }) {
  const target = `/applications/${encodeURIComponent(app.name)}`;
  const runs = agg?.totalRuns ?? 0;
  const active = agg?.activeRuns ?? 0;

  return (
    <li>
      {/* Standardization sweep (2026-05-13) — Applications cards now use
          the unified .appCard shell shared with Capacity / Documents /
          Templates / Automation. Health badge sits in the head row with
          flex-wrap so it never overflows the card on narrow widths. */}
      <Link to={target} className="appCard" aria-label={`Open ${app.name}`}>
        <div className="appCard__head">
          <h3 className="appCard__name">{app.name}</h3>
          <HealthBadge app={app} compact />
        </div>
        {app.sealId && (
          <div className="appCard__sealId mono ink-soft">{app.sealId}</div>
        )}
        {app.description && (
          <p className="appCard__desc">{app.description}</p>
        )}
        <dl className="appCard__stats">
          <div><dt>Runs</dt><dd className="mono">{runs}</dd></div>
          {active > 0 && (
            <div><dt>Active</dt><dd className="mono appCard__active">{active}</dd></div>
          )}
          <div><dt>Endpoints</dt><dd className="mono">{app.healthEndpoints.length}</dd></div>
        </dl>
        {agg?.lastRun ? (
          <p className="ink-soft appCard__lastRun appCard__footer">
            Last run:{" "}
            <span className={`badge badge--${badgeVariantForRunState(agg.lastRun.state)}`}>
              {agg.lastRun.state}
            </span>{" "}
            <span className="mono">{formatRelative(agg.lastRun.createdAt)}</span>
          </p>
        ) : (
          <p className="ink-soft appCard__hint appCard__footer">No runs yet — click to launch one.</p>
        )}
      </Link>
    </li>
  );
}

// ── Table (list view) ─────────────────────────────────────────────

function ApplicationListView({
  apps, groups, aggregates,
}: { apps: Application[]; groups: ApplicationGroup[]; aggregates: Record<string, AppAggregates> }) {
  return (
    <table className="runsTable applicationListTable">
      <thead>
        <tr>
          <th>Name</th>
          <th>Health</th>
          <th>Seal ID</th>
          <th>Description</th>
          <th>Runs</th>
          <th>Active</th>
          <th>Last run</th>
        </tr>
      </thead>
      <tbody>
        {apps.map((app, i) => {
          const agg = aggregates[app.name];
          const heading = groupHeading(app, groups);
          const first = i === 0 || groupHeading(apps[i - 1], groups).key !== heading.key;
          return (
            <Fragment key={app.applicationId}>
            {first && (
              <tr className="appGroupRow">
                <td colSpan={7}><GroupHeading name={heading.name} id={heading.id} /></td>
              </tr>
            )}
            <tr>
              <td>
                <Link to={`/applications/${encodeURIComponent(app.name)}`} className="mono capacityListRow__name">
                  {app.name}
                </Link>
              </td>
              <td><HealthBadge app={app} compact /></td>
              <td className="mono ink-soft">{app.sealId ?? "—"}</td>
              <td className="appListTable__desc">
                {app.description ?? <span className="ink-soft">—</span>}
              </td>
              <td className="mono">{agg?.totalRuns ?? 0}</td>
              <td className="mono">{agg?.activeRuns ?? 0}</td>
              <td>
                {agg?.lastRun ? (
                  <>
                    <span className={`badge badge--${badgeVariantForRunState(agg.lastRun.state)}`}>
                      {agg.lastRun.state}
                    </span>{" "}
                    <span className="mono ink-soft">
                      {formatRelative(agg.lastRun.createdAt)}
                    </span>
                  </>
                ) : <span className="ink-soft">—</span>}
              </td>
            </tr>
            </Fragment>
          );
        })}
      </tbody>
    </table>
  );
}

// HealthBadge moved to ../components/HealthBadge.tsx (Phase 5b — reused on Capacity list).

// ── Helpers ───────────────────────────────────────────────────────

function aggregateByApp(runs: Run[]): Record<string, AppAggregates> {
  const out: Record<string, AppAggregates> = {};
  for (const run of runs) {
    if (!run.application) continue;
    const key = run.application;
    const existing = out[key] ?? { totalRuns: 0, activeRuns: 0 };
    existing.totalRuns += 1;
    if (ACTIVE_STATES.has(run.state)) existing.activeRuns += 1;
    if (!existing.lastRun) existing.lastRun = run;
    out[key] = existing;
  }
  return out;
}

function badgeVariantForRunState(state: string): string {
  switch (state) {
    case "RUNNING":
    case "STARTING":
    case "PREPARING":
    case "DRAINING":  return "warn";
    case "COMPLETED": return "ok";
    case "FAILED":
    case "ABORTED":   return "err";
    default:          return "info";
  }
}
