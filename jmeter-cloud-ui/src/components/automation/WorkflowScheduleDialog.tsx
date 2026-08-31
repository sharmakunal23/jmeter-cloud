import { useEffect, useState } from "react";

import { cronJobsApi, CronJobApiError, type CronJobSummary } from "../../api/automation";
import { applicationGroupsApi, type ApplicationGroup } from "../../api/applicationGroups";
import { workflowsApi, type Workflow } from "../../api/workflows";
import { browserTimeZone } from "../../lib/cron";
import { Modal } from "../Modal";
import { ScheduleBuilder, type ScheduleValue } from "../ScheduleBuilder";

/**
 * Schedule a workflow. Pick the group, then one of its workflows, then a cadence
 * — a workflow is group-scoped, so the group is what narrows the second list.
 *
 * <p>No email fields here on purpose: a workflow sends its own mail through its
 * EMAIL nodes and the group's notify defaults, and the platform-wide reports are
 * their own section. Two places to configure one email is how operators end up
 * with none.
 */
export interface WorkflowScheduleDialogProps {
  /** When set, edits this schedule (PUT) instead of creating one. */
  editing?: CronJobSummary;
  onSaved: (job: CronJobSummary) => void;
  onClose: () => void;
}

export function WorkflowScheduleDialog({ editing, onSaved, onClose }: WorkflowScheduleDialogProps) {
  const isEdit = editing != null;
  const [name, setName] = useState(editing?.name ?? "");
  const [groupId, setGroupId] = useState(editing?.groupId ?? "");
  const [workflowId, setWorkflowId] = useState(editing?.workflowId ?? "");
  const [schedule, setSchedule] = useState<ScheduleValue>({
    cronExpression: editing?.cronExpression ?? "0 2 * * *",
    timeZone: editing?.timeZone ?? browserTimeZone(),
  });
  const [groups, setGroups] = useState<ApplicationGroup[] | null>(null);
  const [workflows, setWorkflows] = useState<Workflow[] | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  useEffect(() => {
    const ctl = new AbortController();
    applicationGroupsApi.list(ctl.signal).then(setGroups).catch(() => setGroups([]));
    return () => ctl.abort();
  }, []);

  // The workflow list follows the group. Clearing the selection when the group
  // changes matters: a stale workflowId from the previous group would be
  // refused by the server, and the operator would not see why.
  useEffect(() => {
    if (!groupId) { setWorkflows(null); return; }
    const ctl = new AbortController();
    setWorkflows(null);
    workflowsApi.list(groupId, ctl.signal).then(setWorkflows).catch(() => setWorkflows([]));
    return () => ctl.abort();
  }, [groupId]);

  function pickGroup(next: string) {
    setGroupId(next);
    if (next !== groupId) setWorkflowId("");
  }

  const trimmedName = name.trim();
  const canSubmit = !submitting && trimmedName !== "" && groupId !== "" && workflowId !== ""
    && schedule.cronExpression.trim() !== "";

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setServerError(null);
    try {
      const body = {
        name: trimmedName,
        kind: "LAUNCH_WORKFLOW" as const,
        groupId,
        workflowId,
        cronExpression: schedule.cronExpression,
        timeZone: schedule.timeZone,
      };
      onSaved(isEdit ? await cronJobsApi.update(editing.cronJobId, body) : await cronJobsApi.create(body));
    } catch (err) {
      if (err instanceof CronJobApiError) {
        if (err.code === "CRON_JOB_CONFLICT") {
          setServerError(`A schedule named "${trimmedName}" already exists in this group.`);
        } else if (err.code === "INVALID_CRON") {
          setServerError(`Invalid cron expression: ${err.message}`);
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

  const selectedWorkflow = workflows?.find((w) => w.workflowId === workflowId);

  return (
    <Modal
      title={isEdit ? "Edit workflow schedule" : "New workflow schedule"}
      infoTip="Runs a saved workflow on a schedule, in the group that owns it."
      width="form"
      onClose={onClose}
      closeDisabled={submitting}
    >
      <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
        <div className="formField">
          <label htmlFor="wfSchedGroup">Group *</label>
          <select id="wfSchedGroup" value={groupId} onChange={(e) => pickGroup(e.target.value)} required>
            <option value="">Select a group…</option>
            {(groups ?? []).map((g) => (
              <option key={g.groupId} value={g.groupId}>{g.name}</option>
            ))}
          </select>
          <small>Workflows belong to a group; its reservation is what the run draws on.</small>
        </div>

        <div className="formField">
          <label htmlFor="wfSchedWorkflow">Workflow *</label>
          <select
            id="wfSchedWorkflow"
            value={workflowId}
            onChange={(e) => setWorkflowId(e.target.value)}
            disabled={!groupId || workflows === null}
            required
          >
            <option value="">
              {!groupId ? "Select a group first…"
                : workflows === null ? "Loading…"
                : workflows.length === 0 ? "This group has no workflows"
                : "Select a workflow…"}
            </option>
            {(workflows ?? []).map((w) => (
              <option key={w.workflowId} value={w.workflowId}>{w.name}</option>
            ))}
          </select>
          {selectedWorkflow && !selectedWorkflow.enabled && (
            <small className="text--error">
              This workflow is disabled — the schedule will fire but skip until it is enabled.
            </small>
          )}
        </div>

        <div className="formField">
          <label htmlFor="wfSchedName">Name *</label>
          <input
            id="wfSchedName"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="nightly-regression"
            maxLength={128}
            required
          />
          <small>Unique within the group.</small>
        </div>

        <ScheduleBuilder value={schedule} onChange={setSchedule} idPrefix="wfSched" defaultTime="02:00" />

        {serverError && <div className="formError" role="alert">{serverError}</div>}

        <Modal.Footer>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn--primary" disabled={!canSubmit} aria-busy={submitting}>
            {submitting ? (isEdit ? "Saving…" : "Creating…") : (isEdit ? "Save changes" : "Create schedule")}
          </button>
        </Modal.Footer>
      </form>
    </Modal>
  );
}
