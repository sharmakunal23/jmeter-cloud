import { useCallback, useEffect, useState } from "react";

import {
  CapacityApiError,
  boundGroupOf,
  capacityApi,
  type CapacitySnapshot,
  type PodView,
} from "../api/capacity";
import { applicationsApi } from "../api/applications";
import { applicationGroupsApi } from "../api/applicationGroups";
import { usePlatformCapabilities } from "../hooks/usePlatformCapabilities";

/**
 * The operator-facing surface for a fleet the
 * control plane does not provision.
 *
 * <p>Rendered on the Application detail page in place of the Capacity tab,
 * which is hidden in this mode. Exactly one of the two is ever live: the
 * Capacity tab is entirely spin / restart / drain, none of which apply to a
 * worker somebody deployed by hand. The workers are the application GROUP's
 * pool — declared here, they serve every application in the group.
 *
 * <p>What an operator does here mirrors what they actually did in the
 * terminal: they ran `klogin -a {dataCenter}` and `kubectl apply`, they have
 * a worker name and an address, and they declare it. The reachability check
 * happens server-side at declare time so a typo'd address fails here rather
 * than at the next run launch.
 */

interface Props {
  groupId: string;
  /** The group's display name, for the heading. */
  groupName?: string;
  /** Data centers configured for the group (its capacity rows). */
  regions: string[];
}

interface RegionState {
  snapshot: CapacitySnapshot | null;
  error: string | null;
  loading: boolean;
}

export function DataCentersSection({ groupId, groupName, regions }: Props) {
  const { regionNoun } = usePlatformCapabilities();
  const [byRegion, setByRegion] = useState<Record<string, RegionState>>({});
  const [declaring, setDeclaring] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async (region: string) => {
    setByRegion((prev) => ({
      ...prev,
      [region]: { snapshot: prev[region]?.snapshot ?? null, error: null, loading: true },
    }));
    try {
      const snapshot = await capacityApi.listPods(groupId, region);
      setByRegion((prev) => ({ ...prev, [region]: { snapshot, error: null, loading: false } }));
    } catch (err) {
      setByRegion((prev) => ({
        ...prev,
        [region]: {
          snapshot: prev[region]?.snapshot ?? null,
          error: err instanceof Error ? err.message : String(err),
          loading: false,
        },
      }));
    }
  }, [groupId]);

  useEffect(() => {
    regions.forEach((r) => void load(r));
  }, [regions, load]);

  async function release(region: string, podName: string) {
    if (!window.confirm(
      `Remove ${podName} from ${region}?\n\n`
      + "The worker itself keeps running — this only removes it from the platform's "
      + "registry, so no new test will be sent to it.",
    )) return;
    try {
      await capacityApi.drainPod(groupId, region, podName);
      setNotice(`${podName} released. The worker is still running — stop it with kubectl if you meant to.`);
      await load(region);
    } catch (err) {
      setNotice(describeError(err));
    }
  }

  const poolLabel = <>Workers of group <span className="mono">{groupName ?? groupId}</span></>;

  if (regions.length === 0) {
    return (
      <section className="dataCenters" aria-labelledby="dataCentersTitle">
        <h2 id="dataCentersTitle">{regionNoun({ plural: true, capitalize: true })} — {poolLabel}</h2>
        <p className="ink-soft">
          No {regionNoun({ plural: true })} configured for this group yet. A group gets a
          row per {regionNoun()} when it is created; declare a worker into one and it appears here.
        </p>
      </section>
    );
  }

  return (
    <section className="dataCenters" aria-labelledby="dataCentersTitle">
      <h2 id="dataCentersTitle">{regionNoun({ plural: true, capitalize: true })} — {poolLabel}</h2>
      <p className="ink-soft dataCenters__intro">
        Workers here are deployed and owned by you — the platform uses them but never
        creates, restarts or destroys them. Declare each one you deployed so runs can
        be sent to it; every application in the group shares them.
      </p>

      {notice && (
        <div className="formError" role="status" onClick={() => setNotice(null)}>
          {notice}
        </div>
      )}

      {regions.map((region) => {
        const state = byRegion[region] ?? { snapshot: null, error: null, loading: true };
        const workers = state.snapshot?.pods ?? [];
        return (
          <div className="dataCenters__card" key={region}>
            <header className="dataCenters__cardHeader">
              <h3 className="mono">{region}</h3>
              <span className="chip">{workers.length} declared</span>
              <div className="dataCenters__cardActions">
                <button
                  type="button"
                  className="btn btn--primary"
                  onClick={() => setDeclaring(region)}
                >
                  + Declare a worker
                </button>
              </div>
            </header>

            {state.loading && !state.snapshot && <p className="ink-soft">Loading…</p>}
            {state.error && <div className="formError" role="alert">{state.error}</div>}

            {!state.loading && workers.length === 0 && !state.error && (
              <p className="ink-soft">
                No workers declared in {region}. Deploy one, then declare it with its
                name and address.
              </p>
            )}

            {workers.length > 0 && (
              <table className="table dataCenters__table">
                <thead>
                  <tr>
                    <th scope="col">Worker</th>
                    <th scope="col">State</th>
                    <th scope="col">Last seen</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {workers.map((w) => (
                    <WorkerRow
                      key={w.podName}
                      worker={w}
                      onRelease={() => void release(region, w.podName)}
                    />
                  ))}
                </tbody>
              </table>
            )}

            {declaring === region && (
              <DeclareWorkerForm
                groupId={groupId}
                region={region}
                onDone={async (message) => {
                  setDeclaring(null);
                  setNotice(message);
                  await load(region);
                }}
                onCancel={() => setDeclaring(null)}
              />
            )}
          </div>
        );
      })}
    </section>
  );
}

function WorkerRow({ worker, onRelease }: { worker: PodView; onRelease: () => void }) {
  const inUse = worker.state === "IN_USE";
  return (
    <tr>
      <td className="mono">{worker.podName}</td>
      <td>
        <span className={`chip ${chipClass(worker.state)}`}>{stateLabel(worker.state)}</span>
        {worker.blockedBy && (
          <span className="ink-soft"> run {worker.blockedBy.runId}</span>
        )}
      </td>
      <td className="ink-soft">{worker.lastHeartbeat ?? "never"}</td>
      <td>
        <button
          type="button"
          className="btn btn--ghost"
          onClick={onRelease}
          disabled={inUse}
          title={inUse
            ? "This worker is running a test — abort the run first"
            : "Remove from the platform registry. The worker itself keeps running."}
        >
          Release
        </button>
      </td>
    </tr>
  );
}

/**
 * The declare form. `force` exists because a worker is frequently declared
 * during a rollout, before it answers — refusing outright would make the
 * operator wait and retry for no reason.
 */
function DeclareWorkerForm({
  groupId, region, onDone, onCancel,
}: {
  groupId: string;
  region: string;
  onDone: (message: string) => void | Promise<void>;
  onCancel: () => void;
}) {
  const [podName, setPodName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [offerForce, setOfferForce] = useState(false);

  async function submit(force: boolean) {
    setSaving(true);
    setError(null);
    try {
      const result = await capacityApi.declareWorker(
        groupId, region, podName.trim(), baseUrl.trim(), force);
      await onDone(
        result.reachable
          ? `${result.podName} declared in ${region}.`
          : `${result.podName} declared in ${region} — it did not answer yet, so it stays unavailable until it does.`,
      );
    } catch (err) {
      if (err instanceof CapacityApiError && err.code === "WORKER_UNREACHABLE") {
        setOfferForce(true);
      }
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  const canSubmit = podName.trim().length > 0 && baseUrl.trim().length > 0 && !saving;

  return (
    <form
      className="dataCenters__declareForm"
      onSubmit={(e) => { e.preventDefault(); if (canSubmit) void submit(false); }}
    >
      <label>
        <span>Worker name</span>
        <input
          className="mono"
          value={podName}
          onChange={(e) => { setPodName(e.target.value); setOfferForce(false); }}
          placeholder="payments-na-east-worker-1"
          autoFocus
        />
        <small className="ink-soft">
          Must match the pod name exactly — it is also the id the worker stamps on its
          metrics.
        </small>
      </label>
      <label>
        <span>Address</span>
        <input
          className="mono"
          value={baseUrl}
          onChange={(e) => { setBaseUrl(e.target.value); setOfferForce(false); }}
          placeholder="http://payments-na-east-worker-1.workers:8080"
        />
        <small className="ink-soft">
          The address this platform can reach the worker at — not necessarily the one
          the worker sees itself as.
        </small>
      </label>

      {error && <div className="formError" role="alert">{error}</div>}

      <div className="dataCenters__declareActions">
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
        {offerForce && (
          <button
            type="button"
            className="btn"
            onClick={() => void submit(true)}
            disabled={saving}
            title="Declare anyway — use this when the worker is deployed but not up yet"
          >
            Declare anyway
          </button>
        )}
        <button type="submit" className="btn btn--primary" disabled={!canSubmit} aria-busy={saving}>
          {saving ? "Declaring…" : "Declare"}
        </button>
      </div>
    </form>
  );
}

function chipClass(state: PodView["state"]): string {
  switch (state) {
    case "READY":  return "chip--ok";
    case "IN_USE": return "chip--warn";
    case "LOST":   return "chip--err";
    default:       return "";
  }
}

/**
 * Operator-facing wording. "LOST" is accurate in the registry but reads as
 * alarming for a worker the operator owns and can see running — what it
 * actually means here is that the platform's liveness probe stopped getting
 * an answer.
 */
function stateLabel(state: PodView["state"]): string {
  switch (state) {
    case "READY":  return "Ready";
    case "IN_USE": return "Running a test";
    case "LOST":   return "Not answering";
    default:       return state;
  }
}

function describeError(err: unknown): string {
  if (err instanceof CapacityApiError) {
    if (err.code === "POD_IN_USE") {
      return "That worker is running a test — abort the run first.";
    }
    if (err.code === "PROVISIONING_REQUIRES_STATIC") {
      return "This deployment provisions its own workers; use the group's Capacity page instead.";
    }
    if (err.code === "POD_BOUND_ELSEWHERE") {
      const other = boundGroupOf(err);
      return other
        ? `That worker is already declared to group "${other}" — release it there first.`
        : "That worker is already declared to another group — release it there first.";
    }
    return err.message;
  }
  return err instanceof Error ? err.message : String(err);
}

/** Resolves the application → its group → the group's regions, then renders the section. */
export function DataCentersSectionForApp({ appName }: { appName: string }) {
  const [pool, setPool] = useState<{ groupId: string; groupName: string; regions: string[] } | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const apps = await applicationsApi.list();
        const found = apps.find((a) => a.name === appName);
        if (!found) return;
        const group = await applicationGroupsApi.get(found.metricsGroupId);
        if (active) {
          setPool({
            groupId: group.groupId,
            groupName: group.name,
            regions: (group.capacity ?? []).map((c) => c.region).sort(),
          });
        }
      } catch { /* the section simply doesn't render */ }
    })();
    return () => { active = false; };
  }, [appName]);

  if (!pool) return null;
  return <DataCentersSection groupId={pool.groupId} groupName={pool.groupName} regions={pool.regions} />;
}
