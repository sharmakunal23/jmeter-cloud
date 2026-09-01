import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationsApi, type Application } from "../api/applications";
import { templatesApi } from "../api/templates";
import { AppListToolbar } from "../components/AppListToolbar";
import { DataList } from "../components/DataList";
import { useRowLink } from "../hooks/useRowLink";

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
  const rowLink = useRowLink();
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");

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
        <div className="pageHeader__actions">
          {loading ? (
            <span className="skeleton skeleton--chip" aria-hidden="true" />
          ) : (
            <span className="chip" title="Across all applications">
              <span className="mono">templates</span>
              <span className="mono">{totalCount}</span>
            </span>
          )}
        </div>
      </header>


      <DataList<RowAggregate>
        toolbar={<AppListToolbar
          search={search}
          onSearchChange={setSearch}
          count={sortedFiltered.length}
          total={totalRowCount}
          loading={loading}
        />}
        label="Applications"
        loading={loading}
        rows={sortedFiltered}
        rowKey={(r) => r.app.applicationId}
        itemNoun="applications"
        resetKey={`${search}|${sortKey}|${sortDir}`}
        empty={totalRowCount === 0 ? (
          <>
            <strong>No applications registered yet.</strong>
            <div>Register one in <Link to="/applications">Applications</Link> to start saving
                 templates against it.</div>
          </>
        ) : <>No applications match &quot;{search}&quot;.</>}
        rowProps={(r) => rowLink(`/templates/${encodeURIComponent(r.app.name)}`,
                                      `Open templates for ${r.app.name}`)}
        columns={[
          { key: "name", header: "Application",
            onSort: () => toggleSort("name"),
            sortDirection: sortKey === "name" ? sortDir : null,
            cell: (r) => (
              <Link to={`/templates/${encodeURIComponent(r.app.name)}`}
                    className="mono listRow__name"
                    onClick={(e) => e.stopPropagation()}>
                {r.app.name}
              </Link>
            ) },
          { key: "activity", header: "Activity",
            cell: (r) => <ActivityChip lastSave={r.mostRecentSave} hasTemplates={r.count > 0} /> },
          { key: "count", header: "Templates", className: "dataList__num",
            onSort: () => toggleSort("count"),
            sortDirection: sortKey === "count" ? sortDir : null,
            cell: (r) => <strong className="mono">{r.count}</strong> },
        ]}
      />
    </section>
  );
}

// ── Row ──────────────────────────────────────────────────────────


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


// ── Loading skeleton ────────────────────────────────────────────


// ── Helpers ─────────────────────────────────────────────────────

function compareRows(a: RowAggregate, b: RowAggregate, key: SortKey): number {
  switch (key) {
    case "name":  return a.app.name.localeCompare(b.app.name);
    case "count": return a.count - b.count;
  }
}

export { TemplatesListPage as default };
