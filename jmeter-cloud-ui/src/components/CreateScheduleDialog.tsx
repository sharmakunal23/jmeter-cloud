import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import {
  cronJobsApi,
  CronJobApiError,
  type CronJobKind,
  type CronJobSummary,
} from "../api/automation";
import { templatesApi, type TemplateSummary } from "../api/templates";
import { browserTimeZone } from "../lib/cron";
import { Modal } from "./Modal";
import { ScheduleBuilder, type ScheduleValue } from "./ScheduleBuilder";

/**
 * Modal to create a CRON schedule for one application. Invoked
 * from `<AutomationDetailPage>` (the app is fixed in that context). Supports
 * the three Phase-C kinds:
 *   LAUNCH_RUN       fire a saved Template;
 *   DRAIN_REGION     drain every IDLE worker in a region overnight (cost saving);
 *   PROVISION_REGION spin workers back up to the configured cap in the morning.
 *
 * <p>The schedule + timezone UX (Simple presets / Advanced cron / local-time
 * preview) lives in the shared {@link ScheduleBuilder}. The server validates on
 * submit.
 */

// Per-app kinds only — platform report kinds are created from the Automation
// list page's "+ Report schedule" dialog, not this per-app one.
const PER_APP_KINDS: ReadonlyArray<{ kind: CronJobKind; label: string }> = [
  { kind: "LAUNCH_RUN", label: "Launch a test" },
  { kind: "DRAIN_REGION", label: "Drain region (cost saving)" },
  { kind: "PROVISION_REGION", label: "Provision region" },
];

export interface CreateScheduleDialogProps {
  application: string;
  /** The app's group — its Capacity page is where regions are added. */
  groupId?: string;
  /** The group's configured regions (its capacity grid) — for DRAIN / PROVISION. */
  regions: string[];
  /** When set, the dialog edits this schedule (PUT) instead of creating a new one. */
  editing?: CronJobSummary;
  onCreated: (job: CronJobSummary) => void;
  onClose: () => void;
}

export function CreateScheduleDialog({ application, groupId, regions, editing, onCreated, onClose }: CreateScheduleDialogProps) {
  const isEdit = editing != null;
  const [name, setName] = useState(editing?.name ?? "");
  const [kind, setKind] = useState<CronJobKind>(editing?.kind ?? "LAUNCH_RUN");
  const [templateBlobId, setTemplateBlobId] = useState(editing?.templateBlobId ?? "");
  const [region, setRegion] = useState(editing?.region ?? regions[0] ?? "");
  const [schedule, setSchedule] = useState<ScheduleValue>({
    cronExpression: editing?.cronExpression ?? "0 2 * * *",
    timeZone: editing?.timeZone ?? browserTimeZone(),
  });
  const [templates, setTemplates] = useState<TemplateSummary[] | null>(null);
  const [templatesError, setTemplatesError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  // Templates only matter for LAUNCH_RUN — load them lazily either way; cheap.
  useEffect(() => {
    const ctl = new AbortController();
    templatesApi.list(ctl.signal)
      .then((all) => setTemplates(all.filter((t) => t.application === application)))
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setTemplatesError(err instanceof Error ? err.message : String(err));
      });
    return () => ctl.abort();
  }, [application]);

  const trimmedName = name.trim();
  const isLaunch = kind === "LAUNCH_RUN";
  const canSubmit = !submitting
    && trimmedName !== ""
    && schedule.cronExpression.trim() !== ""
    && (isLaunch ? templateBlobId !== "" : region !== "");

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setServerError(null);
    try {
      const body = {
        name: trimmedName,
        applicationName: application,
        cronExpression: schedule.cronExpression,
        timeZone: schedule.timeZone,
        kind,
        ...(isLaunch ? { templateBlobId } : { region }),
      };
      const job = isEdit
        ? await cronJobsApi.update(editing.cronJobId, body)
        : await cronJobsApi.create(body);
      onCreated(job);
    } catch (err) {
      if (err instanceof CronJobApiError) {
        if (err.code === "CRON_JOB_CONFLICT") {
          setServerError(`A schedule named "${trimmedName}" already exists for ${application}.`);
        } else if (err.code === "INVALID_CRON") {
          setServerError(`Invalid cron expression: ${err.message}`);
        } else if (err.code === "TEMPLATE_UNAVAILABLE") {
          setServerError(`Template can't be loaded: ${err.message}`);
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
      title={isEdit ? "Edit schedule" : "New schedule"}
      infoTip="Runs the chosen action for this application on a cron schedule — launch a saved template, drain a region overnight, or provision it back up."
      width="form"
      onClose={onClose}
      closeDisabled={submitting}
    >
      <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
        <p style={{ margin: 0 }}>
          For <span className="mono">{application}</span>
        </p>
        <div className="formField">
          <label htmlFor="schedName">Name *</label>
          <input
            id="schedName"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={isLaunch ? "nightly-baseline" : "overnight-drain"}
            maxLength={128}
            autoFocus
            required
          />
          <small>Unique within {application}.</small>
        </div>

        <div className="formField">
          <label htmlFor="schedKind">Action *</label>
          <select id="schedKind" value={kind} onChange={(e) => setKind(e.target.value as CronJobKind)}>
            {PER_APP_KINDS.map((k) => (
              <option key={k.kind} value={k.kind}>{k.label}</option>
            ))}
          </select>
          <small>
            {isLaunch
              ? "Fires a saved test template."
              : kind === "DRAIN_REGION"
                ? "Drains every idle worker in the region (no replacement) — skipped if the app is always-on."
                : "Spins workers back up to the region's configured maximum."}
          </small>
        </div>

        {isLaunch ? (
          <div className="formField">
            <label htmlFor="schedTemplate">Template *</label>
            {templatesError ? (
              <p className="text--error" style={{ fontSize: "0.8rem" }}>{templatesError}</p>
            ) : templates === null ? (
              <p className="ink-soft" style={{ fontSize: "0.82rem" }}>Loading templates…</p>
            ) : templates.length === 0 ? (
              <p className="ink-soft" style={{ fontSize: "0.82rem" }}>
                No saved templates for <span className="mono">{application}</span>. Save one from the{" "}
                <Link to={`/applications/${encodeURIComponent(application)}/runs/new`}>run launcher</Link> first.
              </p>
            ) : (
              <select
                id="schedTemplate"
                value={templateBlobId}
                onChange={(e) => setTemplateBlobId(e.target.value)}
                required
              >
                <option value="" disabled>Select a template…</option>
                {templates.map((t) => (
                  <option key={t.blobId} value={t.blobId}>{t.name}</option>
                ))}
              </select>
            )}
          </div>
        ) : (
          <div className="formField">
            <label htmlFor="schedRegion">Region *</label>
            {regions.length === 0 ? (
              <p className="ink-soft" style={{ fontSize: "0.82rem" }}>
                No regions configured for <span className="mono">{application}</span>'s group. Add capacity on the{" "}
                {groupId
                  ? <Link to={`/capacity/${encodeURIComponent(groupId)}`}>group's Capacity page</Link>
                  : <Link to="/capacity">Capacity tab</Link>} first.
              </p>
            ) : (
              <select id="schedRegion" value={region} onChange={(e) => setRegion(e.target.value)} required>
                {regions.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            )}
          </div>
        )}

        <ScheduleBuilder value={schedule} onChange={setSchedule} idPrefix="sched" defaultTime="02:00" />

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
