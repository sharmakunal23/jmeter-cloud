import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { workflowsApi, type WorkflowGroupSummary } from "../api/workflows";
import { AppListToolbar } from "../components/AppListToolbar";
import { Paginator } from "../components/Paginator";
import { useClientPagination } from "../hooks/useClientPagination";

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
  const { page, pageItems, setPage, pageSize, setPageSize, total } = useClientPagination(filtered, term);

  return (
    <section className="workflowsPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Workflows</h1>
          <small className="ink-soft">
            A workflow chains health checks, load tests and notifications for one application group.
          </small>
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

      {loading ? (
        <div className="emptyState"><p className="ink-soft">Loading…</p></div>
      ) : filtered.length === 0 ? (
        <div className="emptyState">
          {all.length === 0 ? (
            <>
              <p>No application groups yet.</p>
              <p className="ink-soft">
                Create one with "Manage groups" in <Link to="/applications">Applications</Link> —
                a workflow belongs to a group.
              </p>
            </>
          ) : (
            <p className="ink-soft">No groups match "{search}".</p>
          )}
        </div>
      ) : (
        <table className="runsTable workflowGroupsTable">
          <thead>
            <tr>
              <th scope="col">Group</th>
              <th scope="col">Team</th>
              <th scope="col" className="num">Workflows</th>
              <th scope="col">Notifications</th>
            </tr>
          </thead>
          <tbody>
            {pageItems.map((g) => (
              <tr key={g.groupId}>
                <td>
                  <Link to={`/workflows/groups/${encodeURIComponent(g.groupId)}`} className="runsTable__link">
                    {g.name}
                  </Link>
                </td>
                <td>{g.teamName ?? <span className="ink-soft">—</span>}</td>
                <td className="num mono">{g.workflowCount}</td>
                <td>
                  {g.notifyTo.length === 0 && g.notifyCc.length === 0 && g.notifyBcc.length === 0 ? (
                    <span className="ink-soft" title="Email tasks in this group's workflows must name their own recipients">
                      none set
                    </span>
                  ) : (
                    <span className="ink-soft" style={{ fontSize: "0.85rem" }}>
                      {g.notifyTo.length > 0 && <>To {g.notifyTo.length}</>}
                      {g.notifyCc.length > 0 && <> · Cc {g.notifyCc.length}</>}
                      {g.notifyBcc.length > 0 && <> · Bcc {g.notifyBcc.length}</>}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {total > pageSize && (
        <Paginator
          page={page}
          pageSize={pageSize}
          total={total}
          label="groups"
          onChange={setPage}
          onPageSizeChange={setPageSize}
        />
      )}
    </section>
  );
}
