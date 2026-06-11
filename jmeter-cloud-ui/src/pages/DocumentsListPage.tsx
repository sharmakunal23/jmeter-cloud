import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationsApi, type Application } from "../api/applications";
import { blobsApi, type BlobType } from "../api/blobs";
import { AppListToolbar } from "../components/AppListToolbar";
import { Paginator } from "../components/Paginator";
import { useClientPagination } from "../hooks/useClientPagination";
import {
  ViewModeToggle,
  type ListViewMode,
  persistViewMode,
  readPersistedViewMode,
} from "../components/ViewModeToggle";

const VIEW_MODE_STORAGE_KEY = "jmeterCloud.documents.listViewMode";

/**
 * Phase IA-Documents (2026-05-12) — Documents list view following the
 * IA pattern proven on `/capacity`. One row per
 * registered application, per-row counts of testPlan / dataFiles /
 * result / other / total + Activity chip showing the most recent
 * upload across the app's docs.
 *
 * <p>Click a row → `/documents/{appName}` for the per-app drill-in
 * with the existing 4-tab strip (Test Plans / Data Files / Results
 * / Other) scoped to that app.
 *
 * <p>Polling: documents change less often than capacity / runs, so
 * this page does NOT visibility-poll. Refresh on mount + a manual
 * "Refresh" button covers the common cases without burning RDS reads.
 *
 * <p>Aggregation: a single `blobsApi.list({ limit: 500 })` returns
 * everything tagged with an application; we group by `application` +
 * count by `type` client-side. Untagged blobs (legacy uploads pre-
 * Step 28) are excluded — operators rarely care about them post-
 * tagging, and they'd need a special "Unassigned" pseudo-row that
 * doesn't fit the per-app IA. (If they bite later, add the row.)
 */

const FETCH_LIMIT = 500;
const TYPES_IN_TABLE: BlobType[] = ["testPlan", "dataFiles", "result", "other"];

interface RowAggregate {
  app: Application;
  total: number;
  byType: Record<BlobType, number>;
  /** Most recent `uploadedAt` across the app's docs, or undefined. */
  mostRecentUpload?: Date;
}

type SortKey = "name" | "total" | "testPlan" | "dataFiles" | "result" | "other";
type SortDir = "asc" | "desc";

type State =
  | { status: "loading" }
  | { status: "ok"; rows: RowAggregate[]; totalsByType: Record<BlobType, number>; refreshedAt: Date }
  | { status: "error"; message: string };

export function DocumentsListPage() {
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
      const [apps, listing] = await Promise.all([
        applicationsApi.list(signal),
        blobsApi.list({ limit: FETCH_LIMIT }, signal),
      ]);
      const rowsByApp = new Map<string, RowAggregate>();
      for (const app of apps) {
        rowsByApp.set(app.name, {
          app,
          total: 0,
          byType: { testPlan: 0, dataFiles: 0, result: 0, other: 0, template: 0 },
        });
      }
      const totalsByType: Record<BlobType, number> = {
        testPlan: 0, dataFiles: 0, result: 0, other: 0, template: 0,
      };
      for (const blob of listing.items) {
        if (!blob.application) continue;            // skip legacy untagged
        if (blob.type === "template") continue;     // hidden from Documents tab by convention
        const row = rowsByApp.get(blob.application);
        if (!row) continue;                          // tagged with an app no longer in registry
        const t = (blob.type as BlobType) || "other";
        row.total += 1;
        row.byType[t] = (row.byType[t] ?? 0) + 1;
        totalsByType[t] = (totalsByType[t] ?? 0) + 1;
        if (blob.uploadedAt) {
          const u = new Date(blob.uploadedAt);
          if (!row.mostRecentUpload || u > row.mostRecentUpload) {
            row.mostRecentUpload = u;
          }
        }
      }
      setState({
        status: "ok",
        rows: Array.from(rowsByApp.values()),
        totalsByType,
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
  const totalsByType = state.status === "ok" ? state.totalsByType : null;

  return (
    <section className="documentsListPage capacityPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Documents</h1>
          <small className="ink-soft" aria-live="polite">
            {loading
              ? "Loading…"
              : `Refreshed ${formatRelative((state as Extract<State, {status:"ok"}>).refreshedAt.toISOString())}`}
          </small>
        </div>
        <div className="capacityPage__regionTotals">
          {loading || !totalsByType ? (
            <span className="skeleton skeleton--chip" aria-hidden="true" />
          ) : (
            TYPES_IN_TABLE.map((t) => (
              <span key={t} className="chip" title="Across all applications">
                <span className="mono">{labelFor(t)}</span>
                <span className="mono">{totalsByType[t] ?? 0}</span>
              </span>
            ))
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
                Register one in <Link to="/applications">Applications</Link> to start uploading documents against it.
              </p>
            </>
          ) : (
            <p className="ink-soft">No applications match "{search}".</p>
          )}
        </div>
      ) : viewMode === "grid" ? (
        <ul className="appCardGrid" aria-label="application document cards">
          {pageItems.map((r) => (
            <DocumentsCard key={r.app.applicationId} row={r} />
          ))}
        </ul>
      ) : (
        <table className="runsTable capacityListTable">
          <thead>
            <tr>
              <SortHeader label="Application" k="name"      cur={sortKey} dir={sortDir} onClick={toggleSort} />
              <th>Activity</th>
              <SortHeader label="Test Plans" k="testPlan"  cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Data Files" k="dataFiles" cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Results"    k="result"    cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Other"      k="other"     cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Total"      k="total"     cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
            </tr>
          </thead>
          <tbody>
            {pageItems.map((r) => (
              <DocumentsListRow key={r.app.applicationId} row={r} />
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

function DocumentsListRow({ row }: { row: RowAggregate }) {
  const navigate = useNavigate();
  const href = `/documents/${encodeURIComponent(row.app.name)}`;
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
      aria-label={`Open documents for ${row.app.name}`}
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
        <ActivityChip lastUpload={row.mostRecentUpload} hasDocs={row.total > 0} />
      </td>
      <td className="mono num">{row.byType.testPlan}</td>
      <td className="mono num">{row.byType.dataFiles}</td>
      <td className="mono num">{row.byType.result}</td>
      <td className="mono num">{row.byType.other}</td>
      <td className="mono num"><strong>{row.total}</strong></td>
    </tr>
  );
}

// ── Grid card (Documents) ────────────────────────────────────────

function DocumentsCard({ row }: { row: RowAggregate }) {
  const href = `/documents/${encodeURIComponent(row.app.name)}`;
  return (
    <li>
      <Link to={href} className="appCard" aria-label={`Open documents for ${row.app.name}`}>
        <div className="appCard__head">
          <h3 className="appCard__name">{row.app.name}</h3>
          <ActivityChip lastUpload={row.mostRecentUpload} hasDocs={row.total > 0} />
        </div>
        <div className="appCard__body">
          {TYPES_IN_TABLE.map((t) => (
            row.byType[t] > 0 && (
              <span key={t} className="chip">
                <span className="mono">{labelFor(t)}</span>
                <span className="mono">{row.byType[t]}</span>
              </span>
            )
          ))}
          {row.total === 0 && <span className="ink-soft" style={{ fontSize: "0.78rem" }}>no documents</span>}
        </div>
        <div className="appCard__footer">
          <span className="mono">{row.total} total</span>
        </div>
      </Link>
    </li>
  );
}

function ActivityChip({ lastUpload, hasDocs }: { lastUpload?: Date; hasDocs: boolean }) {
  if (!hasDocs) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>no documents</span>;
  if (!lastUpload) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>—</span>;
  const ms = Date.now() - lastUpload.getTime();
  const sec = Math.round(ms / 1000);
  // Documents are uploaded much less frequently than worker heartbeats —
  // colour fresh = within 1h, recent = within 24h, older = stale.
  const variant = sec <= 3600 ? "ok" : sec <= 86400 ? "warn" : "";
  const text =
    sec < 60        ? `${sec}s ago` :
    sec < 3600      ? `${Math.round(sec / 60)}m ago` :
    sec < 86400     ? `${Math.round(sec / 3600)}h ago` :
                      `${Math.round(sec / 86400)}d ago`;
  return (
    <span className={`chip ${variant ? `chip--${variant}` : ""}`} title="Most recent document upload">
      uploaded {text}
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
          <th className="num">Test Plans</th>
          <th className="num">Data Files</th>
          <th className="num">Results</th>
          <th className="num">Other</th>
          <th className="num">Total</th>
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
            <td className="num"><span className="skeleton skeleton--text" style={{ width: "1.5rem" }} /></td>
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
    case "name":      return a.app.name.localeCompare(b.app.name);
    case "total":     return a.total - b.total;
    case "testPlan":  return a.byType.testPlan  - b.byType.testPlan;
    case "dataFiles": return a.byType.dataFiles - b.byType.dataFiles;
    case "result":    return a.byType.result    - b.byType.result;
    case "other":     return a.byType.other     - b.byType.other;
  }
}

function labelFor(t: BlobType): string {
  switch (t) {
    case "testPlan":  return "test plans";
    case "dataFiles": return "data files";
    case "result":    return "results";
    case "other":     return "other";
    default:          return t;
  }
}
