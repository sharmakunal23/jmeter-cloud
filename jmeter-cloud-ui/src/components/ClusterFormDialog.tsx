import { useMemo, useState } from "react";

import {
  ClusterApiError,
  clustersApi,
  type ClusterCheck,
  type ClusterStatus,
} from "../api/clusters";
import { InfoTip } from "./InfoTip";
import { Modal } from "./Modal";

/**
 * Add / edit a cluster (CLUSTER-CAPACITY). Registration runs the hub's
 * validation chain server-side; a `422` carries the whole ✓/✗ checklist,
 * rendered inline so the operator sees exactly what is wrong (endpoint
 * unreachable, region-id mismatch, missing worker image, RBAC, quota). The
 * id is immutable once registered — the locked-field pattern from the group
 * editor.
 */

const REGION_ID_PATTERN = /^[a-z0-9]([-a-z0-9]{0,18}[a-z0-9])?$/;

export interface ClusterFormDialogProps {
  /** Present = edit mode (id locked, URL change re-validates). */
  existing?: ClusterStatus;
  onClose: () => void;
  /** Called after a successful save with a toast-ready message. */
  onSaved: (message: string) => void;
}

export function ClusterFormDialog({ existing, onClose, onSaved }: ClusterFormDialogProps) {
  const editing = existing != null;
  const [region, setRegion] = useState(existing?.region ?? "");
  const [label, setLabel] = useState(existing?.label ?? "");
  const [regionalUrl, setRegionalUrl] = useState(existing?.regionalUrl ?? "");
  const [maxWorkers, setMaxWorkers] = useState(String(existing?.maxWorkers ?? 20));
  const [busy, setBusy] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [checks, setChecks] = useState<ClusterCheck[] | null>(null);
  const [touched, setTouched] = useState(false);

  const fieldErrors = useMemo(() => {
    const errors: { region?: string; label?: string; regionalUrl?: string; maxWorkers?: string } = {};
    if (!editing && !REGION_ID_PATTERN.test(region)) {
      errors.region = "Lowercase letters, digits and hyphens, max 20 chars — it must equal the regional's REGION.";
    }
    if (!label.trim() || label.trim().length > 255) {
      errors.label = "A display name is required (max 255 chars).";
    }
    if (!/^https?:\/\/.+/.test(regionalUrl.trim())) {
      errors.regionalUrl = "An http(s) URL is required.";
    }
    const n = Number(maxWorkers);
    if (!Number.isInteger(n) || n < 1 || n > 20) {
      errors.maxWorkers = "1 to 20 — a cluster hosts at most 20 workers.";
    }
    return errors;
  }, [editing, region, label, regionalUrl, maxWorkers]);

  const invalid = Object.keys(fieldErrors).length > 0;

  const footprint = existing?.capabilities;
  const footprintHint = footprint?.workerMemoryMb
    ? `${maxWorkers || "N"} workers × (${Math.round(Number(footprint.workerMemoryMb) / 1024)} Gi memory` +
      (footprint.workerEphemeralStorage ? ` + ${footprint.workerEphemeralStorage} disk` : "") + ") each"
    : "e.g. 20 workers × 9 GB (4 Gi memory + 5 Gi results disk) = 180 GB of a 200 GB cluster grant";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (invalid || busy) return;
    setBusy(true);
    setServerError(null);
    setChecks(null);
    try {
      if (editing) {
        await clustersApi.update(existing.region, {
          label: label.trim(),
          regionalUrl: regionalUrl.trim(),
          maxWorkers: Number(maxWorkers),
        });
        onSaved(`Cluster ${label.trim()} updated.`);
      } else {
        const created = await clustersApi.register({
          region: region.trim(),
          label: label.trim(),
          regionalUrl: regionalUrl.trim(),
          maxWorkers: Number(maxWorkers),
        });
        setChecks(created.checks ?? null);
        onSaved(`Cluster ${label.trim()} registered — every validation check passed.`);
      }
      onClose();
    } catch (err) {
      if (err instanceof ClusterApiError) {
        setServerError(err.message);
        setChecks(err.checks ?? null);
      } else {
        setServerError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={editing ? `Edit cluster ${existing.label}` : "Add a cluster"}
      infoTip={
        "A cluster is a Kubernetes data center fronted by one jmeter-regional-orchestrator; " +
        "registration only succeeds after the hub proves the endpoint answers, agrees on the id, " +
        "and can actually create worker pods."
      }
      infoTipExample="id na-east ↔ the regional deployed with REGION=na-east"
      width="form"
      onClose={onClose}
      closeDisabled={busy}
    >
      <form className="modal__body createApp" onSubmit={submit} noValidate>
        <div className="formField">
          <label htmlFor="clusterRegion">Cluster id</label>
          <input
            id="clusterRegion"
            value={region}
            disabled={editing || busy}
            aria-invalid={touched && !!fieldErrors.region}
            onChange={(e) => setRegion(e.target.value)}
            placeholder="na-east"
          />
          {editing ? (
            <small>Locked. The id is the placement axis on runs and workers — register a new cluster to rename.</small>
          ) : touched && fieldErrors.region ? (
            <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>{fieldErrors.region}</p>
          ) : (
            <small>Must equal the regional orchestrator&apos;s REGION env — validated on submit.</small>
          )}
        </div>

        <div className="formField">
          <label htmlFor="clusterLabel">Display name</label>
          <input
            id="clusterLabel"
            value={label}
            disabled={busy}
            aria-invalid={touched && !!fieldErrors.label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="NA East (mt-d2)"
          />
          {touched && fieldErrors.label && (
            <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>{fieldErrors.label}</p>
          )}
        </div>

        <div className="formField">
          <label htmlFor="clusterUrl">Regional orchestrator URL</label>
          <input
            id="clusterUrl"
            value={regionalUrl}
            disabled={busy}
            aria-invalid={touched && !!fieldErrors.regionalUrl}
            onChange={(e) => setRegionalUrl(e.target.value)}
            placeholder="https://jmeter-regional-orchestrator.apps.mt-d2.example.net"
          />
          {touched && fieldErrors.regionalUrl ? (
            <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>{fieldErrors.regionalUrl}</p>
          ) : (
            <small>
              The cluster-host of the regional&apos;s deployment.
              {editing ? " Changing it re-runs the full validation." : ""}
            </small>
          )}
        </div>

        <div className="formField">
          <div className="formField__labelRow">
            <label htmlFor="clusterMaxWorkers">Max workers</label>
            <InfoTip label="How the ceiling is sized" example={footprintHint}>
              The most workers this cluster may hold across every group&apos;s reservation —
              capped at 20, the namespace grant divided by the per-worker footprint.
            </InfoTip>
          </div>
          <input
            id="clusterMaxWorkers"
            type="number"
            min={1}
            max={20}
            value={maxWorkers}
            disabled={busy}
            aria-invalid={touched && !!fieldErrors.maxWorkers}
            onChange={(e) => setMaxWorkers(e.target.value)}
            style={{ maxWidth: "8rem" }}
          />
          {touched && fieldErrors.maxWorkers && (
            <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>{fieldErrors.maxWorkers}</p>
          )}
        </div>

        {checks && (
          <ul className="clusterChecks" aria-label="Validation checks">
            {checks.map((c) => (
              <li key={c.name} className={c.ok ? "clusterChecks__row--ok" : "clusterChecks__row--fail"}>
                <span aria-hidden="true">{c.ok ? "✓" : "✗"}</span>
                <span>
                  <strong>{describeCheck(c.name)}</strong>
                  <small>{c.detail}</small>
                </span>
              </li>
            ))}
          </ul>
        )}
        {serverError && (
          <div className="formError" role="alert">{serverError}</div>
        )}

        <Modal.Footer>
          <button type="button" className="btn btn--ghost" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? "Validating…" : editing ? "Save changes" : "Validate and add"}
          </button>
        </Modal.Footer>
      </form>
    </Modal>
  );
}

function describeCheck(name: string): string {
  switch (name) {
    case "endpointReachable":  return "Regional orchestrator reachable";
    case "regionMatches":      return "Cluster id matches the regional";
    case "imageConfigured":    return "Worker image configured";
    case "rbacPods":           return "Can manage worker pods (RBAC)";
    case "rbacPodsLog":        return "Can read pod logs (RBAC)";
    case "rbacResourceQuotas": return "Can read the namespace quota (RBAC)";
    case "quotaHeadroom":      return "Quota admits at least one worker";
    case "provisioningCheck":  return "Provisioning dry run";
    default:                   return name;
  }
}
