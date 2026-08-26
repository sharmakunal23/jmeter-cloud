import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import {
  applicationsApi,
  ApplicationApiError,
  type Application,
  type CreateApplicationRequest,
} from "../api/applications";

/* D-Capacity v2 polish — capacity is sponsor-controlled, NOT operator-set.
 * The form no longer collects per-region maxAvailable. Newly-registered
 * apps land at 0 across the default regions (us-east + us-west, seeded
 * by ApplicationController on POST). Operator's only path to a non-zero
 * ceiling is the "Request more capacity" workflow on /capacity. */

/**
 * D-AppRegistry — modal form for registering a new application.
 *
 * <p>Mirrors the controller-side validation:
 * <ul>
 *   <li>{@code name} required, DNS-friendly (lowercase, digits, hyphens, underscores).</li>
 *   <li>{@code sealId} optional, ≤ 128 chars.</li>
 *   <li>{@code description} optional, ≤ 512 chars.</li>
 *   <li>{@code healthEndpoints} optional, max 8 entries; each must be http(s).</li>
 * </ul>
 *
 * <p>Server-side errors (409 name-taken, 400 validation) are surfaced
 * inline. On success the parent's {@code onCreated} callback fires
 * with the persisted record so the list can refresh in lockstep.
 */

const NAME_PATTERN = /^[a-z0-9]([-a-z0-9_]{0,62}[a-z0-9])?$/;
const MAX_HEALTH_ENDPOINTS = 8;

export interface CreateApplicationDialogProps {
  /**
   * Operator-driven mode. {@code "create"} POSTs a new app; {@code "edit"}
   * PUTs over the {@code initial} app. Defaults to "create" when the
   * prop is omitted so existing call sites keep working.
   */
  mode?: "create" | "edit";
  /** Required when mode === "edit". */
  initial?: Application;
  onCreated: (app: Application) => void;
  /**
   * Fired after a successful soft-delete (edit mode only). The caller should
   * close the dialog and navigate away — the app is now hidden, so the
   * current application page no longer has anything to show.
   */
  onDeleted?: () => void;
  onClose: () => void;
}

export function CreateApplicationDialog({
  mode = "create", initial, onCreated, onDeleted, onClose,
}: CreateApplicationDialogProps) {
  const isEdit = mode === "edit" && initial != null;
  const [name, setName] = useState(initial?.name ?? "");
  const [sealId, setSealId] = useState(initial?.sealId ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [healthEndpoints, setHealthEndpoints] = useState<string[]>(initial?.healthEndpoints ?? []);
  // When true, scheduled DRAIN_REGION jobs skip this app.
  const [alwaysOn, setAlwaysOn] = useState<boolean>(initial?.alwaysOn ?? false);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  // Soft-delete (edit mode): a two-step confirm that explains the semantics
  // before the destructive call. `deleting`/`deleteError` mirror the
  // submitting/serverError pair for the delete path.
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // ESC closes; mirrors the per-pod drawer's dismiss UX.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const trimmedName = name.trim();
  const nameError =
    trimmedName === "" ? "name is required"
    : !NAME_PATTERN.test(trimmedName) ? "lowercase / digits / - / _ only; can't start or end with - or _"
    : null;
  const endpointErrors = healthEndpoints.map((url) => {
    const t = url.trim();
    if (!t) return null; // blank rows ignored
    if (t.length > 256) return "URL too long (≤ 256)";
    if (!t.startsWith("http://") && !t.startsWith("https://")) return "must start with http:// or https://";
    try { new URL(t); } catch { return "not a valid URL"; }
    return null;
  });
  const allEndpointsValid = endpointErrors.every((e) => e === null);

  const canSubmit = !submitting && nameError === null && allEndpointsValid;

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setServerError(null);
    // Capacity is intentionally NOT in the request body — sponsor-controlled
    // post-D-Capacity-v2-polish; the backend ignores it on POST/PUT and
    // auto-seeds new apps at 0 across us-east + us-west on create.
    const body: CreateApplicationRequest = {
      name: trimmedName,
      sealId: sealId.trim() || undefined,
      description: description.trim() || undefined,
      healthEndpoints: healthEndpoints.map((u) => u.trim()).filter(Boolean),
      alwaysOn,
    };
    try {
      const result = isEdit
        ? await applicationsApi.update(initial!.applicationId, body)
        : await applicationsApi.create(body);
      onCreated(result);
    } catch (err) {
      if (err instanceof ApplicationApiError) {
        if (err.code === "APPLICATION_NAME_TAKEN") {
          setServerError(`An application named "${trimmedName}" already exists.`);
        } else if (err.code === "INVALID_REQUEST") {
          setServerError(err.message);
        } else {
          setServerError(`${err.code}: ${err.message}`);
        }
      } else {
        setServerError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (!isEdit) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await applicationsApi.delete(initial!.applicationId);
      onDeleted?.();
    } catch (err) {
      if (err instanceof ApplicationApiError && err.code === "APPLICATION_HAS_ACTIVE_RUNS") {
        setDeleteError("This application has active runs — abort them or let them finish before hiding it.");
      } else if (err instanceof ApplicationApiError) {
        setDeleteError(`${err.code}: ${err.message}`);
      } else {
        setDeleteError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setDeleting(false);
    }
  }

  function setEndpoint(idx: number, url: string) {
    setHealthEndpoints((prev) => prev.map((u, i) => (i === idx ? url : u)));
  }
  function removeEndpoint(idx: number) {
    setHealthEndpoints((prev) => prev.filter((_, i) => i !== idx));
  }
  function addEndpoint() {
    if (healthEndpoints.length >= MAX_HEALTH_ENDPOINTS) return;
    setHealthEndpoints((prev) => [...prev, ""]);
  }

  // ── Soft-delete confirmation (edit mode) ───────────────────────────
  if (isEdit && confirmingDelete) {
    return (
      <div className="modal__overlay" onClick={onClose}>
        <div
          className="modal modal--application"
          role="dialog"
          aria-label={`Delete application ${initial!.name}`}
          onClick={(e) => e.stopPropagation()}
        >
          <header className="modal__header">
            <div>
              <h3>Delete application</h3>
              <small className="ink-soft">
                Soft-delete — <span className="mono">{initial!.name}</span> will be hidden, not erased.
              </small>
            </div>
            <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close">×</button>
          </header>

          <div className="modal__body">
            <p>Soft-deleting <span className="mono">{initial!.name}</span> will remove it from
              the applications list, launcher, and capacity views.</p>
            <p className="ink-soft" style={{ margin: "0.5rem 0" }}>
              <strong>Retained:</strong> this app's run history, metrics, and uploaded files
              (test plans, data files, results). A future cleanup job will purge them.
            </p>

            {deleteError && (
              <div className="formError" role="alert">{deleteError}</div>
            )}

            <footer className="modal__footer">
              <button
                type="button"
                className="btn"
                onClick={() => { setConfirmingDelete(false); setDeleteError(null); }}
                disabled={deleting}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn--danger"
                onClick={handleDelete}
                disabled={deleting}
                aria-busy={deleting}
              >
                {deleting ? "Deleting…" : "Soft Delete Application"}
              </button>
            </footer>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="modal__overlay" onClick={onClose}>
      <div
        className="modal modal--application"
        role="dialog"
        aria-label={isEdit ? `Edit application ${initial!.name}` : "Register a new application"}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3>{isEdit ? "Edit application" : "Register application"}</h3>
            <small className="ink-soft">
              {isEdit
                ? <>Updating <span className="mono">{initial!.name}</span>. Capacity rows
                   replaced wholesale on save; health snapshot is owned by the poller
                   and isn't touched.</>
                : "Persisted in the global registry. Health endpoints, when supplied, are polled every 30 seconds."}
            </small>
          </div>
          <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close">×</button>
        </header>

        <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
          <div className="formField">
            <label htmlFor="appName">Name *</label>
            <input
              id="appName"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="checkout-svc"
              maxLength={64}
              autoFocus={!isEdit}
              required
              disabled={isEdit}
              aria-invalid={nameError != null}
            />
            <small>
              {isEdit
                ? "Locked — name is the cross-table key for runs + blobs; renaming would orphan history."
                : <>Used as the cross-table key. Lowercase, digits, <code>-</code>, <code>_</code>; max 64 chars.</>}
            </small>
            {nameError && name && (
              <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>
                {nameError}
              </p>
            )}
          </div>

          <div className="formField">
            <label htmlFor="appSealId">Seal ID</label>
            <input
              id="appSealId"
              type="text"
              value={sealId}
              onChange={(e) => setSealId(e.target.value)}
              placeholder="optional internal catalog ID"
              maxLength={128}
            />
          </div>

          <div className="formField">
            <label htmlFor="appDescription">Description</label>
            <textarea
              id="appDescription"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="optional — what this app does, who owns it, etc."
              rows={3}
              maxLength={512}
            />
          </div>

          {/* Capacity is intentionally NOT in this form post D-Capacity v2 polish.
              Compute costs money; capacity is sponsor-controlled. New apps land
              at 0 across us-east + us-west; the operator's only path to a
              non-zero ceiling is the "Request more capacity" workflow on
              the Capacity tab (auto-routes through the sponsor for approval). */}
          {!isEdit && (
            <p className="ink-soft" style={{ fontSize: "0.82rem", margin: "0.5rem 0 1rem" }}>
              <strong>Capacity</strong> seeds at <code>0</code> for us-east + us-west;
              request a non-zero ceiling on the{" "}
              <Link to="/capacity">Capacity</Link> tab once registered.
            </p>
          )}

          <div className="formField">
            <label htmlFor="appAlwaysOn" style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
              <input
                id="appAlwaysOn"
                type="checkbox"
                checked={alwaysOn}
                onChange={(e) => setAlwaysOn(e.target.checked)}
                style={{ width: "auto" }}
              />
              Always on (production-like)
            </label>
            <small>
              When checked, scheduled <strong>drain-region</strong> automation jobs skip this app —
              its workers are never auto-drained for overnight cost saving. Provision + launch jobs are unaffected.
            </small>
          </div>

          <fieldset className="createApp__endpoints">
            <legend>Health-check endpoints</legend>
            <small className="ink-soft">
              Optional. Max {MAX_HEALTH_ENDPOINTS} URLs. Each gets a GET every ~30s; aggregate
              status (HEALTHY / DEGRADED / UNHEALTHY) shows on the application card.
            </small>
            {healthEndpoints.length === 0 && (
              <p className="ink-soft" style={{ fontSize: "0.82rem", margin: "0.4rem 0" }}>
                No endpoints configured — application status will stay UNKNOWN.
              </p>
            )}
            {healthEndpoints.map((url, idx) => (
              <div key={idx} className="createApp__endpointRow">
                <input
                  type="url"
                  value={url}
                  onChange={(e) => setEndpoint(idx, e.target.value)}
                  placeholder="https://app.example.com/healthz"
                  aria-label={`health endpoint ${idx + 1}`}
                  aria-invalid={endpointErrors[idx] != null}
                />
                <button
                  type="button"
                  className="btn btn--ghost createApp__endpointRemove"
                  onClick={() => removeEndpoint(idx)}
                  aria-label={`remove health endpoint ${idx + 1}`}
                  title="Remove endpoint"
                >
                  ×
                </button>
                {endpointErrors[idx] && (
                  <span className="text--error">{endpointErrors[idx]}</span>
                )}
              </div>
            ))}
            {healthEndpoints.length < MAX_HEALTH_ENDPOINTS && (
              <button type="button" className="btn btn--ghost btn--sm createApp__addEndpoint" onClick={addEndpoint}>
                + Add endpoint
              </button>
            )}
          </fieldset>

          {serverError && (
            <div className="formError" role="alert">{serverError}</div>
          )}

          <footer className="modal__footer">
            {isEdit && (
              <button
                type="button"
                className="btn btn--ghost text--error"
                style={{ marginRight: "auto" }}
                onClick={() => setConfirmingDelete(true)}
              >
                Delete application
              </button>
            )}
            <button type="button" className="btn" onClick={onClose}>Cancel</button>
            <button
              type="submit"
              className="btn btn--primary"
              disabled={!canSubmit}
              aria-busy={submitting}
            >
              {submitting
                ? (isEdit ? "Saving…" : "Registering…")
                : (isEdit ? "Save changes" : "Register")}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
