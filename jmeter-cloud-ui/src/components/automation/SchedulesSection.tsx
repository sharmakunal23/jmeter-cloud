import { useState, type ReactNode } from "react";

import { cronJobsApi, CronJobApiError, type CronJobSummary } from "../../api/automation";
import { ConfirmDialog } from "../ConfirmDialog";
import { InfoTip } from "../InfoTip";
import { NextFireCell, ScheduleCell, EnableToggle } from "../ScheduleCells";
import { ToastView, useToast } from "../Toast";
import { formatInZone } from "../../lib/cron";
import { formatRelative } from "../../lib/time";

/**
 * One section of the Automation page. All three — Workflow automation,
 * Platform reports, Platform infrastructure — are this component with different
 * columns and a different dialog, so every schedule is enabled, fired, skipped,
 * edited and deleted exactly the same way whatever it does.
 *
 * <p>The three destructive-ish actions (fire now, skip next, delete) always
 * confirm first: firing early and skipping are both invisible afterwards, so
 * the dialog is the only place the operator can see what is about to happen.
 */

/** A column between Name and Schedule — what makes this section's rows specific. */
export interface ScheduleColumn {
  header: string;
  cell: (job: CronJobSummary) => ReactNode;
  /** Extra class on both the header and the body cell. */
  className?: string;
}

export interface SchedulesSectionProps {
  title: string;
  /** The ⓘ text. One sentence, max — anything longer belongs in the section body. */
  info: string;
  /** Label of the create button, without the "+ " prefix. */
  addLabel: string;
  /** Shown instead of the table when there are none. */
  empty: ReactNode;
  jobs: CronJobSummary[];
  columns: ScheduleColumn[];
  /** Verb for the manual fire — "Run now", "Send now", "Apply now". */
  fireLabel: string;
  /** What firing now does, for the confirm dialog. */
  fireBody: ReactNode;
  /** What deleting removes, for the confirm dialog. */
  deleteBody?: ReactNode;
  /** Extra per-row buttons rendered before Edit (the reports' Preview). */
  rowExtras?: (job: CronJobSummary, busy: boolean) => ReactNode;
  /** The create/edit dialog. Rendered only while open. */
  renderDialog: (args: {
    editing?: CronJobSummary;
    onClose: () => void;
    onSaved: (job: CronJobSummary) => void;
  }) => ReactNode;
  /** Called after any change so the page refetches. */
  onChanged: () => void;
}

/** A critical action awaiting confirmation. */
type PendingAction = { kind: "fire" | "skip" | "delete"; job: CronJobSummary };

export function SchedulesSection({
  title, info, addLabel, empty, jobs, columns,
  fireLabel, fireBody, deleteBody, rowExtras, renderDialog, onChanged,
}: SchedulesSectionProps) {
  const [showCreate, setShowCreate] = useState(false);
  const [editJob, setEditJob] = useState<CronJobSummary | null>(null);
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
      // A fire reports its own outcome: a workflow that is already running, or
      // one whose graph no longer fits the group's reservation, comes back
      // SKIPPED with the reason rather than as an error.
      await runAction(j.cronJobId, async () => {
        const r = await cronJobsApi.fireNow(j.cronJobId);
        if (r.outcome === "LAUNCHED") {
          showToast({ variant: "ok", text: `Started "${j.name}".` });
        } else if (r.outcome === "SKIPPED") {
          showToast({ variant: "warn", text: `"${j.name}" skipped`, detail: r.error ?? "nothing to do" });
        } else {
          showToast({ variant: "err", text: `"${j.name}" failed`, detail: r.error ?? "see logs" });
        }
      }, "");
    }
    setPending(null);
  }

  return (
    <section className="schedulesSection" aria-label={title}>
      <div className="pageHeader schedulesSection__head">
        <h2>
          {title}
          <InfoTip label={`About ${title}`}>{info}</InfoTip>
        </h2>
        <button type="button" className="btn btn--primary btn--sm" onClick={() => setShowCreate(true)}>
          + {addLabel}
        </button>
      </div>

      <ToastView toast={toast} onDismiss={dismiss} />

      {jobs.length === 0 ? (
        <p className="ink-soft schedulesSection__empty">{empty}</p>
      ) : (
        <table className="runsTable">
          <thead>
            <tr>
              <th>Name</th>
              {columns.map((c) => <th key={c.header} className={c.className}>{c.header}</th>)}
              <th>Schedule</th>
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
                  {columns.map((c) => <td key={c.header} className={c.className}>{c.cell(j)}</td>)}
                  <td><ScheduleCell cron={j.cronExpression} timeZone={j.timeZone} /></td>
                  <td>
                    {j.lastFiredAt ? formatRelative(j.lastFiredAt) : "—"}
                    {j.lastFireStatus && (
                      <small className="ink-soft" style={{ display: "block" }}>{j.lastFireStatus}</small>
                    )}
                  </td>
                  <td><NextFireCell nextFireAt={j.nextFireAt} timeZone={j.timeZone} enabled={j.enabled} /></td>
                  <td className="runsTable__actions">
                    <EnableToggle enabled={j.enabled} busy={busy} onToggle={() => toggleEnabled(j)} />
                    {rowExtras?.(j, busy)}
                    <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                            onClick={() => setPending({ kind: "fire", job: j })}>
                      {fireLabel}
                    </button>
                    {j.enabled && j.nextFireAt && (
                      <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                              onClick={() => setPending({ kind: "skip", job: j })}
                              title="Skip the next scheduled fire">
                        Skip next
                      </button>
                    )}
                    <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                            onClick={() => setEditJob(j)}>
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

      {showCreate && renderDialog({
        onClose: () => setShowCreate(false),
        onSaved: (job) => {
          setShowCreate(false);
          showToast({ variant: "ok", text: `"${job.name}" created.` });
          onChanged();
        },
      })}

      {editJob && renderDialog({
        editing: editJob,
        onClose: () => setEditJob(null),
        onSaved: (job) => {
          setEditJob(null);
          showToast({ variant: "ok", text: `"${job.name}" updated.` });
          onChanged();
        },
      })}

      {pending && (
        <ConfirmDialog
          title={
            pending.kind === "delete" ? `Delete "${pending.job.name}"?`
            : pending.kind === "skip" ? `Skip the next "${pending.job.name}"?`
            : `${fireLabel} — "${pending.job.name}"?`
          }
          body={
            pending.kind === "delete"
              ? (deleteBody ?? <>This permanently removes the schedule. This can&apos;t be undone.</>)
              : pending.kind === "skip"
                ? <>The next scheduled fire{pending.job.nextFireAt
                    ? <> (<span className="mono">{formatInZone(new Date(pending.job.nextFireAt), pending.job.timeZone)}</span>)</>
                    : null} won&apos;t happen — the schedule advances to the one after it and stays enabled.</>
                : fireBody
          }
          confirmLabel={
            pending.kind === "delete" ? "Delete schedule"
            : pending.kind === "skip" ? "Skip next" : fireLabel
          }
          danger={pending.kind === "delete"}
          busy={busyId === pending.job.cronJobId}
          onConfirm={() => void confirmPending()}
          onCancel={() => setPending(null)}
        />
      )}
    </section>
  );
}
