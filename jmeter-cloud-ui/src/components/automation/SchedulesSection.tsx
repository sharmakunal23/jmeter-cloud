import { useState, type ReactNode } from "react";

import { cronJobsApi, CronJobApiError, type CronJobSummary } from "../../api/automation";
import { ConfirmDialog } from "../ConfirmDialog";
import { DataList, type DataListColumn } from "../DataList";
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
  /** A bulk delete awaiting confirmation — deleting many at once must never be one click. */
  const [pendingBulk, setPendingBulk] = useState<CronJobSummary[] | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
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

  /**
   * Enable or disable a selection. Each call is independent, so a partial
   * failure leaves the rest applied and says how many landed rather than
   * pretending the whole batch failed.
   */
  async function bulkSetEnabled(rows: CronJobSummary[], enabled: boolean) {
    const todo = rows.filter((r) => r.enabled !== enabled);
    if (todo.length === 0) return;
    setBulkBusy(true);
    const results = await Promise.allSettled(todo.map((r) =>
      enabled ? cronJobsApi.enable(r.cronJobId) : cronJobsApi.disable(r.cronJobId)));
    setBulkBusy(false);
    const failed = results.filter((r) => r.status === "rejected").length;
    showToast(failed === 0
      ? { variant: "ok", text: `${todo.length} schedule(s) ${enabled ? "enabled" : "disabled"}.` }
      : { variant: "warn", text: `${todo.length - failed} of ${todo.length} ${enabled ? "enabled" : "disabled"}`,
          detail: `${failed} failed — see the row status.` });
    onChanged();
  }

  async function confirmBulkDelete() {
    const rows = pendingBulk ?? [];
    setBulkBusy(true);
    const results = await Promise.allSettled(rows.map((r) => cronJobsApi.delete(r.cronJobId)));
    setBulkBusy(false);
    setPendingBulk(null);
    const failed = results.filter((r) => r.status === "rejected").length;
    showToast(failed === 0
      ? { variant: "ok", text: `${rows.length} schedule(s) deleted.` }
      : { variant: "warn", text: `${rows.length - failed} of ${rows.length} deleted`,
          detail: `${failed} failed.` });
    onChanged();
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

      <DataList<CronJobSummary>
        label={title}
        rows={jobs}
        rowKey={(j) => j.cronJobId}
        itemNoun="schedules"
        empty={empty}
        columns={[
          { key: "name", header: "Name", cell: (j) => <strong>{j.name}</strong> },
          ...columns.map((c): DataListColumn<CronJobSummary> => ({
            key: c.header, header: c.header, className: c.className, cell: c.cell,
          })),
          { key: "schedule", header: "Schedule",
            cell: (j) => <ScheduleCell cron={j.cronExpression} timeZone={j.timeZone} /> },
          { key: "lastFired", header: "Last Fired", cell: (j) => (
            <>
              {j.lastFiredAt ? formatRelative(j.lastFiredAt) : "—"}
              {j.lastFireStatus && (
                <small className="ink-soft" style={{ display: "block" }}>{j.lastFireStatus}</small>
              )}
            </>
          ) },
          { key: "nextFire", header: "Next Fire", cell: (j) => (
            <NextFireCell nextFireAt={j.nextFireAt} timeZone={j.timeZone} enabled={j.enabled} />
          ) },
          { key: "actions", header: <span className="visuallyHidden">Actions</span>,
            className: "runsTable__actions",
            cell: (j) => {
              const busy = busyId === j.cronJobId;
              return (
                <>
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
                  {/* Bulk delete is additional, not a replacement: deleting one
                      row must not require selecting it first. */}
                  <button type="button" className="btn btn--ghost btn--sm text--error" disabled={busy}
                          onClick={() => setPending({ kind: "delete", job: j })}>
                    Delete
                  </button>
                </>
              );
            } },
        ]}
        bulkActions={[
          { label: "Enable", onRun: (rows) => void bulkSetEnabled(rows, true),
            disabled: (rows) => rows.every((r) => r.enabled) },
          { label: "Disable", onRun: (rows) => void bulkSetEnabled(rows, false),
            disabled: (rows) => rows.every((r) => !r.enabled) },
          { label: "Delete", danger: true, onRun: (rows) => setPendingBulk(rows) },
        ]}
      />

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

      {pendingBulk && (
        <ConfirmDialog
          title={`Delete ${pendingBulk.length} schedule(s)?`}
          body={<>This permanently removes {pendingBulk.length} schedule(s). This can&apos;t be undone.</>}
          confirmLabel={`Delete ${pendingBulk.length}`}
          danger
          busy={bulkBusy}
          onConfirm={() => void confirmBulkDelete()}
          onCancel={() => setPendingBulk(null)}
        />
      )}

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
