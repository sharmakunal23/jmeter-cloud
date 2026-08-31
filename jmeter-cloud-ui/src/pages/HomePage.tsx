import { Fragment, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import {
  applicationsApi,
  type Application,
  type HealthStatus,
} from "../api/applications";
import { runsApi, type Run } from "../api/runs";
import { applicationGroupsApi, sortByGroup, type ApplicationGroup } from "../api/applicationGroups";
import { regionsApi, type RegionCapacity } from "../api/regions";
import { platformHealthApi, hubUnreachable, type PlatformHealth, type PlatformHealthComponent, type PlatformStatus } from "../api/platformHealth";
import { cronJobsApi, isReportKind, type CronJobKind } from "../api/automation";
import { useVisiblePolling } from "../hooks/useVisiblePolling";
import { describeCron, formatInZone } from "../lib/cron";
import { formatFuture, formatRelative } from "../lib/time";

/**
 * UI-D4 + D-HomeRebuild — Home dashboard, framed as a checklist.
 *
 * <p>Two sections:
 * <ul>
 *   <li><strong>Health checklist</strong> — one row per platform service
 *       (plus one "Database" row) and every registered application, each
 *       with an UP / DOWN-style badge and nothing else. The statuses come
 *       from the hub's {@code GET /api/v1/platform/health} (it probes
 *       everything, regionals included), collapsed by
 *       {@code simplifyPlatform}; apps use the registry's poller-populated
 *       {@code lastHealthStatus}.</li>
 *   <li><strong>Upcoming CRON jobs</strong> — currently a stub. The
 *       Automation track ships the real scheduler; this section
 *       reserves the layout slot.</li>
 * </ul>
 *
 * <p>Polled every 10s through {@link useVisiblePolling} — pauses when
 * the browser tab is hidden.
 */

const POLL_INTERVAL_MS = 60_000;   // health is a per-minute picture, server-side and here
/** Home is a snapshot — every tile previews a bounded number of rows and links to
 *  its full page, so the dashboard never grows unbounded. Applications (the main
 *  health checklist) gets a larger cap than the secondary tiles. */
const HOME_APPS_LIMIT = 15;
const HOME_PREVIEW_LIMIT = 5;

/** Statuses of the hub's platform-health tree → the existing badge palette. */
const BADGE: Record<PlatformStatus, string> = { UP: "healthy", DEGRADED: "degraded", DOWN: "unhealthy", UNKNOWN: "unknown" };

interface CapacityRollupRow {
  region: string;
  inUse: number;
  readyToUse: number;
  maxAvailable: number;
}

interface DashboardSummary {
  applications: Application[];
  groups: ApplicationGroup[];
  health: PlatformHealth;
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
      // One call for the whole platform's health: the hub is the only
      // component that talks to every other one (regionals included), so the
      // browser never probes a data-plane service or a regional itself.
      const [apps, health, activeListing, regions, groups] = await Promise.all([
        applicationsApi.list(signal),
        platformHealthApi.get(signal).catch((e: unknown) => hubUnreachable(e instanceof Error ? e.message : String(e))),
        runsApi.listPage({ activeOnly: true, limit: 200 }, signal),
        regionsApi.list().catch(() => [] as RegionCapacity[]),
        applicationGroupsApi.list(signal).catch(() => [] as ApplicationGroup[]),
      ]);
      const capacityByRegion = aggregateCapacityByRegion(groups, activeListing.runs, regions);
      setState({
        status: "ok",
        summary: {
          applications: sortByGroup(apps, groups),
          groups,
          health,
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

  const { applications, groups, health, capacityByRegion, refreshedAt } = state.summary;
  const platformRows = simplifyPlatform(health);
  const everythingHealthy =
    health.status === "UP" &&
    applications.every((a) => (a.lastHealthStatus ?? "UNKNOWN") === "HEALTHY");
  const failingChecks =
    platformRows.filter((r) => r.status === "DOWN" || r.status === "DEGRADED").length +
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
        <ApplicationsChecklist applications={applications} groups={groups} />

        {/* Right column — Platform + Scheduled runs stacked. Platform is
            small (3 rows today), Schedule is a stub for D6. */}
        <div className="homeGrid__right">
          <PlatformChecklist status={health.status} checkedAt={health.checkedAt} rows={platformRows} />
          <CapacityRollup rows={capacityByRegion} />
          <ScheduleChecklist />
        </div>
      </div>
    </section>
  );
}

// ── Sub-sections ──────────────────────────────────────────────────

function ApplicationsChecklist({ applications, groups }: { applications: Application[]; groups: ApplicationGroup[] }) {
  const total = applications.length;
  const visible = useMemo(() => applications.slice(0, HOME_APPS_LIMIT), [applications]);
  // A label row opens each run of apps in the same group.
  const groupName = (a: Application) => groups.find((g) => g.groupId === a.metricsGroupId)?.name ?? a.metricsGroupId;

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
            {visible.map((a, i) => (
              <Fragment key={a.applicationId}>
                {(i === 0 || groupName(visible[i - 1]) !== groupName(a)) && (
                  <li className="checklist__group" role="presentation">{groupName(a)}</li>
                )}
                <ChecklistRow
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
              </Fragment>
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

interface PlatformRow { id: string; name: string; status: PlatformStatus; reason?: string }

const STATUS_RANK: Record<PlatformStatus, number> = { UP: 0, UNKNOWN: 1, DEGRADED: 2, DOWN: 3 };
function worseOf(a: PlatformStatus, b: PlatformStatus): PlatformStatus {
  return STATUS_RANK[b] > STATUS_RANK[a] ? b : a;
}
/** A database dependency anywhere in the tree — folded into the one "Database" row. */
function isDatabase(c: PlatformHealthComponent): boolean {
  return c.name.startsWith("Oracle") || c.id === "db" || c.id.startsWith("db.") || c.id.endsWith(".db");
}

/**
 * Collapses the hub's health tree to what the operator needs at a glance: one
 * row per top-level service (worst status across its non-database branch, with
 * the first failing reason) plus a single "Database" row aggregating every
 * database dependency. Healthy rows carry no facts — free space, envelope
 * ages, and probe latencies stay in the hub's tree, not on Home.
 */
function simplifyPlatform(health: PlatformHealth): PlatformRow[] {
  const dbParts: PlatformHealthComponent[] = [];
  const collectDb = (c: PlatformHealthComponent) => {
    if (isDatabase(c)) dbParts.push(c);
    (c.components ?? []).forEach(collectDb);
  };
  health.components.forEach(collectDb);

  // The hub already aggregates each branch into its top-level status — trust
  // it; the walk below only finds a one-line reason for a row that is not UP.
  const rows: PlatformRow[] = health.components.map((top) => {
    let reason: string | undefined;
    if (top.status !== "UP") {
      reason = top.detail ?? undefined;
      const walk = (c: PlatformHealthComponent) => {
        if (isDatabase(c)) return; // reported on the Database row instead
        if (c !== top && c.status !== "UP") {
          reason ??= `${c.name}: ${c.detail ?? c.status.toLowerCase()}`;
        }
        (c.components ?? []).forEach(walk);
      };
      walk(top);
    }
    return { id: top.id, name: top.name, status: top.status, reason };
  });

  if (dbParts.length > 0) {
    let status: PlatformStatus = "UP";
    let reason: string | undefined;
    for (const p of dbParts) {
      if (p.status !== "UP") {
        status = worseOf(status, p.status);
        reason ??= `${p.name}: ${p.detail ?? p.status.toLowerCase()}`;
      }
    }
    rows.splice(Math.min(1, rows.length), 0, { id: "database", name: "Database", status, reason });
  }
  return rows;
}

/**
 * One row per platform service plus the single "Database" row — the operator
 * needs UP/DOWN per service, not the dependency tree. Only a row that is not
 * UP shows its one-line reason; healthy rows show the name and the badge.
 */
function PlatformChecklist({ status, checkedAt, rows }: { status: PlatformStatus; checkedAt: string; rows: PlatformRow[] }) {
  const attention = rows.filter((r) => r.status !== "UP").length;
  return (
    <section className="checklist">
      <header className="checklist__head">
        <h2>Platform</h2>
        <small className="ink-soft">
          {status === "UP"
            ? `All healthy · checked ${formatRelative(checkedAt)}`
            : status === "UNKNOWN"
              ? "Waiting for the first health round"
              : `${attention} component${attention === 1 ? "" : "s"} need${attention === 1 ? "s" : ""} attention · checked ${formatRelative(checkedAt)}`}
        </small>
      </header>
      <ul className="checklist__items healthTree" aria-label="platform checks">
        {rows.map((r) => (
          <li key={r.id} className="checklistRow healthTree__row" data-testid={`health-${r.id}`}>
            <div className="checklistRow__link checklistRow__link--static">
              <span className="checklistRow__label mono">{r.name}</span>
              {r.status !== "UP" && r.reason && (
                <small className="checklistRow__detail ink-soft">{r.reason}</small>
              )}
              <span
                className={`healthBadge healthBadge--${BADGE[r.status] ?? "unknown"} healthBadge--compact checklistRow__badge`}
                aria-label={`status: ${r.status.toLowerCase()}`}
              >
                <span className="healthBadge__dot" aria-hidden="true" />
                {r.status}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Platform-wide capacity rollup — sums maxAvailable + inUse across all
 * application groups for each region. Click-through goes to /capacity for
 * the per-group breakdown. Empty state renders when no group has capacity.
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
          <Link to="/capacity">Per-group breakdown →</Link>
        </small>
      </header>
      {rows.length === 0 ? (
        <div className="emptyState emptyState--compact">
          <p className="ink-soft">No application group has capacity — nothing to roll up yet.</p>
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
                      {/* Same inline bar + % as the per-group Capacity detail
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

/**
 * Sums per-(group, region) capacity into per-region totals + computes
 * inUse from the active runs' fleet members. readyToUse is bounded by
 * the actual pods registered in the region (post-Capacity-rework, pods
 * are provisioned on demand — a region with `maxAvailable=8` but no
 * provisioned pods has `readyToUse=0`, not `8`).
 */
function aggregateCapacityByRegion(
  groups: ApplicationGroup[],
  activeRuns: Run[],
  regions: RegionCapacity[],
): CapacityRollupRow[] {
  // Sum maxAvailable across all groups per region.
  const maxByRegion = new Map<string, number>();
  for (const group of groups) {
    for (const c of group.capacity ?? []) {
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

