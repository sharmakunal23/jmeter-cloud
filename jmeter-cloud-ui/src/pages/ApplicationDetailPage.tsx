import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";

import { runsApi, type Run, type RunListing } from "../api/runs";
import { applicationsApi, type Application } from "../api/applications";
import { usePlatformCapabilities } from "../hooks/usePlatformCapabilities";
import { DataCentersSectionForApp } from "../components/DataCentersSection";
import { Paginator } from "../components/Paginator";
import { RegionBadgeList } from "../components/RegionBadge";
import { CreateApplicationDialog } from "../components/CreateApplicationDialog";
import { DeleteRunsConfirmDialog } from "../components/DeleteRunsConfirmDialog";
import { BulkPurgeRunsDialog } from "../components/BulkPurgeRunsDialog";
import { RunsComparePage } from "./RunsComparePage";

/**
 * 2026-05-16 — Compare 2 runs revival. The feature used to live on the
 * old flat /runs listing page; after the application-centric IA
 * cutover the page itself was removed, taking the compare entry
 * point with it. The behaviour is restored here, scoped to a single
 * app: checkbox per row → "Compare 2 runs →" button → URL becomes
 * `/applications/{app}?compare=A,B`, which renders the existing
 * {@link RunsComparePage}. The compare URL stays inside the
 * application's namespace so the back-link from compare lands on
 * the operator's previous page.
 */
const MAX_COMPARE_SELECTION = 2;

/**
 * Track UI-D3 — per-application detail. Header chips with rollups
 * (total runs, active runs); body is the runs list paginated 25 per
 * page (page driven by {@code ?page=N}). Wires up the new
 * {@code /api/v1/runs?application=…&offset=…&limit=25} backend
 * (UI-D3 added the {@code application} column + {@code X-Total-Count}
 * response header).
 */

const PAGE_SIZE = 25;

type State =
  | { status: "loading" }
  | { status: "ok"; listing: RunListing; activeCount: number }
  | { status: "error"; message: string };

/**
 * Outer shell — dispatches between the compare sub-view and the main
 * runs listing based on the {@code ?compare} URL param. Each branch
 * is its own component so hook order stays stable when the param
 * toggles (React's Rules of Hooks forbid an early return placed
 * BEFORE the rest of the hooks fire).
 */
export function ApplicationDetailPage() {
  const { appName = "" } = useParams<{ appName: string }>();
  const [searchParams] = useSearchParams();
  const compareParam = searchParams.get("compare");
  if (compareParam) {
    return <RunsComparePage runIds={parseCompareIds(compareParam)} appName={appName} />;
  }
  return <ApplicationDetailBody appName={appName} />;
}

function ApplicationDetailBody({ appName }: { appName: string }) {
  const navigate = useNavigate();
  const { isStaticFleet } = usePlatformCapabilities();
  const [searchParams, setSearchParams] = useSearchParams();
  const page = Math.max(1, Number.parseInt(searchParams.get("page") ?? "1", 10) || 1);
  const offset = (page - 1) * PAGE_SIZE;

  const [state, setState] = useState<State>({ status: "loading" });
  // App-settings dialog state. We fetch the full Application record on
  // demand (the runs listing only carries the name) so the dialog can
  // hydrate sealId / description / healthEndpoints / group correctly.
  const [editingApp, setEditingApp] = useState<Application | null>(null);
  const [editLoading, setEditLoading] = useState(false);
  // Bumped to force a re-fetch (after a delete) without changing the page.
  const [reloadNonce, setReloadNonce] = useState(0);
  // Runs queued for deletion (the confirm dialog is open while non-null);
  // either a single row's run or the current bulk selection.
  const [deleteTargets, setDeleteTargets] = useState<Run[] | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  // HARD-DELETE — the "Archived" view lists this app's HIDDEN runs (the purge
  // surface). Runs queued for permanent deletion (bulk purge dialog open while
  // non-null) — the current selection.
  const [archived, setArchived] = useState(false);
  const [purgeTargets, setPurgeTargets] = useState<Run[] | null>(null);

  async function openEdit() {
    setEditLoading(true);
    try {
      const apps = await applicationsApi.list();
      const found = apps.find((a) => a.name === appName);
      if (found) setEditingApp(found);
      else alert(`Application "${appName}" not found in the registry.`);
    } catch (err) {
      alert(err instanceof Error ? err.message : String(err));
    } finally {
      setEditLoading(false);
    }
  }

  // Re-fetch whenever the app or the page changes. activeCount is a
  // separate, smaller fetch (limit=200, no body parsed beyond length)
  // so the chip stays accurate without scanning the full run history.
  useEffect(() => {
    const ctl = new AbortController();
    setState({ status: "loading" });
    // Archived view → only hidden runs; active-count chip is irrelevant there.
    const listP = runsApi.listPage(
      { application: appName, hidden: archived, offset, limit: PAGE_SIZE }, ctl.signal);
    const activeP = archived
      ? Promise.resolve(null)
      : runsApi.listPage({ application: appName, activeOnly: true, limit: 200 }, ctl.signal);
    Promise.all([listP, activeP])
      .then(([page, active]) => {
        setState({ status: "ok", listing: page, activeCount: active ? active.runs.length : 0 });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setState({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [appName, offset, reloadNonce, archived]);

  // Switch between Active and Archived run views; reset to page 1 so the offset
  // is never out of range for the (different) result set.
  function selectArchived(next: boolean) {
    if (next === archived) return;
    setSelected(new Set());
    setPage(1);
    setArchived(next);
  }

  function setPage(nextPage: number) {
    const next = new URLSearchParams(searchParams);
    if (nextPage === 1) next.delete("page");
    else next.set("page", String(nextPage));
    setSearchParams(next);
  }

  const totalRuns = state.status === "ok" ? state.listing.total : null;
  const activeRuns = state.status === "ok" ? state.activeCount : null;

  // 2026-05-16 — compare-selection lives at this level so the Compare
  // button can sit on the chips row alongside Total/Active while the
  // checkboxes stay inside RunsTableForApp.
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // Unlimited multi-select — bulk delete acts on any number of runs.
  // Compare is a separate gate that lights up only at exactly 2.
  function toggleSelected(runId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(runId)) next.delete(runId);
      else next.add(runId);
      return next;
    });
  }

  function startCompare() {
    if (selected.size !== MAX_COMPARE_SELECTION) return;
    setSearchParams({ compare: Array.from(selected).join(",") });
  }

  const canCompare = selected.size === MAX_COMPARE_SELECTION;

  const showToast = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(null), 6000);
  };

  // Runs selected AND present on the current page (we need their Run objects
  // — with state — to partition terminal vs active in the confirm dialog).
  const selectedRuns =
    state.status === "ok" ? state.listing.runs.filter((r) => selected.has(r.runId)) : [];

  // After the dialog hides runs: drop them from the selection, refresh, toast.
  function onDeleted(deletedRunIds: string[]) {
    setDeleteTargets(null);
    setSelected((prev) => {
      const next = new Set(prev);
      for (const id of deletedRunIds) next.delete(id);
      return next;
    });
    setReloadNonce((n) => n + 1);
    showToast(`${deletedRunIds.length} run${deletedRunIds.length === 1 ? "" : "s"} archived.`);
  }

  return (
    <section className="applicationDetailPage">
      <header className="pageHeader applicationDetailPage__header">
        {/* 2026-05-16 — "← All applications" promoted to the LEFT of the
            H1 to match the back-link pattern used everywhere else
            (RunDetailPage, NewRunPage). */}
        <div className="applicationDetailPage__headerLeft">
          <Link to="/applications" className="applicationDetailPage__backLink">
            ← All applications
          </Link>
          <h1 className="mono">{appName}</h1>
        </div>
        <div className="appDetailPage__actions">
          <button
            type="button"
            className="btn btn--ghost"
            onClick={openEdit}
            disabled={editLoading}
            aria-busy={editLoading}
          >
            {editLoading ? "Loading…" : "App settings"}
          </button>
          <Link to={`/templates/${encodeURIComponent(appName)}`} className="btn btn--ghost">
            Templates →
          </Link>
          <Link to={`/applications/${encodeURIComponent(appName)}/runs/new`} className="btn btn--primary">
            + Start a new run
          </Link>
        </div>
      </header>

      <div className="segmentedToggle segmentedToggle--block" role="tablist" aria-label="run view">
        <button
          type="button"
          role="tab"
          aria-selected={!archived}
          className={`btn ${archived ? "btn--ghost" : "btn--primary"}`}
          onClick={() => selectArchived(false)}
        >
          Active runs
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={archived}
          className={`btn ${archived ? "btn--primary" : "btn--ghost"}`}
          onClick={() => selectArchived(true)}
          title="Archived runs — permanently delete them here to reclaim storage"
        >
          Archived
        </button>
      </div>

      <div className="appDetailChips" role="group" aria-label="application summary">
        <Chip label={archived ? "Archived runs" : "Total runs"} value={totalRuns} />
        {!archived && <Chip label="Active" value={activeRuns} />}
        {/* Compare + Clear sit inline with the chips so the row reads
            "Total runs · Active · Compare Results · Clear" left-to-right.
            Both use `appDetailChip` styling so they pair visually with
            Total/Active instead of looking like primary actions.
            Compare is an Active-view affordance; the Archived view offers
            bulk permanent-delete instead. */}
        {!archived && (
          <button
            type="button"
            className={`appDetailChip appDetailChip--action${canCompare ? " appDetailChip--actionPrimary" : ""}`}
            disabled={!canCompare}
            onClick={startCompare}
            title={
              canCompare
                ? "Open the side-by-side comparison view"
                : selected.size === 1
                  ? "Select one more run to compare (the comparison view shows exactly two)"
                  : "Select two runs to compare"
            }
          >
            Compare Results
          </button>
        )}
        {archived && selectedRuns.length > 0 && (
          <button
            type="button"
            className="appDetailChip appDetailChip--action appDetailChip--danger"
            onClick={() => setPurgeTargets(selectedRuns)}
            title="Permanently delete the selected runs — reclaims storage, cannot be undone"
          >
            Delete permanently ({selectedRuns.length})
          </button>
        )}
        {selected.size > 0 && (
          <button
            type="button"
            className="appDetailChip appDetailChip--action"
            onClick={() => setSelected(new Set())}
            aria-label="Clear selection"
          >
            Clear ({selected.size})
          </button>
        )}
        {!archived && selectedRuns.length > 0 && (
          <button
            type="button"
            className="appDetailChip appDetailChip--action appDetailChip--danger"
            onClick={() => setDeleteTargets(selectedRuns)}
            title="Archive the selected runs — they move to the Archived tab; results and metrics are kept"
          >
            Archive selected ({selectedRuns.length})
          </button>
        )}
      </div>

      {state.status === "loading" && <p className="ink-soft">Loading runs…</p>}
      {state.status === "error" && <p className="text--error">{state.message}</p>}

      {state.status === "ok" && state.listing.runs.length === 0 && (
        <div className="emptyState">
          {archived ? (
            <p>No archived (hidden) runs for <strong className="mono">{appName}</strong>.</p>
          ) : (
            <>
              <p>No runs for <strong className="mono">{appName}</strong> yet.</p>
              <p className="ink-soft">
                <Link to={`/applications/${encodeURIComponent(appName)}/runs/new`}>
                  Start a new run →
                </Link>
              </p>
            </>
          )}
        </div>
      )}

      {state.status === "ok" && state.listing.runs.length > 0 && (
        <RunsTableForApp
          appName={appName}
          listing={state.listing}
          page={page}
          setPage={setPage}
          selected={selected}
          onToggle={toggleSelected}
          archived={archived}
          onDeleteOne={(run) => setDeleteTargets([run])}
        />
      )}

      {editingApp && (
        <CreateApplicationDialog
          mode="edit"
          initial={editingApp}
          onCreated={() => setEditingApp(null)}
          onDeleted={() => {
            // App is now hidden — leave the (empty) detail page for the list.
            setEditingApp(null);
            navigate("/applications");
          }}
          onClose={() => setEditingApp(null)}
        />
      )}

      {/* STATIC-FLEET Phase 7 — the operator-facing worker surface on a fleet
          this platform does not provision: the application's GROUP pool,
          declared from here. Mutually exclusive with the Capacity tab, which
          is hidden in that mode. */}
      {isStaticFleet && <DataCentersSectionForApp appName={appName} />}

      {deleteTargets && deleteTargets.length > 0 && (
        <DeleteRunsConfirmDialog
          selected={deleteTargets}
          onDeleted={onDeleted}
          onClose={() => setDeleteTargets(null)}
        />
      )}

      {purgeTargets && purgeTargets.length > 0 && (
        <BulkPurgeRunsDialog
          selected={purgeTargets}
          onPurged={(purgedRunIds) => {
            setPurgeTargets(null);
            setSelected((prev) => {
              const next = new Set(prev);
              for (const id of purgedRunIds) next.delete(id);
              return next;
            });
            setReloadNonce((n) => n + 1);
            showToast(
              `${purgedRunIds.length} run${purgedRunIds.length === 1 ? "" : "s"} permanently deleted.`,
            );
          }}
          onClose={() => setPurgeTargets(null)}
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

function RunsTableForApp({
  appName, listing, page, setPage, selected, onToggle, archived, onDeleteOne,
}: {
  appName: string;
  listing: RunListing;
  page: number;
  setPage: (n: number) => void;
  selected: Set<string>;
  onToggle: (runId: string) => void;
  archived: boolean;
  onDeleteOne: (run: Run) => void;
}) {
  return (
    <>
      <table className="runsTable">
        <thead>
          <tr>
            <th aria-label="select" className="runsTable__check"></th>
            <th>Run</th>
            <th>State</th>
            <th>Regions</th>
            <th>Pods</th>
            <th>Started</th>
            <th aria-label="actions"></th>
          </tr>
        </thead>
        <tbody>
          {listing.runs.map((run) => {
            const podCount = run.fleetMembers.length;
            // Only terminal runs can be hidden (an active run is still
            // important + pins live pods — abort it first).
            const terminal = run.state === "COMPLETED" || run.state === "FAILED" || run.state === "ABORTED";
            return (
              <tr key={run.runId}>
                <td className="runsTable__check">
                  <input
                    type="checkbox"
                    aria-label={`select ${run.runId}`}
                    checked={selected.has(run.runId)}
                    onChange={() => onToggle(run.runId)}
                  />
                </td>
                <td>
                  <Link
                    to={`/applications/${encodeURIComponent(appName)}/runs/${run.runId}`}
                    className="mono"
                  >
                    {run.runId}
                  </Link>
                </td>
                <td>
                  <span className={`badge badge--${badgeVariantForState(run.state)}`}>
                    {run.state}
                  </span>
                </td>
                <td>
                  <RegionBadgeList run={run} />
                </td>
                <td className="mono">{podCount}</td>
                <td>{formatDateTime(run.startedAt ?? run.createdAt)}</td>
                <td className="runsTable__actions">
                  {/* Archived view → permanent delete is a SELECTION action
                      (check rows, then "Delete permanently (N)"); no per-row
                      button. Active view keeps the per-row archive. */}
                  {!archived && (
                    <button
                      type="button"
                      className="btn btn--ghost btn--sm btn--danger"
                      onClick={() => onDeleteOne(run)}
                      disabled={!terminal}
                      title={terminal
                        ? "Archive this run — it moves to the Archived tab"
                        : "Active runs can't be archived — abort or let it finish first"}
                      aria-label={`archive run ${run.runId}`}
                    >
                      Archive
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <Paginator
        page={page}
        pageSize={listing.limit}
        total={listing.total}
        label="runs"
        onChange={setPage}
      />
    </>
  );
}

function parseCompareIds(raw: string): string[] {
  return raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function Chip({ label, value }: { label: string; value: number | null }) {
  return (
    <span className="appDetailChip">
      <span className="appDetailChip__label">{label}</span>
      <strong className="appDetailChip__value mono">
        {value ?? "—"}
      </strong>
    </span>
  );
}

function badgeVariantForState(state: string): string {
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

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}
