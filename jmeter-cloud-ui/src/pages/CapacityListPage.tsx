import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationGroupsApi, type ApplicationGroup } from "../api/applicationGroups";
import { capacityApi, type ReconcileWorkersResult } from "../api/capacity";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { AppListToolbar } from "../components/AppListToolbar";
import { DataList } from "../components/DataList";
import { useCapacitySectionStatus } from "./CapacitySection";
import { useRowLink } from "../hooks/useRowLink";
import { ReconcileWorkersDialog } from "../components/ReconcileWorkersDialog";

/**
 * Capacity › Reservations — one row per application group, since the worker pool is
 * the group's (GROUP-CAPACITY, 2026-08-30): every application in a group
 * draws on the same per-region budget. Per row: regions, ready, in use,
 * `{provisioned}/{max}` with a utilization bar, and a recent-activity chip
 * from the most recent `pod.lastHeartbeat` across the group's regions. The
 * whole row is the click target (the name is a real `<Link>` too), `/`
 * focuses the search box, and skeleton rows hold the layout during the
 * initial fetch.
 */

const POLL_INTERVAL_MS = 10_000;

interface RowAggregate {
  group: ApplicationGroup;
  regions: number;
  maxAvailable: number;
  provisioned: number;
  ready: number;
  inUse: number;
  /** Most recent `pod.lastHeartbeat` across all regions for this group. */
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
  const rowLink = useRowLink();
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<SortKey>("name");
  const [sortDir, setSortDir] = useState<SortDir>("asc");
  const [reconcileOpen, setReconcileOpen] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);

  const showToast = useCallback((t: Toast) => {
    setToast(t);
    window.setTimeout(() => setToast((cur) => (cur === t ? null : cur)), 6000);
  }, []);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      // TWO requests for the whole page, whatever the fleet size. This used to
      // be 1 + groups×regions, and each of those reached the region's
      // Kubernetes API for per-pod container status the list never showed.
      // `fresh` on the groups read: the reservation grid is on a 10 s poll, so
      // the TTL would hold a just-saved change back for a tick or two.
      const [groups, summary] = await Promise.all([
        applicationGroupsApi.list(signal, { fresh: true }),
        capacityApi.summary(signal),
      ]);

      const rowsByGroup = new Map<string, RowAggregate>();
      const regionTotals: Record<string, RegionTotal> = {};
      for (const group of groups) {
        rowsByGroup.set(group.groupId, {
          group, regions: 0, maxAvailable: 0, provisioned: 0, ready: 0, inUse: 0,
        });
      }
      for (const s of summary) {
        const row = rowsByGroup.get(s.groupId);
        if (!row) continue;
        row.regions      += 1;
        row.maxAvailable += s.maxAvailable;
        row.provisioned  += s.provisioned;
        row.ready        += s.ready;
        row.inUse        += s.inUse;
        if (s.lastActivityAt) {
          const t = new Date(s.lastActivityAt);
          if (!row.mostRecentActivity || t > row.mostRecentActivity) {
            row.mostRecentActivity = t;
          }
        }
        const tot = regionTotals[s.region] ?? { provisioned: 0, inUse: 0 };
        tot.provisioned += s.provisioned;
        tot.inUse       += s.inUse;
        regionTotals[s.region] = tot;
      }

      setState({
        status: "ok",
        rows: Array.from(rowsByGroup.values()),
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
      ? state.rows.filter((r) =>
          r.group.name.toLowerCase().includes(needle) || r.group.groupId.toLowerCase().includes(needle))
      : state.rows;
    const sorted = [...filtered].sort((a, b) => {
      const cmp = compareRows(a, b, sortKey);
      return sortDir === "asc" ? cmp : -cmp;
    });
    return sorted;
  }, [state, search, sortKey, sortDir]);

  function toggleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir(key === "name" ? "asc" : "desc");
    }
  }

  // Above the early return below: a hook after a conditional return runs on
  // some renders and not others, which React rejects outright.
  useCapacitySectionStatus(
    state.status === "error" ? null
      : state.status === "loading" ? "Loading…"
      : isPaused ? "Polling paused (tab hidden)"
      : `Refreshed ${formatRelative((state as Extract<State, {status:"ok"}>).refreshedAt.toISOString())}`);

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
      {/* No <h1> and no status line here — the Capacity section shell owns
          both, so the status reads beside the title instead of costing the
          body a row. This header carries only the tab's own actions. */}
      <header className="pageHeader">
        <div className="capacityPage__regionTotals">
          {loading ? (
            <span className="skeleton skeleton--chip" aria-hidden="true" />
          ) : regionEntries.length === 0 ? (
            <span className="ink-soft" style={{ fontSize: "0.85rem" }}>No regions configured.</span>
          ) : regionEntries.map(([region, t]) => (
            <span key={region} className="chip" title="Across all application groups">
              <span className="mono">{region}</span>
              <span className="mono">{t.provisioned} worker{t.provisioned === 1 ? "" : "s"}</span>
              {t.inUse > 0 && <span className="mono ink-soft">· {t.inUse} in use</span>}
            </span>
          ))}
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
        noun="group"
        search={search}
        onSearchChange={setSearch}
        count={sortedFiltered.length}
        total={totalRowCount}
        loading={loading}
      />

      <DataList<RowAggregate>
        label="Application groups"
        loading={loading}
        rows={sortedFiltered}
        rowKey={(r) => r.group.groupId}
        itemNoun="groups"
        resetKey={`${search}|${sortKey}|${sortDir}`}
        empty={totalRowCount === 0 ? (
          <>
            <strong>No application groups yet.</strong>
            <div>Create one with &quot;Manage groups&quot; in <Link to="/applications">Applications</Link> —
                 the worker pool is the group&apos;s.</div>
          </>
        ) : <>No groups match &quot;{search}&quot;.</>}
        rowProps={(r) => {
          const ratio = r.maxAvailable > 0 ? (r.ready + r.inUse) / r.maxAvailable : 0;
          const variant = ratio >= 1 ? "err" : ratio >= 0.8 ? "warn" : "ok";
          const base = rowLink(`/capacity/groups/${encodeURIComponent(r.group.groupId)}`,
                               `Open capacity for ${r.group.name}`);
          // The row is tinted by how full the pool is — the one signal an
          // operator scans this page for.
          return { ...base, className: `${base.className} capacityListRow--${variant}` };
        }}
        columns={[
          { key: "name", header: "Group",
            onSort: () => toggleSort("name"),
            sortDirection: sortKey === "name" ? sortDir : null,
            cell: (r) => (
              <Link to={`/capacity/groups/${encodeURIComponent(r.group.groupId)}`}
                    className="capacityListRow__name"
                    onClick={(e) => e.stopPropagation()}>
                {r.group.name}
              </Link>
            ) },
          { key: "activity", header: "Activity",
            cell: (r) => <ActivityChip lastActivity={r.mostRecentActivity} hasWorkers={r.provisioned > 0} /> },
          { key: "regions", header: "Clusters", className: "dataList__num",
            onSort: () => toggleSort("regions"),
            sortDirection: sortKey === "regions" ? sortDir : null,
            cell: (r) => <span className="mono">{r.regions}</span> },
          { key: "ready", header: "Ready", className: "dataList__num",
            onSort: () => toggleSort("ready"),
            sortDirection: sortKey === "ready" ? sortDir : null,
            cell: (r) => <span className="mono">{r.ready}</span> },
          { key: "inUse", header: "In Use", className: "dataList__num",
            onSort: () => toggleSort("inUse"),
            sortDirection: sortKey === "inUse" ? sortDir : null,
            cell: (r) => <span className="mono">{r.inUse}</span> },
          { key: "provisioned", header: "Usage", className: "dataList__num",
            onSort: () => toggleSort("provisioned"),
            sortDirection: sortKey === "provisioned" ? sortDir : null,
            cell: (r) => (
              <span className="mono">{r.provisioned}<span className="ink-soft">/{r.maxAvailable}</span></span>
            ) },
          { key: "util", header: <span className="visuallyHidden">Utilization</span>, cell: (r) => {
            const ratio = r.maxAvailable > 0 ? (r.ready + r.inUse) / r.maxAvailable : 0;
            const variant = ratio >= 1 ? "err" : ratio >= 0.8 ? "warn" : "ok";
            return (
              <div className={`capacityBar capacityBar--${variant}`} aria-hidden="true">
                <span style={{ width: `${Math.min(100, Math.round(ratio * 100))}%` }} />
              </div>
            );
          } },
        ]}
      />

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

// ── Row ──────────────────────────────────────────────────────────


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


// ── Loading skeleton ────────────────────────────────────────────


// ── Helpers ──────────────────────────────────────────────────────

function compareRows(a: RowAggregate, b: RowAggregate, key: SortKey): number {
  switch (key) {
    case "name":        return a.group.name.localeCompare(b.group.name);
    case "regions":     return a.regions - b.regions;
    case "provisioned": return a.provisioned - b.provisioned;
    case "ready":       return a.ready - b.ready;
    case "inUse":       return a.inUse - b.inUse;
  }
}
