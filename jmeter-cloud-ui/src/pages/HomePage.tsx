import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import {
  applicationsApi,
  type Application,
  type HealthStatus,
} from "../api/applications";
import { runsApi, type Run } from "../api/runs";
import { regionsApi, type RegionCapacity } from "../api/regions";
import { cronJobsApi, isReportKind, type CronJobKind } from "../api/automation";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { describeCron, formatInZone } from "../lib/cron";
import { formatFuture, formatRelative } from "../lib/time";

/**
 * UI-D4 + D-HomeRebuild — Home dashboard, framed as a checklist.
 *
 * <p>Two sections:
 * <ul>
 *   <li><strong>Health checklist</strong> — every backend service
 *       (global-orch, document-service, postgres) plus every registered
 *       application. Each row shows a HEALTHY / DEGRADED / UNHEALTHY /
 *       UNKNOWN badge. Backends use {@code /actuator/health}; apps use
 *       the registry's poller-populated {@code lastHealthStatus}.</li>
 *   <li><strong>Upcoming CRON jobs</strong> — currently a stub. The
 *       Automation track (UI-D6) ships the real scheduler; this section
 *       reserves the layout slot.</li>
 * </ul>
 *
 * <p>Polled every 10s through {@link useVisiblePolling} — pauses when
 * the browser tab is hidden.
 */

const POLL_INTERVAL_MS = 10_000;
/** Home is a snapshot — every tile previews a bounded number of rows and links to
 *  its full page, so the dashboard never grows unbounded. Applications (the main
 *  health checklist) gets a larger cap than the secondary tiles. */
const HOME_APPS_LIMIT = 15;
const HOME_PREVIEW_LIMIT = 5;

interface BackendCheck {
  id: string;
  label: string;
  url: string;
}

const BACKEND_CHECKS: BackendCheck[] = [
  { id: "global-orchestrator", label: "global-orchestrator", url: "/actuator/health" },
  // document-service shares the nginx proxy via /api/v1/blob* but its
  // actuator isn't exposed; ping the listing endpoint as a cheap liveness check.
  { id: "document-service",    label: "document-service",    url: "/api/v1/blob?limit=1" },
  // postgres is upstream of global-orch's actuator (db check rolls up there);
  // for a simple-row check we hit /api/v1/regions which queries the run-state DB.
  { id: "postgres",            label: "postgres (run-state)", url: "/api/v1/regions" },
];

interface PlatformCheck {
  id: string;
  label: string;
  status: HealthStatus;
  detail?: string;
}

interface CapacityRollupRow {
  region: string;
  inUse: number;
  readyToUse: number;
  maxAvailable: number;
}

interface DashboardSummary {
  applications: Application[];
  backends: PlatformCheck[];
  capacityByRegion: CapacityRollupRow[];
  refreshedAt: Date;
}

type State =
  | { status: "loading" }
  | { status: "ok"; summary: DashboardSummary }
  | { status: "error"; message: string };

export function HomePage() {
  const [state, setState] = useState<State>({ status: "loading" });

  async function refresh(signal?: AbortSignal) {
    try {
      const [apps, backends, activeListing, regions] = await Promise.all([
        applicationsApi.list(signal),
        Promise.all(BACKEND_CHECKS.map((c) => probeBackend(c, signal))),
        runsApi.listPage({ activeOnly: true, limit: 200 }, signal),
        regionsApi.list().catch(() => [] as RegionCapacity[]),
      ]);
      const capacityByRegion = aggregateCapacityByRegion(apps, activeListing.runs, regions);
      setState({
        status: "ok",
        summary: {
          applications: apps,
          backends,
          capacityByRegion,
          refreshedAt: new Date(),
        },
      });
    } catch (err: unknown) {
      if (signal?.aborted) return;
      setState((prev) =>
        prev.status === "ok"
          ? prev
          : { status: "error", message: err instanceof Error ? err.message : String(err) },
      );
    }
  }

  useEffect(() => {
    const ctl = new AbortController();
    void refresh(ctl.signal);
    return () => ctl.abort();
  }, []);

  const { isPaused } = useVisiblePolling(() => { void refresh(); }, POLL_INTERVAL_MS);

  if (state.status === "loading") return <p className="ink-soft">Loading dashboard…</p>;
  if (state.status === "error")   return <p className="text--error">{state.message}</p>;

  const { applications, backends, capacityByRegion, refreshedAt } = state.summary;
  const everythingHealthy =
    backends.every((b) => b.status === "HEALTHY") &&
    applications.every((a) => (a.lastHealthStatus ?? "UNKNOWN") === "HEALTHY");
  const failingChecks =
    backends.filter((b) => b.status === "UNHEALTHY" || b.status === "DEGRADED").length +
    applications.filter((a) => {
      const s = a.lastHealthStatus ?? "UNKNOWN";
      return s === "UNHEALTHY" || s === "DEGRADED";
    }).length;

  return (
    <section className="homePage">
      {/* Standardization sweep (2026-05-13) — Home page header now uses
          the same .pageHeader + .pageHeader__titleGroup shape as every
          IA list page (Applications/Capacity/Documents/Templates/
          Automation), so the h1 sizing + the "Refreshed Xs ago" placement
          line up across the app. The bespoke .homePage__header / .homeChip
          classes are gone; the status chip uses the shared .chip family. */}
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <h1>Performance Platform</h1>
          <small className="ink-soft" aria-live="polite">
            {isPaused
              ? "Polling paused (tab hidden)"
              : `Refreshed ${formatRelative(refreshedAt.toISOString())}`}
          </small>
        </div>
        <span
          className={`chip ${everythingHealthy ? "chip--ok" : failingChecks > 0 ? "chip--warn" : ""}`}
          title="Aggregate health of backends + applications"
        >
          <span className="mono">status</span>
          <span className="mono">
            {everythingHealthy ? "all healthy" : failingChecks > 0 ? `${failingChecks} failing` : "mixed"}
          </span>
        </span>
      </header>

      <div className="homeGrid">
        {/* Left column — Applications. Home is a snapshot, so it caps at
            HOME_APPS_LIMIT (15) with a "view all" link rather than paginating;
            the full Applications tab paginates at 15/page (useClientPagination). */}
        <ApplicationsChecklist applications={applications} />

        {/* Right column — Platform + Scheduled runs stacked. Platform is
            small (3 rows today), Schedule is a stub for D6. */}
        <div className="homeGrid__right">
          <PlatformChecklist backends={backends} />
          <CapacityRollup rows={capacityByRegion} />
          <ScheduleChecklist />
        </div>
      </div>
    </section>
  );
}

// ── Sub-sections ──────────────────────────────────────────────────

function ApplicationsChecklist({ applications }: { applications: Application[] }) {
  const total = applications.length;
  const visible = useMemo(() => applications.slice(0, HOME_APPS_LIMIT), [applications]);

  return (
    <section className="checklist homeGrid__apps">
      <header className="checklist__head">
        <h2>Applications</h2>
        <small className="ink-soft">
          <Link to="/applications">Manage applications →</Link>
        </small>
      </header>
      {applications.length === 0 ? (
        <div className="emptyState">
          <p>No applications registered yet.</p>
          <p className="ink-soft">
            Register one in <Link to="/applications">Applications</Link> to start
            launching runs against it.
          </p>
        </div>
      ) : (
        <>
          <ul className="checklist__items" aria-label="application checks">
            {visible.map((a) => (
              <ChecklistRow
                key={a.applicationId}
                label={a.name}
                href={`/applications/${encodeURIComponent(a.name)}`}
                status={a.lastHealthStatus ?? "UNKNOWN"}
                detail={
                  a.healthEndpoints.length === 0
                    ? "no health endpoints configured"
                    : a.lastHealthCheckedAt
                      ? `checked ${formatRelative(a.lastHealthCheckedAt)}`
                      : "not yet polled"
                }
              />
            ))}
          </ul>
          {total > visible.length && (
            <footer className="checklist__more ink-soft">
              <Link to="/applications">+{total - visible.length} more · View all {total} →</Link>
            </footer>
          )}
        </>
      )}
    </section>
  );
}

function PlatformChecklist({ backends }: { backends: PlatformCheck[] }) {
  return (
    <section className="checklist">
      <header className="checklist__head">
        <h2>Platform</h2>
        <small className="ink-soft">Backend services + datastores</small>
      </header>
      <ul className="checklist__items" aria-label="platform checks">
        {backends.map((b) => (
          <ChecklistRow
            key={b.id}
            label={b.label}
            status={b.status}
            detail={b.detail}
          />
        ))}
      </ul>
    </section>
  );
}

/**
 * Platform-wide capacity rollup — sums maxAvailable + inUse across all
 * apps for each region. Click-through goes to /capacity for the per-app
 * breakdown. Empty state renders when no apps are registered.
 */
function CapacityRollup({ rows }: { rows: CapacityRollupRow[] }) {
  const totals = rows.reduce(
    (acc, r) => ({
      inUse: acc.inUse + r.inUse,
      maxAvailable: acc.maxAvailable + r.maxAvailable,
    }),
    { inUse: 0, maxAvailable: 0 },
  );
  return (
    <section className="checklist">
      <header className="checklist__head">
        <h2>Capacity</h2>
        <small className="ink-soft">
          <Link to="/capacity">Per-app breakdown →</Link>
        </small>
      </header>
      {rows.length === 0 ? (
        <div className="emptyState emptyState--compact">
          <p className="ink-soft">No applications registered — nothing to roll up yet.</p>
        </div>
      ) : (
        <>
          <table className="runsTable capacityRollupTable">
            <thead>
              <tr>
                <th>Region</th>
                <th className="num">In Use</th>
                <th className="num">Ready</th>
                <th className="num">Max</th>
                <th><span className="visuallyHidden">Utilization</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.slice(0, HOME_PREVIEW_LIMIT).map((r) => {
                const ratio = r.maxAvailable > 0 ? r.inUse / r.maxAvailable : 0;
                const variant: "ok" | "warn" | "err" =
                  ratio >= 1 ? "err" : ratio >= 0.80 ? "warn" : "ok";
                return (
                  <tr key={r.region}>
                    <td className="mono">{r.region}</td>
                    <td className="mono num">{r.inUse}</td>
                    <td className="mono num">{r.readyToUse}</td>
                    <td className="mono num">{r.maxAvailable}</td>
                    <td>
                      {/* Same inline bar + % as the per-app Capacity detail
                          page (.regionPanel__util) so they render identically. */}
                      <span className="regionPanel__util" title={`${Math.round(ratio * 100)}% utilized`}>
                        <span
                          className={`capacityBar capacityBar--${variant} regionPanel__utilBar`}
                          aria-hidden="true"
                        >
                          <span style={{ width: `${Math.min(100, Math.round(ratio * 100))}%` }} />
                        </span>
                        <small className="mono ink-soft">{Math.round(ratio * 100)}%</small>
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <footer className="capacityRollup__footer ink-soft">
            <strong className="mono">{totals.inUse}</strong> of{" "}
            <strong className="mono">{totals.maxAvailable}</strong> workers in use across all regions
            {rows.length > HOME_PREVIEW_LIMIT && (
              <> · <Link to="/capacity">+{rows.length - HOME_PREVIEW_LIMIT} more regions →</Link></>
            )}
          </footer>
        </>
      )}
    </section>
  );
}

interface ScheduledJob {
  id: string;
  name: string;
  /** What the schedule does, in human terms ("nightly → checkout"). */
  target: string;
  kind: CronJobKind;
  cronExpression: string;
  timeZone: string;
  nextFireAt: string;
}

/** "Daily report" / "Drain us-east-1" / "nightly → checkout" — what fires. */
function jobTarget(name: string, appName: string | null | undefined, kind: CronJobKind, region: string | null | undefined): string {
  if (kind === "DRAIN_REGION")     return `Drain ${region ?? "region"} · ${appName ?? "—"}`;
  if (kind === "PROVISION_REGION") return `Provision ${region ?? "region"} · ${appName ?? "—"}`;
  if (isReportKind(kind))          return "Platform email report";
  return `${name} → ${appName ?? "—"}`;
}

function ScheduleChecklist() {
  // AUTOMATION Phase A+B — enabled schedules with an upcoming fire, soonest
  // first. A failed fetch leaves the section empty rather than breaking the
  // dashboard. Home shows only the soonest few; /automation has the full list.
  const [jobs, setJobs] = useState<ScheduledJob[]>([]);
  useEffect(() => {
    const ctl = new AbortController();
    cronJobsApi.list({ enabled: true }, ctl.signal)
      .then((items) =>
        setJobs(
          items
            .filter((j) => j.nextFireAt)
            .map((j) => ({
              id: j.cronJobId,
              name: j.name,
              target: jobTarget(j.name, j.applicationName, j.kind, j.region),
              kind: j.kind,
              cronExpression: j.cronExpression,
              timeZone: j.timeZone,
              nextFireAt: j.nextFireAt as string,
            }))
            .sort((a, b) => new Date(a.nextFireAt).getTime() - new Date(b.nextFireAt).getTime()),
        ),
      )
      .catch(() => { /* dashboard tile — stay empty on error */ });
    return () => ctl.abort();
  }, []);

  const total = jobs.length;
  const visible = jobs.slice(0, HOME_PREVIEW_LIMIT);
  const [next, ...rest] = visible;

  return (
    <section className="checklist">
      <header className="checklist__head">
        <h2>Upcoming scheduled runs</h2>
        <small className="ink-soft">
          <Link to="/automation">Configure →</Link>
        </small>
      </header>
      {total === 0 ? (
        <div className="emptyState emptyState--compact">
          <p>No scheduled jobs yet.</p>
          <p className="ink-soft">
            Create a schedule in <Link to="/automation">Automation</Link> to fire a
            saved template on a CRON expression. Enabled jobs land here ordered by
            next fire-time.
          </p>
        </div>
      ) : (
        <>
          {/* Next-up — the single soonest fire, called out so the operator sees
              what runs next at a glance (was a broken "fires -NNNs ago"). */}
          <div className="nextFire">
            <span className="nextFire__eyebrow">Next up</span>
            <div className="nextFire__name">{next.target}</div>
            <div className="nextFire__when">
              <strong>{formatInZone(new Date(next.nextFireAt), next.timeZone)}</strong>
              <span className="ink-soft"> · {formatFuture(next.nextFireAt)}</span>
            </div>
            <div className="nextFire__cadence ink-soft">
              {describeCron(next.cronExpression) ?? <code className="mono">{next.cronExpression}</code>}
            </div>
          </div>

          {rest.length > 0 && (
            <ul className="upcomingList" aria-label="upcoming scheduled jobs">
              {rest.map((j) => (
                <li key={j.id} className="upcomingList__row">
                  <span className="upcomingList__target">{j.target}</span>
                  <span className="upcomingList__when ink-soft" title={formatInZone(new Date(j.nextFireAt), j.timeZone)}>
                    {formatFuture(j.nextFireAt)}
                  </span>
                </li>
              ))}
            </ul>
          )}

          {total > visible.length && (
            <footer className="upcomingList__footer ink-soft">
              <Link to="/automation">+{total - visible.length} more in Automation →</Link>
            </footer>
          )}
        </>
      )}
    </section>
  );
}

// ── Checklist row ─────────────────────────────────────────────────

function ChecklistRow({
  label, status, detail, href,
}: { label: string; status: HealthStatus; detail?: string; href?: string }) {
  const inner = (
    <>
      <span className="checklistRow__label mono">{label}</span>
      {detail && <small className="checklistRow__detail ink-soft">{detail}</small>}
      <span
        className={`healthBadge healthBadge--${status.toLowerCase()} healthBadge--compact checklistRow__badge`}
        aria-label={`status: ${status.toLowerCase()}`}
      >
        <span className="healthBadge__dot" aria-hidden="true" />
        {status}
      </span>
    </>
  );
  return (
    <li className="checklistRow">
      {href
        ? <Link to={href} className="checklistRow__link">{inner}</Link>
        : <div className="checklistRow__link checklistRow__link--static">{inner}</div>}
    </li>
  );
}

// ── Backend probe ─────────────────────────────────────────────────

async function probeBackend(c: BackendCheck, signal?: AbortSignal): Promise<PlatformCheck> {
  const started = performance.now();
  try {
    const resp = await fetch(c.url, { signal, headers: { Accept: "application/json" } });
    const elapsed = Math.round(performance.now() - started);
    return {
      id: c.id,
      label: c.label,
      status: resp.ok ? "HEALTHY" : "UNHEALTHY",
      detail: resp.ok ? `${elapsed}ms · HTTP ${resp.status}` : `HTTP ${resp.status}`,
    };
  } catch (err) {
    return {
      id: c.id,
      label: c.label,
      status: "UNHEALTHY",
      detail: err instanceof Error ? err.message : String(err),
    };
  }
}

/**
 * Sums per-(app, region) capacity into per-region totals + computes
 * inUse from the active runs' fleet members. readyToUse is bounded by
 * the actual pods registered in the region (post-Capacity-rework, pods
 * are provisioned on demand — a region with `maxAvailable=8` but no
 * provisioned pods has `readyToUse=0`, not `8`).
 */
function aggregateCapacityByRegion(
  apps: Application[],
  activeRuns: Run[],
  regions: RegionCapacity[],
): CapacityRollupRow[] {
  // Sum maxAvailable across all apps per region.
  const maxByRegion = new Map<string, number>();
  for (const app of apps) {
    for (const c of app.capacity ?? []) {
      maxByRegion.set(c.region, (maxByRegion.get(c.region) ?? 0) + c.maxAvailable);
    }
  }
  // Sum inUse across all active runs per region (count fleet members).
  const inUseByRegion = new Map<string, number>();
  for (const run of activeRuns) {
    for (const m of run.fleetMembers) {
      inUseByRegion.set(m.region, (inUseByRegion.get(m.region) ?? 0) + 1);
    }
  }
  const idleByRegion = new Map(regions.map((r) => [r.region, r.idlePods]));
  // Sort by region name so the order is deterministic.
  const allRegions = Array.from(new Set([
    ...maxByRegion.keys(),
    ...inUseByRegion.keys(),
  ])).sort();
  return allRegions.map((region) => {
    const max = maxByRegion.get(region) ?? 0;
    const inUse = inUseByRegion.get(region) ?? 0;
    const headroom = Math.max(0, max - inUse);
    // Missing region in /regions response means no pods registered there
    // (pre-rework this defaulted to `?? max` assuming static orchestrator
    // pods always existed; that assumption broke when Phase 6 deleted the
    // static pods + made provisioning per-app on-demand). Fall back to 0.
    const idle = idleByRegion.get(region) ?? 0;
    return { region, maxAvailable: max, inUse, readyToUse: Math.min(headroom, idle) };
  });
}

