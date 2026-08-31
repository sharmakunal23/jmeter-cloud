import { useCallback, useEffect, useMemo, useState } from "react";

import { ClusterApiError, clustersApi, type ClusterStatus } from "../api/clusters";
import { AppListToolbar } from "../components/AppListToolbar";
import { ClusterFormDialog } from "../components/ClusterFormDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { Paginator } from "../components/Paginator";
import { ToastView, useToast } from "../components/Toast";
import { useClientPagination } from "../hooks/useClientPagination";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { formatRelative } from "../lib/time";

/**
 * Capacity › Clusters — the cluster registry (CLUSTER-CAPACITY): one row per registered data
 * center, off the single `GET /api/v1/regions/status` read — registration
 * facts, live reachability, the groups' reservations against the ceiling,
 * and the last test-provisioning verdict. "+ Add cluster" runs the hub's
 * validation chain before anything is written; "Test provisioning" spins one
 * real probe worker asynchronously and the verdict lands on the next poll.
 */

const POLL_INTERVAL_MS = 10_000;

type State =
  | { status: "loading" }
  | { status: "ok"; clusters: ClusterStatus[]; refreshedAt: Date }
  | { status: "error"; message: string };

export function ClustersPage() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<ClusterStatus | null>(null);
  const [removing, setRemoving] = useState<ClusterStatus | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);
  const { toast, showToast, dismiss } = useToast();

  const refresh = useCallback(async () => {
    try {
      const clusters = await clustersApi.status();
      setState({ status: "ok", clusters, refreshedAt: new Date() });
    } catch (err) {
      setState((prev) =>
        prev.status === "ok" ? prev : { status: "error", message: err instanceof Error ? err.message : String(err) });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);
  const { isPaused } = useVisiblePolling(refresh, POLL_INTERVAL_MS, { name: "clusters" });

  const clusters = state.status === "ok" ? state.clusters : [];
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return clusters;
    return clusters.filter((c) =>
      c.region.toLowerCase().includes(q)
      || c.label.toLowerCase().includes(q)
      || c.regionalUrl.toLowerCase().includes(q));
  }, [clusters, search]);
  const { page, setPage, pageItems, total, pageSize, setPageSize } = useClientPagination(filtered, search);

  async function testProvision(cluster: ClusterStatus) {
    try {
      await clustersApi.testProvision(cluster.region);
      showToast({
        variant: "ok",
        text: `Probing ${cluster.label} — spinning one test worker.`,
        detail: "The verdict lands in the Probe column within a few minutes.",
      });
      void refresh();
    } catch (err) {
      showToast({
        variant: "err",
        text: `Could not start the probe for ${cluster.label}.`,
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  }

  async function confirmRemove() {
    if (!removing) return;
    setRemoveBusy(true);
    try {
      await clustersApi.remove(removing.region);
      showToast({ variant: "ok", text: `Cluster ${removing.label} removed.` });
      setRemoving(null);
      void refresh();
    } catch (err) {
      const detail = err instanceof ClusterApiError && err.code === "CLUSTER_IN_USE"
        ? err.message
        : err instanceof Error ? err.message : String(err);
      showToast({ variant: "err", text: `Could not remove ${removing.label}.`, detail });
      setRemoving(null);
    } finally {
      setRemoveBusy(false);
    }
  }

  return (
    <section>
      {/* No <h1> here — the Capacity section shell owns it; this row carries
          the tab's own status line and actions. */}
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <small className="ink-soft" aria-live="polite">
            {state.status === "ok"
              ? isPaused ? "Polling paused (tab hidden)" : `Refreshed ${formatRelative(state.refreshedAt.toISOString())}`
              : state.status === "loading" ? "Loading…" : ""}
          </small>
        </div>
        <div className="pageHeader__actions">
          <button className="btn btn--primary" onClick={() => setAdding(true)}>+ Add cluster</button>
        </div>
      </header>

      <AppListToolbar
        search={search}
        onSearchChange={setSearch}
        count={filtered.length}
        total={clusters.length}
        loading={state.status === "loading"}
        noun="cluster"
      />

      {state.status === "error" ? (
        <div className="emptyState">
          <p>Could not load the cluster registry.</p>
          <p className="ink-soft">{state.message}</p>
        </div>
      ) : state.status === "ok" && clusters.length === 0 ? (
        <div className="emptyState">
          <p>No clusters registered yet.</p>
          <p className="ink-soft">
            Deploy a jmeter-regional-orchestrator into the data center, then add the cluster here —
            registration validates the endpoint before anything can run there.
          </p>
        </div>
      ) : (
        <>
          <div style={{ overflowX: "auto" }}>
            <table className="runsTable">
              <thead>
                <tr>
                  <th>Cluster</th>
                  <th>Health</th>
                  <th>Workers</th>
                  <th>Reserved</th>
                  <th>Validated</th>
                  <th>Probe</th>
                  <th aria-label="actions" />
                </tr>
              </thead>
              <tbody>
                {state.status === "loading"
                  ? Array.from({ length: 3 }, (_, i) => (
                      <tr key={i}>
                        <td colSpan={7}><span className="skeleton skeleton--text" aria-hidden="true" /></td>
                      </tr>
                    ))
                  : pageItems.map((c) => (
                      <ClusterRow
                        key={c.region}
                        cluster={c}
                        onTestProvision={() => void testProvision(c)}
                        onEdit={() => setEditing(c)}
                        onRemove={() => setRemoving(c)}
                      />
                    ))}
              </tbody>
            </table>
          </div>
          <Paginator
            page={page}
            pageSize={pageSize}
            total={total}
            label="clusters"
            onChange={setPage}
            onPageSizeChange={setPageSize}
          />
        </>
      )}

      {adding && (
        <ClusterFormDialog
          onClose={() => setAdding(false)}
          onSaved={(text) => {
            showToast({ variant: "ok", text });
            void refresh();
          }}
        />
      )}
      {editing && (
        <ClusterFormDialog
          existing={editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            showToast({ variant: "ok", text });
            void refresh();
          }}
        />
      )}
      {removing && (
        <ConfirmDialog
          title={`Remove cluster ${removing.label}?`}
          body={
            "The cluster leaves the registry and nothing can run there any more. " +
            "Groups must have released their reservations and workers there first."
          }
          confirmLabel="Remove cluster"
          danger
          busy={removeBusy}
          onConfirm={() => void confirmRemove()}
          onCancel={() => setRemoving(null)}
        />
      )}
      <ToastView toast={toast} onDismiss={dismiss} />
    </section>
  );
}

function ClusterRow({
  cluster,
  onTestProvision,
  onEdit,
  onRemove,
}: {
  cluster: ClusterStatus;
  onTestProvision: () => void;
  onEdit: () => void;
  onRemove: () => void;
}) {
  const util = cluster.maxWorkers > 0 ? cluster.provisionedWorkers / cluster.maxWorkers : 0;
  const barVariant = util >= 1 ? "err" : util >= 0.8 ? "warn" : "ok";
  return (
    <tr>
      <td>
        <div className="clusterCell">
          <strong>{cluster.label}</strong>
          <small className="ink-soft">{cluster.region} · {cluster.regionalUrl}</small>
        </div>
      </td>
      <td>{healthChip(cluster)}</td>
      <td>
        <span className="regionPanel__util" title={`${cluster.provisionedWorkers} of ${cluster.maxWorkers} workers`}>
          <span className={`capacityBar capacityBar--${barVariant} regionPanel__utilBar`} aria-hidden="true">
            <span style={{ width: `${Math.min(100, Math.round(util * 100))}%` }} />
          </span>
          {cluster.provisionedWorkers}/{cluster.maxWorkers}
        </span>
      </td>
      <td>
        <span title="Sum of every group's reservation against the cluster ceiling">
          {cluster.reservedWorkers}/{cluster.maxWorkers}
        </span>
      </td>
      <td>
        {cluster.lastValidatedAt
          ? <span title={cluster.lastValidatedAt}>{formatRelative(cluster.lastValidatedAt)}</span>
          : <span className="ink-soft">—</span>}
      </td>
      <td>{probeChip(cluster)}</td>
      <td className="runsTable__actions">
        <button
          className="btn btn--sm btn--ghost"
          onClick={onTestProvision}
          disabled={cluster.probing}
          title="Spin one real probe worker, wait until it is ready, delete it"
        >
          {cluster.probing ? "Probing…" : "Test provisioning"}
        </button>
        <button className="btn btn--sm btn--ghost" onClick={onEdit}>Edit</button>
        <button className="btn btn--sm btn--ghost text--error" onClick={onRemove}>Remove</button>
      </td>
    </tr>
  );
}

function healthChip(c: ClusterStatus) {
  if (c.reachable == null) {
    return <span className="chip">Not probed yet</span>;
  }
  if (!c.reachable) {
    return (
      <span className="chip chip--err" title={c.lastError ?? undefined}>
        Unreachable
      </span>
    );
  }
  const caps = c.capabilities;
  const detail = caps?.workersFree != null ? ` · room for ${caps.workersFree}` : "";
  return (
    <span className="chip chip--ok" title={caps?.image ? `worker image ${caps.image}` : undefined}>
      Reachable{detail}
    </span>
  );
}

function probeChip(c: ClusterStatus) {
  if (c.probing) return <span className="chip">Probing…</span>;
  if (!c.lastProbe) return <span className="ink-soft">never run</span>;
  const ok = c.lastProbe.status === "PASS";
  return (
    <span className={`chip ${ok ? "chip--ok" : "chip--err"}`} title={c.lastProbe.detail}>
      {ok ? "PASS" : "FAIL"} · {formatRelative(c.lastProbe.at)}
    </span>
  );
}
