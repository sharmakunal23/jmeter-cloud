import { useCallback, useEffect, useMemo, useState } from "react";

import { ClusterApiError, clustersApi, type ClusterStatus } from "../api/clusters";
import { AppListToolbar } from "../components/AppListToolbar";
import { ClusterFormDialog } from "../components/ClusterFormDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { DataList } from "../components/DataList";
import { ToastView, useToast } from "../components/Toast";
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
      ) : null}

      <DataList<ClusterStatus>
        label="Clusters"
        loading={state.status === "loading"}
        rows={filtered}
        rowKey={(c) => c.region}
        itemNoun="clusters"
        resetKey={search}
        empty={<>No clusters match &quot;{search}&quot;.</>}
        columns={[
          { key: "cluster", header: "Cluster", cell: (c) => (
            <div className="clusterCell">
              <strong>{c.label}</strong>
              <small className="ink-soft">{c.region} · {c.regionalUrl}</small>
            </div>
          ) },
          { key: "health", header: "Health", cell: (c) => healthChip(c) },
          { key: "workers", header: "Workers", cell: (c) => {
            const util = c.maxWorkers > 0 ? c.provisionedWorkers / c.maxWorkers : 0;
            const barVariant = util >= 1 ? "err" : util >= 0.8 ? "warn" : "ok";
            return (
              <span className="regionPanel__util"
                    title={`${c.provisionedWorkers} of ${c.maxWorkers} workers`}>
                <span className={`capacityBar capacityBar--${barVariant} regionPanel__utilBar`} aria-hidden="true">
                  <span style={{ width: `${Math.min(100, Math.round(util * 100))}%` }} />
                </span>
                {c.provisionedWorkers}/{c.maxWorkers}
              </span>
            );
          } },
          { key: "reserved", header: "Reserved", cell: (c) => (
            <span title="Sum of every group's reservation against the cluster ceiling">
              {c.reservedWorkers}/{c.maxWorkers}
            </span>
          ) },
          { key: "validated", header: "Validated", cell: (c) => (
            c.lastValidatedAt
              ? <span title={c.lastValidatedAt}>{formatRelative(c.lastValidatedAt)}</span>
              : <span className="ink-soft">—</span>
          ) },
          { key: "probe", header: "Probe", cell: (c) => probeChip(c) },
          { key: "actions", header: <span className="visuallyHidden">actions</span>,
            className: "runsTable__actions", cell: (c) => (
              <>
                <button className="btn btn--sm btn--ghost" onClick={() => void testProvision(c)}
                        disabled={c.probing}
                        title="Spin one real probe worker, wait until it is ready, delete it">
                  {c.probing ? "Probing…" : "Test provisioning"}
                </button>
                <button className="btn btn--sm btn--ghost" onClick={() => setEditing(c)}>Edit</button>
                <button className="btn btn--sm btn--ghost text--error"
                        onClick={() => setRemoving(c)}>Remove</button>
              </>
            ) },
        ]}
      />


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
