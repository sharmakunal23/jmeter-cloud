import { useState } from "react";

import {
  CapacityApiError,
  boundGroupOf,
  capacityApi,
} from "../api/capacity";
import { InfoTip } from "./InfoTip";
import { Modal } from "./Modal";

/**
 * Declare an operator-deployed worker into a (group, cluster) pool
 * (CLUSTER-CAPACITY: declared workers coexist with spun ones and count
 * against the group's reservation). `force` exists because a worker is
 * frequently declared during a rollout, before it answers — refusing
 * outright would make the operator wait and retry for no reason.
 */
export function DeclareWorkerDialog({
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
      setError(describeDeclareError(err));
    } finally {
      setSaving(false);
    }
  }

  const canSubmit = podName.trim().length > 0 && baseUrl.trim().length > 0 && !saving;

  return (
    <Modal
      title={<>Declare a worker <span className="modal__titleApp mono">{region}</span></>}
      infoTip={
        "A declared worker is one you deployed yourself — the platform sends runs to it "
        + "but never creates, restarts or destroys it. It counts against the group's reservation "
        + "exactly like a spun worker."
      }
      width="confirm"
      onClose={onCancel}
      closeDisabled={saving}
    >
      <form
        className="modal__body createApp"
        onSubmit={(e) => { e.preventDefault(); if (canSubmit) void submit(false); }}
      >
        <div className="formField">
          <div className="formField__labelRow">
            <label htmlFor="declareWorkerName">Worker name</label>
            <InfoTip label="About worker name">
              Must match the pod name exactly — it is also the id the worker
              stamps on its metrics.
            </InfoTip>
          </div>
          <input
            id="declareWorkerName"
            className="mono"
            value={podName}
            onChange={(e) => { setPodName(e.target.value); setOfferForce(false); }}
            placeholder="payments-na-east-worker-1"
            autoFocus
          />
        </div>
        <div className="formField">
          <div className="formField__labelRow">
            <label htmlFor="declareWorkerAddress">Address</label>
            <InfoTip label="About worker address">
              The address this platform can reach the worker at — on a hosted
              cluster that is its ingress host, not its in-cluster DNS name.
            </InfoTip>
          </div>
          <input
            id="declareWorkerAddress"
            className="mono"
            value={baseUrl}
            onChange={(e) => { setBaseUrl(e.target.value); setOfferForce(false); }}
            placeholder="https://worker-1.apps.mt-d2.example.net"
          />
        </div>

        {error && <div className="formError" role="alert">{error}</div>}

        <Modal.Footer>
          <button type="button" className="btn btn--ghost" onClick={onCancel} disabled={saving}>
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
        </Modal.Footer>
      </form>
    </Modal>
  );
}

export function describeDeclareError(err: unknown): string {
  if (err instanceof CapacityApiError) {
    if (err.code === "POD_IN_USE") {
      return "That worker is running a test — abort the run first.";
    }
    if (err.code === "POD_BOUND_ELSEWHERE") {
      const other = boundGroupOf(err);
      return other
        ? `That worker is already declared to group "${other}" — release it there first.`
        : "That worker is already declared to another group — release it there first.";
    }
    if (err.code === "CAPACITY_REGION_NOT_FOUND") {
      return "The group has no reservation on this cluster yet — attach it and reserve capacity first.";
    }
    if (err.code === "APPLICATION_CAPACITY_EXCEEDED" || err.code === "GROUP_CAPACITY_EXCEEDED") {
      return "The group's reservation on this cluster is full — raise it or drain a worker first.";
    }
    return err.message;
  }
  return err instanceof Error ? err.message : String(err);
}
