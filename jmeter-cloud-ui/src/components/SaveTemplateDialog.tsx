import { useState } from "react";

import { templatesApi, type TemplateBody } from "../api/templates";
import { Modal } from "./Modal";

/**
 * D5 — Save Template modal. Captures a name + optional description,
 * then POSTs a new blob with X-Type=template containing the launcher's
 * current form state. On success, closes via {@code onSaved} with the
 * new blobId so the parent can show a "saved" toast or navigate to
 * /templates.
 */

export interface SaveTemplateDialogProps {
  body: TemplateBody;
  onSaved: (blobId: string) => void;
  onClose: () => void;
}

export function SaveTemplateDialog({ body, onSaved, onClose }: SaveTemplateDialogProps) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const trimmedName = name.trim();
  const nameError = trimmedName === "" ? "name is required" : null;
  const canSubmit = !submitting && nameError === null;

  // Count workers carrying at least one per-worker -J override, so the
  // operator can see those parameters ARE part of the snapshot (each
  // allocation entry's perNodeProperties[i] is the i-th worker's prop map).
  const workersWithOverrides = body.fleetAllocation.reduce(
    (acc, e) =>
      acc + (e.perNodeProperties ?? []).filter((p) => Object.keys(p).length > 0).length,
    0,
  );

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setServerError(null);
    try {
      const blobId = await templatesApi.save(body, {
        name: trimmedName,
        description: description.trim() || undefined,
      });
      onSaved(blobId);
    } catch (err) {
      setServerError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title="Save as template"
      infoTip="Saves the full launcher state — plan, data files, fleet with per-worker parameters, global properties, plugins, and save-results — as a reusable template."
      width="form"
      onClose={onClose}
      closeDisabled={submitting}
    >
      <form onSubmit={submit} className="modal__body createApp" noValidate>
        <div className="formField">
          <label htmlFor="tplName">Template name *</label>
          <input
            id="tplName"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="checkout-baseline · 5pods · weekly"
            maxLength={64}
            autoFocus
            required
            aria-invalid={nameError != null}
          />
          {nameError && name && (
            <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>
              {nameError}
            </p>
          )}
        </div>
        <div className="formField">
          <label htmlFor="tplDescription">Description</label>
          <textarea
            id="tplDescription"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="optional — what this template is for, when to use it"
            rows={3}
            maxLength={512}
          />
        </div>
        <div className="formField">
          <label>Snapshot summary</label>
          <ul className="ink-soft" style={{ fontSize: "0.78rem", margin: 0, paddingLeft: "1.2rem" }}>
            <li>Application: <span className="mono">{body.application || "(unset)"}</span></li>
            <li>Test plan: <span className="mono">{body.testPlanBlobId.slice(0, 8)}…</span></li>
            {body.dataFilesBlobId && (
              <li>Data files: <span className="mono">{body.dataFilesBlobId.slice(0, 8)}…</span></li>
            )}
            <li>Fleet: {body.fleetAllocation.length === 0 ? "none" :
              body.fleetAllocation.map((e) => `${e.region}×${e.count}`).join(", ")}</li>
            {workersWithOverrides > 0 && (
              <li>Per-worker parameters: {workersWithOverrides} worker{workersWithOverrides === 1 ? "" : "s"} with overrides</li>
            )}
            {body.globalProperties && Object.keys(body.globalProperties).length > 0 && (
              <li>Global properties: {Object.keys(body.globalProperties).length}</li>
            )}
            {(body.pluginIds?.length ?? 0) > 0 && (
              <li>Plugins: {body.pluginIds!.length}</li>
            )}
            <li>Save results: {body.saveResults ? "on" : "off"}</li>
          </ul>
        </div>
        {serverError && <div className="formError" role="alert">{serverError}</div>}
        <Modal.Footer>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button
            type="submit"
            className="btn btn--primary"
            disabled={!canSubmit}
            aria-busy={submitting}
          >
            {submitting ? "Saving…" : "Save template"}
          </button>
        </Modal.Footer>
      </form>
    </Modal>
  );
}
