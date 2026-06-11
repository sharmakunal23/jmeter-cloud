import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { formatRelative } from "../lib/time";

import { applicationsApi, type Application } from "../api/applications";
import { templatesApi, type TemplateSummary } from "../api/templates";

/**
 * Phase IA-Templates (2026-05-13) — per-application templates drill-in.
 * Reached via `/templates/{appName}` (the click target on every row of
 * `<TemplatesListPage>`). The body is the existing grid / list toggle
 * preserved from the old flat `<TemplatesPage>`, scoped to a single
 * application.
 *
 * <p>Header follows page-rule #7 (header navigation continuity):
 * Back → /templates · Open Application · Launch a Run.
 *
 * <p>Delete uses a centered confirmation modal (page-rule #8 — modals
 * not drawers, modal not browser confirm()).
 */

type ViewMode = "grid" | "list";
const VIEW_MODE_STORAGE_KEY = "jmeterCloud.templates.viewMode";

function readStoredViewMode(): ViewMode {
  try {
    const v = localStorage.getItem(VIEW_MODE_STORAGE_KEY);
    return v === "grid" ? "grid" : "list";
  } catch { return "list"; }
}

type AppLookup =
  | { status: "loading" }
  | { status: "ok"; app: Application }
  | { status: "notFound" }
  | { status: "error"; message: string };

type TemplatesState =
  | { status: "loading" }
  | { status: "ok"; templates: TemplateSummary[] }
  | { status: "error"; message: string };

export function TemplatesDetailPage() {
  const { appName: appNameParam = "" } = useParams<{ appName: string }>();

  const [appLookup, setAppLookup] = useState<AppLookup>({ status: "loading" });
  const [templates, setTemplates] = useState<TemplatesState>({ status: "loading" });
  const [viewMode, setViewMode] = useState<ViewMode>(readStoredViewMode);
  const [pendingDelete, setPendingDelete] = useState<TemplateSummary | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    try { localStorage.setItem(VIEW_MODE_STORAGE_KEY, viewMode); }
    catch { /* ignore */ }
  }, [viewMode]);

  useEffect(() => {
    const ctl = new AbortController();
    applicationsApi.list(ctl.signal)
      .then((apps) => {
        const app = apps.find((a) => a.name === appNameParam);
        setAppLookup(app ? { status: "ok", app } : { status: "notFound" });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setAppLookup({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [appNameParam]);

  const refreshTemplates = useCallback((signal?: AbortSignal) => {
    setTemplates({ status: "loading" });
    templatesApi.list(signal)
      .then((all) => {
        const scoped = all.filter((t) => t.application === appNameParam);
        setTemplates({ status: "ok", templates: scoped });
      })
      .catch((err: unknown) => {
        if (signal?.aborted) return;
        setTemplates({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
  }, [appNameParam]);

  useEffect(() => {
    const ctl = new AbortController();
    refreshTemplates(ctl.signal);
    return () => ctl.abort();
  }, [refreshTemplates]);

  async function confirmDelete() {
    if (!pendingDelete) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await templatesApi.delete(pendingDelete.blobId);
      setPendingDelete(null);
      refreshTemplates();
    } catch (err: unknown) {
      setDeleteError(err instanceof Error ? err.message : String(err));
    } finally {
      setDeleteBusy(false);
    }
  }

  if (appLookup.status === "loading") {
    return <p className="ink-soft">Loading templates for {appNameParam}…</p>;
  }
  if (appLookup.status === "error") {
    return <p className="text--error">{appLookup.message}</p>;
  }
  if (appLookup.status === "notFound") {
    return (
      <section className="capacityPage">
        <p className="text--error">
          Application <span className="mono">{appNameParam}</span> not found.
        </p>
        <p><Link to="/templates" className="btn btn--ghost">← Back to Templates</Link></p>
      </section>
    );
  }

  const { app } = appLookup;

  return (
    <section className="capacityPage capacityDetail templatesDetailPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <Link to="/templates" className="ink-soft" style={{ fontSize: "0.85rem" }}>← Templates</Link>
          <h1 className="capacityDetail__title"><span className="mono">{app.name}</span></h1>
        </div>
        <div className="capacityDetail__nav">
          <ViewModeToggle viewMode={viewMode} onChange={setViewMode} />
          <Link to={`/applications/${encodeURIComponent(app.name)}`} className="btn btn--ghost">
            Open Application →
          </Link>
          <Link
            to={`/applications/${encodeURIComponent(app.name)}/runs/new`}
            className="btn btn--primary"
          >
            Launch a Run →
          </Link>
        </div>
      </header>

      {templates.status === "loading" && <p className="ink-soft">Loading templates…</p>}
      {templates.status === "error" && <p className="text--error">{templates.message}</p>}

      {templates.status === "ok" && templates.templates.length === 0 && (
        <div className="emptyState">
          <p>No templates saved for <span className="mono">{app.name}</span> yet.</p>
          <p className="ink-soft">
            Open <strong>Launch a Run</strong>, fill the form, and click{" "}
            <strong>Save Template</strong> in the launcher header.
          </p>
        </div>
      )}

      {templates.status === "ok" && templates.templates.length > 0 && viewMode === "grid" && (
        <ul className="templateGrid" aria-label="template cards">
          {templates.templates.map((t) => (
            <TemplateCard key={t.blobId} template={t} app={app} onDelete={() => setPendingDelete(t)} />
          ))}
        </ul>
      )}

      {templates.status === "ok" && templates.templates.length > 0 && viewMode === "list" && (
        <TemplateListView
          templates={templates.templates}
          app={app}
          onDelete={(t) => setPendingDelete(t)}
        />
      )}

      {pendingDelete && (
        <DeleteTemplateDialog
          template={pendingDelete}
          busy={deleteBusy}
          errorMessage={deleteError}
          onCancel={() => { setPendingDelete(null); setDeleteError(null); }}
          onConfirm={confirmDelete}
        />
      )}
    </section>
  );
}

// ── ViewMode toggle ──────────────────────────────────────────────

function ViewModeToggle({
  viewMode, onChange,
}: { viewMode: ViewMode; onChange: (next: ViewMode) => void }) {
  return (
    <div className="viewModeToggle" role="tablist" aria-label="View mode">
      {(["grid", "list"] as const).map((mode) => (
        <button
          key={mode}
          type="button"
          role="tab"
          aria-selected={viewMode === mode}
          className={`btn ${viewMode === mode ? "btn--primary" : "btn--ghost"}`}
          onClick={() => onChange(mode)}
        >
          {mode === "grid" ? "Grid" : "List"}
        </button>
      ))}
    </div>
  );
}

// ── Grid view (cards) ────────────────────────────────────────────

function TemplateCard({ template, app, onDelete }: {
  template: TemplateSummary;
  app: Application;
  onDelete: () => void;
}) {
  const useUrl = `/applications/${encodeURIComponent(app.name)}/runs/new?template=${encodeURIComponent(template.blobId)}`;
  return (
    <li className="templateCard">
      <header className="templateCard__head">
        <h2 className="templateCard__name">{template.name}</h2>
      </header>
      {template.description && (
        <p className="templateCard__desc">{template.description}</p>
      )}
      <dl className="templateCard__stats">
        <div><dt>Saved</dt><dd>{formatRelative(template.uploadedAt)}</dd></div>
        <div><dt>Size</dt><dd className="mono">{formatBytes(template.sizeBytes)}</dd></div>
      </dl>
      <footer className="templateCard__footer">
        <Link to={useUrl} className="btn btn--primary">Use →</Link>
        <button type="button" className="btn btn--ghost text--error" onClick={onDelete}>
          Delete
        </button>
      </footer>
    </li>
  );
}

// ── List view (table) ────────────────────────────────────────────

function TemplateListView({
  templates, app, onDelete,
}: { templates: TemplateSummary[]; app: Application; onDelete: (t: TemplateSummary) => void }) {
  return (
    <table className="runsTable">
      <thead>
        <tr>
          <th>Name</th>
          <th>Description</th>
          <th>Saved</th>
          <th>Size</th>
          <th><span className="visuallyHidden">Actions</span></th>
        </tr>
      </thead>
      <tbody>
        {templates.map((t) => {
          const useUrl = `/applications/${encodeURIComponent(app.name)}/runs/new?template=${encodeURIComponent(t.blobId)}`;
          return (
            <tr key={t.blobId}>
              <td><strong>{t.name}</strong></td>
              <td className="appListTable__desc">
                {t.description ?? <span className="ink-soft">—</span>}
              </td>
              <td>{formatRelative(t.uploadedAt)}</td>
              <td className="mono">{formatBytes(t.sizeBytes)}</td>
              <td>
                <Link to={useUrl} className="btn btn--ghost">Use →</Link>
                <button
                  type="button"
                  className="btn btn--ghost text--error"
                  onClick={() => onDelete(t)}
                >
                  Delete
                </button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

// ── Delete confirmation modal ────────────────────────────────────

function DeleteTemplateDialog({
  template, busy, errorMessage, onCancel, onConfirm,
}: {
  template: TemplateSummary;
  busy: boolean;
  errorMessage: string | null;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="modal__overlay" role="presentation" onClick={onCancel}>
      <div
        className="modal modal--application"
        role="dialog"
        aria-modal="true"
        aria-labelledby="deleteTemplateTitle"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3 id="deleteTemplateTitle">Delete template?</h3>
            <small className="ink-soft">
              <span className="mono">{template.name}</span>
            </small>
          </div>
          <button type="button" className="btn btn--ghost" onClick={onCancel} aria-label="Close">×</button>
        </header>

        <div className="modal__body">
          <p>
            Deleting a template removes the saved launcher snapshot. Any URL
            currently pointing at <span className="mono">?template={template.blobId.slice(0, 8)}…</span>{" "}
            will fall back to an empty form.
          </p>
          {errorMessage && (
            <p className="text--error" role="alert">{errorMessage}</p>
          )}
        </div>

        <footer className="modal__footer">
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>Cancel</button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={onConfirm}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? "Deleting…" : "Delete"}
          </button>
        </footer>
      </div>
    </div>
  );
}

// ── Helpers ──────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export { TemplatesDetailPage as default };
