import { useEffect, useState } from "react";
import { InfoTip } from "./InfoTip";
import { Paginator } from "./Paginator";
import { useClientPagination } from "../hooks/useClientPagination";
import { isDashboardUrl } from "../lib/grafanaLink";

import {
  applicationGroupsApi,
  GROUP_ID_PATTERN,
  type ApplicationGroup,
} from "../api/applicationGroups";
import { ApplicationApiError } from "../api/applications";
import { ConfirmDialog } from "./ConfirmDialog";
import { Modal } from "./Modal";
import { PodPolicyFields, pickerPolicyOf, policySummary, type PodPolicyValue } from "./RecyclePolicyEditor";
import { useToast, ToastView } from "./Toast";

/**
 * Manage application groups: list, add, edit, delete. A group's id is the
 * routing key its workers send as `?groupId=`, so it is immutable and must
 * match the metrics schema's group registry. The group owns the worker pool,
 * so its lifecycle policy and always-on flag are edited here (the pool's
 * capacity is on the Capacity tab); a group with applications, workers or
 * capacity rows cannot be deleted. Every save sends the full record — the
 * server replaces it wholesale.
 */

const DEFAULT_POLICY: PodPolicyValue = { recyclePolicy: "REUSE", alwaysOn: false };

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

/**
 * A comma-separated address box to the list the API takes. Trimmed and
 * de-duplicated here so the same address typed twice does not become two
 * recipients; the server validates the shape.
 */
function addressList(raw: string): string[] {
  const seen = new Set<string>();
  for (const part of raw.split(",")) {
    const value = part.trim();
    if (value) seen.add(value);
  }
  return [...seen];
}

/** Every address the boxes hold, for the "N recipients" hint under them. */
function addressCount(...raw: string[]): number {
  return raw.reduce((n, r) => n + addressList(r).length, 0);
}

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
  const [teamName, setTeamName] = useState("");
  const [notifyTo, setNotifyTo] = useState("");
  const [notifyCc, setNotifyCc] = useState("");
  const [notifyBcc, setNotifyBcc] = useState("");
  const [policy, setPolicy] = useState<PodPolicyValue>(DEFAULT_POLICY);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Inline rename
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editGrafanaLiveUrl, setEditGrafanaLiveUrl] = useState("");
  const [editGrafanaHistoryUrl, setEditGrafanaHistoryUrl] = useState("");
  const [editHotDays, setEditHotDays] = useState("7");
  const [editTeamName, setEditTeamName] = useState("");
  const [editNotifyTo, setEditNotifyTo] = useState("");
  const [editNotifyCc, setEditNotifyCc] = useState("");
  const [editNotifyBcc, setEditNotifyBcc] = useState("");
  const [editPolicy, setEditPolicy] = useState<PodPolicyValue>(DEFAULT_POLICY);
  const [saving, setSaving] = useState(false);

  // Delete confirm
  const [deleteTarget, setDeleteTarget] = useState<ApplicationGroup | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Two tabs — the existing-groups table and the add form stacked made the
  // modal overflow, and mixing them read as one giant form.
  const [tab, setTab] = useState<"existing" | "add">("existing");
  const groups = list.status === "ok" ? list.groups : [];
  const { page, setPage, pageItems, total, pageSize } = useClientPagination(groups, undefined, 8);
  // Editing swaps the list for a first-class form (same layout as Add);
  // the immutable id renders as a locked field, like the app dialog's name.
  const editing = editingId !== null ? groups.find((g) => g.groupId === editingId) ?? null : null;

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
  const canSaveEdit = editName.trim() !== "" && urlError(editGrafanaLiveUrl) == null
    && urlError(editGrafanaHistoryUrl) == null && hotDaysError(editHotDays) == null;
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
        teamName: teamName.trim() || null,
        notifyTo: addressList(notifyTo),
        notifyCc: addressList(notifyCc),
        notifyBcc: addressList(notifyBcc),
        grafanaLiveUrl: grafanaLiveUrl.trim() || undefined,
        grafanaHistoryUrl: grafanaHistoryUrl.trim() || undefined,
        hotDays: Number(hotDays),
        recyclePolicy: policy.recyclePolicy,
        // The three offered policies take no thresholds.
        maxRunsPerPod: null,
        podMaxAgeHours: null,
        alwaysOn: policy.alwaysOn,
      });
      setGroupId(""); setName(""); setDescription(""); setPolicy(DEFAULT_POLICY);
      showToast({ variant: "ok", text: `Group "${name.trim()}" created.` });
      setTab("existing");
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
    setEditTeamName(g.teamName ?? "");
    setEditNotifyTo((g.notifyTo ?? []).join(", "));
    setEditNotifyCc((g.notifyCc ?? []).join(", "));
    setEditNotifyBcc((g.notifyBcc ?? []).join(", "));
    setEditPolicy({ recyclePolicy: pickerPolicyOf(g.recyclePolicy), alwaysOn: g.alwaysOn ?? false });
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
        teamName: editTeamName.trim() || null,
        notifyTo: addressList(editNotifyTo),
        notifyCc: addressList(editNotifyCc),
        notifyBcc: addressList(editNotifyBcc),
        recyclePolicy: editPolicy.recyclePolicy,
        maxRunsPerPod: null,
        podMaxAgeHours: null,
        alwaysOn: editPolicy.alwaysOn,
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
      const hasApps = err instanceof ApplicationApiError && err.code === "APPLICATION_GROUP_HAS_APPLICATIONS";
      const hasWorkers = err instanceof ApplicationApiError && err.code === "APPLICATION_GROUP_HAS_WORKERS";
      showToast({
        variant: "err",
        text: hasApps ? "This group still has applications."
          : hasWorkers ? "This group still has workers or capacity."
          : "Could not delete the group.",
        detail: hasApps ? "Move them to another group or purge them first."
          : hasWorkers ? "Drain its workers and remove its regions on the Capacity tab first."
          : describe(err),
      });
      setDeleteTarget(null);
    } finally {
      setDeleting(false);
    }
  }

  return (
    <Modal
      title="Application groups"
      infoTip="A group owns the worker pool its applications run on and names the metrics tables its workers write to."
      infoTipExample="group id cps → tables CPS_METRICS"
      width="form"
      className="groupsModal"
      onClose={onClose}
      // While the delete confirmation is stacked on top, Esc must close only
      // that dialog — its own Modal handles it; this one stands down.
      closeDisabled={deleteTarget !== null}
    >
      <div className="modal__body">
        <div className="tabBar" role="tablist" aria-label="Application group views">
          <button
            type="button" role="tab" aria-selected={tab === "existing"}
            className={`tabBar__tab ${tab === "existing" ? "tabBar__tab--active" : ""}`}
            onClick={() => setTab("existing")}
          >
            Existing groups {list.status === "ok" && <span className="tabBar__count">{list.groups.length}</span>}
          </button>
          <button
            type="button" role="tab" aria-selected={tab === "add"}
            className={`tabBar__tab ${tab === "add" ? "tabBar__tab--active" : ""}`}
            onClick={() => { setTab("add"); setEditingId(null); }}
          >
            Add a group
          </button>
        </div>
        {tab === "existing" && list.status === "loading" && <p className="ink-soft">Loading groups…</p>}
        {tab === "existing" && list.status === "error" && <p className="text--error">{list.message}</p>}
        {tab === "existing" && list.status === "ok" && list.groups.length === 0 && (
          <p className="ink-soft">No groups yet — add one on the "Add a group" tab.</p>
        )}
        {tab === "existing" && editingId === null && list.status === "ok" && list.groups.length > 0 && (
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
              {pageItems.map((g) => (
                <tr key={g.groupId}>
                      <td>
                        <strong>{g.name}</strong>
                        {g.description && <div className="ink-soft appListTable__desc">{g.description}</div>}
                        {g.grafanaLiveUrl && (
                          <div className="ink-soft appListTable__desc">
                            Grafana: live{g.grafanaHistoryUrl ? " + history" : ""} · {g.hotDays ?? 7} hot days
                          </div>
                        )}
                        <div className="ink-soft appListTable__desc">
                          Workers: {policySummary(g.recyclePolicy, g.maxRunsPerPod, g.podMaxAgeHours)}
                          {g.alwaysOn ? " Always on." : ""}
                        </div>
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
                                title={(g.applicationCount ?? 0) > 0 ? "Move or purge its applications first" : "Delete this group (it must have no workers or capacity)"}>
                          Delete
                        </button>
                      </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {tab === "existing" && editingId === null && list.status === "ok" && list.groups.length > 0 && (
          <Paginator page={page} pageSize={pageSize} total={total} label="groups" onChange={setPage} />
        )}

        {tab === "existing" && editing && (
          <form
            className="createApp"
            noValidate
            onSubmit={(e) => { e.preventDefault(); if (canSaveEdit && !saving) void handleSave(editing); }}
          >
            <fieldset className="createApp__endpoints">
              <legend>Edit group</legend>
              <div className="formField">
                <label htmlFor="editGroupIdLocked">Id</label>
                <input id="editGroupIdLocked" type="text" value={editing.groupId} disabled />
                <small>Locked.</small>
              </div>
              <div className="formField">
                <label htmlFor="editGroupName">Name *</label>
                <input
                  id="editGroupName"
                  type="text"
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  maxLength={MAX_NAME_LEN}
                  autoFocus
                />
              </div>
              <div className="formField">
                <label htmlFor="editGroupDescription">Description</label>
                <input
                  id="editGroupDescription"
                  type="text"
                  value={editDescription}
                  onChange={(e) => setEditDescription(e.target.value)}
                  placeholder="optional"
                  maxLength={MAX_DESCRIPTION_LEN}
                />
              </div>
              <div className="formField">
                <label htmlFor="editGroupGrafanaLive">Grafana live dashboard URL</label>
                <input
                  id="editGroupGrafanaLive"
                  type="url"
                  value={editGrafanaLiveUrl}
                  onChange={(e) => setEditGrafanaLiveUrl(e.target.value)}
                  aria-invalid={urlError(editGrafanaLiveUrl) != null}
                  placeholder="optional"
                  maxLength={2000}
                />
              </div>
              <div className="formField">
                <label htmlFor="editGroupGrafanaHistory">Grafana history dashboard URL</label>
                <input
                  id="editGroupGrafanaHistory"
                  type="url"
                  value={editGrafanaHistoryUrl}
                  onChange={(e) => setEditGrafanaHistoryUrl(e.target.value)}
                  aria-invalid={urlError(editGrafanaHistoryUrl) != null}
                  placeholder="optional"
                  maxLength={2000}
                />
              </div>
              <div className="formField">
                <label htmlFor="editGroupHotDays">Hot days</label>
                <input
                  id="editGroupHotDays"
                  type="number"
                  value={editHotDays}
                  onChange={(e) => setEditHotDays(e.target.value)}
                  aria-invalid={hotDaysError(editHotDays) != null}
                  min={1}
                  max={3650}
                  style={{ width: "6rem" }}
                  title="Days the live dashboard covers; older runs open the history dashboard"
                />
              </div>
              <OwnershipFields
                idPrefix={`edit-${editing.groupId}`}
                teamName={editTeamName} onTeamName={setEditTeamName}
                to={editNotifyTo} onTo={setEditNotifyTo}
                cc={editNotifyCc} onCc={setEditNotifyCc}
                bcc={editNotifyBcc} onBcc={setEditNotifyBcc}
                disabled={saving}
              />
              <PodPolicyFields idPrefix={`edit-${editing.groupId}`} value={editPolicy} onChange={setEditPolicy} disabled={saving} />
              <div className="groupEditActions">
                <button type="button" className="btn btn--ghost"
                        onClick={() => setEditingId(null)} disabled={saving}>
                  Cancel
                </button>
                <button type="submit" className="btn btn--primary"
                        disabled={saving || !canSaveEdit} aria-busy={saving}>
                  {saving ? "Saving…" : "Save changes"}
                </button>
              </div>
            </fieldset>
          </form>
        )}

        {tab === "add" && (
        <form onSubmit={handleCreate} className="createApp" noValidate style={{ marginTop: "1rem" }}>
          <fieldset className="createApp__endpoints">
            <legend>New group</legend>
            <div className="formField">
              <div className="formField__labelRow">
                <label htmlFor="groupIdInput">Id *</label>
                <InfoTip
                  label="About group id"
                  example={<>group id <span className="mono">cps</span> → tables <span className="mono">CPS_METRICS</span></>}
                >
                  Names the group's metrics tables and is what its workers send
                  as <span className="mono">?groupId=</span> with every metrics
                  batch.
                </InfoTip>
              </div>
              <input
                id="groupIdInput"
                type="text"
                value={groupId}
                onChange={(e) => setGroupId(e.target.value)}
                placeholder="cps"
                maxLength={30}
                aria-invalid={idError != null && groupId !== ""}
              />
              <small>Lowercase, letter first, max 30; can't be changed later.</small>
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
              <div className="formField__labelRow">
                <label htmlFor="groupGrafanaLiveInput">Grafana live dashboard URL</label>
                <InfoTip label="About the live dashboard URL">
                  The group's hosted dashboard over its metrics tables — the run
                  page's "Open in Grafana" opens it with the run's time range
                  and application pre-selected.
                </InfoTip>
              </div>
              <input
                id="groupGrafanaLiveInput"
                type="url"
                value={grafanaLiveUrl}
                onChange={(e) => setGrafanaLiveUrl(e.target.value)}
                placeholder="https://grafana…/d/cpsProductMetrics/servicing-mq?orgId=1"
                maxLength={2000}
                aria-invalid={urlError(grafanaLiveUrl) != null}
              />
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
              <div className="formField__labelRow">
                <label htmlFor="groupHotDaysInput">Hot days</label>
                <InfoTip label="About hot days">
                  Days the live dashboard covers (the group's hot retention) —
                  runs older than this open the history dashboard instead.
                </InfoTip>
              </div>
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
            </div>
            <OwnershipFields
              idPrefix="new"
              teamName={teamName} onTeamName={setTeamName}
              to={notifyTo} onTo={setNotifyTo}
              cc={notifyCc} onCc={setNotifyCc}
              bcc={notifyBcc} onBcc={setNotifyBcc}
              disabled={creating}
            />
            <PodPolicyFields idPrefix="new" value={policy} onChange={setPolicy} disabled={creating} />
            {createUrlError && grafanaLiveUrl + grafanaHistoryUrl !== "" && (
              <p className="text--error" role="alert" style={{ fontSize: "0.78rem" }}>Dashboard URL {createUrlError}.</p>
            )}
            {createError && <div className="formError" role="alert">{createError}</div>}
            <button type="submit" className="btn btn--primary btn--sm" disabled={!canCreate} aria-busy={creating}>
              {creating ? "Adding…" : "+ Add group"}
            </button>
          </fieldset>
        </form>
        )}
      </div>

      {deleteTarget && (
        <ConfirmDialog
          title={`Delete group "${deleteTarget.name}"?`}
          body={
            <p>
              Removes the group <span className="mono">{deleteTarget.groupId}</span> from the
              registry. Its metrics tables are not touched; a group that still has applications,
              workers or capacity rows cannot be deleted.
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
    </Modal>
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

/**
 * Who owns the group and where its workflows send mail. The lists are the
 * defaults an email task inherits when it names no recipients of its own, so a
 * group that changes owners needs no workflow edited.
 */
function OwnershipFields({
  idPrefix, teamName, onTeamName, to, onTo, cc, onCc, bcc, onBcc, disabled,
}: {
  idPrefix: string;
  teamName: string; onTeamName: (v: string) => void;
  to: string; onTo: (v: string) => void;
  cc: string; onCc: (v: string) => void;
  bcc: string; onBcc: (v: string) => void;
  disabled?: boolean;
}) {
  const total = addressCount(to, cc, bcc);
  return (
    <>
      <div className="formField">
        <label htmlFor={`${idPrefix}GroupTeam`}>Owning team</label>
        <input
          id={`${idPrefix}GroupTeam`}
          type="text"
          value={teamName}
          onChange={(e) => onTeamName(e.target.value)}
          placeholder="optional — e.g. Payments Platform"
          maxLength={255}
          disabled={disabled}
        />
      </div>
      <div className="formField">
        <label htmlFor={`${idPrefix}GroupNotifyTo`}>
          Notify — To
          <InfoTip label="About notification defaults">
            Comma-separated. A workflow's email task uses these unless it names its own recipients.
          </InfoTip>
        </label>
        <input
          id={`${idPrefix}GroupNotifyTo`}
          type="text"
          value={to}
          onChange={(e) => onTo(e.target.value)}
          placeholder="perf-team@example.com, oncall@example.com"
          disabled={disabled}
        />
      </div>
      <div className="formField">
        <label htmlFor={`${idPrefix}GroupNotifyCc`}>Notify — Cc</label>
        <input
          id={`${idPrefix}GroupNotifyCc`}
          type="text"
          value={cc}
          onChange={(e) => onCc(e.target.value)}
          placeholder="optional"
          disabled={disabled}
        />
      </div>
      <div className="formField">
        <label htmlFor={`${idPrefix}GroupNotifyBcc`}>Notify — Bcc</label>
        <input
          id={`${idPrefix}GroupNotifyBcc`}
          type="text"
          value={bcc}
          onChange={(e) => onBcc(e.target.value)}
          placeholder="optional"
          disabled={disabled}
        />
        {total > 0 && (
          <small className="ink-soft">
            {total} recipient{total === 1 ? "" : "s"} across To, Cc and Bcc.
          </small>
        )}
      </div>
    </>
  );
}
