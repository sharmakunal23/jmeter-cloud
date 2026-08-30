import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";

import { applicationsApi, type Application } from "../api/applications";
import { applicationGroupsApi, type ApplicationGroup } from "../api/applicationGroups";
import { cronJobsApi, CronJobApiError, type CronJobKind, type CronJobSummary } from "../api/automation";
import { CreateScheduleDialog } from "../components/CreateScheduleDialog";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { ScheduleCell, NextFireCell, EnableToggle } from "../components/ScheduleCells";
import { ToastView, useToast } from "../components/Toast";
import { formatInZone } from "../lib/cron";
import { formatRelative } from "../lib/time";

/** Phase C — render schedules grouped by kind: [kind, section title, target-column label]. */
const KIND_SECTIONS: ReadonlyArray<readonly [CronJobKind, string, string]> = [
  ["LAUNCH_RUN", "Launches", "Template"],
  ["DRAIN_REGION", "Drains", "Region"],
  ["PROVISION_REGION", "Provisions", "Region"],
];

/**
 * Per-application automation drill-in (`/automation/{appName}`). Lists the
 * app's CRON schedules and drives the full lifecycle against the live backend
 * (AUTOMATION Phase A+B): create (modal), enable/disable, fire-now, delete.
 *
 * <p>See `jmeter-cloud-ui/docs/automationPlan.md` for the design.
 */

type AppLookup =
  | { status: "loading" }
  | { status: "ok"; app: Application }
  | { status: "notFound" }
  | { status: "error"; message: string };

type CronJobsState =
  | { status: "loading" }
  | { status: "ok"; jobs: CronJobSummary[] }
  | { status: "error"; message: string };

/** A critical action awaiting confirmation in the modal. */
type PendingAction = { kind: "fire" | "skip" | "delete"; job: CronJobSummary };

export function AutomationDetailPage() {
  const { appName: appNameParam = "" } = useParams<{ appName: string }>();

  const [appLookup, setAppLookup] = useState<AppLookup>({ status: "loading" });
  // The app's group — its capacity grid is the region list for DRAIN / PROVISION schedules.
  const [group, setGroup] = useState<ApplicationGroup | null>(null);
  const [jobs, setJobs] = useState<CronJobsState>({ status: "loading" });
  const [showCreate, setShowCreate] = useState(false);
  const [editJob, setEditJob] = useState<CronJobSummary | null>(null);
  // Critical actions (fire / skip / delete) route through a confirm modal.
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [alsoSkip, setAlsoSkip] = useState(false);
  const { toast, showToast, dismiss } = useToast();
  // Per-row in-flight guard so double-clicks can't fire twice.
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    const ctl = new AbortController();
    applicationsApi.list(ctl.signal)
      .then((apps) => {
        const app = apps.find((a) => a.name === appNameParam);
        setAppLookup(app ? { status: "ok", app } : { status: "notFound" });
        if (app) {
          applicationGroupsApi.get(app.metricsGroupId, ctl.signal)
            .then(setGroup)
            .catch(() => { if (!ctl.signal.aborted) setGroup(null); });
        }
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setAppLookup({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [appNameParam]);

  const refreshJobs = useCallback((signal?: AbortSignal) => {
    cronJobsApi.list({ application: appNameParam }, signal)
      .then((items) => {
        const scoped = items.filter((j) => j.applicationName === appNameParam);
        setJobs({ status: "ok", jobs: scoped });
      })
      .catch((err: unknown) => {
        if (signal?.aborted) return;
        setJobs({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
  }, [appNameParam]);

  useEffect(() => {
    const ctl = new AbortController();
    refreshJobs(ctl.signal);
    return () => ctl.abort();
  }, [refreshJobs]);

  async function runAction(id: string, action: () => Promise<unknown>, successMsg: string) {
    setBusyId(id);
    try {
      await action();
      // fireNow shows its own (outcome-specific) toast — don't clobber it with
      // an empty successMsg.
      if (successMsg) showToast({ variant: "ok", text: successMsg });
      refreshJobs();
    } catch (err) {
      const msg = err instanceof CronJobApiError ? `${err.code}: ${err.message}`
        : err instanceof Error ? err.message : String(err);
      showToast({ variant: "err", text: msg });
    } finally {
      setBusyId(null);
    }
  }

  // Enable/disable is reversible + low-stakes → no confirm, just act + toast.
  function toggleEnabled(j: CronJobSummary) {
    void runAction(
      j.cronJobId,
      () => (j.enabled ? cronJobsApi.disable(j.cronJobId) : cronJobsApi.enable(j.cronJobId)),
      `Schedule "${j.name}" ${j.enabled ? "disabled" : "enabled"}.`,
    );
  }

  // Run the confirmed critical action. Fire optionally also skips the next slot.
  async function confirmPending() {
    if (!pending) return;
    const j = pending.job;
    if (pending.kind === "delete") {
      await runAction(j.cronJobId, () => cronJobsApi.delete(j.cronJobId), `Schedule "${j.name}" deleted.`);
    } else if (pending.kind === "skip") {
      await runAction(j.cronJobId, () => cronJobsApi.skipNext(j.cronJobId),
        `Skipped the next "${j.name}" run.`);
    } else {
      const skipToo = alsoSkip && j.enabled && !!j.nextFireAt;
      await runAction(j.cronJobId, async () => {
        const r = await cronJobsApi.fireNow(j.cronJobId);
        const suffix = skipToo ? " Next scheduled run skipped." : "";
        if (r.outcome === "LAUNCHED") {
          if (skipToo) await cronJobsApi.skipNext(j.cronJobId);
          showToast({
            variant: "ok",
            text: `Fired "${j.name}" — run ${r.runId} launched.${suffix}`,
            action: r.runId ? { label: "Open run →", href: `/applications/${encodeURIComponent(app.name)}/runs/${r.runId}` } : undefined,
          });
        } else if (r.outcome === "SKIPPED") {
          showToast({ variant: "warn", text: `"${j.name}" skipped`, detail: r.error ?? "no capacity" });
        } else {
          showToast({ variant: "err", text: `"${j.name}" failed`, detail: r.error ?? "see logs" });
        }
      }, "");
    }
    setPending(null);
    setAlsoSkip(false);
  }

  if (appLookup.status === "loading") {
    return <p className="ink-soft">Loading automation for {appNameParam}…</p>;
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
        <p><Link to="/automation" className="btn btn--ghost">← Back to Automation</Link></p>
      </section>
    );
  }

  const { app } = appLookup;

  return (
    <section className="capacityPage capacityDetail automationDetailPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <Link to="/automation" className="ink-soft" style={{ fontSize: "0.85rem" }}>← Automation</Link>
          <h1 className="capacityDetail__title"><span className="mono">{app.name}</span></h1>
        </div>
        <div className="capacityDetail__nav">
          <Link to={`/applications/${encodeURIComponent(app.name)}`} className="btn btn--ghost">
            Open Application →
          </Link>
          <button type="button" className="btn btn--primary" onClick={() => setShowCreate(true)}>
            + New schedule
          </button>
        </div>
      </header>

      <ToastView toast={toast} onDismiss={dismiss} />

      {jobs.status === "loading" && <p className="ink-soft">Loading schedules…</p>}
      {jobs.status === "error" && <p className="text--error">{jobs.message}</p>}

      {jobs.status === "ok" && jobs.jobs.length === 0 && (
        <div className="emptyState">
          <p>
            No schedules for <span className="mono">{app.name}</span> yet.
          </p>
          <p className="ink-soft">
            Click <strong>+ New schedule</strong> to pick a{" "}
            <Link to={`/templates/${encodeURIComponent(app.name)}`}>template</Link>{" "}
            and a CRON expression.
          </p>
        </div>
      )}

      {jobs.status === "ok" && jobs.jobs.length > 0 && (
        // Phase C — grouped by kind so the operator can audit the daily cycle
        // (Launches / Drains / Provisions) at a glance.
        <>
          {KIND_SECTIONS.map(([kind, title, targetLabel]) => {
            const group = (jobs as Extract<CronJobsState, { status: "ok" }>).jobs
              .filter((j) => j.kind === kind);
            if (group.length === 0) return null;
            return (
              <section key={kind} className="automationGroup">
                <h2 className="automationGroup__title">
                  {title} <span className="ink-soft">({group.length})</span>
                </h2>
                <table className="runsTable">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>{targetLabel}</th>
                      <th>Schedule</th>
                      <th>Last Fired</th>
                      <th>Next Fire</th>
                      <th><span className="visuallyHidden">Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {group.map((j) => {
                      const busy = busyId === j.cronJobId;
                      return (
                        <tr key={j.cronJobId}>
                          <td><strong>{j.name}</strong></td>
                          <td className="mono">
                            {j.kind === "LAUNCH_RUN"
                              ? (j.templateBlobId ? `${j.templateBlobId.slice(0, 12)}…` : "—")
                              : (j.region ?? "—")}
                          </td>
                          <td><ScheduleCell cron={j.cronExpression} timeZone={j.timeZone} /></td>
                          <td>
                            {j.lastFiredAt ? formatRelative(j.lastFiredAt) : "—"}
                            {j.lastFireStatus && (
                              <small className="ink-soft" style={{ display: "block" }}>
                                {j.lastFireStatus}
                              </small>
                            )}
                          </td>
                          <td><NextFireCell nextFireAt={j.nextFireAt} timeZone={j.timeZone} enabled={j.enabled} /></td>
                          <td className="runsTable__actions">
                            <EnableToggle enabled={j.enabled} busy={busy} onToggle={() => toggleEnabled(j)} />
                            <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                                    onClick={() => setPending({ kind: "fire", job: j })} title="Fire this schedule now">
                              Fire now
                            </button>
                            {j.enabled && j.nextFireAt && (
                              <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                                      onClick={() => setPending({ kind: "skip", job: j })} title="Skip the next scheduled run">
                                Skip next
                              </button>
                            )}
                            <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                                    onClick={() => setEditJob(j)} title="Edit this schedule">
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
              </section>
            );
          })}
        </>
      )}

      {showCreate && (
        <CreateScheduleDialog
          application={app.name}
          groupId={app.metricsGroupId}
          regions={(group?.capacity ?? []).map((c) => c.region)}
          onClose={() => setShowCreate(false)}
          onCreated={(job) => {
            setShowCreate(false);
            showToast({ variant: "ok", text: `Schedule "${job.name}" created.` });
            refreshJobs();
          }}
        />
      )}

      {editJob && (
        <CreateScheduleDialog
          application={app.name}
          groupId={app.metricsGroupId}
          regions={(group?.capacity ?? []).map((c) => c.region)}
          editing={editJob}
          onClose={() => setEditJob(null)}
          onCreated={(job) => {
            setEditJob(null);
            showToast({ variant: "ok", text: `Schedule "${job.name}" updated.` });
            refreshJobs();
          }}
        />
      )}

      {pending && (
        <ConfirmDialog
          title={
            pending.kind === "delete" ? `Delete "${pending.job.name}"?`
            : pending.kind === "skip" ? `Skip the next "${pending.job.name}" run?`
            : `Fire "${pending.job.name}" now?`
          }
          body={confirmBody(pending, app.name)}
          confirmLabel={
            pending.kind === "delete" ? "Delete schedule"
            : pending.kind === "skip" ? "Skip next run"
            : "Fire now"
          }
          danger={pending.kind === "delete"}
          busy={busyId === pending.job.cronJobId}
          onConfirm={() => void confirmPending()}
          onCancel={() => { setPending(null); setAlsoSkip(false); }}
        >
          {pending.kind === "fire" && pending.job.enabled && pending.job.nextFireAt && (
            <label className="confirmDialog__check">
              <input type="checkbox" checked={alsoSkip} onChange={(e) => setAlsoSkip(e.target.checked)} />
              <span>
                Also skip the next scheduled run
                {" "}(<span className="mono">{formatInZone(new Date(pending.job.nextFireAt), pending.job.timeZone)}</span>)
              </span>
            </label>
          )}
        </ConfirmDialog>
      )}
    </section>
  );
}

/** Confirm-modal body copy per action. */
function confirmBody(pending: PendingAction, appName: string): ReactNode {
  if (pending.kind === "delete") {
    return <>This permanently removes the schedule for <span className="mono">{appName}</span>. Its fire history is kept. This can't be undone.</>;
  }
  if (pending.kind === "skip") {
    return <>The next occurrence won't fire — the schedule advances to the one after it and stays enabled.</>;
  }
  return <>This launches the schedule immediately, in addition to its normal cadence.</>;
}

export { AutomationDetailPage as default };
