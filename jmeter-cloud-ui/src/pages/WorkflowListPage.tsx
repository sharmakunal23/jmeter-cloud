import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";

import {
  workflowsApi, type Workflow, type WorkflowGroupSummary,
} from "../api/workflows";
import { formatRelative } from "../lib/time";
import { AppListToolbar } from "../components/AppListToolbar";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { ExecutionStateChip } from "../components/workflow/ExecutionStateChip";

/**
 * One application group's workflows. Each row shows how the last run went and
 * what the graph holds, so an operator can tell at a glance which workflow is
 * the one they came for.
 */
export function WorkflowListPage() {
  const { groupId = "" } = useParams();
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
          <h1>{group?.name ?? groupId}</h1>
          <small className="ink-soft">
            <Link to="/workflows">Workflows</Link>
            {group?.teamName ? <> · owned by {group.teamName}</> : null}
          </small>
        </div>
        <div className="pageHeader__actions">
          <Link className="btn btn--primary" to={`/workflows/groups/${encodeURIComponent(groupId)}/new`}>
            + New workflow
          </Link>
        </div>
      </header>

      {error && <div className="banner banner--error" role="alert">{error}</div>}

      <AppListToolbar
        noun="workflow"
        search={search}
        onSearchChange={setSearch}
        count={filtered.length}
        total={all.length}
        loading={loading}
      />

      {loading ? (
        <div className="emptyState"><p className="ink-soft">Loading…</p></div>
      ) : filtered.length === 0 ? (
        <div className="emptyState">
          {all.length === 0 ? (
            <>
              <p>No workflows in this group yet.</p>
              <p className="ink-soft">
                A workflow chains health checks, load tests, waits, approvals and
                notifications — draw one and it runs itself.
              </p>
            </>
          ) : (
            <p className="ink-soft">No workflows match "{search}".</p>
          )}
        </div>
      ) : (
        <table className="runsTable workflowListTable">
          <thead>
            <tr>
              <th scope="col">Workflow</th>
              <th scope="col">Tasks</th>
              <th scope="col">Last run</th>
              <th scope="col">Updated</th>
              <th scope="col" className="runsTable__actions">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((w) => (
              <tr key={w.workflowId} className={w.enabled ? undefined : "isMuted"}>
                <td>
                  <Link to={`/workflows/${w.workflowId}`} className="runsTable__link">{w.name}</Link>
                  {!w.enabled && <span className="chip chip--muted" style={{ marginLeft: 8 }}>disabled</span>}
                  {w.description && (
                    <div className="ink-soft" style={{ fontSize: "0.82rem" }}>{w.description}</div>
                  )}
                </td>
                <td><TaskMix workflow={w} /></td>
                <td>
                  {w.lastExecution ? (
                    <Link to={`/workflows/executions/${w.lastExecution.executionId}`} className="runsTable__link">
                      <ExecutionStateChip state={w.lastExecution.state} />
                      <span className="ink-soft" style={{ marginLeft: 6, fontSize: "0.82rem" }}>
                        {formatRelative(w.lastExecution.startedAt)}
                      </span>
                    </Link>
                  ) : (
                    <span className="ink-soft">never run</span>
                  )}
                </td>
                <td className="ink-soft" style={{ fontSize: "0.85rem" }}>
                  {formatRelative(w.updatedAt)}
                  {w.updatedBy ? <> by {w.updatedBy}</> : null}
                </td>
                <td className="runsTable__actions">
                  <Link className="btn btn--ghost btn--sm" to={`/workflows/${w.workflowId}/edit`}>Edit</Link>
                  <button type="button" className="btn btn--ghost btn--sm" onClick={() => setPendingDelete(w)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

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
