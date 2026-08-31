import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { applicationsApi, type Application } from "../api/applications";
import { cronJobsApi, isReportKind, type CronJobSummary } from "../api/automation";
import { formatFuture, formatRelative } from "../lib/time";
import { AppListToolbar } from "../components/AppListToolbar";
import { Paginator } from "../components/Paginator";
import { useClientPagination } from "../hooks/useClientPagination";
import { PlatformSchedulesSection } from "../components/PlatformSchedulesSection";

/**
 * Phase IA-Automation (2026-05-13) — list view following the IA pattern
 * proven on `/capacity`, `/documents`, and `/templates`. One row per
 * registered application + per-row cron-job count (with enabled /
 * disabled split) + Activity (last-fired) chip.
 *
 * <p>D6-A backend hasn't shipped yet, so `cronJobsApi.list()` returns
 * `[]` for every app. The page still renders the IA correctly: rows,
 * sort, search, '/' shortcut, skeleton — operators see the shape of
 * the future surface without a "coming soon" stub. When D6-A lands,
 * only the API client body changes (see `api/automation.ts` header).
 *
 * <p>Click a row → `/automation/{appName}` for the per-app drill-in.
 */

interface RowAggregate {
  app: Application;
  total: number;
  enabled: number;
  disabled: number;
  /** Most recent `lastFiredAt` across this app's CRON jobs. */
  mostRecentFire?: Date;
  /** Earliest `nextFireAt` across this app's enabled CRON jobs. */
  nextFire?: Date;
}

type SortKey = "name" | "total" | "enabled" | "lastFired" | "nextFire";
type SortDir = "asc" | "desc";

type State =
  | { status: "loading" }
  | { status: "ok"; rows: RowAggregate[]; platformJobs: CronJobSummary[]; totalCount: number; refreshedAt: Date }
  | { status: "error"; message: string };

export function AutomationListPage() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");
  // Two tabs so a long list of platform reports never pushes the app list
  // off-screen (operator request).
  const [tab, setTab] = useState<"applications" | "platform">("applications");

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const [apps, jobs] = await Promise.all([
        applicationsApi.list(signal),
        cronJobsApi.list(undefined, signal),
      ]);
      const rowsByApp = new Map<string, RowAggregate>();
      for (const app of apps) {
        rowsByApp.set(app.name, {
          app, total: 0, enabled: 0, disabled: 0,
        });
      }
      let totalCount = 0;
      // Platform-wide report schedules (INFRA_READINESS / DAILY_REPORT) have no
      // application — surface them in their own section, not the per-app rows.
      const platformJobs: CronJobSummary[] = [];
      for (const job of jobs) {
        if (isReportKind(job.kind) || !job.applicationName) {
          platformJobs.push(job);
          totalCount += 1;
          continue;
        }
        const row = rowsByApp.get(job.applicationName);
        if (!row) continue;
        row.total += 1;
        totalCount += 1;
        if (job.enabled) row.enabled += 1;
        else row.disabled += 1;
        if (job.lastFiredAt) {
          const t = new Date(job.lastFiredAt);
          if (!row.mostRecentFire || t > row.mostRecentFire) row.mostRecentFire = t;
        }
        if (job.enabled && job.nextFireAt) {
          const t = new Date(job.nextFireAt);
          if (!row.nextFire || t < row.nextFire) row.nextFire = t;
        }
      }
      setState({
        status: "ok",
        rows: Array.from(rowsByApp.values()),
        platformJobs,
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
  const platformCount = state.status === "ok" ? state.platformJobs.length : 0;
  const appsScheduleCount = totalCount - platformCount;

  return (
    <section className="automationListPage capacityPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Automation</h1>
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
            <span className="chip" title="Across all applications + platform reports">
              <span className="mono">schedules</span>
              <span className="mono">{totalCount}</span>
            </span>
          )}
        </div>
      </header>

      <div className="tabBar" role="tablist" aria-label="Automation views">
        <button
          type="button" role="tab" aria-selected={tab === "applications"}
          className={`tabBar__tab ${tab === "applications" ? "tabBar__tab--active" : ""}`}
          onClick={() => setTab("applications")}
        >
          Applications {!loading && <span className="tabBar__count">{appsScheduleCount}</span>}
        </button>
        <button
          type="button" role="tab" aria-selected={tab === "platform"}
          className={`tabBar__tab ${tab === "platform" ? "tabBar__tab--active" : ""}`}
          onClick={() => setTab("platform")}
        >
          Platform reports {!loading && <span className="tabBar__count">{platformCount}</span>}
        </button>
      </div>

      {tab === "platform" ? (
        loading ? (
          <p className="ink-soft">Loading…</p>
        ) : (
          <PlatformSchedulesSection
            jobs={(state as Extract<State, { status: "ok" }>).platformJobs}
            onChanged={() => void refresh()}
          />
        )
      ) : (
      <>
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
                Register one in <Link to="/applications">Applications</Link> to start scheduling against it.
              </p>
            </>
          ) : (
            <p className="ink-soft">No applications match "{search}".</p>
          )}
        </div>
      ) : (
        <table className="runsTable capacityListTable">
          <thead>
            <tr>
              <SortHeader label="Application" k="name"      cur={sortKey} dir={sortDir} onClick={toggleSort} />
              <th>Activity</th>
              <SortHeader label="Last Fired"  k="lastFired" cur={sortKey} dir={sortDir} onClick={toggleSort} />
              <SortHeader label="Next Fire"   k="nextFire"  cur={sortKey} dir={sortDir} onClick={toggleSort} />
              <SortHeader label="Enabled"     k="enabled"   cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
              <SortHeader label="Schedules"   k="total"     cur={sortKey} dir={sortDir} onClick={toggleSort} numeric />
            </tr>
          </thead>
          <tbody>
            {pageItems.map((r) => (
              <AutomationListRow key={r.app.applicationId} row={r} />
            ))}
          </tbody>
        </table>
      )}

      {!loading && sortedFiltered.length > 0 && (
        <Paginator page={page} pageSize={pageSize} total={total} label="applications" onChange={setPage} />
      )}
      </>
      )}
    </section>
  );
}

// ── Row ──────────────────────────────────────────────────────────

function AutomationListRow({ row }: { row: RowAggregate }) {
  const navigate = useNavigate();
  const href = `/automation/${encodeURIComponent(row.app.name)}`;
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
      aria-label={`Open automation for ${row.app.name}`}
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
        <ActivityChip lastFire={row.mostRecentFire} hasJobs={row.total > 0} />
      </td>
      <td className="ink-soft" style={{ fontSize: "0.85rem" }}>
        {row.mostRecentFire ? formatRelative(row.mostRecentFire.toISOString()) : "—"}
      </td>
      <td className="ink-soft" style={{ fontSize: "0.85rem" }}>
        {row.nextFire ? formatFuture(row.nextFire.toISOString()) : "—"}
      </td>
      <td className="mono num">
        {row.total > 0
          ? <><strong>{row.enabled}</strong>{row.disabled > 0 && <span className="ink-soft">/{row.total}</span>}</>
          : <span className="ink-soft">0</span>}
      </td>
      <td className="mono num"><strong>{row.total}</strong></td>
    </tr>
  );
}

function ActivityChip({ lastFire, hasJobs }: { lastFire?: Date; hasJobs: boolean }) {
  if (!hasJobs) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>no schedules</span>;
  if (!lastFire) return <span className="ink-soft" style={{ fontSize: "0.78rem" }}>not yet fired</span>;
  const ms = Date.now() - lastFire.getTime();
  const sec = Math.round(ms / 1000);
  // CRON jobs typically fire daily / weekly — fresh = within 24h, recent = within 7d, older = stale.
  const variant = sec <= 86400 ? "ok" : sec <= 604800 ? "warn" : "";
  const text =
    sec < 60        ? `${sec}s ago` :
    sec < 3600      ? `${Math.round(sec / 60)}m ago` :
    sec < 86400     ? `${Math.round(sec / 3600)}h ago` :
                      `${Math.round(sec / 86400)}d ago`;
  return (
    <span className={`chip ${variant ? `chip--${variant}` : ""}`} title="Most recent CRON fire">
      fired {text}
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
          <th>Last Fired</th>
          <th>Next Fire</th>
          <th className="num">Enabled</th>
          <th className="num">Schedules</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((_, i) => (
          <tr key={i} className="capacityListRow capacityListRow--skeleton">
            <td><span className="skeleton skeleton--text" style={{ width: "8rem" }} /></td>
            <td><span className="skeleton skeleton--chip" /></td>
            <td><span className="skeleton skeleton--text" style={{ width: "5rem" }} /></td>
            <td><span className="skeleton skeleton--text" style={{ width: "5rem" }} /></td>
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
    case "name":    return a.app.name.localeCompare(b.app.name);
    case "total":   return a.total - b.total;
    case "enabled": return a.enabled - b.enabled;
    case "lastFired": {
      const av = a.mostRecentFire?.getTime() ?? -Infinity;
      const bv = b.mostRecentFire?.getTime() ?? -Infinity;
      return av - bv;
    }
    case "nextFire": {
      // Apps with no upcoming fires sort to the bottom of an asc sort.
      const av = a.nextFire?.getTime() ?? Infinity;
      const bv = b.nextFire?.getTime() ?? Infinity;
      return av - bv;
    }
  }
}

export { AutomationListPage as default };
