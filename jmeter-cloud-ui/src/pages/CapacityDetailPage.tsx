import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationGroupsApi, type ApplicationGroup } from "../api/applicationGroups";
import {
  capacityApi,
  CapacityApiError,
  isCapacityExceeded,
  type CapacitySnapshot,
  type PodView,
} from "../api/capacity";
import {
  BulkActionConfirmDialog,
  type BulkAction,
} from "../components/BulkActionConfirmDialog";
import { RequestCapacityDialog } from "../components/RequestCapacityDialog";
import { RegionPicker } from "../components/RegionPicker";
import { DeclareWorkerDialog } from "../components/DeclareWorkerDialog";
import { useVisiblePolling } from "../hooks/useVisiblePolling";

/**
 * Per-group capacity drill-in — the worker pool is the application group's
 * (GROUP-CAPACITY, 2026-08-30). Reached via /capacity/groups/{groupId}
 * (clicking a row on the Capacity › Reservations tab). The pool's lifecycle policy is edited with
 * the group itself ("Manage groups" on Applications).
 *
 * <p>Per region: chips (Ready / In Use / Provisioned / Max), a count
 * input + "Provision Worker(s)" button, "Request Capacity" button
 * (renamed from "Request more"), then a worker table with checkbox
 * select-all / per-row select. Above the table, a sticky bulk-action
 * toolbar appears when at least one worker is selected — Restart
 * Selected and Drain Selected open a confirmation modal that
 * partitions the selection into "will proceed" vs "will skip
 * (IN_USE)" up-front so the operator never half-clicks through 409s.
 *
 * <p>Polled every 10 s; targeted refresh after each action keeps the
 * post-click latency sub-second.
 */

const POLL_INTERVAL_MS = 10_000;

interface ToastAction {
  label: string;
  href: string;
}
interface Toast {
  variant: "ok" | "warn" | "err";
  text: string;
  detail?: string;
  /** Phase 5c — optional follow-up CTA rendered inside the toast. */
  action?: ToastAction;
}

interface SelectionState {
  /** Per-region: set of selected podNames. */
  byRegion: Record<string, Set<string>>;
}

type State =
  | { status: "loading" }
  | { status: "ok"; group: ApplicationGroup; snapshots: Record<string, CapacitySnapshot>; refreshedAt: Date }
  | { status: "notFound" }
  | { status: "error"; message: string };

export function CapacityDetailPage() {
  const { groupId = "" } = useParams<{ groupId: string }>();
  const [state, setState] = useState<State>({ status: "loading" });
  const [toast, setToast] = useState<Toast | null>(null);
  const [selection, setSelection] = useState<SelectionState>({ byRegion: {} });
  const [bulkDialog, setBulkDialog] = useState<
    { region: string; action: BulkAction; pods: PodView[] } | null
  >(null);
  const [requestCap, setRequestCap] = useState<{ region: string; current: number } | null>(null);
  const [managingRegions, setManagingRegions] = useState(false);
  const [declaring, setDeclaring] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      let group: ApplicationGroup;
      try {
        group = await applicationGroupsApi.get(groupId, signal);
      } catch (err) {
        if (signal?.aborted) return;
        if (isNotFound(err)) { setState({ status: "notFound" }); return; }
        throw err;
      }
      const regions = (group.capacity ?? []).map((c) => c.region);
      const snapPairs = await Promise.all(
        regions.map((region) =>
          capacityApi
            .listPods(group.groupId, region, signal)
            .then((snap) => [region, snap] as const)
            .catch(() => [region, syntheticSnap(group, region)] as const),
        ),
      );
      const snapshots: Record<string, CapacitySnapshot> = {};
      for (const [region, snap] of snapPairs) snapshots[region] = snap;
      setState({ status: "ok", group, snapshots, refreshedAt: new Date() });

      // Drop selections for pods that no longer exist (drained / replaced).
      setSelection((prev) => {
        const next: Record<string, Set<string>> = {};
        for (const region of regions) {
          const existing = prev.byRegion[region];
          if (!existing) continue;
          const validNames = new Set(snapshots[region].pods.map((p) => p.podName));
          const filtered = new Set([...existing].filter((n) => validNames.has(n)));
          if (filtered.size > 0) next[region] = filtered;
        }
        return { byRegion: next };
      });
    } catch (err: unknown) {
      if (signal?.aborted) return;
      setState({ status: "error", message: err instanceof Error ? err.message : String(err) });
    }
  }, [groupId]);

  useEffect(() => {
    const ctl = new AbortController();
    void refresh(ctl.signal);
    return () => ctl.abort();
  }, [refresh]);

  const { isPaused } = useVisiblePolling(() => { void refresh(); }, POLL_INTERVAL_MS);

  const showToast = useCallback((t: Toast) => {
    setToast(t);
    window.setTimeout(() => setToast((cur) => (cur === t ? null : cur)), 6000);
  }, []);

  const refreshOne = useCallback(async (gid: string, region: string) => {
    try {
      const snap = await capacityApi.listPods(gid, region);
      setState((prev) => {
        if (prev.status !== "ok") return prev;
        return {
          ...prev,
          snapshots: { ...prev.snapshots, [region]: snap },
          refreshedAt: new Date(),
        };
      });
    } catch { /* next polling tick will reconcile */ }
  }, []);

  if (state.status === "loading") return <p className="ink-soft">Loading capacity for {groupId}…</p>;
  if (state.status === "notFound") {
    return (
      <section className="capacityPage">
        <p className="text--error">
          Application group <span className="mono">{groupId}</span> not found.
        </p>
        <p><Link to="/capacity" className="btn btn--ghost">← Back to Capacity</Link></p>
      </section>
    );
  }
  if (state.status === "error") return <p className="text--error">{state.message}</p>;

  const { group, snapshots } = state;
  const regions = (group.capacity ?? []).map((c) => c.region);

  // ── Action handlers ─────────────────────────────────────────────

  async function provisionN(region: string, n: number) {
    const max = (group.capacity ?? []).find((c) => c.region === region)?.maxAvailable ?? 0;
    setBusy(true);
    let ok = 0, failed = 0;
    let firstError: string | null = null;
    for (let i = 0; i < n; i++) {
      try {
        await capacityApi.spinPod(group.groupId, region);
        ok += 1;
      } catch (err) {
        failed += 1;
        if (!firstError) firstError = err instanceof Error ? err.message : String(err);
        // If we hit the cap, stop the loop — subsequent calls will all 409.
        if (isCapacityExceeded(err)) break;
      }
    }
    setBusy(false);
    void refreshOne(group.groupId, region);
    if (failed === 0) {
      showToast({ variant: "ok", text: `Provisioned ${ok} worker${ok === 1 ? "" : "s"} in ${region}` });
    } else if (ok === 0) {
      showToast({ variant: "err", text: `Could not provision in ${region}`, detail: firstError ?? `Max=${max}` });
    } else {
      showToast({
        variant: "warn",
        text: `Provisioned ${ok} of ${n} worker${n === 1 ? "" : "s"} in ${region}`,
        detail: firstError ?? "Stopped at cap.",
      });
    }
  }

  async function executeBulk(region: string, action: BulkAction, pods: PodView[]) {
    setBusy(true);
    let ok = 0, failed = 0;
    let firstError: string | null = null;
    for (const p of pods) {
      try {
        if (action === "drain") {
          await capacityApi.drainPod(group.groupId, region, p.podName);
        } else {
          await capacityApi.restartPod(group.groupId, region, p.podName);
        }
        ok += 1;
      } catch (err) {
        failed += 1;
        if (!firstError) firstError = err instanceof Error ? err.message : String(err);
      }
    }
    setBusy(false);
    setBulkDialog(null);
    setSelection((prev) => ({ byRegion: { ...prev.byRegion, [region]: new Set() } }));
    void refreshOne(group.groupId, region);
    const verb = action === "drain" ? "Drained" : "Restarted";
    if (failed === 0) {
      showToast({ variant: "ok", text: `${verb} ${ok} worker${ok === 1 ? "" : "s"} in ${region}` });
    } else if (ok === 0) {
      showToast({ variant: "err", text: `Could not ${action} workers in ${region}`, detail: firstError ?? "" });
    } else {
      showToast({ variant: "warn", text: `${verb} ${ok} of ${pods.length} in ${region}`, detail: firstError ?? "" });
    }
  }

  async function applyRequestedCap(region: string, newMax: number) {
    try {
      await capacityApi.setMax(group.groupId, region, newMax);
      // The toast's CTA leads to the group's applications — any of them can
      // launch against the new ceiling.
      showToast({
        variant: "ok",
        text: `Max for ${region} set to ${newMax}`,
        action: { label: "Open Applications →", href: "/applications" },
      });
      void refreshOne(group.groupId, region);
    } catch (err) {
      // Bubble up so the dialog can render the error inline before closing.
      if (err instanceof CapacityApiError && err.code === "CAPACITY_SHRINK_BELOW_PROVISIONED") {
        const provisioned = err.extra?.provisioned as number | undefined;
        const requested = err.extra?.requested as number | undefined;
        throw new Error(
          `Cannot shrink to ${requested}: ${provisioned} pod${provisioned === 1 ? "" : "s"} currently provisioned. Drain first.`,
        );
      }
      if (err instanceof CapacityApiError && err.code === "CLUSTER_CAPACITY_EXCEEDED") {
        const maxWorkers = err.extra?.maxWorkers as number | undefined;
        const others = err.extra?.reservedByOthers as number | undefined;
        const free = maxWorkers != null && others != null ? Math.max(0, maxWorkers - others) : undefined;
        throw new Error(
          `The cluster cannot fit this reservation — other groups hold ${others} of its ${maxWorkers} workers`
          + (free != null ? `; at most ${free} can be reserved.` : "."),
        );
      }
      if (err instanceof CapacityApiError && err.code === "CLUSTER_NOT_REGISTERED") {
        throw new Error("This cluster is not registered any more — see the Clusters page.");
      }
      throw err instanceof Error ? err : new Error(String(err));
    }
  }

  async function applyRegionChanges(selected: string[]) {
    const currentRegions = (group.capacity ?? []).map((c) => c.region);
    const toAdd = selected.filter((r) => !currentRegions.includes(r));
    const toRemove = currentRegions.filter((r) => !selected.includes(r));
    setBusy(true);
    let ok = 0, failed = 0;
    let firstError: string | null = null;
    for (const r of toAdd) {
      try { await capacityApi.addRegion(group.groupId, r); ok += 1; }
      catch (e) { failed += 1; firstError ??= e instanceof Error ? e.message : String(e); }
    }
    for (const r of toRemove) {
      try { await capacityApi.removeRegion(group.groupId, r); ok += 1; }
      catch (e) { failed += 1; firstError ??= e instanceof Error ? e.message : String(e); }
    }
    setBusy(false);
    setManagingRegions(false);
    await refresh();
    const summary = `${toAdd.length} added, ${toRemove.length} removed`;
    if (failed === 0) {
      showToast({ variant: "ok", text: `Clusters updated — ${summary}` });
    } else if (ok === 0) {
      showToast({ variant: "err", text: "Could not update clusters", detail: firstError ?? "" });
    } else {
      showToast({ variant: "warn", text: `Clusters partially updated — ${summary}`, detail: firstError ?? "" });
    }
  }

  // Regions that still have provisioned workers can't be removed (drain first).
  const lockedRegions = new Set(
    regions.filter((r) => (snapshots[r]?.provisioned ?? 0) > 0),
  );

  return (
    <section className="capacityPage capacityDetail">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <Link to="/capacity" className="ink-soft" style={{ fontSize: "0.85rem" }}>← Capacity</Link>
          <h1 className="capacityDetail__title">
            {group.name} <span className="mono ink-soft appGroupHeading__id">{group.groupId}</span>
          </h1>
          <small className="ink-soft" aria-live="polite">
            {isPaused
              ? "Polling paused (tab hidden)"
              : `Refreshed ${formatRelative(state.refreshedAt.toISOString())}`}
          </small>
        </div>
        {/* A group has many applications, so the way forward is the
            Applications list (filed under this group), not one launcher. */}
        <div className="capacityDetail__nav">
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => setManagingRegions(true)}
          >
            Manage clusters
          </button>
          <Link to="/applications" className="btn btn--ghost" title={`The ${group.applicationCount ?? 0} application(s) in this group`}>
            Applications ({group.applicationCount ?? 0}) →
          </Link>
        </div>
      </header>

      {toast && (
        <div
          role="status"
          aria-live="polite"
          className={`toast toast--${toast.variant}`}
        >
          <div className="toast__body" onClick={() => setToast(null)}>
            <strong>{toast.text}</strong>
            {toast.detail && <div className="toast__detail">{toast.detail}</div>}
          </div>
          {toast.action && (
            <Link
              to={toast.action.href}
              className="toast__cta"
              onClick={() => setToast(null)}
            >
              {toast.action.label}
            </Link>
          )}
        </div>
      )}

      {regions.length === 0 ? (
        <div className="emptyState">
          <p>This group has no clusters attached.</p>
          <p className="ink-soft">
            Attach up to two registered clusters and reserve capacity on them —
            the group&apos;s applications share the pool.
          </p>
          <p>
            <button type="button" className="btn btn--primary" onClick={() => setManagingRegions(true)}>
              + Attach clusters
            </button>
          </p>
        </div>
      ) : (
        regions.map((region) => (
          <RegionPanel
            key={region}
            snapshot={snapshots[region]}
            selection={selection.byRegion[region] ?? new Set()}
            onSelectionChange={(next) =>
              setSelection((prev) => ({ byRegion: { ...prev.byRegion, [region]: next } }))
            }
            onProvision={(n) => provisionN(region, n)}
            onBulkRestart={(pods) => setBulkDialog({ region, action: "restart", pods })}
            onBulkDrain={(pods)   => setBulkDialog({ region, action: "drain",   pods })}
            onRequestCapacity={(current) => setRequestCap({ region, current })}
            onDeclare={() => setDeclaring(region)}
            busy={busy}
          />
        ))
      )}

      {bulkDialog && (
        <BulkActionConfirmDialog
          action={bulkDialog.action}
          selected={bulkDialog.pods}
          busy={busy}
          onConfirm={(toAct) => executeBulk(bulkDialog.region, bulkDialog.action, toAct)}
          onCancel={() => setBulkDialog(null)}
        />
      )}

      {requestCap && (
        <RequestCapacityDialog
          groupName={group.name}
          region={requestCap.region}
          current={requestCap.current}
          onSubmit={async (newMax) => {
            await applyRequestedCap(requestCap.region, newMax);
            setRequestCap(null);
          }}
          onCancel={() => setRequestCap(null)}
        />
      )}

      {declaring && (
        <DeclareWorkerDialog
          groupId={group.groupId}
          region={declaring}
          onDone={async (message) => {
            setDeclaring(null);
            showToast({ variant: "ok", text: message });
            await refreshOne(group.groupId, declaring);
          }}
          onCancel={() => setDeclaring(null)}
        />
      )}

      {managingRegions && (
        <RegionPicker
          groupName={group.name}
          current={regions}
          lockedRegions={lockedRegions}
          busy={busy}
          onSubmit={applyRegionChanges}
          onCancel={() => setManagingRegions(false)}
        />
      )}
    </section>
  );
}

// ── Region panel ─────────────────────────────────────────────────

function RegionPanel({
  snapshot, selection, onSelectionChange,
  onProvision, onBulkRestart, onBulkDrain, onRequestCapacity, onDeclare, busy,
}: {
  snapshot: CapacitySnapshot;
  selection: Set<string>;
  onSelectionChange: (next: Set<string>) => void;
  onProvision: (n: number) => void;
  onBulkRestart: (pods: PodView[]) => void;
  onBulkDrain:   (pods: PodView[]) => void;
  onRequestCapacity: (currentMax: number) => void;
  /** CLUSTER-CAPACITY — declare an operator-deployed worker into this pool. */
  onDeclare: () => void;
  busy: boolean;
}) {
  const [provisionN, setProvisionN] = useState("1");

  // Reset the provision-count input when spinnable shrinks below the
  // current draft (e.g. someone else spun a worker on another tab).
  useEffect(() => {
    const n = Number.parseInt(provisionN, 10);
    if (!Number.isFinite(n) || n > snapshot.spinnable) {
      setProvisionN(String(Math.max(1, Math.min(snapshot.spinnable, n || 1))));
    }
  }, [snapshot.spinnable, provisionN]);

  const selectedPods = useMemo(
    () => snapshot.pods.filter((p) => selection.has(p.podName)),
    [snapshot.pods, selection],
  );
  const allSelected = snapshot.pods.length > 0 && selectedPods.length === snapshot.pods.length;
  const someSelected = selectedPods.length > 0;

  const ratio = snapshot.maxAvailable > 0
    ? (snapshot.ready + snapshot.inUse) / snapshot.maxAvailable
    : 0;
  const variant: "ok" | "warn" | "err" =
    ratio >= 1 ? "err" : ratio >= 0.8 ? "warn" : "ok";

  const provisionCount = Number.parseInt(provisionN, 10);
  const provisionValid =
    Number.isFinite(provisionCount) &&
    provisionCount >= 1 &&
    provisionCount <= snapshot.spinnable;

  function toggleAll(checked: boolean) {
    onSelectionChange(checked ? new Set(snapshot.pods.map((p) => p.podName)) : new Set());
  }

  function togglePod(podName: string, checked: boolean) {
    const next = new Set(selection);
    if (checked) next.add(podName); else next.delete(podName);
    onSelectionChange(next);
  }

  return (
    <section className={`regionPanel regionPanel--${variant}`}>
      <header className="regionPanel__head">
        <div className="regionPanel__title">
          <span className="mono regionPanel__region">{snapshot.region}</span>
          <span className="capacityChips">
            <span className="chip chip--ok"   title="Workers running and idle">Ready {snapshot.ready}</span>
            <span className="chip chip--warn" title="Workers currently held by an active run">In Use {snapshot.inUse}</span>
            <span className="chip" title="Workers existing as containers (Ready + In Use) of the maximum allowed">Usage {snapshot.provisioned}/{snapshot.maxAvailable}</span>
            {/* Small inline utilization bar — replaces the full-width bar
                that used to sit below the header (matches the Capacity tab
                table's compact Usage indicator). */}
            <span className="regionPanel__util" title={`${Math.round(ratio * 100)}% utilized`}>
              <span className={`capacityBar capacityBar--${variant} regionPanel__utilBar`} aria-hidden="true">
                <span style={{ width: `${Math.min(100, Math.round(ratio * 100))}%` }} />
              </span>
              <small className="mono ink-soft">{Math.round(ratio * 100)}%</small>
            </span>
          </span>
        </div>
        <div className="regionPanel__actions">
          <div className="provisionGroup" title={
            snapshot.spinnable === 0
              ? "Reserve more capacity to raise the limit above the current ceiling."
              : `Provision up to ${snapshot.spinnable} more worker(s)`
          }>
            <input
              type="number"
              min={1}
              max={snapshot.spinnable || 1}
              value={provisionN}
              onChange={(e) => setProvisionN(e.target.value)}
              className="provisionGroup__count mono"
              disabled={busy || snapshot.spinnable === 0}
              aria-label={`Number of workers to provision in ${snapshot.region}`}
            />
            <button
              type="button"
              className="btn btn--primary"
              onClick={() => onProvision(provisionCount)}
              disabled={busy || !provisionValid}
            >
              + Provision Worker{provisionCount === 1 ? "" : "s"}
            </button>
          </div>
          {/* Phase 5c — Drain-all-idle shortcut. Only enabled when at
              least one READY worker exists; uses the same bulk dialog
              so the operator confirms before the destructive call. */}
          <button
            type="button"
            className="btn btn--ghost btn--danger"
            onClick={() => onBulkDrain(snapshot.pods.filter((p) => p.state === "READY"))}
            disabled={busy || snapshot.ready === 0}
            title={snapshot.ready === 0
              ? "No idle workers to drain"
              : `Drain all ${snapshot.ready} READY worker${snapshot.ready === 1 ? "" : "s"} (skips in-use)`}
          >
            Drain All Ready
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onDeclare}
            disabled={busy}
            title="Bind a worker you deployed yourself — it counts against the reservation like a spun one"
          >
            + Declare a worker
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => onRequestCapacity(snapshot.maxAvailable)}
            disabled={busy}
          >
            Reserve capacity
          </button>
        </div>
      </header>

      {someSelected && (
        <div className="bulkToolbar" role="toolbar" aria-label={`Bulk actions for ${snapshot.region}`}>
          <span className="bulkToolbar__count">
            {selectedPods.length} selected
          </span>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => onBulkRestart(selectedPods)}
            disabled={busy}
          >
            Restart Selected
          </button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => onBulkDrain(selectedPods)}
            disabled={busy}
          >
            Drain Selected
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => onSelectionChange(new Set())}
            disabled={busy}
          >
            Clear
          </button>
        </div>
      )}

      {snapshot.pods.length === 0 ? (
        <p className="ink-soft regionPanel__empty">
          No workers provisioned. Click <strong>+ Provision Worker</strong> to add one.
        </p>
      ) : (
        <table className="runsTable podTable">
          <thead>
            <tr>
              <th className="podTable__check">
                <input
                  type="checkbox"
                  checked={allSelected}
                  onChange={(e) => toggleAll(e.target.checked)}
                  aria-label={`Select all workers in ${snapshot.region}`}
                />
              </th>
              <th>Worker</th>
              <th>State</th>
              <th>Source</th>
              <th>Container</th>
              {/* Phase F1 — WORKER-HYGIENE bookkeeping fields surfaced
                  per pod so operators can spot a pod approaching its
                  recycle threshold (runsServed near maxRunsPerPod, or
                  provisionedAt older than podMaxAgeHours) or running
                  on a stale image. */}
              <th>Runs</th>
              <th>Age</th>
              <th>Image</th>
              <th>Last heartbeat</th>
            </tr>
          </thead>
          <tbody>
            {snapshot.pods.map((p) => (
              <PodRow
                key={p.podName}
                pod={p}
                checked={selection.has(p.podName)}
                onToggle={(c) => togglePod(p.podName, c)}
              />
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ── Worker row ──────────────────────────────────────────────────

function PodRow({
  pod, checked, onToggle,
}: { pod: PodView; checked: boolean; onToggle: (checked: boolean) => void }) {
  const stateClass =
    pod.state === "READY"     ? "chip--ok"
  : pod.state === "IN_USE"    ? "chip--warn"
  : pod.state === "LOST"      ? "chip--err"
  : pod.state === "RECYCLING" ? "chip--warn"
  : "";
  // Phase F1 — RECYCLING tooltip distinguishes "active claim, deferred"
  // from "idle, in-flight drain". Today's backend marks state RECYCLING
  // only on idle pods (DRAINING_FOR_RECYCLE is gated on `state='IDLE'`
  // in markDrainingForRecycle), so the message is unambiguous.
  const stateTitle =
      pod.state === "IN_USE" && pod.blockedBy
        ? `Held by run ${pod.blockedBy.runId} (${pod.blockedBy.state})`
    : pod.state === "RECYCLING"
        ? "Will recycle now (idle) — drain + replace in flight."
        : undefined;
  return (
    <tr className={checked ? "podRow--selected" : ""}>
      <td className="podTable__check">
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => onToggle(e.target.checked)}
          aria-label={`Select ${pod.podName}`}
        />
      </td>
      <td className="mono">{pod.podName}</td>
      <td>
        <span className={`chip ${stateClass}`} title={stateTitle}>
          {pod.state}
        </span>
      </td>
      <td>
        <span
          className="chip"
          title={pod.source === "STATIC"
            ? "Operator-deployed and declared — never restarted or recycled by the platform"
            : "Spun by the cluster's regional orchestrator"}
        >
          {pod.source === "STATIC" ? "Declared" : "Spun"}
        </span>
      </td>
      <td>
        <span className={`chip ${pod.containerRunning ? "chip--ok" : ""}`}>
          {pod.containerRunning ? "running" : "stopped"}
        </span>
      </td>
      {/* Phase F1 — runsServed / age / imageDigest cells. */}
      <td className="mono">{pod.runsServed ?? 0}</td>
      <td className="ink-soft mono" title={pod.provisionedAt ?? undefined}>
        {pod.provisionedAt ? formatRelative(pod.provisionedAt) : "—"}
      </td>
      <td className="ink-soft mono" title={pod.imageDigest ?? "unknown"}>
        {pod.imageDigest ? shortDigest(pod.imageDigest) : "—"}
      </td>
      <td className="ink-soft mono">
        {pod.lastHeartbeat ? formatRelative(pod.lastHeartbeat) : "—"}
      </td>
    </tr>
  );
}

/** Phase F1 — short-hash render for image digests like "sha256:abc…". */
function shortDigest(digest: string): string {
  // Trim the "sha256:" prefix if present and take the first 7 chars
  // (git-style abbrev).
  const idx = digest.indexOf(":");
  const body = idx >= 0 ? digest.slice(idx + 1) : digest;
  return body.slice(0, 7);
}

// ── Helpers ──────────────────────────────────────────────────────

function syntheticSnap(group: ApplicationGroup, region: string): CapacitySnapshot {
  const max = (group.capacity ?? []).find((c) => c.region === region)?.maxAvailable ?? 0;
  return {
    groupId: group.groupId, region, maxAvailable: max,
    provisioned: 0, ready: 0, inUse: 0, spinnable: max, pods: [],
  };
}

/** The registry's 404 for an unknown group (its client throws ApplicationApiError-shaped errors). */
function isNotFound(err: unknown): boolean {
  return typeof err === "object" && err !== null
    && ((err as { httpStatus?: number }).httpStatus === 404
      || (err as { code?: string }).code === "APPLICATION_GROUP_NOT_FOUND");
}
