import { useCallback, useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";

import {
  workflowsApi, type Workflow, type WorkflowGroupSummary,
} from "../api/workflows";
import { formatRelative } from "../lib/time";
import { AppListToolbar } from "../components/AppListToolbar";
import { DataList } from "../components/DataList";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { InfoTip } from "../components/InfoTip";
import { ExecutionStateChip } from "../components/workflow/ExecutionStateChip";

/**
 * One application group's workflows. Each row shows how the last run went and
 * what the graph holds, so an operator can tell at a glance which workflow is
 * the one they came for.
 */
/** What the detail page hands over after deleting a workflow. */
interface DeletedWorkflow {
  deletedWorkflow?: { name: string; cancelledExecutions: number; deletedExecutions: number };
}

export function WorkflowListPage() {
  const { groupId = "" } = useParams();
  // Handed over by the detail page's delete, so the outcome is reported where
  // the operator lands rather than on a page that no longer exists.
  const location = useLocation();
  const deleted = (location.state as DeletedWorkflow | null)?.deletedWorkflow ?? null;
  /** Where the builder's "Exit" returns to — this list, filtered as it is. */
  const here = `${location.pathname}${location.search}`;
  const [group, setGroup] = useState<WorkflowGroupSummary | null>(null);
  const [rows, setRows] = useState<Workflow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [pendingDelete, setPendingDelete] = useState<Workflow | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const [groups, list] = await Promise.all([
        workflowsApi.groups(signal),
        workflowsApi.list(groupId, signal),
      ]);
      setGroup(groups.find((g) => g.groupId === groupId) ?? null);
      setRows(list);
      setError(null);
    } catch (e) {
      if ((e as Error)?.name === "AbortError") return;
      setError((e as Error).message);
    }
  }, [groupId]);

  useEffect(() => {
    const ac = new AbortController();
    void load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function confirmDelete() {
    if (!pendingDelete) return;
    setBusy(true);
    try {
      await workflowsApi.remove(pendingDelete.workflowId);
      setPendingDelete(null);
      await load();
    } catch (e) {
      setError((e as Error).message);
      setPendingDelete(null);
    } finally {
      setBusy(false);
    }
  }

  const loading = rows === null && error === null;
  const all = rows ?? [];
  const term = search.trim().toLowerCase();
  const filtered = term ? all.filter((w) => w.name.toLowerCase().includes(term)) : all;

  return (
    <section className="workflowsPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <div className="formField__labelRow">
            <h1>{group?.name ?? groupId}</h1>
            <InfoTip label="About this group's workflows">
              Every workflow here runs against this group's applications and draws
              its workers from the group's reserved capacity.
            </InfoTip>
          </div>
          <small className="ink-soft">
            <Link to="/workflows">Workflows</Link>
            {group?.teamName ? <> · owned by {group.teamName}</> : null}
          </small>
        </div>
        <div className="pageHeader__actions">
          <Link
            className="btn btn--primary"
            to={`/workflows/groups/${encodeURIComponent(groupId)}/new`}
            state={{ from: here }}
          >
            + New workflow
          </Link>
        </div>
      </header>

      {deleted && (
        <div className="banner banner--info">
          Deleted "{deleted.name}" and {deleted.deletedExecutions} run record
          {deleted.deletedExecutions === 1 ? "" : "s"}
          {deleted.cancelledExecutions > 0
            ? `, cancelling ${deleted.cancelledExecutions} run in progress.`
            : "."}
          {" "}The load tests' own runs are unaffected.
        </div>
      )}

      {error && <div className="banner banner--error" role="alert">{error}</div>}


      <DataList<Workflow>
        toolbar={<AppListToolbar
          noun="workflow"
          search={search}
          onSearchChange={setSearch}
          count={filtered.length}
          total={all.length}
          loading={loading}
        />}
        label="Workflows"
        loading={loading}
        rows={filtered}
        rowKey={(w) => w.workflowId}
        itemNoun="workflows"
        resetKey={search}
        empty={all.length === 0 ? (
          <>
            <strong>No workflows in this group yet.</strong>
            <div>A workflow chains health checks, load tests, waits, approvals and
                 notifications — draw one and it runs itself.</div>
          </>
        ) : <>No workflows match &quot;{search}&quot;.</>}
        rowProps={(w) => (w.enabled ? {} : { className: "isMuted" })}
        columns={[
          { key: "workflow", header: "Workflow", cell: (w) => (
            <>
              <Link to={`/workflows/${w.workflowId}`} className="runsTable__link">{w.name}</Link>
              {!w.enabled && <span className="chip chip--muted" style={{ marginLeft: 8 }}>disabled</span>}
              {w.description && (
                <div className="ink-soft" style={{ fontSize: "0.82rem" }}>{w.description}</div>
              )}
            </>
          ) },
          { key: "tasks", header: "Tasks", cell: (w) => <TaskMix workflow={w} /> },
          { key: "lastRun", header: "Last run", cell: (w) => (
            w.lastExecution ? (
              <Link to={`/workflows/executions/${w.lastExecution.executionId}`} className="runsTable__link">
                <ExecutionStateChip state={w.lastExecution.state} />
                <span className="ink-soft" style={{ marginLeft: 6, fontSize: "0.82rem" }}>
                  {formatRelative(w.lastExecution.startedAt)}
                </span>
              </Link>
            ) : <span className="ink-soft">never run</span>
          ) },
          { key: "updated", header: "Updated", cell: (w) => (
            <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
              {formatRelative(w.updatedAt)}{w.updatedBy ? <> by {w.updatedBy}</> : null}
            </span>
          ) },
          { key: "actions", header: <span className="visuallyHidden">Actions</span>,
            className: "runsTable__actions", cell: (w) => (
              <>
                <Link className="btn btn--ghost btn--sm"
                      to={`/workflows/${w.workflowId}/edit`} state={{ from: here }}>Edit</Link>
                <button type="button" className="btn btn--ghost btn--sm text--error"
                        onClick={() => setPendingDelete(w)}>
                  Delete
                </button>
              </>
            ) },
        ]}
      />

      {pendingDelete && (
        <ConfirmDialog
          title={`Delete "${pendingDelete.name}"?`}
          confirmLabel="Delete"
          danger
          busy={busy}
          onCancel={() => setPendingDelete(null)}
          onConfirm={() => void confirmDelete()}
        >
          <p>
            The workflow definition goes. Executions that already ran keep their own
            copy of the graph, so their history stays readable.
          </p>
        </ConfirmDialog>
      )}
    </section>
  );
}

/** A compact count per task type — what this workflow is made of, without opening it. */
function TaskMix({ workflow }: { workflow: Workflow }) {
  const counts = new Map<string, number>();
  for (const n of workflow.graph.nodes) counts.set(n.type, (counts.get(n.type) ?? 0) + 1);
  if (counts.size === 0) return <span className="ink-soft">empty</span>;
  const label: Record<string, string> = {
    HEALTH_CHECK: "health", LOAD_TEST: "load test", EMAIL: "email",
    DELAY: "wait", APPROVAL: "approval",
  };
  return (
    <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
      {[...counts.entries()]
        .map(([type, n]) => `${n} ${label[type] ?? type}${n === 1 ? "" : "s"}`)
        .join(" · ")}
    </span>
  );
}
