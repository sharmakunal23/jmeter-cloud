import { useState } from "react";

import { cronJobsApi, CronJobApiError, type CronJobKind, type CronJobSummary } from "../api/automation";
import { browserTimeZone } from "../lib/cron";
import { EmailPreviewModal } from "./EmailPreviewModal";
import { Modal } from "./Modal";
import { ScheduleBuilder, type ScheduleValue } from "./ScheduleBuilder";

/**
 * Modal to create a platform-wide report schedule. The
 * operator picks the report kind:
 *   • INFRA_READINESS — daily infra-readiness email (all backends + every app's
 *     24h health);
 *   • DAILY_REPORT — daily perf-test report (per-app run counts + p50/p95/
 *     error-rate vs the 6-day baseline + top regressions).
 * No application / template / region — just a name, schedule, recipients, and an
 * optional custom subject + intro note. The server emails the rendered report on
 * each fire (recipients fall back to the AUTOMATION_REPORT_RECIPIENTS env when blank).
 */

const REPORT_KINDS: { value: CronJobKind; label: string; blurb: string }[] = [
  { value: "INFRA_READINESS", label: "Infra readiness",
    blurb: "All backends + every app's 24h health." },
  { value: "DAILY_REPORT", label: "Daily perf report",
    blurb: "Per-app run counts + p50/p95/error-rate vs the 6-day baseline + top regressions." },
];

export interface CreateReportScheduleDialogProps {
  /** When set, the dialog edits this report schedule (PUT) instead of creating one. */
  editing?: CronJobSummary;
  onCreated: (job: CronJobSummary) => void;
  onClose: () => void;
}

export function CreateReportScheduleDialog({ editing, onCreated, onClose }: CreateReportScheduleDialogProps) {
  const isEdit = editing != null;
  const [name, setName] = useState(editing?.name ?? "");
  const [kind, setKind] = useState<CronJobKind>(editing?.kind ?? "INFRA_READINESS");
  const [schedule, setSchedule] = useState<ScheduleValue>({
    cronExpression: editing?.cronExpression ?? "0 7 * * *",
    timeZone: editing?.timeZone ?? browserTimeZone(),
  });
  const [recipients, setRecipients] = useState(editing?.recipients ?? "");
  const [customSubject, setCustomSubject] = useState(editing?.customSubject ?? "");
  const [customIntro, setCustomIntro] = useState(editing?.customIntro ?? "");
  const [showPreview, setShowPreview] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const trimmedName = name.trim();
  const canSubmit = !submitting && trimmedName !== "" && schedule.cronExpression.trim() !== "";

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setServerError(null);
    try {
      const body = {
        name: trimmedName,
        applicationName: "", // ignored for report kinds (platform-wide)
        cronExpression: schedule.cronExpression,
        timeZone: schedule.timeZone,
        kind,
        recipients: recipients.trim() || undefined,
        customSubject: customSubject.trim() || undefined,
        customIntro: customIntro.trim() || undefined,
      };
      const job = isEdit
        ? await cronJobsApi.update(editing.cronJobId, body)
        : await cronJobsApi.create(body);
      onCreated(job);
    } catch (err) {
      if (err instanceof CronJobApiError) {
        if (err.code === "CRON_JOB_CONFLICT") {
          setServerError(`A platform schedule named "${trimmedName}" already exists.`);
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

  return (
    <Modal
      title={isEdit ? "Edit report schedule" : "New report schedule"}
      infoTip="Emails the selected platform-wide report on a cron schedule — recipients default to the server's configured list."
      width="form"
      onClose={onClose}
      // While the email-preview modal is stacked on top, Esc must close only
      // the preview — its own Modal handles that; this one stands down.
      closeDisabled={submitting || showPreview}
    >
      <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
        <div className="formField">
          <label htmlFor="reportKind">Report *</label>
          <select
            id="reportKind"
            value={kind}
            onChange={(e) => setKind(e.target.value as CronJobKind)}
          >
            {REPORT_KINDS.map((k) => (
              <option key={k.value} value={k.value}>{k.label}</option>
            ))}
          </select>
          <small>{REPORT_KINDS.find((k) => k.value === kind)?.blurb}</small>
        </div>

        <div className="formField">
          <label htmlFor="reportName">Name *</label>
          <input
            id="reportName"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="daily-infra-readiness"
            maxLength={128}
            autoFocus
            required
          />
          <small>Unique across platform schedules.</small>
        </div>

        <ScheduleBuilder value={schedule} onChange={setSchedule} idPrefix="report" defaultTime="07:00" />

        <div className="formField">
          <label htmlFor="reportRecipients">Recipients</label>
          <input
            id="reportRecipients"
            type="text"
            value={recipients}
            onChange={(e) => setRecipients(e.target.value)}
            placeholder="ops@example.com, sre@example.com"
          />
          <small>Comma-separated. Leave blank to use the server's <code>AUTOMATION_REPORT_RECIPIENTS</code> default.</small>
        </div>

        <div className="formField">
          <label htmlFor="reportSubject">Subject (optional)</label>
          <input
            id="reportSubject"
            type="text"
            value={customSubject}
            onChange={(e) => setCustomSubject(e.target.value)}
            placeholder="Leave blank for the default subject"
            maxLength={200}
          />
        </div>

        <div className="formField">
          <label htmlFor="reportIntro">Intro note (optional)</label>
          <textarea
            id="reportIntro"
            value={customIntro}
            onChange={(e) => setCustomIntro(e.target.value)}
            placeholder="A short note shown at the top of the email."
            rows={2}
            maxLength={1000}
          />
          <small>Shown above the report. Use <strong>Preview email</strong> to see exactly what recipients get.</small>
        </div>

        {serverError && <div className="formError" role="alert">{serverError}</div>}

        <Modal.Footer>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="button" className="btn btn--ghost" onClick={() => setShowPreview(true)}>
            Preview email
          </button>
          <button type="submit" className="btn btn--primary" disabled={!canSubmit} aria-busy={submitting}>
            {submitting ? (isEdit ? "Saving…" : "Creating…") : (isEdit ? "Save changes" : "Create schedule")}
          </button>
        </Modal.Footer>
      </form>

      {showPreview && (
        <EmailPreviewModal
          kind={kind}
          customSubject={customSubject.trim() || undefined}
          customIntro={customIntro.trim() || undefined}
          onClose={() => setShowPreview(false)}
        />
      )}
    </Modal>
  );
}
