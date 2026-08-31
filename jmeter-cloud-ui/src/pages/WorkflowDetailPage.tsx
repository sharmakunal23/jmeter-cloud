import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import {
  clustersOf, workflowsApi,
  type RegionDemand, type Workflow, type WorkflowExecution, type WorkflowValidation,
} from "../api/workflows";
import { formatRelative } from "../lib/time";
import { WorkflowCanvas } from "../components/workflow/WorkflowCanvas";
import { CapacityPanel } from "../components/workflow/CapacityPanel";
import { ExecutionStateChip } from "../components/workflow/ExecutionStateChip";

/**
 * One workflow: what it will do, what it will cost in workers, and how the last
 * runs went. The canvas is read-only here — editing is a mode of its own, so a
 * glance at a workflow can never accidentally rewire it.
 */
export function WorkflowDetailPage() {
  const { workflowId = "" } = useParams();
  const navigate = useNavigate();
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
        workflowsApi.history(workflowId, 10, signal),
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

  if (error && !workflow) {
    return <section className="workflowsPage"><div className="banner banner--error">{error}</div></section>;
  }
  if (!workflow) {
    return <section className="workflowsPage"><p className="ink-soft">Loading…</p></section>;
  }

  const blocked = (validation && !validation.valid) || refusedClusters.length > 0;

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
          <Link className="btn btn--ghost" to={`/workflows/${workflow.workflowId}/edit`}>Edit</Link>
          <button
            type="button"
            className="btn btn--primary"
            disabled={launching || !workflow.enabled}
            title={workflow.enabled ? undefined : "This workflow is disabled"}
            onClick={() => void launch()}
          >
            {launching ? "Starting…" : "Run workflow"}
          </button>
        </div>
      </header>

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

      <div className="workflowDetail__layout">
        <div className="workflowDetail__canvas">
          <WorkflowCanvas graph={workflow.graph} height={420} />
        </div>
        <aside className="workflowDetail__side">
          <CapacityPanel validation={validation} groupId={workflow.groupId} />
        </aside>
      </div>

      <h2 className="sectionHeading">Recent runs</h2>
      {history.length === 0 ? (
        <div className="emptyState emptyState--compact">
          <p className="ink-soft">
            {blocked ? "Not run yet — fix the problems above first." : "Not run yet."}
          </p>
        </div>
      ) : (
        <table className="runsTable">
          <thead>
            <tr>
              <th scope="col">Started</th>
              <th scope="col">State</th>
              <th scope="col">By</th>
              <th scope="col">Detail</th>
            </tr>
          </thead>
          <tbody>
            {history.map((e) => (
              <tr key={e.executionId}>
                <td>
                  <Link to={`/workflows/executions/${e.executionId}`} className="runsTable__link">
                    {formatRelative(e.startedAt)}
                  </Link>
                </td>
                <td><ExecutionStateChip state={e.state} /></td>
                <td className="ink-soft">{e.triggeredBy}</td>
                <td className="ink-soft" style={{ fontSize: "0.85rem" }}>{e.stateReason ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
