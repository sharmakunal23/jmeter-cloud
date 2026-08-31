import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { useInterval } from "../hooks/useInterval";
import { runsApi, type Run } from "../api/runs";
import { RegionBadgeList } from "../components/RegionBadge";
import { DeleteRunsConfirmDialog } from "../components/DeleteRunsConfirmDialog";
import { RunsComparePage } from "./RunsComparePage";

/**
 * Runs listing — Step 17 adds 5-s polling against
 * {@code GET /api/v1/runs?state=…} and a comparison toolbar (multi-select
 * checkboxes → "Compare selected" button → navigates to
 * {@code /runs?compare=runA,runB,…}).
 *
 * <p>When the {@code compare} search param is present, the page renders
 * {@link RunsComparePage} instead of the table — the plan asked for the
 * comparison to live behind the {@code /runs?compare=A,B} URL rather
 * than a separate route.
 */
type ListState =
  | { status: "loading" }
  | { status: "ok"; runs: Run[] }
  | { status: "error"; message: string };

const POLL_INTERVAL_MS = 5_000;

/**
 * Comparison view is strictly two runs. Selecting a third in
 * the table replaces the oldest pick rather than blocking the click.
 */
const MAX_COMPARE_SELECTION = 2;

export function RunsListPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  // ── Comparison sub-mode ────────────────────────────────────────────
  const compareParam = searchParams.get("compare");
  if (compareParam) {
    return <RunsComparePage runIds={parseCompareIds(compareParam)} />;
  }

  return <RunsTable searchParams={searchParams} setSearchParams={setSearchParams} />;
}

// ── Implementation split out so React can short-circuit on the
//    comparison sub-mode without entering the polling effect. ─────────

interface RunsTableProps {
  searchParams: URLSearchParams;
  setSearchParams: ReturnType<typeof useSearchParams>[1];
}

function RunsTable({ searchParams, setSearchParams }: RunsTableProps) {
  const activeOnly = searchParams.get("state") === "active";
  const [state, setState] = useState<ListState>({ status: "loading" });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  // Tracks the most recent successful fetch — useful for showing
  // "last refreshed at" and for surfacing transient network blips
  // without flicking the whole table to "error".
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);

  const fetchOnce = useCallback(() => {
    const ctl = new AbortController();
    runsApi
      // UI-D2 standardized page size — 25 newest runs. Full URL-driven
      // pagination is UI-D3's job (needs backend offset support; the
      // current /runs API only honors `limit`).
      .list({ activeOnly, limit: 25 }, ctl.signal)
      .then((runs) => {
        setState({ status: "ok", runs });
        setLastRefreshed(new Date());
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        // On the very first fetch, surface the error; on subsequent
        // poll ticks, keep showing the last good data and just stop
        // updating lastRefreshed.
        setState((prev) =>
          prev.status === "ok"
            ? prev
            : { status: "error", message: err instanceof Error ? err.message : String(err) },
        );
      });
    return ctl;
  }, [activeOnly]);

  // Initial + activeOnly-flip fetch.
  useEffect(() => {
    const ctl = fetchOnce();
    return () => ctl.abort();
  }, [fetchOnce]);

  // 5-s polling — paused once the page navigates away (cleanup runs in useInterval).
  useInterval(fetchOnce, POLL_INTERVAL_MS);

  // ── Toolbar handlers ───────────────────────────────────────────────

  const toggleActiveOnly = (checked: boolean) => {
    const next = new URLSearchParams(searchParams);
    if (checked) next.set("state", "active");
    else next.delete("state");
    setSearchParams(next);
  };

  // Unlimited multi-select — bulk delete needs to act on any
  // number of runs. Compare is a separate gate that lights up only at exactly
  // MAX_COMPARE_SELECTION; selecting a third no longer auto-evicts the oldest
  // (that only made sense when 2 was the hard cap).
  const toggleSelected = (runId: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(runId)) next.delete(runId);
      else next.add(runId);
      return next;
    });
  };

  const showToast = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(null), 6000);
  };

  // Called by the delete dialog with the runIds it successfully hid: drop them
  // from the selection and refresh so they vanish from the list immediately
  // (the 5-s poll would catch it too, but this is instant).
  const onDeleted = (deletedRunIds: string[]) => {
    setDeleteOpen(false);
    setSelected((prev) => {
      const next = new Set(prev);
      for (const id of deletedRunIds) next.delete(id);
      return next;
    });
    fetchOnce();
    showToast(
      `${deletedRunIds.length} run${deletedRunIds.length === 1 ? "" : "s"} archived.`,
    );
  };

  const selectedRuns =
    state.status === "ok" ? state.runs.filter((r) => selected.has(r.runId)) : [];

  const compareUrl =
    selected.size === MAX_COMPARE_SELECTION
      ? `/applications?compare=${[...selected].join(",")}`
      : null;

  return (
    <section>
      <header className="pageHeader">
        <h1>Runs</h1>
        <Link to="/applications" className="btn btn--primary">+ New run</Link>
      </header>

      <div className="runsToolbar">
        <label className="filterToggle">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => toggleActiveOnly(e.target.checked)}
          />
          Active only (hide COMPLETED / FAILED / ABORTED)
        </label>

        <span className="runsToolbar__spacer" />

        {selected.size > 0 && (
          <span className="runsToolbar__selected">
            {selected.size} selected
            <button
              type="button"
              className="btn btn--ghost"
              onClick={() => setSelected(new Set())}
            >
              Clear
            </button>
            <button
              type="button"
              className="btn btn--danger btn--sm"
              onClick={() => setDeleteOpen(true)}
            >
              Archive selected
            </button>
          </span>
        )}

        {compareUrl ? (
          <Link to={compareUrl} className="btn btn--primary">
            Compare 2 runs →
          </Link>
        ) : (
          <button
            type="button"
            className="btn"
            disabled
            title={selected.size === 1
              ? "Select one more run to compare (the comparison view shows exactly two)"
              : "Select two runs to compare"}
          >
            Compare selected
          </button>
        )}

        <span className="runsToolbar__refreshed">
          {lastRefreshed
            ? `refreshed ${formatRelative(lastRefreshed)}`
            : "loading…"}
        </span>
      </div>

      {state.status === "loading" && <p>loading runs…</p>}
      {state.status === "error" && <p className="text--error">{state.message}</p>}
      {state.status === "ok" && state.runs.length === 0 && (
        <p>No runs yet. <Link to="/applications">Start your first run →</Link></p>
      )}
      {state.status === "ok" && state.runs.length > 0 && (
        <table className="runsTable">
          <thead>
            <tr>
              <th aria-label="select" className="runsTable__check"></th>
              <th>Run ID</th>
              <th>State</th>
              <th>Cluster</th>
              <th>Fleet</th>
              <th>Started</th>
              <th>Initiated by</th>
            </tr>
          </thead>
          <tbody>
            {state.runs.map((run) => (
              <tr key={run.runId}>
                <td className="runsTable__check">
                  <input
                    type="checkbox"
                    aria-label={`select ${run.runId}`}
                    checked={selected.has(run.runId)}
                    onChange={() => toggleSelected(run.runId)}
                  />
                </td>
                <td>
                  <Link to={`/applications/_/runs/${run.runId}`} className="mono">
                    {run.runId}
                  </Link>
                </td>
                <td>
                  <span className={`badge badge--${badgeForState(run.state)}`}>
                    {run.state}
                  </span>
                </td>
                <td><RegionBadgeList run={run} /></td>
                <td>{run.fleetMembers?.length ?? 0}</td>
                <td>{formatDate(run.startedAt ?? run.createdAt)}</td>
                <td>{run.initiatedBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {deleteOpen && selectedRuns.length > 0 && (
        <DeleteRunsConfirmDialog
          selected={selectedRuns}
          onDeleted={onDeleted}
          onClose={() => setDeleteOpen(false)}
        />
      )}

      {toast && (
        <div
          className="formError"
          role="status"
          style={{
            position: "fixed", bottom: "1rem", right: "1rem",
            background: "rgba(16, 185, 129, 0.10)", color: "#047857",
            border: "1px solid rgba(16, 185, 129, 0.30)", maxWidth: "320px",
          }}
        >
          {toast}
        </div>
      )}
    </section>
  );
}

function parseCompareIds(raw: string): string[] {
  return raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function badgeForState(state: Run["state"]): "ok" | "warn" | "err" | "info" {
  switch (state) {
    case "RUNNING":
    case "STARTING":
    case "PREPARING":
    case "DRAINING":
      return "info";
    case "COMPLETED":
      return "ok";
    case "FAILED":
    case "ABORTED":
      return "err";
    default:
      return "warn";
  }
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}
