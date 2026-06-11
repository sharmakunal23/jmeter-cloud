import { useState, type ReactNode } from "react";

import { cronJobsApi, CronJobApiError, type CronJobSummary } from "../api/automation";
import { CreateReportScheduleDialog } from "./CreateReportScheduleDialog";
import { EmailPreviewModal } from "./EmailPreviewModal";
import { ScheduleCell, NextFireCell, EnableToggle } from "./ScheduleCells";
import { ConfirmDialog } from "./ConfirmDialog";
import { ToastView, useToast } from "./Toast";
import { formatInZone } from "../lib/cron";
import { formatRelative } from "../lib/time";

/**
 * AUTOMATION Phase E — platform-wide report schedules (INFRA_READINESS +
 * DAILY_REPORT). These have no application, so they live on the Automation list
 * page's "Platform reports" tab. Full lifecycle: create / edit / send-now /
 * skip-next / enable-disable / delete, mirroring the per-app detail page.
 */

const KIND_LABEL: Record<string, string> = {
  INFRA_READINESS: "Infra readiness",
  DAILY_REPORT: "Daily report",
};

/** A critical action awaiting confirmation. */
type PendingAction = { kind: "send" | "skip" | "delete"; job: CronJobSummary };

export function PlatformSchedulesSection({
  jobs, onChanged,
}: { jobs: CronJobSummary[]; onChanged: () => void }) {
  const [showCreate, setShowCreate] = useState(false);
  const [editJob, setEditJob] = useState<CronJobSummary | null>(null);
  const [previewJob, setPreviewJob] = useState<CronJobSummary | null>(null);
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const { toast, showToast, dismiss } = useToast();

  async function runAction(id: string, action: () => Promise<unknown>, successMsg: string) {
    setBusyId(id);
    try {
      await action();
      if (successMsg) showToast({ variant: "ok", text: successMsg });
      onChanged();
    } catch (err) {
      showToast({
        variant: "err",
        text: err instanceof CronJobApiError ? `${err.code}: ${err.message}`
          : err instanceof Error ? err.message : String(err),
      });
    } finally {
      setBusyId(null);
    }
  }

  function toggleEnabled(j: CronJobSummary) {
    void runAction(
      j.cronJobId,
      () => (j.enabled ? cronJobsApi.disable(j.cronJobId) : cronJobsApi.enable(j.cronJobId)),
      `"${j.name}" ${j.enabled ? "disabled" : "enabled"}.`,
    );
  }

  async function confirmPending() {
    if (!pending) return;
    const j = pending.job;
    if (pending.kind === "delete") {
      await runAction(j.cronJobId, () => cronJobsApi.delete(j.cronJobId), `"${j.name}" deleted.`);
    } else if (pending.kind === "skip") {
      await runAction(j.cronJobId, () => cronJobsApi.skipNext(j.cronJobId), `Skipped the next "${j.name}".`);
    } else {
      await runAction(j.cronJobId, async () => {
        const r = await cronJobsApi.fireNow(j.cronJobId);
        if (r.outcome === "LAUNCHED") {
          showToast({ variant: "ok", text: `Sent "${j.name}".` });
        } else if (r.outcome === "SKIPPED") {
          showToast({ variant: "warn", text: `"${j.name}" skipped`, detail: r.error ?? "no recipients" });
        } else {
          showToast({ variant: "err", text: `"${j.name}" failed`, detail: r.error ?? "see logs" });
        }
      }, "");
    }
    setPending(null);
  }

  return (
    <section className="platformSchedules" aria-label="Platform report schedules">
      <div className="pageHeader platformSchedules__head">
        <h2>Platform reports</h2>
        <button type="button" className="btn btn--primary btn--sm" onClick={() => setShowCreate(true)}>
          + Report schedule
        </button>
      </div>

      <ToastView toast={toast} onDismiss={dismiss} />

      {jobs.length === 0 ? (
        <p className="ink-soft platformSchedules__empty">
          No platform report schedules. Add one to email the daily infra-readiness report
          (all backends + every app's 24h health) or the daily perf report.
        </p>
      ) : (
        <table className="runsTable">
          <thead>
            <tr>
              <th>Name</th>
              <th>Report</th>
              <th>Schedule</th>
              <th>Recipients</th>
              <th>Last Fired</th>
              <th>Next Fire</th>
              <th><span className="visuallyHidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((j) => {
              const busy = busyId === j.cronJobId;
              return (
                <tr key={j.cronJobId}>
                  <td><strong>{j.name}</strong></td>
                  <td>{KIND_LABEL[j.kind] ?? j.kind}</td>
                  <td><ScheduleCell cron={j.cronExpression} timeZone={j.timeZone} /></td>
                  <td className="ink-soft platformSchedules__recipients">
                    {j.recipients ? j.recipients : <em>env default</em>}
                  </td>
                  <td>
                    {j.lastFiredAt ? formatRelative(j.lastFiredAt) : "—"}
                    {j.lastFireStatus && (
                      <small className="ink-soft" style={{ display: "block" }}>{j.lastFireStatus}</small>
                    )}
                  </td>
                  <td><NextFireCell nextFireAt={j.nextFireAt} timeZone={j.timeZone} enabled={j.enabled} /></td>
                  <td className="runsTable__actions">
                    <EnableToggle enabled={j.enabled} busy={busy} onToggle={() => toggleEnabled(j)} />
                    <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                            onClick={() => setPreviewJob(j)} title="See what this email looks like">
                      Preview
                    </button>
                    <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                            onClick={() => setPending({ kind: "send", job: j })} title="Send this report now">
                      Send now
                    </button>
                    {j.enabled && j.nextFireAt && (
                      <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                              onClick={() => setPending({ kind: "skip", job: j })} title="Skip the next scheduled send">
                        Skip next
                      </button>
                    )}
                    <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                            onClick={() => setEditJob(j)} title="Edit this report schedule">
                      Edit
                    </button>
                    <button type="button" className="btn btn--ghost btn--sm text--error" disabled={busy}
                            onClick={() => setPending({ kind: "delete", job: j })}>
                      Delete
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {showCreate && (
        <CreateReportScheduleDialog
          onClose={() => setShowCreate(false)}
          onCreated={(job) => {
            setShowCreate(false);
            showToast({ variant: "ok", text: `Report schedule "${job.name}" created.` });
            onChanged();
          }}
        />
      )}

      {editJob && (
        <CreateReportScheduleDialog
          editing={editJob}
          onClose={() => setEditJob(null)}
          onCreated={(job) => {
            setEditJob(null);
            showToast({ variant: "ok", text: `Report schedule "${job.name}" updated.` });
            onChanged();
          }}
        />
      )}

      {pending && (
        <ConfirmDialog
          title={
            pending.kind === "delete" ? `Delete "${pending.job.name}"?`
            : pending.kind === "skip" ? `Skip the next "${pending.job.name}"?`
            : `Send "${pending.job.name}" now?`
          }
          body={confirmBody(pending)}
          confirmLabel={
            pending.kind === "delete" ? "Delete schedule"
            : pending.kind === "skip" ? "Skip next send"
            : "Send now"
          }
          danger={pending.kind === "delete"}
          busy={busyId === pending.job.cronJobId}
          onConfirm={() => void confirmPending()}
          onCancel={() => setPending(null)}
        />
      )}

      {previewJob && (
        <EmailPreviewModal
          kind={previewJob.kind}
          customSubject={previewJob.customSubject ?? undefined}
          customIntro={previewJob.customIntro ?? undefined}
          onClose={() => setPreviewJob(null)}
        />
      )}
    </section>
  );
}

function confirmBody(pending: PendingAction): ReactNode {
  if (pending.kind === "delete") {
    return <>This permanently removes the report schedule. This can't be undone.</>;
  }
  if (pending.kind === "skip") {
    const when = pending.job.nextFireAt
      ? <> (<span className="mono">{formatInZone(new Date(pending.job.nextFireAt), pending.job.timeZone)}</span>)</>
      : null;
    return <>The next scheduled send{when} won't go out — the schedule advances to the one after it and stays enabled.</>;
  }
  return <>This emails the report immediately, in addition to its normal cadence.</>;
}
