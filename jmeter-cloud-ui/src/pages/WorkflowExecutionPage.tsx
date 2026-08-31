import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";

import {
  workflowsApi, type WorkflowExecution, type WorkflowTask,
} from "../api/workflows";
import { formatRelative } from "../lib/time";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { WorkflowCanvas } from "../components/workflow/WorkflowCanvas";
import { WorkflowMetricsPanel } from "../components/workflow/WorkflowMetricsPanel";
import { ExecutionStateChip } from "../components/workflow/ExecutionStateChip";
import { ConfirmDialog } from "../components/ConfirmDialog";

const POLL_MS = 4_000;

/**
 * A workflow execution as it happens: the graph with every task's state on it,
 * the approvals waiting on someone, and the load tests' metrics on the same
 * screen, split by application.
 *
 * <p>Polls while the execution is live and the tab is visible; a finished one
 * is read once and left alone.
 */
export function WorkflowExecutionPage() {
  const { executionId = "" } = useParams();
  const [execution, setExecution] = useState<WorkflowExecution | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      setExecution(await workflowsApi.execution(executionId, signal));
      setError(null);
    } catch (e) {
      if ((e as Error)?.name === "AbortError") return;
      setError((e as Error).message);
    }
  }, [executionId]);

  useEffect(() => {
    const ac = new AbortController();
    void load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const live = execution?.state === "RUNNING";
  useVisiblePolling(() => { void load(); }, live ? POLL_MS : null, { name: "workflowExecution" });

  async function act(fn: () => Promise<WorkflowExecution>) {
    setBusy(true);
    try {
      setExecution(await fn());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
      setCancelOpen(false);
    }
  }

  if (error && !execution) {
    return <section className="workflowsPage"><div className="banner banner--error">{error}</div></section>;
  }
  if (!execution) {
    return <section className="workflowsPage"><p className="ink-soft">Loading…</p></section>;
  }

  const states: Record<string, string> = {};
  for (const t of execution.tasks) states[t.nodeId] = t.state;
  const waiting = execution.tasks.filter((t) => t.state === "AWAITING_APPROVAL");

  return (
    <section className="workflowsPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>{execution.workflowName}</h1>
          <small className="ink-soft">
            <Link to="/workflows">Workflows</Link>
            {" · "}
            <Link to={`/workflows/${execution.workflowId}`}>the workflow</Link>
            {" · started "}{formatRelative(execution.startedAt)} by {execution.triggeredBy}
          </small>
        </div>
        <div className="pageHeader__actions">
          <ExecutionStateChip state={execution.state} />
          {live && (
            <button type="button" className="btn btn--ghost" disabled={busy} onClick={() => setCancelOpen(true)}>
              Cancel
            </button>
          )}
        </div>
      </header>

      {error && <div className="banner banner--error" role="alert">{error}</div>}
      {execution.stateReason && (
        <div className={`banner ${execution.state === "FAILED" ? "banner--error" : "banner--info"}`}>
          {execution.stateReason}
        </div>
      )}

      {waiting.map((t) => (
        <div className="banner banner--warn" key={t.taskId}>
          <b>{t.name}</b> is waiting for you.
          {typeof t.result?.instructions === "string" && <> {t.result.instructions}</>}
          <span className="banner__actions">
            <button
              type="button" className="btn btn--primary btn--sm" disabled={busy}
              onClick={() => void act(() => workflowsApi.approve(execution.executionId, t.taskId))}
            >Approve</button>
            <button
              type="button" className="btn btn--ghost btn--sm" disabled={busy}
              onClick={() => void act(() => workflowsApi.reject(execution.executionId, t.taskId))}
            >Reject</button>
          </span>
        </div>
      ))}

      <WorkflowCanvas graph={execution.graph} states={states} />

      <h2 className="sectionHeading">Metrics</h2>
      <WorkflowMetricsPanel tasks={execution.tasks} live={live} />

      <h2 className="sectionHeading">Tasks</h2>
      <table className="runsTable">
        <thead>
          <tr>
            <th scope="col">Task</th>
            <th scope="col">State</th>
            <th scope="col">Started</th>
            <th scope="col">Took</th>
            <th scope="col">Detail</th>
          </tr>
        </thead>
        <tbody>
          {execution.tasks.map((t) => (
            <tr key={t.taskId}>
              <td>
                {t.name}
                <div className="ink-soft mono" style={{ fontSize: "0.78rem" }}>{t.type}</div>
              </td>
              <td><ExecutionStateChip state={t.state} /></td>
              <td className="ink-soft" style={{ fontSize: "0.85rem" }}>
                {t.startedAt ? formatRelative(t.startedAt) : "—"}
              </td>
              <td className="ink-soft mono" style={{ fontSize: "0.85rem" }}>{duration(t)}</td>
              <td className="ink-soft" style={{ fontSize: "0.85rem" }}><TaskDetail task={t} /></td>
            </tr>
          ))}
        </tbody>
      </table>

      {cancelOpen && (
        <ConfirmDialog
          title="Cancel this workflow?"
          confirmLabel="Cancel workflow"
          danger
          busy={busy}
          onCancel={() => setCancelOpen(false)}
          onConfirm={() => void act(() => workflowsApi.cancel(execution.executionId))}
        >
          <p>
            Every task that has not finished is cancelled. A load test that already
            started keeps running — stop it from its own run page if you need to.
          </p>
        </ConfirmDialog>
      )}
    </section>
  );
}

function duration(task: WorkflowTask): string {
  if (!task.startedAt) return "—";
  const end = task.completedAt ? new Date(task.completedAt) : new Date();
  const secs = Math.max(0, Math.round((end.getTime() - new Date(task.startedAt).getTime()) / 1000));
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  return mins < 60 ? `${mins}m ${secs % 60}s` : `${Math.floor(mins / 60)}h ${mins % 60}m`;
}

/** The one thing worth reading per task type — the error when there is one. */
function TaskDetail({ task }: { task: WorkflowTask }) {
  if (task.errorReason) return <span className="ink-warn">{task.errorReason}</span>;
  const r = task.result ?? {};
  if (task.type === "HEALTH_CHECK" && typeof r.healthy === "number") {
    return <>{r.healthy} of {String(r.total)} endpoints healthy</>;
  }
  if (task.type === "LOAD_TEST" && typeof r.runState === "string") {
    const workers = typeof r.workers === "number" ? r.workers : null;
    return <>run {r.runState}{workers === null ? "" : `, ${workers} worker${workers === 1 ? "" : "s"}`}</>;
  }
  if (task.type === "EMAIL" && Array.isArray(r.to)) {
    const cc = Array.isArray(r.cc) ? r.cc.length : 0;
    return <>sent to {(r.to as string[]).length}{cc > 0 ? ` (+${cc} cc)` : ""}</>;
  }
  if (task.type === "APPROVAL" && typeof r.decision === "string") {
    return <>{String(r.decision).toLowerCase()} by {String(r.decidedBy ?? "someone")}</>;
  }
  if (task.type === "DELAY" && typeof r.waitSeconds === "number") {
    return <>waited {r.waitSeconds}s</>;
  }
  return <>—</>;
}
