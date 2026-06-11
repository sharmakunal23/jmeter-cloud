import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { formatRelative } from "../lib/time";

import {
  applicationsApi,
  type Application,
} from "../api/applications";
import { capacityApi, type ReconcileWorkersResult } from "../api/capacity";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { AppListToolbar } from "../components/AppListToolbar";
import { Paginator } from "../components/Paginator";
import { useClientPagination } from "../hooks/useClientPagination";
import { ReconcileWorkersDialog } from "../components/ReconcileWorkersDialog";
import {
  ViewModeToggle,
  type ListViewMode,
  persistViewMode,
  readPersistedViewMode,
} from "../components/ViewModeToggle";

const VIEW_MODE_STORAGE_KEY = "jmeterCloud.capacity.listViewMode";

/**
 * Phase 5c — Capacity list view, restyled per 2026-05-12 user feedback:
 *
 *  - **Health column dropped** (irrelevant in this context — health-status
 *    confusion was a distraction in the capacity view; it lives on the
 *    Applications tab where it's the primary signal).
 *  - **Manage column dropped**; the entire row is the click target now,
 *    so app-name and "Manage →" no longer duplicate. The app name still
 *    renders as a real `<Link>` so right-click / middle-click still work.
 *  - **`Provisioned` → `Usage`** — single column showing
 *    `{provisioned}/{max}` next to the utilization bar.
 *  - **Recent-activity chip** per row — derived from the most recent
 *    `pod.lastHeartbeat` across the app's regions; surfaces "active 5m
 *    ago" without requiring the operator to drill in.
 *  - **`/` keyboard shortcut** focuses the search box.
 *  - **Loading skeleton rows** during initial fetch (no layout shift).
 */

const POLL_INTERVAL_MS = 10_000;

interface RowAggregate {
  app: Application;
  regions: number;
  maxAvailable: number;
  provisioned: number;
  ready: number;
  inUse: number;
  /** Most recent `pod.lastHeartbeat` across all regions for this app. */
  mostRecentActivity?: Date;
}

type SortKey = "name" | "provisioned" | "ready" | "inUse" | "regions";
type SortDir = "asc" | "desc";

type State =
  | { status: "loading" }
  | { status: "ok"; rows: RowAggregate[]; refreshedAt: Date; regionTotals: Record<string, RegionTotal> }
  | { status: "error"; message: string };

interface RegionTotal { provisioned: number; inUse: number; }

interface Toast { variant: "ok" | "warn" | "err"; text: string; detail?: string; }

export function CapacityListPage() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");
  const [viewMode, setViewMode] = useState<ListViewMode>(() => readPersistedViewMode(VIEW_MODE_STORAGE_KEY));
  const [reconcileOpen, setReconcileOpen] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);

  const showToast = useCallback((t: Toast) => {
    setToast(t);
    window.setTimeout(() => setToast((cur) => (cur === t ? null : cur)), 6000);
  }, []);

  function changeViewMode(next: ListViewMode) {
    setViewMode(next);
    persistViewMode(VIEW_MODE_STORAGE_KEY, next);
  }

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const apps = await applicationsApi.list(signal);
      const fetches = apps.flatMap((app) =>
        (app.capacity ?? []).map((c) =>
          capacityApi
            .listPods(app.applicationId, c.region, signal)
            .catch(() => null)
            .then((snap) => ({ appId: app.applicationId, region: c.region, snap, max: c.maxAvailable })),
        ),
      );
      const fetched = await Promise.all(fetches);

      const rowsByApp = new Map<string, RowAggregate>();
      const regionTotals: Record<string, RegionTotal> = {};
      for (const app of apps) {
        rowsByApp.set(app.applicationId, {
          app, regions: 0, maxAvailable: 0, provisioned: 0, ready: 0, inUse: 0,
        });
      }
      for (const { appId, region, snap, max } of fetched) {
        const row = rowsByApp.get(appId);
        if (!row) continue;
        row.regions += 1;
        row.maxAvailable += max;
        if (snap) {
          row.provisioned += snap.provisioned;
          row.ready       += snap.ready;
          row.inUse       += snap.inUse;
          for (const p of snap.pods) {
            if (!p.lastHeartbeat) continue;
            const t = new Date(p.lastHeartbeat);
            if (!row.mostRecentActivity || t > row.mostRecentActivity) {
              row.mostRecentActivity = t;
            }
          }
          const tot = regionTotals[region] ?? { provisioned: 0, inUse: 0 };
          tot.provisioned += snap.provisioned;
          tot.inUse       += snap.inUse;
          regionTotals[region] = tot;
        }
      }

      setState({
        status: "ok",
        rows: Array.from(rowsByApp.values()),
        refreshedAt: new Date(),
        regionTotals,
      });
    } catch (err: unknown) {
      if (signal?.aborted) return;
      setState((prev) =>
        prev.status === "ok"
          ? prev
          : { status: "error", message: err instanceof Error ? err.message : String(err) },
      );
    }
  }, []);

  useEffect(() => {
    const ctl = new AbortController();
    void refresh(ctl.signal);
    return () => ctl.abort();
  }, [refresh]);

  const { isPaused } = useVisiblePolling(() => { void refresh(); }, POLL_INTERVAL_MS);

  // `/` keyboard shortcut + search-input ref live inside <AppListToolbar>
  // (standardization sweep 2026-05-13).

  const sortedFiltered = useMemo(() => {
    if (state.status !== "ok") return [] as RowAggregate[];
    const needle = search.trim().toLowerCase();
    const filtered = needle
      ? state.rows.filter((r) => r.app.name.toLowerCase().includes(needle))
      : state.rows;
    const sorted = [...filtered].sort((a, b) => {
      const cmp = compareRows(a, b, sortKey);
      return sortDir === "asc" ? cmp : -cmp;
    });
    return sorted;
  }, [state, search, sortKey, sortDir]);

  const { page, setPage, pageItems, total, pageSize } =
    useClientPagination(sortedFiltered, `${search}|${sortKey}|${sortDir}`);

  function toggleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir(key === "name" ? "asc" : "desc");
    }
  }

  if (state.status === "error") return <p className="text--error">{state.message}</p>;

  // Loading state renders the same chrome (header, toolbar, table) with
  // skeleton rows so there's no layout-shift jolt when data arrives.
  const loading = state.status === "loading";
  const regionEntries = state.status === "ok"
    ? Object.entries(state.regionTotals).sort(([a], [b]) => a.localeCompare(b))
    : [];
  const totalRowCount = state.status === "ok" ? state.rows.length : 0;

  return (
    <section className="capacityPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Capacity</h1>
          <small className="ink-soft" aria-live="polite">
            {loading
              ? "Loading…"
              : isPaused
                ? "Polling paused (tab hidden)"
                : `Refreshed ${formatRelative((state as Extract<State, {status:"ok"}>).refreshedAt.toISOString())}`}
          </small>
        </div>
        <div className="capacityPage__regionTotals">
          {loading ? (
            <span className="skeleton skeleton--chip" aria-hidden="true" />
          ) : regionEntries.length === 0 ? (
            <span className="ink-soft" style={{ fontSize: "0.85rem" }}>No regions configured.</span>
          ) : regionEntries.map(([region, t]) => (
            <span key={region} className="chip" title="Across all applications">
              <span className="mono">{region}</span>
              <span className="mono">{t.provisioned} worker{t.provisioned === 1 ? "" : "s"}</span>
              {t.inUse > 0 && <span className="mono ink-soft">· {t.inUse} in use</span>}
            </span>
          ))}
          <ViewModeToggle viewMode={viewMode} onChange={changeViewMode} />
          {/* Registry-wide reconcile — the operator's cleanup for a worker
              stuck after its container died (drops the orphaned row so the
              slot frees up). Global on purpose; lives on the list page, not a
              per-app detail page. */}
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => setReconcileOpen(true)}
            title="Reconcile the worker registry against actual containers (removes stale rows)"
          >
            Reconcile workers
          </button>
        </div>
      </header>

      <AppListToolbar
        search={search}
        onSearchChange={setSearch}
        count={sortedFiltered.length}
        total={totalRowCount}
        loading={loading}
      />

      {loading ? (
        <SkeletonTable />
      ) : sortedFiltered.length === 0 ? (
        <div className="emptyState">
          {totalRowCount === 0 ? (
            <>
              <p>No applications registered yet.</p>
              <p className="ink-soft">
                Register one in <Link to="/applications">Applications</Link> to allocate capacity.
              </p>
            </>
          ) : (
            <p className="ink-soft">No applications match "{search}".</p>
          )}
        </div>
      ) : viewMode === "grid" ? (
        <ul className="appCardGrid" aria-label="application capacity cards">
          {pageItems.map((r) => (
            <CapacityCard key={r.app.applicationId} row={r} />
          ))}
        </ul>
      ) : (
        <table className="runsTable capacityListTable">
          <thead>
            <tr>
              <SortHeader label="Application" k="name"        cur={sortKey} dir={sortDir} onClick={toggleSort} />
              <th>Activity</th>
              <SortHeader label="Regions"     k="regions"     cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Ready"       k="ready"       cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="In Use"      k="inUse"       cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Usage"       k="provisioned" cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <th><span className="visuallyHidden">Utilization</span></th>
            </tr>
          </thead>
          <tbody>
            {pageItems.map((r) => (
              <CapacityListRow key={r.app.applicationId} row={r} />
            ))}
          </tbody>
        </table>
      )}

      {!loading && sortedFiltered.length > 0 && (
        <Paginator page={page} pageSize={pageSize} total={total} label="applications" onChange={setPage} />
      )}

      {reconcileOpen && (
        <ReconcileWorkersDialog
          onClose={() => setReconcileOpen(false)}
          onSuccess={(result) => {
            showToast(summariseReconcile(result));
            void refresh();
          }}
        />
      )}

      {toast && (
        <div role="status" aria-live="polite" className={`toast toast--${toast.variant}`}>
          <div className="toast__body" onClick={() => setToast(null)}>
            <strong>{toast.text}</strong>
            {toast.detail && <div className="toast__detail">{toast.detail}</div>}
          </div>
        </div>
      )}
    </section>
  );
}

/** Turn a reconcile result into a one-line toast (ok / warn on errors). */
function summariseReconcile(result: ReconcileWorkersResult): Toast {
  const removed = result.orphansDeleted.length;
  const adopted = result.adopted.length;
  const started = result.started.length;
  const errs    = result.errors.length;

  const parts: string[] = [];
  if (removed) parts.push(`${removed} stale worker${removed === 1 ? "" : "s"} removed`);
  if (adopted) parts.push(`${adopted} adopted`);
  if (started) parts.push(`${started} started`);
  const summary = parts.length > 0 ? parts.join(", ") : "nothing to clean up";

  if (errs > 0) {
    return {
      variant: "warn",
      text: `Reconcile finished with ${errs} error${errs === 1 ? "" : "s"}`,
      detail: summary,
    };
  }
  return { variant: "ok", text: `Reconcile complete — ${summary}` };
}

// ── Grid card (Capacity) ─────────────────────────────────────────

function CapacityCard({ row }: { row: RowAggregate }) {
  const ratio = row.maxAvailable > 0 ? (row.ready + row.inUse) / row.maxAvailable : 0;
  const variant: "ok" | "warn" | "err" =
    ratio >= 1 ? "err" : ratio >= 0.8 ? "warn" : "ok";
  const href = `/capacity/${encodeURIComponent(row.app.name)}`;
  return (
    <li>
      <Link to={href} className="appCard" aria-label={`Open capacity for ${row.app.name}`}>
        <div className="appCard__head">
          <h3 className="appCard__name">{row.app.name}</h3>
          <ActivityChip lastActivity={row.mostRecentActivity} hasWorkers={row.provisioned > 0} />
        </div>
        <div className="appCard__body">
          <span className="chip"><span className="mono">regions</span><span className="mono">{row.regions}</span></span>
          <span className="chip chip--ok"><span className="mono">ready</span><span className="mono">{row.ready}</span></span>
          {row.inUse > 0 && <span className="chip chip--warn"><span className="mono">in use</span><span className="mono">{row.inUse}</span></span>}
        </div>
        <div className={`capacityBar capacityBar--${variant}`} aria-hidden="true">
          <span style={{ width: `${Math.min(100, Math.round(ratio * 100))}%` }} />
        </div>
        <div className="appCard__footer">
          <span className="mono">{row.provisioned}/{row.maxAvailable} workers</span>
          <span>{Math.round(ratio * 100)}%</span>
        </div>
      </Link>
    </li>
  );
}

// ── Row ──────────────────────────────────────────────────────────

function CapacityListRow({ row }: { row: RowAggregate }) {
  const navigate = useNavigate();
  const ratio = row.maxAvailable > 0 ? (row.ready + row.inUse) / row.maxAvailable : 0;
  const variant: "ok" | "warn" | "err" =
    ratio >= 1 ? "err" : ratio >= 0.8 ? "warn" : "ok";
  const href = `/capacity/${encodeURIComponent(row.app.name)}`;
  function open() { navigate(href); }
  function onKey(e: React.KeyboardEvent<HTMLTableRowElement>) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      open();
    }
  }
  return (
    <tr
      className={`capacityListRow capacityListRow--${variant} capacityListRow--clickable`}
      onClick={open}
      onKeyDown={onKey}
      tabIndex={0}
      role="link"
      aria-label={`Open capacity for ${row.app.name}`}
    >
      <td>
        <Link
          to={href}
          className="mono capacityListRow__name"
          onClick={(e) => e.stopPropagation()}
        >
          {row.app.name}
        </Link>
      </td>
      <td>
        <ActivityChip lastActivity={row.mostRecentActivity} hasWorkers={row.provisioned > 0} />
      </td>
      <td className="mono num">{row.regions}</td>
      <td className="mono num">{row.ready}</td>
      <td className="mono num">{row.inUse}</td>
      <td className="mono num">
        {row.provisioned}<span className="ink-soft">/{row.maxAvailable}</span>
      </td>
      <td>
        <div className={`capacityBar capacityBar--${variant}`} aria-hidden="true">
          <span style={{ width: `${Math.min(100, Math.round(ratio * 100))}%` }} />
        </div>
      </td>
    </tr>
  );
}

function ActivityChip({ lastActivity, hasWorkers }: { lastActivity?: Date; hasWorkers: boolean }) {
  if (!hasWorkers) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>no workers</span>;
  if (!lastActivity) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>—</span>;
  const ms = Date.now() - lastActivity.getTime();
  const sec = Math.round(ms / 1000);
  // Pod heartbeats are every 30s. Anything within 90s is "fresh"; 90s-5m is
  // "recent"; older is "stale" (probably LOST). Color the chip accordingly.
  const variant = sec <= 90 ? "ok" : sec <= 300 ? "warn" : "err";
  const text =
    sec < 60   ? `${sec}s ago` :
    sec < 3600 ? `${Math.round(sec / 60)}m ago` :
                 `${Math.round(sec / 3600)}h ago`;
  return (
    <span className={`chip chip--${variant}`} title={`Most recent worker heartbeat`}>
      active {text}
    </span>
  );
}

// ── Sortable header ──────────────────────────────────────────────

function SortHeader({
  label, k, cur, dir, onClick, numeric = false,
}: {
  label: string;
  k: SortKey;
  cur: SortKey;
  dir: SortDir;
  onClick: (k: SortKey) => void;
  numeric?: boolean;
}) {
  const active = k === cur;
  const arrow = active ? (dir === "asc" ? "▲" : "▼") : "";
  return (
    <th className={numeric ? "num" : ""}>
      <button
        type="button"
        className={`sortHeader ${active ? "sortHeader--active" : ""}`}
        onClick={() => onClick(k)}
        title={`Sort by ${label}`}
      >
        {label} <span className="sortHeader__arrow" aria-hidden="true">{arrow}</span>
      </button>
    </th>
  );
}

// ── Loading skeleton ────────────────────────────────────────────

function SkeletonTable() {
  // 6 placeholder rows is enough to hint at the eventual shape without
  // shifting too tall on small viewports.
  const rows = Array.from({ length: 6 });
  return (
    <table className="runsTable capacityListTable" aria-busy="true">
      <thead>
        <tr>
          <th>Application</th>
          <th>Activity</th>
          <th className="num">Regions</th>
          <th className="num">Ready</th>
          <th className="num">In Use</th>
          <th className="num">Usage</th>
          <th><span className="visuallyHidden">Utilization</span></th>
        </tr>
      </thead>
      <tbody>
        {rows.map((_, i) => (
          <tr key={i} className="capacityListRow capacityListRow--skeleton">
            <td><span className="skeleton skeleton--text" style={{ width: "8rem" }} /></td>
            <td><span className="skeleton skeleton--chip" /></td>
            <td className="num"><span className="skeleton skeleton--text" style={{ width: "1.5rem" }} /></td>
            <td className="num"><span className="skeleton skeleton--text" style={{ width: "1.5rem" }} /></td>
            <td className="num"><span className="skeleton skeleton--text" style={{ width: "1.5rem" }} /></td>
            <td className="num"><span className="skeleton skeleton--text" style={{ width: "2.5rem" }} /></td>
            <td><span className="skeleton skeleton--bar" /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ── Helpers ──────────────────────────────────────────────────────

function compareRows(a: RowAggregate, b: RowAggregate, key: SortKey): number {
  switch (key) {
    case "name":        return a.app.name.localeCompare(b.app.name);
    case "regions":     return a.regions - b.regions;
    case "provisioned": return a.provisioned - b.provisioned;
    case "ready":       return a.ready - b.ready;
    case "inUse":       return a.inUse - b.inUse;
  }
}
