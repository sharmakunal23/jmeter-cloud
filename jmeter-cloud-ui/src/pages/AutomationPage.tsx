import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import {
  cronJobsApi, isInfraKind, isReportKind, isWorkflowKind,
  type CronJobSummary,
} from "../api/automation";
import { applicationGroupsApi, type ApplicationGroup } from "../api/applicationGroups";
import { SchedulesSection } from "../components/automation/SchedulesSection";
import { WorkflowScheduleDialog } from "../components/automation/WorkflowScheduleDialog";
import { InfraScheduleDialog } from "../components/automation/InfraScheduleDialog";
import { ReportSchedulesSection } from "../components/automation/ReportSchedulesSection";
import { TabStrip, TabPanel } from "../components/TabStrip";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { formatRelative } from "../lib/time";

/**
 * Automation — every schedule the platform fires, in three tabs:
 * <b>Workflow automation</b> (run a workflow), <b>Platform reports</b> (email a
 * report) and <b>Platform infrastructure</b> (scale a cluster out or in). Tabs
 * rather than stacked sections so each list owns the viewport and the counts
 * are visible without scrolling — the same shape Capacity uses.
 *
 * <p>Schedules are scoped to an application <i>group</i>, not an application
 * (AUTOMATION-3, 2026-08-31): a load test is scheduled by scheduling the
 * workflow that runs it, and workers are scaled per (group, cluster) — the axis
 * the reservation grid has always used. There is no per-application drill-in
 * any more, because there is nothing per-application left to show.
 */

const POLL_INTERVAL_MS = 30_000;

type TabId = "workflow" | "reports" | "infrastructure";

type State =
  | { status: "loading" }
  | { status: "ok"; jobs: CronJobSummary[]; groups: ApplicationGroup[]; refreshedAt: Date }
  | { status: "error"; message: string };

export function AutomationPage() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [tab, setTab] = useState<TabId>("workflow");

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const [jobs, groups] = await Promise.all([
        cronJobsApi.list(undefined, signal),
        applicationGroupsApi.list(signal),
      ]);
      setState({ status: "ok", jobs, groups, refreshedAt: new Date() });
    } catch (err: unknown) {
      if (signal?.aborted) return;
      // Keep the last good page on a failed refresh — a blip must not blank a
      // screen the operator is reading.
      setState((prev) => prev.status === "ok" ? prev
        : { status: "error", message: err instanceof Error ? err.message : String(err) });
    }
  }, []);

  useEffect(() => {
    const ctl = new AbortController();
    void refresh(ctl.signal);
    return () => ctl.abort();
  }, [refresh]);

  useVisiblePolling(() => refresh(), POLL_INTERVAL_MS, { name: "automation" });

  const jobs = state.status === "ok" ? state.jobs : [];
  const groupName = useMemo(() => {
    const byId = new Map((state.status === "ok" ? state.groups : []).map((g) => [g.groupId, g.name]));
    return (groupId?: string | null) => (groupId ? byId.get(groupId) ?? groupId : "—");
  }, [state]);

  const workflowJobs = jobs.filter((j) => isWorkflowKind(j.kind));
  const reportJobs = jobs.filter((j) => isReportKind(j.kind));
  const infraJobs = jobs.filter((j) => isInfraKind(j.kind));

  const onChanged = useCallback(() => { void refresh(); }, [refresh]);

  if (state.status === "error") return <p className="text--error">{state.message}</p>;

  const tabs = [
    { id: "workflow" as const, label: "Workflow automation", badge: countBadge(workflowJobs.length) },
    { id: "reports" as const, label: "Platform reports", badge: countBadge(reportJobs.length) },
    { id: "infrastructure" as const, label: "Platform infrastructure", badge: countBadge(infraJobs.length) },
  ];

  return (
    <section className="automationPage">
      <div className="pageHeader">
        <h1>Automation</h1>
        {state.status === "ok" && (
          <span className="ink-soft">Refreshed {formatRelative(state.refreshedAt.toISOString())}</span>
        )}
      </div>

      <TabStrip tabs={tabs} active={tab} onChange={setTab}
                idPrefix="automation" ariaLabel="Automation sections" />

      <TabPanel id="workflow" idPrefix="automation" active={tab === "workflow"}>
          <SchedulesSection
            title="Workflow automation"
            info="Runs a saved workflow on a schedule, in the group that owns it."
            addLabel="Workflow schedule"
            empty={<>No workflow schedules. Add one to run a <Link to="/workflows">workflow</Link> on a
                   cadence — a one-step workflow is how you schedule a single load test.</>}
            jobs={workflowJobs}
            columns={[
              { header: "Group", cell: (j) => groupName(j.groupId) },
              {
                header: "Workflow",
                cell: (j) => j.workflowId
                  ? <Link to={`/workflows/${j.workflowId}`}>{j.workflowName ?? j.workflowId}</Link>
                  // The registry keeps no FK to the workflow, so deleting one leaves
                  // the schedule behind rather than failing the delete. Say so here
                  // instead of rendering a dead link.
                  : <em className="text--error">deleted</em>,
              },
              {
                header: "Last Run",
                cell: (j) => j.lastFiredExecutionId
                  ? <Link to={`/workflows/executions/${j.lastFiredExecutionId}`}>View</Link>
                  : <span className="ink-soft">—</span>,
              },
            ]}
            fireLabel="Run now"
            fireBody={<>This starts the workflow immediately, in addition to its normal cadence. It is
                      skipped if that workflow is already running or its graph no longer fits the
                      group&apos;s reservation.</>}
            deleteBody={<>This removes the schedule. The workflow itself is untouched and can still be
                        run by hand.</>}
            renderDialog={({ editing, onClose, onSaved }) => (
              <WorkflowScheduleDialog editing={editing} onClose={onClose} onSaved={onSaved} />
            )}
            onChanged={onChanged}
          />
      </TabPanel>

      <TabPanel id="reports" idPrefix="automation" active={tab === "reports"}>
        <ReportSchedulesSection jobs={reportJobs} onChanged={onChanged} />
      </TabPanel>

      <TabPanel id="infrastructure" idPrefix="automation" active={tab === "infrastructure"}>
          <SchedulesSection
            title="Platform infrastructure"
            info="Adds or releases workers in one cluster on a schedule."
            addLabel="Scaling schedule"
            empty={<>No scaling schedules. Pair a scale-in in the evening with a scale-out in the
                   morning to stop paying for idle workers overnight.</>}
            jobs={infraJobs}
            columns={[
              { header: "Group", cell: (j) => groupName(j.groupId) },
              { header: "Cluster", cell: (j) => <span className="mono">{j.region ?? "—"}</span> },
              {
                header: "Direction",
                cell: (j) => (
                  <span className={`chip ${j.kind === "SCALE_OUT" ? "chip--ok" : ""}`}>
                    {j.kind === "SCALE_OUT" ? "Scale out" : "Scale in"}
                  </span>
                ),
              },
            ]}
            fireLabel="Apply now"
            fireBody={<>This scales the cluster immediately, in addition to its normal cadence. Workers
                      in use are never released.</>}
            deleteBody={<>This removes the schedule. Workers currently running are untouched.</>}
            renderDialog={({ editing, onClose, onSaved }) => (
              <InfraScheduleDialog editing={editing} onClose={onClose} onSaved={onSaved} />
            )}
            onChanged={onChanged}
          />
      </TabPanel>
    </section>
  );
}

/** A count beside a tab label; nothing at zero, so an empty tab reads as empty. */
function countBadge(n: number) {
  return n > 0 ? n : undefined;
}
