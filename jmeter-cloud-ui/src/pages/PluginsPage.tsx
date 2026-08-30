import { useEffect, useState } from "react";

import { PluginApiError, pluginsApi, type PluginSummary } from "../api/plugins";
import { AddPluginDialog } from "../components/AddPluginDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { InfoTip } from "../components/InfoTip";
import { ToastView, useToast } from "../components/Toast";
import { formatRelative } from "../lib/time";

/**
 * The global plugin library (UX-DYNAMICS T3) — one flat page, application-
 * agnostic: what's here is selectable in every launcher. One version per
 * plugin name; upgrading is delete + re-upload.
 */

type ListState =
  | { status: "loading" }
  | { status: "ok"; items: PluginSummary[] }
  | { status: "error"; message: string };

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function PluginsPage() {
  const [list, setList] = useState<ListState>({ status: "loading" });
  const [addOpen, setAddOpen] = useState(false);
  const [toDelete, setToDelete] = useState<PluginSummary | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const { toast, showToast, dismiss } = useToast();

  async function refresh() {
    try {
      const items = await pluginsApi.list();
      setList({ status: "ok", items });
    } catch (err: unknown) {
      setList({ status: "error", message: err instanceof Error ? err.message : String(err) });
    }
  }

  useEffect(() => {
    const ctl = new AbortController();
    pluginsApi.list(ctl.signal)
      .then((items) => setList({ status: "ok", items }))
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setList({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, []);

  async function confirmDelete() {
    if (!toDelete) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await pluginsApi.delete(toDelete.pluginId);
      showToast({ variant: "ok", text: `Plugin ${toDelete.name}@${toDelete.version} deleted.` });
      setToDelete(null);
      void refresh();
    } catch (err: unknown) {
      if (err instanceof PluginApiError && err.code === "PLUGIN_IN_USE") {
        setDeleteError("This plugin is referenced by an active run — wait for the run to finish, then delete.");
      } else {
        setDeleteError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setDeleteBusy(false);
    }
  }

  return (
    <section>
      <header className="pageHeader">
        <div className="formField__labelRow">
          <h1>Plugins</h1>
          <InfoTip label="About plugins">
            Shared JMeter plugin jars, selectable per run in the launcher — one
            version per plugin name.
          </InfoTip>
        </div>
        <button type="button" className="btn btn--primary" onClick={() => setAddOpen(true)}>
          + Add plugin
        </button>
      </header>

      {list.status === "loading" && <p className="ink-soft">Loading plugins…</p>}
      {list.status === "error" && (
        <div className="formError" role="alert">{list.message}</div>
      )}
      {list.status === "ok" && list.items.length === 0 && (
        <div className="emptyState">
          <p>No plugins yet.</p>
          <p className="ink-soft">
            Upload a JMeter plugin jar (or a zip bundle of the plugin plus its
            dependency jars) once, and every run can stage it.
          </p>
        </div>
      )}
      {list.status === "ok" && list.items.length > 0 && (
        <table className="runsTable">
          <thead>
            <tr>
              <th>Name</th>
              <th>Version</th>
              <th>File</th>
              <th>Size</th>
              <th>Uploaded</th>
              <th>By</th>
              <th><span className="visuallyHidden">actions</span></th>
            </tr>
          </thead>
          <tbody>
            {list.items.map((p) => (
              <tr key={p.pluginId}>
                <td className="mono" title={p.description ?? undefined}>{p.name}</td>
                <td>{p.version}</td>
                <td className="mono">{p.fileName}</td>
                <td>{formatSize(p.sizeBytes)}</td>
                <td>{formatRelative(p.createdAt)}</td>
                <td>{p.createdBy ?? "—"}</td>
                <td>
                  <button
                    type="button"
                    className="btn btn--danger btn--sm"
                    onClick={() => { setToDelete(p); setDeleteError(null); }}
                    aria-label={`delete plugin ${p.name}`}
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {addOpen && (
        <AddPluginDialog
          onAdded={(p) => {
            setAddOpen(false);
            showToast({ variant: "ok", text: `Plugin ${p.name}@${p.version} added.` });
            void refresh();
          }}
          onClose={() => setAddOpen(false)}
        />
      )}

      {toDelete && (
        <ConfirmDialog
          title="Delete plugin?"
          infoTip="Runs already launched keep their staged copy; future runs can no longer select it."
          danger
          busy={deleteBusy}
          confirmLabel="Delete plugin"
          onConfirm={() => { void confirmDelete(); }}
          onCancel={() => { setToDelete(null); setDeleteError(null); }}
          body={
            <p>
              <span className="mono">{toDelete.name}@{toDelete.version}</span> will be removed
              from the library.
            </p>
          }
        >
          {deleteError && <div className="formError" role="alert">{deleteError}</div>}
        </ConfirmDialog>
      )}

      <ToastView toast={toast} onDismiss={dismiss} />
    </section>
  );
}
