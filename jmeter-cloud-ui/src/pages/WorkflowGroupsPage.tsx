import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { workflowsApi, type WorkflowGroupSummary } from "../api/workflows";
import { AppListToolbar } from "../components/AppListToolbar";
import { InfoTip } from "../components/InfoTip";
import { DataList } from "../components/DataList";

/**
 * Workflows — the landing surface, one row per application group.
 *
 * <p>Groups come first because a workflow's load tests draw on its group's
 * reserved capacity: the group, not the application, is what a workflow is
 * scoped to and what limits how much it can run at once.
 */
export function WorkflowGroupsPage() {
  const [rows, setRows] = useState<WorkflowGroupSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      setRows(await workflowsApi.groups(signal));
      setError(null);
    } catch (e) {
      if ((e as Error)?.name === "AbortError") return;
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    void load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const loading = rows === null && error === null;
  const all = rows ?? [];
  const term = search.trim().toLowerCase();
  const filtered = term
    ? all.filter((g) =>
        g.name.toLowerCase().includes(term) ||
        g.groupId.toLowerCase().includes(term) ||
        (g.teamName ?? "").toLowerCase().includes(term))
    : all;

  return (
    <section className="workflowsPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <div className="formField__labelRow">
            <h1>Workflows</h1>
            <InfoTip label="About workflows">
              A workflow chains health checks, load tests and notifications for one
              application group.
            </InfoTip>
          </div>
        </div>
      </header>

      {error && <div className="banner banner--error" role="alert">{error}</div>}

      <AppListToolbar
        noun="group"
        search={search}
        onSearchChange={setSearch}
        count={filtered.length}
        total={all.length}
        loading={loading}
      />

      <DataList<WorkflowGroupSummary>
        label="Workflow groups"
        loading={loading}
        rows={filtered}
        rowKey={(g) => g.groupId}
        itemNoun="groups"
        resetKey={search}
        empty={all.length === 0 ? (
          <>
            <strong>No application groups yet.</strong>
            <div>Create one with &quot;Manage groups&quot; in <Link to="/applications">Applications</Link> —
                 a workflow belongs to a group.</div>
          </>
        ) : <>No groups match &quot;{search}&quot;.</>}
        columns={[
          { key: "group", header: "Group", cell: (g) => (
            <Link to={`/workflows/groups/${encodeURIComponent(g.groupId)}`} className="runsTable__link">
              {g.name}
            </Link>
          ) },
          { key: "team", header: "Team",
            cell: (g) => g.teamName ?? <span className="ink-soft">—</span> },
          { key: "workflows", header: "Workflows", className: "dataList__num",
            cell: (g) => <span className="mono">{g.workflowCount}</span> },
          { key: "notifications", header: "Notifications", cell: (g) => (
            g.notifyTo.length === 0 && g.notifyCc.length === 0 && g.notifyBcc.length === 0 ? (
              <span className="ink-soft"
                    title="Email tasks in this group's workflows must name their own recipients">
                none set
              </span>
            ) : (
              <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
                {g.notifyTo.length > 0 && <>To {g.notifyTo.length}</>}
                {g.notifyCc.length > 0 && <> · Cc {g.notifyCc.length}</>}
                {g.notifyBcc.length > 0 && <> · Bcc {g.notifyBcc.length}</>}
              </span>
            )
          ) },
        ]}
      />
    </section>
  );
}
