import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";

import {
  clustersOf, workflowsApi,
  type RegionDemand, type Workflow, type WorkflowExecution, type WorkflowValidation,
} from "../api/workflows";
import { formatRelative } from "../lib/time";
import { useClientPagination } from "../hooks/useClientPagination";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { Paginator } from "../components/Paginator";
import { TabPanel, TabStrip, useTabInUrl, type TabDefinition } from "../components/TabStrip";
import { WorkflowCanvas } from "../components/workflow/WorkflowCanvas";
import { CapacityPanel } from "../components/workflow/CapacityPanel";
import { ExecutionStateChip } from "../components/workflow/ExecutionStateChip";

type Tab = "flow" | "runs";
const HISTORY_LIMIT = 50;
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
  const [searchParams, setSearchParams] = useSearchParams();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [history, setHistory] = useState<WorkflowExecution[]>([]);
  const [validation, setValidation] = useState<WorkflowValidation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refusedClusters, setRefusedClusters] = useState<RegionDemand[]>([]);
  const [launching, setLaunching] = useState(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const wf = await workflowsApi.get(workflowId, signal);
      setWorkflow(wf);
      const [runs, check] = await Promise.all([
        workflowsApi.history(workflowId, HISTORY_LIMIT, signal),
        workflowsApi.validate(wf.groupId, wf.graph, signal).catch(() => null),
      ]);
      setHistory(runs);
      setValidation(check);
      setError(null);
    } catch (e) {
      if ((e as Error)?.name === "AbortError") return;
      setError((e as Error).message);
    }
  }, [workflowId]);

  useEffect(() => {
    const ac = new AbortController();
    void load(ac.signal);
    return () => ac.abort();
  }, [load]);

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
    useClientPagination(history, workflowId, RUNS_PER_PAGE);

  if (error && !workflow) {
    return <section className="workflowsPage"><div className="banner banner--error">{error}</div></section>;
  }
  if (!workflow) {
    return <section className="workflowsPage"><p className="ink-soft">Loading…</p></section>;
  }

  const tabs: ReadonlyArray<TabDefinition<Tab>> = [
    { id: "flow", label: "Flow" },
    { id: "runs", label: "Recent runs", badge: history.length > 0 ? history.length : undefined },
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
          <h1>{workflow.name}</h1>
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
            <Link className="btn btn--ghost" to={`/workflows/${workflow.workflowId}/edit`}>Edit</Link>
          )}
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
        {history.length === 0 ? (
          <div className="emptyState emptyState--compact">
            <p className="ink-soft">Not run yet.</p>
          </div>
        ) : (
          <>
            <table className="runsTable">
              <thead>
                <tr>
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
