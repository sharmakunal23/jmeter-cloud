import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationsApi, type Application } from "../api/applications";
import { templatesApi } from "../api/templates";
import { AppListToolbar } from "../components/AppListToolbar";
import { Paginator } from "../components/Paginator";
import { useClientPagination } from "../hooks/useClientPagination";
import {
  ViewModeToggle,
  type ListViewMode,
  persistViewMode,
  readPersistedViewMode,
} from "../components/ViewModeToggle";

// Distinct from `jmeterCloud.templates.viewMode` (DETAIL page's grid/list)
// so toggling on the list view doesn't change the operator's per-app pick.
const VIEW_MODE_STORAGE_KEY = "jmeterCloud.templates.listViewMode";

/**
 * Phase IA-Templates (2026-05-13) — list view following the IA pattern
 * proven on `/capacity` and `/documents`. One row per registered
 * application + per-row template count + Activity (last-saved) chip.
 *
 * <p>Click a row → `/templates/{appName}` for the per-app drill-in
 * with the existing card / list view scoped to that application via
 * `<TemplatesDetailPage>`.
 *
 * <p>Templates that lack an `application` tag (legacy pre-tagging) are
 * deliberately excluded — the IA is application-first; surfacing
 * orphaned templates here would force a special "(no app)" pseudo-row
 * that doesn't fit. The operator can still see them via the legacy
 * `/templates` flat-list path until that gets removed.
 */

interface RowAggregate {
  app: Application;
  count: number;
  /** Most recent `uploadedAt` across this app's templates. */
  mostRecentSave?: Date;
}

type SortKey = "name" | "count";
type SortDir = "asc" | "desc";

type State =
  | { status: "loading" }
  | { status: "ok"; rows: RowAggregate[]; totalCount: number; refreshedAt: Date }
  | { status: "error"; message: string };

export function TemplatesListPage() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");
  const [viewMode, setViewMode] = useState<ListViewMode>(() => readPersistedViewMode(VIEW_MODE_STORAGE_KEY));

  function changeViewMode(next: ListViewMode) {
    setViewMode(next);
    persistViewMode(VIEW_MODE_STORAGE_KEY, next);
  }

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const [apps, templates] = await Promise.all([
        applicationsApi.list(signal),
        templatesApi.list(signal),
      ]);
      const rowsByApp = new Map<string, RowAggregate>();
      for (const app of apps) {
        rowsByApp.set(app.name, { app, count: 0 });
      }
      let totalCount = 0;
      for (const t of templates) {
        if (!t.application) continue;
        const row = rowsByApp.get(t.application);
        if (!row) continue;
        row.count += 1;
        totalCount += 1;
        if (t.uploadedAt) {
          const u = new Date(t.uploadedAt);
          if (!row.mostRecentSave || u > row.mostRecentSave) {
            row.mostRecentSave = u;
          }
        }
      }
      setState({
        status: "ok",
        rows: Array.from(rowsByApp.values()),
        totalCount,
        refreshedAt: new Date(),
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

  function toggleSort(k: SortKey) {
    if (sortKey === k) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else { setSortKey(k); setSortDir(k === "name" ? "asc" : "desc"); }
  }

  if (state.status === "error") return <p className="text--error">{state.message}</p>;

  const loading = state.status === "loading";
  const totalRowCount = state.status === "ok" ? state.rows.length : 0;
  const totalCount = state.status === "ok" ? state.totalCount : 0;

  return (
    <section className="templatesListPage capacityPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Templates</h1>
          <small className="ink-soft" aria-live="polite">
            {loading
              ? "Loading…"
              : `Refreshed ${formatRelative((state as Extract<State, {status:"ok"}>).refreshedAt.toISOString())}`}
          </small>
        </div>
        <div className="capacityPage__regionTotals">
          {loading ? (
            <span className="skeleton skeleton--chip" aria-hidden="true" />
          ) : (
            <span className="chip" title="Across all applications">
              <span className="mono">templates</span>
              <span className="mono">{totalCount}</span>
            </span>
          )}
          <ViewModeToggle viewMode={viewMode} onChange={changeViewMode} />
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
                Register one in <Link to="/applications">Applications</Link> to start saving templates against it.
              </p>
            </>
          ) : (
            <p className="ink-soft">No applications match "{search}".</p>
          )}
        </div>
      ) : viewMode === "grid" ? (
        <ul className="appCardGrid" aria-label="application template cards">
          {pageItems.map((r) => (
            <TemplatesCard key={r.app.applicationId} row={r} />
          ))}
        </ul>
      ) : (
        <table className="runsTable capacityListTable">
          <thead>
            <tr>
              <SortHeader label="Application" k="name"  cur={sortKey} dir={sortDir} onClick={toggleSort} />
              <th>Activity</th>
              <SortHeader label="Templates"   k="count" cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
            </tr>
          </thead>
          <tbody>
            {pageItems.map((r) => (
              <TemplatesListRow key={r.app.applicationId} row={r} />
            ))}
          </tbody>
        </table>
      )}

      {!loading && sortedFiltered.length > 0 && (
        <Paginator page={page} pageSize={pageSize} total={total} label="applications" onChange={setPage} />
      )}
    </section>
  );
}

// ── Row ──────────────────────────────────────────────────────────

function TemplatesListRow({ row }: { row: RowAggregate }) {
  const navigate = useNavigate();
  const href = `/templates/${encodeURIComponent(row.app.name)}`;
  function open() { navigate(href); }
  function onKey(e: React.KeyboardEvent<HTMLTableRowElement>) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      open();
    }
  }
  return (
    <tr
      className="capacityListRow capacityListRow--clickable"
      onClick={open}
      onKeyDown={onKey}
      tabIndex={0}
      role="link"
      aria-label={`Open templates for ${row.app.name}`}
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
        <ActivityChip lastSave={row.mostRecentSave} hasTemplates={row.count > 0} />
      </td>
      <td className="mono num"><strong>{row.count}</strong></td>
    </tr>
  );
}

// ── Grid card (Templates) ────────────────────────────────────────

function TemplatesCard({ row }: { row: RowAggregate }) {
  const href = `/templates/${encodeURIComponent(row.app.name)}`;
  return (
    <li>
      <Link to={href} className="appCard" aria-label={`Open templates for ${row.app.name}`}>
        <div className="appCard__head">
          <h3 className="appCard__name">{row.app.name}</h3>
          <ActivityChip lastSave={row.mostRecentSave} hasTemplates={row.count > 0} />
        </div>
        <div className="appCard__body">
          {row.count > 0
            ? <span className="chip"><span className="mono">templates</span><span className="mono">{row.count}</span></span>
            : <span className="ink-soft" style={{ fontSize: "0.78rem" }}>no templates</span>}
        </div>
      </Link>
    </li>
  );
}

function ActivityChip({ lastSave, hasTemplates }: { lastSave?: Date; hasTemplates: boolean }) {
  if (!hasTemplates) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>no templates</span>;
  if (!lastSave) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>—</span>;
  const ms = Date.now() - lastSave.getTime();
  const sec = Math.round(ms / 1000);
  // Templates are saved infrequently — fresh = within 24h, recent = within 7d, older = stale.
  const variant = sec <= 86400 ? "ok" : sec <= 604800 ? "warn" : "";
  const text =
    sec < 60        ? `${sec}s ago` :
    sec < 3600      ? `${Math.round(sec / 60)}m ago` :
    sec < 86400     ? `${Math.round(sec / 3600)}h ago` :
                      `${Math.round(sec / 86400)}d ago`;
  return (
    <span className={`chip ${variant ? `chip--${variant}` : ""}`} title="Most recent template save">
      saved {text}
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
  const rows = Array.from({ length: 6 });
  return (
    <table className="runsTable capacityListTable" aria-busy="true">
      <thead>
        <tr>
          <th>Application</th>
          <th>Activity</th>
          <th className="num">Templates</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((_, i) => (
          <tr key={i} className="capacityListRow capacityListRow--skeleton">
            <td><span className="skeleton skeleton--text" style={{ width: "8rem" }} /></td>
            <td><span className="skeleton skeleton--chip" /></td>
            <td className="num"><span className="skeleton skeleton--text" style={{ width: "2rem" }} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ── Helpers ─────────────────────────────────────────────────────

function compareRows(a: RowAggregate, b: RowAggregate, key: SortKey): number {
  switch (key) {
    case "name":  return a.app.name.localeCompare(b.app.name);
    case "count": return a.count - b.count;
  }
}

export { TemplatesListPage as default };
