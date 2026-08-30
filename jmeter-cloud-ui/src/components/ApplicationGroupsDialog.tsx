import { useEffect, useState } from "react";
import { isDashboardUrl } from "../lib/grafanaLink";

import {
  applicationGroupsApi,
  GROUP_ID_PATTERN,
  type ApplicationGroup,
} from "../api/applicationGroups";
import { ApplicationApiError } from "../api/applications";
import { ConfirmDialog } from "./ConfirmDialog";
import { useToast, ToastView } from "./Toast";

/**
 * Manage application groups: list, add, rename, delete. A group's id is the
 * routing key its workers send as `?groupId=`, so it is immutable and must
 * match the metrics schema's group registry; a group with applications cannot
 * be deleted.
 */

const MAX_NAME_LEN = 255;
const MAX_DESCRIPTION_LEN = 512;

export interface ApplicationGroupsDialogProps {
  onClose: () => void;
  /** Fired after any successful create / update / delete so the caller can refresh. */
  onChanged?: () => void;
}

type ListState =
  | { status: "loading" }
  | { status: "ok"; groups: ApplicationGroup[] }
  | { status: "error"; message: string };

export function ApplicationGroupsDialog({ onClose, onChanged }: ApplicationGroupsDialogProps) {
  const [list, setList] = useState<ListState>({ status: "loading" });
  const [reloadSeq, setReloadSeq] = useState(0);
  const { toast, showToast, dismiss } = useToast();

  // Add form
  const [groupId, setGroupId] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [grafanaLiveUrl, setGrafanaLiveUrl] = useState("");
  const [grafanaHistoryUrl, setGrafanaHistoryUrl] = useState("");
  const [hotDays, setHotDays] = useState("7");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Inline rename
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editGrafanaLiveUrl, setEditGrafanaLiveUrl] = useState("");
  const [editGrafanaHistoryUrl, setEditGrafanaHistoryUrl] = useState("");
  const [editHotDays, setEditHotDays] = useState("7");
  const [saving, setSaving] = useState(false);

  // Delete confirm
  const [deleteTarget, setDeleteTarget] = useState<ApplicationGroup | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape" && !deleteTarget) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, deleteTarget]);

  useEffect(() => {
    const ctl = new AbortController();
    applicationGroupsApi.list(ctl.signal)
      .then((groups) => setList({ status: "ok", groups }))
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setList({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [reloadSeq]);

  function changed() {
    setReloadSeq((n) => n + 1);
    onChanged?.();
  }

  const trimmedId = groupId.trim();
  const idError =
    trimmedId === "" ? "id is required"
    : !GROUP_ID_PATTERN.test(trimmedId) ? "lowercase letters / digits / _ only, must start with a letter; max 30 chars"
    : null;
  const nameError = name.trim() === "" ? "name is required" : name.trim().length > MAX_NAME_LEN ? `name > ${MAX_NAME_LEN} chars` : null;
  const urlError = (v: string) => (v.trim() === "" || isDashboardUrl(v.trim()) ? null : "must be an absolute http(s) URL");
  const hotDaysError = (v: string) => (/^\d+$/.test(v.trim()) && Number(v) >= 1 && Number(v) <= 3650 ? null : "1–3650 days");
  const createUrlError = urlError(grafanaLiveUrl) ?? urlError(grafanaHistoryUrl);
  const canCreate = !creating && idError === null && nameError === null && description.length <= MAX_DESCRIPTION_LEN
    && createUrlError === null && hotDaysError(hotDays) === null;

  async function handleCreate(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canCreate) return;
    setCreating(true);
    setCreateError(null);
    try {
      await applicationGroupsApi.create({
        groupId: trimmedId,
        name: name.trim(),
        description: description.trim() || undefined,
        grafanaLiveUrl: grafanaLiveUrl.trim() || undefined,
        grafanaHistoryUrl: grafanaHistoryUrl.trim() || undefined,
        hotDays: Number(hotDays),
      });
      setGroupId(""); setName(""); setDescription("");
      showToast({ variant: "ok", text: `Group "${name.trim()}" created.` });
      changed();
    } catch (err) {
      setCreateError(describe(err));
    } finally {
      setCreating(false);
    }
  }

  function startEdit(g: ApplicationGroup) {
    setEditingId(g.groupId);
    setEditName(g.name);
    setEditDescription(g.description ?? "");
    setEditGrafanaLiveUrl(g.grafanaLiveUrl ?? "");
    setEditGrafanaHistoryUrl(g.grafanaHistoryUrl ?? "");
    setEditHotDays(String(g.hotDays ?? 7));
  }

  async function handleSave(g: ApplicationGroup) {
    if (editName.trim() === "") return;
    setSaving(true);
    try {
      await applicationGroupsApi.update(g.groupId, {
        name: editName.trim(),
        description: editDescription.trim() || undefined,
        grafanaLiveUrl: editGrafanaLiveUrl.trim() || undefined,
        grafanaHistoryUrl: editGrafanaHistoryUrl.trim() || undefined,
        hotDays: Number(editHotDays),
      });
      setEditingId(null);
      showToast({ variant: "ok", text: `Group "${editName.trim()}" saved.` });
      changed();
    } catch (err) {
      showToast({ variant: "err", text: "Could not save the group.", detail: describe(err) });
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await applicationGroupsApi.delete(deleteTarget.groupId);
      showToast({ variant: "ok", text: `Group "${deleteTarget.name}" deleted.` });
      setDeleteTarget(null);
      changed();
    } catch (err) {
      const inUse = err instanceof ApplicationApiError && err.code === "APPLICATION_GROUP_HAS_APPLICATIONS";
      showToast({
        variant: "err",
        text: inUse ? "This group still has applications." : "Could not delete the group.",
        detail: inUse ? "Move them to another group or purge them first." : describe(err),
      });
      setDeleteTarget(null);
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="modal__overlay" onClick={onClose}>
      <div
        className="modal modal--application"
        role="dialog"
        aria-label="Manage application groups"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3>Application groups</h3>
            <small className="ink-soft">
              A group's id is what its workers send as <code>?groupId=</code> with every
              metrics batch; upper-cased it names the group's own tables
              (<code>CPS_METRICS</code>), so it must exist in the metrics database's group registry.
            </small>
          </div>
          <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close">×</button>
        </header>

        <div className="modal__body">
          {list.status === "loading" && <p className="ink-soft">Loading groups…</p>}
          {list.status === "error" && <p className="text--error">{list.message}</p>}
          {list.status === "ok" && list.groups.length === 0 && (
            <p className="ink-soft">No groups yet.</p>
          )}
          {list.status === "ok" && list.groups.length > 0 && (
            <table className="runsTable applicationListTable" aria-label="application groups">
              <thead>
                <tr>
                  <th>Group</th>
                  <th>Id</th>
                  <th>Apps</th>
                  <th aria-label="actions"></th>
                </tr>
              </thead>
              <tbody>
                {list.groups.map((g) => (
                  <tr key={g.groupId}>
                    {editingId === g.groupId ? (
                      <>
                        <td>
                          <input
                            type="text"
                            value={editName}
                            onChange={(e) => setEditName(e.target.value)}
                            aria-label={`name of group ${g.groupId}`}
                            maxLength={MAX_NAME_LEN}
                          />
                          <input
                            type="text"
                            value={editDescription}
                            onChange={(e) => setEditDescription(e.target.value)}
                            aria-label={`description of group ${g.groupId}`}
                            placeholder="description (optional)"
                            maxLength={MAX_DESCRIPTION_LEN}
                            style={{ marginTop: "0.3rem" }}
                          />
                          <input
                            type="url"
                            value={editGrafanaLiveUrl}
                            onChange={(e) => setEditGrafanaLiveUrl(e.target.value)}
                            aria-label={`Grafana live dashboard URL of group ${g.groupId}`}
                            aria-invalid={urlError(editGrafanaLiveUrl) != null}
                            placeholder="Grafana live dashboard URL (optional)"
                            maxLength={2000}
                            style={{ marginTop: "0.3rem" }}
                          />
                          <input
                            type="url"
                            value={editGrafanaHistoryUrl}
                            onChange={(e) => setEditGrafanaHistoryUrl(e.target.value)}
                            aria-label={`Grafana history dashboard URL of group ${g.groupId}`}
                            aria-invalid={urlError(editGrafanaHistoryUrl) != null}
                            placeholder="Grafana history dashboard URL (optional)"
                            maxLength={2000}
                            style={{ marginTop: "0.3rem" }}
                          />
                          <input
                            type="number"
                            value={editHotDays}
                            onChange={(e) => setEditHotDays(e.target.value)}
                            aria-label={`hot days of group ${g.groupId}`}
                            aria-invalid={hotDaysError(editHotDays) != null}
                            min={1}
                            max={3650}
                            style={{ marginTop: "0.3rem", width: "6rem" }}
                            title="Days the live dashboard covers; older runs open the history dashboard"
                          />
                        </td>
                        <td className="mono ink-soft">{g.groupId}</td>
                        <td className="mono">{g.applicationCount ?? 0}</td>
                        <td className="runsTable__actions">
                          <button type="button" className="btn btn--sm btn--primary"
                                  onClick={() => void handleSave(g)}
                                  disabled={saving || editName.trim() === "" || urlError(editGrafanaLiveUrl) != null
                                    || urlError(editGrafanaHistoryUrl) != null || hotDaysError(editHotDays) != null}>
                            Save
                          </button>{" "}
                          <button type="button" className="btn btn--sm btn--ghost"
                                  onClick={() => setEditingId(null)} disabled={saving}>
                            Cancel
                          </button>
                        </td>
                      </>
                    ) : (
                      <>
                        <td>
                          <strong>{g.name}</strong>
                          {g.description && <div className="ink-soft appListTable__desc">{g.description}</div>}
                          {g.grafanaLiveUrl && (
                            <div className="ink-soft appListTable__desc">
                              Grafana: live{g.grafanaHistoryUrl ? " + history" : ""} · {g.hotDays ?? 7} hot days
                            </div>
                          )}
                        </td>
                        <td className="mono ink-soft">{g.groupId}</td>
                        <td className="mono">{g.applicationCount ?? 0}</td>
                        <td className="runsTable__actions">
                          <button type="button" className="btn btn--sm btn--ghost"
                                  onClick={() => startEdit(g)} aria-label={`edit group ${g.groupId}`}>
                            Edit
                          </button>{" "}
                          <button type="button" className="btn btn--sm btn--ghost text--error"
                                  onClick={() => setDeleteTarget(g)} aria-label={`delete group ${g.groupId}`}
                                  title={(g.applicationCount ?? 0) > 0 ? "Move or purge its applications first" : "Delete this group"}>
                            Delete
                          </button>
                        </td>
                      </>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <form onSubmit={handleCreate} className="createApp" noValidate style={{ marginTop: "1rem" }}>
            <fieldset className="createApp__endpoints">
              <legend>Add a group</legend>
              <div className="formField">
                <label htmlFor="groupIdInput">Id *</label>
                <input
                  id="groupIdInput"
                  type="text"
                  value={groupId}
                  onChange={(e) => setGroupId(e.target.value)}
                  placeholder="cps"
                  maxLength={30}
                  aria-invalid={idError != null && groupId !== ""}
                />
                <small>Lowercase, letter first, max 30. Names the group's tables (<code>{trimmedId ? trimmedId.toUpperCase() : "CPS"}_METRICS</code>) and is sent by workers as <code>?groupId=</code>; can't be changed later.</small>
                {idError && groupId && (
                  <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>{idError}</p>
                )}
              </div>
              <div className="formField">
                <label htmlFor="groupNameInput">Name *</label>
                <input
                  id="groupNameInput"
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Servicing MQ"
                  maxLength={MAX_NAME_LEN}
                />
              </div>
              <div className="formField">
                <label htmlFor="groupDescriptionInput">Description</label>
                <input
                  id="groupDescriptionInput"
                  type="text"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="optional"
                  maxLength={MAX_DESCRIPTION_LEN}
                />
              </div>
              <div className="formField">
                <label htmlFor="groupGrafanaLiveInput">Grafana live dashboard URL</label>
                <input
                  id="groupGrafanaLiveInput"
                  type="url"
                  value={grafanaLiveUrl}
                  onChange={(e) => setGrafanaLiveUrl(e.target.value)}
                  placeholder="https://grafana…/d/cpsProductMetrics/servicing-mq?orgId=1"
                  maxLength={2000}
                  aria-invalid={urlError(grafanaLiveUrl) != null}
                />
                <small>The group's dashboard over <code>{trimmedId ? trimmedId.toUpperCase() : "CPS"}_METRICS</code>; the run page's "Open in Grafana" adds the run's time range and <code>var-application</code>.</small>
              </div>
              <div className="formField">
                <label htmlFor="groupGrafanaHistoryInput">Grafana history dashboard URL</label>
                <input
                  id="groupGrafanaHistoryInput"
                  type="url"
                  value={grafanaHistoryUrl}
                  onChange={(e) => setGrafanaHistoryUrl(e.target.value)}
                  placeholder="optional — opened for runs older than the hot days"
                  maxLength={2000}
                  aria-invalid={urlError(grafanaHistoryUrl) != null}
                />
              </div>
              <div className="formField">
                <label htmlFor="groupHotDaysInput">Hot days</label>
                <input
                  id="groupHotDaysInput"
                  type="number"
                  value={hotDays}
                  onChange={(e) => setHotDays(e.target.value)}
                  min={1}
                  max={3650}
                  aria-invalid={hotDaysError(hotDays) != null}
                  style={{ width: "6rem" }}
                />
                <small>Days the live dashboard covers (the group's hot retention); older runs open the history dashboard.</small>
              </div>
              {createUrlError && grafanaLiveUrl + grafanaHistoryUrl !== "" && (
                <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>Dashboard URL {createUrlError}.</p>
              )}
              {createError && <div className="formError" role="alert">{createError}</div>}
              <button type="submit" className="btn btn--primary btn--sm" disabled={!canCreate} aria-busy={creating}>
                {creating ? "Adding…" : "+ Add group"}
              </button>
            </fieldset>
          </form>
        </div>

        {deleteTarget && (
          <ConfirmDialog
            title={`Delete group "${deleteTarget.name}"?`}
            body={
              <p>
                Removes the group <span className="mono">{deleteTarget.groupId}</span> from the
                registry. Its metrics tables are not touched; a group that still has applications
                cannot be deleted.
              </p>
            }
            confirmLabel="Delete group"
            danger
            busy={deleting}
            onConfirm={() => void handleDelete()}
            onCancel={() => setDeleteTarget(null)}
          />
        )}

        <ToastView toast={toast} onDismiss={dismiss} />
      </div>
    </div>
  );
}

function describe(err: unknown): string {
  if (err instanceof ApplicationApiError) {
    switch (err.code) {
      case "APPLICATION_GROUP_ID_TAKEN": return "A group with this id already exists.";
      case "APPLICATION_GROUP_NAME_TAKEN": return "A group with this name already exists.";
      case "INVALID_REQUEST": return err.message;
      default: return `${err.code}: ${err.message}`;
    }
  }
  return err instanceof Error ? err.message : String(err);
}
