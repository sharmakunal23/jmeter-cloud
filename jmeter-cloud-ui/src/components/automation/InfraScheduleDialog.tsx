import { useEffect, useState } from "react";

import { cronJobsApi, CronJobApiError, type CronJobKind, type CronJobSummary } from "../../api/automation";
import { applicationGroupsApi, type ApplicationGroup } from "../../api/applicationGroups";
import { browserTimeZone } from "../../lib/cron";
import { Modal } from "../Modal";
import { ScheduleBuilder, type ScheduleValue } from "../ScheduleBuilder";

/**
 * Schedule a scale-out or scale-in for one (group, cluster).
 *
 * <p>The cluster list is the group's own reservation grid, not every registered
 * cluster: a group can only scale where it holds a reservation, so offering the
 * others would only produce a server-side rejection.
 *
 * <p>Pairing a scale-in in the evening with a scale-out in the morning is the
 * intended overnight shape, which is why both directions live in one dialog.
 */
const DIRECTIONS: { value: CronJobKind; label: string; blurb: string }[] = [
  { value: "SCALE_OUT", label: "Scale out",
    blurb: "Adds workers up to the group's reservation on that cluster." },
  { value: "SCALE_IN", label: "Scale in",
    blurb: "Releases idle workers. Busy ones are left alone, and a group marked always-on is skipped." },
];

export interface InfraScheduleDialogProps {
  /** When set, edits this schedule (PUT) instead of creating one. */
  editing?: CronJobSummary;
  onSaved: (job: CronJobSummary) => void;
  onClose: () => void;
}

export function InfraScheduleDialog({ editing, onSaved, onClose }: InfraScheduleDialogProps) {
  const isEdit = editing != null;
  const [name, setName] = useState(editing?.name ?? "");
  const [kind, setKind] = useState<CronJobKind>(editing?.kind ?? "SCALE_OUT");
  const [groupId, setGroupId] = useState(editing?.groupId ?? "");
  const [region, setRegion] = useState(editing?.region ?? "");
  const [schedule, setSchedule] = useState<ScheduleValue>({
    cronExpression: editing?.cronExpression ?? "0 7 * * 1-5",
    timeZone: editing?.timeZone ?? browserTimeZone(),
  });
  const [groups, setGroups] = useState<ApplicationGroup[] | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  useEffect(() => {
    const ctl = new AbortController();
    applicationGroupsApi.list(ctl.signal).then(setGroups).catch(() => setGroups([]));
    return () => ctl.abort();
  }, []);

  const selectedGroup = groups?.find((g) => g.groupId === groupId);
  const regions = selectedGroup?.capacity ?? [];

  function pickGroup(next: string) {
    setGroupId(next);
    // A cluster the new group holds no reservation on would only be refused.
    if (next !== groupId) setRegion("");
  }

  const trimmedName = name.trim();
  const canSubmit = !submitting && trimmedName !== "" && groupId !== "" && region !== ""
    && schedule.cronExpression.trim() !== "";

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setServerError(null);
    try {
      const body = {
        name: trimmedName,
        kind,
        groupId,
        region,
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
        } else if (err.code === "REGION_NOT_CONFIGURED") {
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

  return (
    <Modal
      title={isEdit ? "Edit scaling schedule" : "New scaling schedule"}
      infoTip="Adds or releases workers in one cluster on a schedule."
      width="form"
      onClose={onClose}
      closeDisabled={submitting}
    >
      <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
        <div className="formField">
          <label htmlFor="infraSchedKind">Direction *</label>
          <select id="infraSchedKind" value={kind} onChange={(e) => setKind(e.target.value as CronJobKind)}>
            {DIRECTIONS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </select>
          <small>{DIRECTIONS.find((d) => d.value === kind)?.blurb}</small>
        </div>

        <div className="formField">
          <label htmlFor="infraSchedGroup">Group *</label>
          <select id="infraSchedGroup" value={groupId} onChange={(e) => pickGroup(e.target.value)} required>
            <option value="">Select a group…</option>
            {(groups ?? []).map((g) => <option key={g.groupId} value={g.groupId}>{g.name}</option>)}
          </select>
        </div>

        <div className="formField">
          <label htmlFor="infraSchedRegion">Cluster *</label>
          <select
            id="infraSchedRegion"
            value={region}
            onChange={(e) => setRegion(e.target.value)}
            disabled={!groupId}
            required
          >
            <option value="">
              {!groupId ? "Select a group first…"
                : regions.length === 0 ? "This group has no reserved clusters"
                : "Select a cluster…"}
            </option>
            {regions.map((c) => (
              <option key={c.region} value={c.region}>
                {c.region} — {c.maxAvailable} worker{c.maxAvailable === 1 ? "" : "s"} reserved
              </option>
            ))}
          </select>
          <small>Only clusters this group has reserved capacity on.</small>
        </div>

        <div className="formField">
          <label htmlFor="infraSchedName">Name *</label>
          <input
            id="infraSchedName"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="morning-scale-out"
            maxLength={128}
            required
          />
          <small>Unique within the group.</small>
        </div>

        <ScheduleBuilder value={schedule} onChange={setSchedule} idPrefix="infraSched" defaultTime="07:00" />

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
