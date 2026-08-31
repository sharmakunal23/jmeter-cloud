import { useEffect, useMemo, useState } from "react";

import { PluginApiError, pluginsApi, type PluginSummary } from "../api/plugins";
import { AddPluginDialog } from "../components/AddPluginDialog";
import { AppListToolbar } from "../components/AppListToolbar";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { InfoTip } from "../components/InfoTip";
import { DataList } from "../components/DataList";
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
  const [search, setSearch] = useState("");
  const [addOpen, setAddOpen] = useState(false);
  const [toDelete, setToDelete] = useState<PluginSummary | null>(null);
  /** A bulk delete awaiting confirmation. */
  const [bulkDelete, setBulkDelete] = useState<PluginSummary[] | null>(null);
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

  // Same filter affordance as every other tab: '/' focuses, narrows by name
  // (version and file name match too — both are how operators know a jar).
  const items = list.status === "ok" ? list.items : [];
  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return items;
    return items.filter((p) =>
      p.name.toLowerCase().includes(needle)
      || p.version.toLowerCase().includes(needle)
      || p.fileName.toLowerCase().includes(needle));
  }, [items, search]);

  /** Delete a selection. Independent calls, so a partial failure keeps what landed. */
  async function runBulkDelete() {
    const rows = bulkDelete ?? [];
    const results = await Promise.allSettled(rows.map((r) => pluginsApi.delete(r.pluginId)));
    setBulkDelete(null);
    const failed = results.filter((r) => r.status === "rejected").length;
    showToast(failed === 0
      ? { variant: "ok", text: `${rows.length} plugin(s) deleted.` }
      : { variant: "warn", text: `${rows.length - failed} of ${rows.length} deleted`,
          detail: `${failed} failed — a plugin in use by a live run cannot be removed.` });
    void refresh();
  }

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

      <AppListToolbar
        noun="plugin"
        search={search}
        onSearchChange={setSearch}
        count={filtered.length}
        total={items.length}
        loading={list.status === "loading"}
      />

      {list.status === "error" && (
        <div className="formError" role="alert">{list.message}</div>
      )}

      <DataList<PluginSummary>
        label="Plugins"
        loading={list.status === "loading"}
        rows={filtered}
        rowKey={(p) => p.pluginId}
        itemNoun="plugins"
        resetKey={search}
        empty={items.length === 0 ? (
          <>
            <strong>No plugins yet.</strong>
            <div>Upload a JMeter plugin jar — or a zip of the plugin plus its dependency
                 jars — once, and every run can stage it.</div>
          </>
        ) : <>No plugins match &quot;{search}&quot;.</>}
        columns={[
          { key: "name", header: "Name",
            cell: (p) => <span className="mono" title={p.description ?? undefined}>{p.name}</span> },
          { key: "version", header: "Version", cell: (p) => p.version },
          { key: "file", header: "File", cell: (p) => <span className="mono">{p.fileName}</span> },
          { key: "size", header: "Size", className: "dataList__num", cell: (p) => formatSize(p.sizeBytes) },
          { key: "uploaded", header: "Uploaded", cell: (p) => formatRelative(p.createdAt) },
          { key: "by", header: "By", cell: (p) => p.createdBy ?? "—" },
          { key: "actions", header: <span className="visuallyHidden">actions</span>,
            className: "runsTable__actions",
            cell: (p) => (
              <button type="button" className="btn btn--ghost btn--sm text--error"
                      onClick={() => { setToDelete(p); setDeleteError(null); }}
                      aria-label={`delete plugin ${p.name}`}>
                Delete
              </button>
            ) },
        ]}
        bulkActions={[
          { label: "Delete", danger: true, onRun: (rows) => setBulkDelete(rows) },
        ]}
      />

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

      {bulkDelete && (
        <ConfirmDialog
          title={`Delete ${bulkDelete.length} plugin(s)?`}
          body={<>Runs already launched keep the jars they staged; new runs can no longer
                select these. This can&apos;t be undone.</>}
          confirmLabel={`Delete ${bulkDelete.length}`}
          danger
          onConfirm={() => void runBulkDelete()}
          onCancel={() => setBulkDelete(null)}
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
