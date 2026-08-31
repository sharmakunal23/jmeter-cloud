import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationsApi, type Application } from "../api/applications";
import { blobsApi, type BlobType } from "../api/blobs";
import { AppListToolbar } from "../components/AppListToolbar";
import { DataList } from "../components/DataList";
import { useRowLink } from "../hooks/useRowLink";

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
  const rowLink = useRowLink();
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");

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
          byType: { testPlan: 0, dataFiles: 0, result: 0, other: 0, template: 0, plugin: 0 },
        });
      }
      const totalsByType: Record<BlobType, number> = {
        testPlan: 0, dataFiles: 0, result: 0, other: 0, template: 0, plugin: 0,
      };
      for (const blob of listing.items) {
        if (!blob.application) continue;            // skip legacy untagged
        if (blob.type === "template" || blob.type === "plugin") continue; // hidden from Documents by convention (managed surfaces own them)
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
        </div>
      </header>

      <AppListToolbar
        search={search}
        onSearchChange={setSearch}
        count={sortedFiltered.length}
        total={totalRowCount}
        loading={loading}
      />

      <DataList<RowAggregate>
        label="Applications"
        loading={loading}
        rows={sortedFiltered}
        rowKey={(r) => r.app.applicationId}
        itemNoun="applications"
        resetKey={`${search}|${sortKey}|${sortDir}`}
        empty={totalRowCount === 0 ? (
          <>
            <strong>No applications registered yet.</strong>
            <div>Register one in <Link to="/applications">Applications</Link> to start uploading
                 documents against it.</div>
          </>
        ) : <>No applications match &quot;{search}&quot;.</>}
        rowProps={(r) => rowLink(`/documents/${encodeURIComponent(r.app.name)}`,
                                 `Open documents for ${r.app.name}`)}
        columns={[
          { key: "name", header: "Application",
            onSort: () => toggleSort("name"),
            sortDirection: sortKey === "name" ? sortDir : null,
            cell: (r) => (
              <Link to={`/documents/${encodeURIComponent(r.app.name)}`}
                    className="mono capacityListRow__name"
                    onClick={(e) => e.stopPropagation()}>
                {r.app.name}
              </Link>
            ) },
          { key: "activity", header: "Activity",
            cell: (r) => <ActivityChip lastUpload={r.mostRecentUpload} hasDocs={r.total > 0} /> },
          { key: "testPlan", header: "Test Plans", className: "dataList__num",
            onSort: () => toggleSort("testPlan"),
            sortDirection: sortKey === "testPlan" ? sortDir : null,
            cell: (r) => <span className="mono">{r.byType.testPlan}</span> },
          { key: "dataFiles", header: "Data Files", className: "dataList__num",
            onSort: () => toggleSort("dataFiles"),
            sortDirection: sortKey === "dataFiles" ? sortDir : null,
            cell: (r) => <span className="mono">{r.byType.dataFiles}</span> },
          { key: "result", header: "Results", className: "dataList__num",
            onSort: () => toggleSort("result"),
            sortDirection: sortKey === "result" ? sortDir : null,
            cell: (r) => <span className="mono">{r.byType.result}</span> },
          { key: "other", header: "Other", className: "dataList__num",
            onSort: () => toggleSort("other"),
            sortDirection: sortKey === "other" ? sortDir : null,
            cell: (r) => <span className="mono">{r.byType.other}</span> },
          { key: "total", header: "Total", className: "dataList__num",
            onSort: () => toggleSort("total"),
            sortDirection: sortKey === "total" ? sortDir : null,
            cell: (r) => <strong className="mono">{r.total}</strong> },
        ]}
      />
    </section>
  );
}

// ── Row ──────────────────────────────────────────────────────────


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


// ── Loading skeleton ────────────────────────────────────────────


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
