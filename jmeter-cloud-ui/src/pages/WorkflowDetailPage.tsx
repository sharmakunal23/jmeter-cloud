import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";

import {
  clustersOf, workflowsApi,
  type RegionDemand, type Workflow, type WorkflowExecution, type WorkflowValidation,
} from "../api/workflows";
import { formatRelative } from "../lib/time";
import { useClientPagination } from "../hooks/useClientPagination";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { InfoTip } from "../components/InfoTip";
import { Paginator } from "../components/Paginator";
import { TabPanel, TabStrip, useTabInUrl, type TabDefinition } from "../components/TabStrip";
import { WorkflowCanvas } from "../components/workflow/WorkflowCanvas";
import { CapacityPanel } from "../components/workflow/CapacityPanel";
import { ExecutionStateChip } from "../components/workflow/ExecutionStateChip";

type Tab = "flow" | "runs";
const HISTORY_LIMIT = 200;
const RUNS_PER_PAGE = 10;
/** While something is running the header's state changes without the operator acting. */
const POLL_MS = 10_000;

/**
 * One workflow: the diagram it will run, and the runs it has had.
 *
 * <p>The flow is its own tab and takes the full height it can, because on this
 * page the diagram is the thing the operator came to see; the run history sits
 * beside it rather than pushing it up the page.
 */
export function WorkflowDetailPage() {
  const { workflowId = "" } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [history, setHistory] = useState<WorkflowExecution[]>([]);
  const [validation, setValidation] = useState<WorkflowValidation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refusedClusters, setRefusedClusters] = useState<RegionDemand[]>([]);
  const [launching, setLaunching] = useState(false);
  /** Which side of the archive line the Recent runs tab is showing. */
  const [showArchived, setShowArchived] = useState(false);
  const [archivedCount, setArchivedCount] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [confirmPurge, setConfirmPurge] = useState(false);
  const [confirmDeleteWorkflow, setConfirmDeleteWorkflow] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const wf = await workflowsApi.get(workflowId, signal);
      setWorkflow(wf);
      const [runs, archived] = await Promise.all([
        workflowsApi.history(workflowId, HISTORY_LIMIT, showArchived, signal),
        workflowsApi.archivedCount(workflowId, signal).catch(() => ({ archived: 0 })),
      ]);
      setHistory(runs);
      setArchivedCount(archived.archived);
      setError(null);
    } catch (e) {
      if ((e as Error)?.name === "AbortError") return;
      setError((e as Error).message);
    }
  }, [workflowId, showArchived]);

  useEffect(() => {
    const ac = new AbortController();
    void load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Validation is keyed on the saved revision, not on the poll: the graph
  // cannot change while a run is in progress (editing is refused), so
  // re-validating every tick would re-read the whole application registry and
  // re-run the capacity analysis to reach the same answer.
  const revision = workflow?.revision;
  const groupId = workflow?.groupId;
  const graph = workflow?.graph;
  useEffect(() => {
    if (!groupId || !graph) return;
    const ac = new AbortController();
    workflowsApi.validate(groupId, graph, ac.signal)
      .then(setValidation)
      .catch(() => { /* a failed check just leaves the panel unfilled */ });
    return () => ac.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflowId, revision]);

  // One execution runs at a time, so the newest one still running is THE one.
  const running = useMemo(
    () => history.find((e) => e.state === "RUNNING")
      ?? (workflow?.lastExecution?.state === "RUNNING" ? workflow.lastExecution : null),
    [history, workflow],
  );
  useVisiblePolling(() => { void load(); }, running ? POLL_MS : null, { name: "workflowDetail" });

  async function launch() {
    setLaunching(true);
    setRefusedClusters([]);
    setError(null);
    try {
      const execution = await workflowsApi.launch(workflowId);
      navigate(`/workflows/executions/${execution.executionId}`);
    } catch (e) {
      const clusters = clustersOf(e);
      if (clusters.length > 0) setRefusedClusters(clusters);
      setError((e as Error).message);
    } finally {
      setLaunching(false);
    }
  }

  const { page, pageItems, setPage, pageSize, total } =
    useClientPagination(history, `${workflowId}:${showArchived}`, RUNS_PER_PAGE);

  // Selection is per view: switching sides clears it, so an Archive click can
  // never act on rows the operator is no longer looking at.
  function switchView(archived: boolean) {
    setShowArchived(archived);
    setSelected(new Set());
    setNotice(null);
  }
  function toggleOne(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }
  const selectableOnPage = pageItems.filter((e) => showArchived || e.state !== "RUNNING");
  const allOnPageSelected = selectableOnPage.length > 0
    && selectableOnPage.every((e) => selected.has(e.executionId));

  async function act(run: () => Promise<string>) {
    setBusy(true);
    try {
      setNotice(await run());
      setSelected(new Set());
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      setConfirmPurge(false);
    }
  }

  async function deleteWorkflow(cancelRunning: boolean) {
    setBusy(true);
    try {
      const result = await workflowsApi.remove(workflowId, cancelRunning);
      // The list says what the delete did; landing there with no word on it
      // leaves the operator guessing whether the run was stopped.
      navigate(`/workflows/groups/${encodeURIComponent(workflow!.groupId)}`, {
        state: { deletedWorkflow: { name: workflow!.name, ...result } },
      });
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
      setConfirmDeleteWorkflow(false);
    }
  }

  if (error && !workflow) {
    return <section className="workflowsPage"><div className="banner banner--error">{error}</div></section>;
  }
  if (!workflow) {
    return <section className="workflowsPage"><p className="ink-soft">Loading…</p></section>;
  }

  const tabs: ReadonlyArray<TabDefinition<Tab>> = [
    { id: "flow", label: "Flow" },
    // No count: `history` is whichever side of the archive line is showing, so
    // a number here would relabel the tab when the operator opens the archive.
    { id: "runs", label: "Recent runs" },
  ];
  const [tab, setTab] = useTabInUrl(tabs, searchParams, setSearchParams);
  // A running execution owns the workflow: re-running it would be refused
  // server-side anyway, and editing it would leave the canvas showing
  // something the run in progress is not doing.
  const lockedReason = running
    ? "A run is in progress — wait for it to finish, or cancel it."
    : !workflow.enabled ? "This workflow is disabled." : null;

  return (
    <section className="workflowsPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <div className="formField__labelRow">
            <h1>{workflow.name}</h1>
            <InfoTip label="About this workflow">
              Running it starts one execution that walks these tasks in order; the
              workflow cannot be edited while a run is in progress.
            </InfoTip>
          </div>
          <small className="ink-soft">
            <Link to="/workflows">Workflows</Link>
            {" · "}
            <Link to={`/workflows/groups/${encodeURIComponent(workflow.groupId)}`}>{workflow.groupId}</Link>
            {workflow.description ? ` · ${workflow.description}` : ""}
          </small>
        </div>
        <div className="pageHeader__actions">
          {running && (
            <Link className="chip chip--info" to={`/workflows/executions/${running.executionId}`}>
              Running now →
            </Link>
          )}
          {running ? (
            <span className="btn btn--ghost isDisabled" aria-disabled="true" title={lockedReason ?? undefined}>
              Edit
            </span>
          ) : (
            <Link
              className="btn btn--ghost"
              to={`/workflows/${workflow.workflowId}/edit`}
              // Where the builder's "Exit edit" comes back to: this page, on
              // the tab it was left on.
              state={{ from: `${location.pathname}${location.search}` }}
            >Edit</Link>
          )}
          <button
            type="button"
            className="btn btn--ghost"
            disabled={busy}
            onClick={() => setConfirmDeleteWorkflow(true)}
          >Delete</button>
          <button
            type="button"
            className="btn btn--primary"
            disabled={launching || Boolean(lockedReason)}
            title={lockedReason ?? undefined}
            onClick={() => void launch()}
          >
            {launching ? "Starting…" : "Run workflow"}
          </button>
        </div>
      </header>

      {lockedReason && (
        <div className="banner banner--info">
          {lockedReason}
          {running && (
            <> <Link to={`/workflows/executions/${running.executionId}`}>Open the run in progress</Link>.</>
          )}
        </div>
      )}

      {error && (
        <div className="banner banner--error" role="alert">
          {error}
          {refusedClusters.length > 0 && (
            <ul className="bannerList">
              {refusedClusters.map((c) => (
                <li key={c.region}>
                  <b className="mono">{c.region}</b>: needs {c.peakWorkers} at once
                  ({c.tasks.join(" + ")}), reserved {c.reserved}.{" "}
                  <Link to={`/capacity/groups/${encodeURIComponent(workflow.groupId)}`}>Adjust the reservation</Link>
                  {" or reduce the workers on those tasks."}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {validation && !validation.valid && (
        <div className="banner banner--warn">
          This workflow will not start until these are fixed:
          <ul className="bannerList">{validation.errors.map((m) => <li key={m}>{m}</li>)}</ul>
        </div>
      )}

      <TabStrip tabs={tabs} active={tab} onChange={setTab} idPrefix="workflow" ariaLabel="Workflow sections" />

      <TabPanel id="flow" idPrefix="workflow" active={tab === "flow"}>
        <div className="workflowDetail__layout">
          <div className="workflowDetail__canvas">
            <WorkflowCanvas graph={workflow.graph} fillViewport />
          </div>
          <aside className="workflowDetail__side">
            <CapacityPanel validation={validation} groupId={workflow.groupId} />
          </aside>
        </div>
      </TabPanel>

      <TabPanel id="runs" idPrefix="workflow" active={tab === "runs"}>
        <div className="runsToolbar">
          <div className="runsToolbar__views">
            <button
              type="button"
              className={`btn btn--ghost btn--sm${showArchived ? "" : " isActive"}`}
              aria-pressed={!showArchived}
              onClick={() => switchView(false)}
            >History</button>
            <button
              type="button"
              className={`btn btn--ghost btn--sm${showArchived ? " isActive" : ""}`}
              aria-pressed={showArchived}
              onClick={() => switchView(true)}
            >Archived{archivedCount > 0 ? ` (${archivedCount})` : ""}</button>
            <InfoTip label="About archiving runs">
              Archiving takes a finished run out of the history without losing it;
              deleting is only offered on the archived list.
            </InfoTip>
          </div>
          <div className="runsToolbar__actions">
            {selected.size > 0 && (
              <span className="ink-soft" style={{ fontSize: "0.85rem" }}>{selected.size} selected</span>
            )}
            {showArchived ? (
              <>
                <button
                  type="button" className="btn btn--ghost btn--sm"
                  disabled={busy || selected.size === 0}
                  onClick={() => void act(async () => {
                    const r = await workflowsApi.restoreRuns(workflowId, [...selected]);
                    return `${r.restored} run(s) put back on the history.`;
                  })}
                >Restore</button>
                <button
                  type="button" className="btn btn--ghost btn--sm"
                  disabled={busy || selected.size === 0}
                  onClick={() => setConfirmPurge(true)}
                >Delete permanently</button>
              </>
            ) : (
              <button
                type="button" className="btn btn--ghost btn--sm"
                disabled={busy || selected.size === 0}
                title="Archiving takes finished runs off this list; a run still going is skipped"
                onClick={() => void act(async () => {
                  const r = await workflowsApi.archiveRuns(workflowId, [...selected]);
                  return `${r.archived} run(s) archived.`;
                })}
              >Archive</button>
            )}
          </div>
        </div>

        {notice && <div className="banner banner--info">{notice}</div>}

        {history.length === 0 ? (
          <div className="emptyState emptyState--compact">
            <p className="ink-soft">{showArchived ? "Nothing archived." : "Not run yet."}</p>
          </div>
        ) : (
          <>
            <table className="runsTable">
              <thead>
                <tr>
                  <th scope="col" className="runsTable__check">
                    <input
                      type="checkbox"
                      aria-label={allOnPageSelected ? "Clear selection" : "Select every run on this page"}
                      checked={allOnPageSelected}
                      disabled={selectableOnPage.length === 0}
                      onChange={() => setSelected(allOnPageSelected
                        ? new Set()
                        : new Set(selectableOnPage.map((e) => e.executionId)))}
                    />
                  </th>
                  <th scope="col">Started</th>
                  <th scope="col">State</th>
                  <th scope="col">Took</th>
                  <th scope="col">By</th>
                  <th scope="col">Detail</th>
                </tr>
              </thead>
              <tbody>
                {pageItems.map((e) => (
                  <tr key={e.executionId}>
                    <td className="runsTable__check">
                      <input
                        type="checkbox"
                        aria-label={`Select the run started ${formatRelative(e.startedAt)}`}
                        checked={selected.has(e.executionId)}
                        // A run still going cannot be archived, so it cannot be
                        // selected for it either.
                        disabled={!showArchived && e.state === "RUNNING"}
                        onChange={() => toggleOne(e.executionId)}
                      />
                    </td>
                    <td>
                      <Link to={`/workflows/executions/${e.executionId}`} className="runsTable__link">
                        {formatRelative(e.startedAt)}
                      </Link>
                    </td>
                    <td><ExecutionStateChip state={e.state} /></td>
                    <td className="ink-soft mono" style={{ fontSize: "0.85rem" }}>{elapsed(e)}</td>
                    <td className="ink-soft">{e.triggeredBy}</td>
                    <td className="ink-soft" style={{ fontSize: "0.85rem" }}>{e.stateReason ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {total > pageSize && (
              <Paginator page={page} pageSize={pageSize} total={total} label="runs" onChange={setPage} />
            )}
          </>
        )}
      </TabPanel>
      {confirmPurge && (
        <ConfirmDialog
          title={`Delete ${selected.size} archived run${selected.size === 1 ? "" : "s"}?`}
          confirmLabel="Delete permanently"
          danger
          busy={busy}
          onCancel={() => setConfirmPurge(false)}
          onConfirm={() => void act(async () => {
            const r = await workflowsApi.deleteRuns(workflowId, [...selected]);
            return `${r.deleted} archived run(s) deleted.`;
          })}
        >
          <p>
            This cannot be undone. The load tests' own runs are not affected —
            they stay listed under their application with their metrics.
          </p>
        </ConfirmDialog>
      )}

      {confirmDeleteWorkflow && (
        <ConfirmDialog
          title={`Delete "${workflow.name}"?`}
          confirmLabel={running ? "Cancel the run and delete" : "Delete workflow"}
          danger
          busy={busy}
          onCancel={() => setConfirmDeleteWorkflow(false)}
          onConfirm={() => void deleteWorkflow(Boolean(running))}
        >
          <p>
            The workflow is deleted, along with every run record it has —
            the history and the archive both.
          </p>
          {running && (
            <p className="ink-warn">
              A run is in progress. Deleting will cancel it first — any load test
              that already started keeps running, and you can stop it from its
              own run page.
            </p>
          )}
          <p className="ink-soft">
            The load tests' own runs are not deleted; they stay under their
            application with their metrics.
          </p>
        </ConfirmDialog>
      )}
    </section>
  );
}

function elapsed(e: WorkflowExecution): string {
  if (!e.startedAt) return "—";
  const end = e.completedAt ? new Date(e.completedAt) : new Date();
  const secs = Math.max(0, Math.round((end.getTime() - new Date(e.startedAt).getTime()) / 1000));
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  return mins < 60 ? `${mins}m ${secs % 60}s` : `${Math.floor(mins / 60)}h ${mins % 60}m`;
}
