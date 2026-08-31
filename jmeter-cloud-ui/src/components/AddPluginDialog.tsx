import { useRef, useState } from "react";
import { InfoTip } from "./InfoTip";

import { blobsApi } from "../api/blobs";
import { PluginApiError, pluginsApi, type PluginSummary } from "../api/plugins";
import { Modal } from "./Modal";

/**
 * Upload-and-register a library plugin (UX-DYNAMICS T3). Two steps behind
 * one submit: the file becomes a document-service blob (`X-Type: plugin`),
 * then `POST /api/v1/plugins` registers it; on a 409 the hub deletes the
 * orphan blob itself, so this dialog only reports the collision.
 */
export interface AddPluginDialogProps {
  onAdded: (plugin: PluginSummary) => void;
  onClose: () => void;
}

type SubmitState =
  | { kind: "idle" }
  | { kind: "uploading"; pct: number }
  | { kind: "registering" }
  | { kind: "error"; message: string };

export function AddPluginDialog({ onAdded, onClose }: AddPluginDialogProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  // The blob a previous attempt already uploaded for THIS file — a register
  // retry after a network blip must not orphan a second copy.
  const uploadedRef = useRef<{ file: File; blobId: string } | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState("");
  const [version, setVersion] = useState("");
  const [description, setDescription] = useState("");
  const [state, setState] = useState<SubmitState>({ kind: "idle" });

  const busy = state.kind === "uploading" || state.kind === "registering";
  const canSubmit = file != null && name.trim().length > 0 && version.trim().length > 0 && !busy;

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0];
    e.target.value = ""; // reset so re-picking the same file fires onChange
    if (!f) return;
    setFile(f);
    setState({ kind: "idle" });
    if (!name.trim()) setName(f.name.replace(/\.(jar|zip)$/i, ""));
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit || !file) return;
    try {
      let blobId: string;
      if (uploadedRef.current && uploadedRef.current.file === file) {
        blobId = uploadedRef.current.blobId;
      } else {
        setState({ kind: "uploading", pct: 0 });
        const blob = await blobsApi.upload(file, {
          type: "plugin",
          name: file.name,
          onProgress: (sent, total) =>
            setState({ kind: "uploading", pct: total > 0 ? Math.round((sent / total) * 100) : 0 }),
        });
        uploadedRef.current = { file, blobId: blob.blobId };
        blobId = blob.blobId;
      }
      setState({ kind: "registering" });
      const created = await pluginsApi.create({
        name: name.trim(),
        version: version.trim(),
        blobId,
        description: description.trim() || undefined,
      });
      onAdded(created);
    } catch (err: unknown) {
      if (err instanceof PluginApiError && err.code.startsWith("PLUGIN_") && err.existing) {
        // On a 409 the hub deletes the orphan blob — the cached id is dead.
        uploadedRef.current = null;
      }
      if (err instanceof PluginApiError && err.code === "PLUGIN_NAME_TAKEN" && err.existing) {
        setState({
          kind: "error",
          message: `'${name.trim()}' already exists at v${err.existing.version} — delete the existing plugin first to upload a new version.`,
        });
      } else if (err instanceof PluginApiError && err.code === "PLUGIN_CONTENT_DUPLICATE" && err.existing) {
        setState({
          kind: "error",
          message: `This exact file is already registered as ${err.existing.name}@${err.existing.version}.`,
        });
      } else {
        setState({ kind: "error", message: err instanceof Error ? err.message : String(err) });
      }
    }
  }

  return (
    <Modal
      title="Add plugin"
      infoTip="Registers one JMeter plugin jar — or a zip bundle of the plugin plus its dependency jars — in the shared library; dependency resolution is the uploader's job."
      infoTipExample="jpgc-casutg → casutg-plugin-and-deps.zip"
      width="form"
      onClose={onClose}
      closeDisabled={busy}
    >
      <form onSubmit={submit} className="modal__body createApp" noValidate>
        <div className="formField">
          <label htmlFor="pluginFile">File *</label>
          <input
            ref={inputRef}
            id="pluginFile"
            type="file"
            accept=".jar,.zip"
            className="visuallyHidden"
            onChange={handleFileChange}
            disabled={busy}
          />
          <div>
            <button
              type="button"
              className="btn"
              onClick={() => inputRef.current?.click()}
              disabled={busy}
            >
              {state.kind === "uploading"
                ? `Uploading… ${state.pct}%`
                : file ? `Change file (${file.name})` : "Choose a .jar or .zip"}
            </button>
          </div>
          <small>A single plugin jar, or a zip of the plugin + its dependency jars.</small>
        </div>

        <div className="formField">
          <div className="formField__labelRow">
            <label htmlFor="pluginName">Name *</label>
            <InfoTip label="About plugin name">
              One version per name across the whole library — a duplicate
              upload is rejected with the existing version.
            </InfoTip>
          </div>
          <input
            id="pluginName"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="jpgc-casutg"
            maxLength={128}
            required
            disabled={busy}
          />
        </div>

        <div className="formField">
          <label htmlFor="pluginVersion">Version *</label>
          <input
            id="pluginVersion"
            type="text"
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            placeholder="3.1"
            maxLength={64}
            required
            disabled={busy}
          />
        </div>

        <div className="formField">
          <label htmlFor="pluginDescription">Description</label>
          <textarea
            id="pluginDescription"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="optional — what this plugin provides, who needs it"
            rows={3}
            maxLength={512}
            disabled={busy}
          />
        </div>

        {state.kind === "error" && (
          <div className="formError" role="alert">{state.message}</div>
        )}

        <Modal.Footer>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button
            type="submit"
            className="btn btn--primary"
            disabled={!canSubmit}
            aria-busy={busy}
          >
            {state.kind === "uploading" ? "Uploading…"
              : state.kind === "registering" ? "Registering…"
              : "Add plugin"}
          </button>
        </Modal.Footer>
      </form>
    </Modal>
  );
}
