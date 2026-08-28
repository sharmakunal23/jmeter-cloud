import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";

import { useInterval } from "../hooks/useInterval";
import { runsApi, type Run, type RunFleetMember, type RunState, type MemberState } from "../api/runs";
import { RegionBadgeList } from "../components/RegionBadge";
import { RunStreamsPanel } from "../components/RunStreamsPanel";
import { RunEventsTimeline } from "../components/RunEventsTimeline";
import { ScaleUpRunModal } from "../components/ScaleUpRunModal";
import { DrainDialog, type DrainMode } from "../components/DrainDialog";
import { AbortRunDialog } from "../components/AbortRunDialog";

/**
 * Run detail — page-level snapshot poller around a {@link RunStreamsPanel}
 * that owns the Metrics / Console / Logs sub-tabs.
 *
 * <p>2026-05-15 (smoke fix 2): operator-driven UX rework.
 * <ul>
 *   <li>Tab order: Insights → Worker Fleet → Metadata. Insights is the
 *       default landing tab — operators spend most time watching metrics.</li>
 *   <li>Status badge pinned immediately next to the runId in the H1 so its
 *       position never reflows. Action buttons left the header entirely
 *       to keep the badge stable.</li>
 *   <li>Worker Fleet tab owns its own action toolbar with "+ Add workers",
 *       "Stop test", state filter chips, and a bulk-action bar that
 *       appears when ≥ 1 worker is checkbox-selected.</li>
 *   <li>Bulk drain via row-checkboxes + select-all header checkbox.</li>
 *   <li>Stop test = drain every live worker via one scaleDown call —
 *       reuses the existing backend; no new endpoint.</li>
 *   <li>Empty state for the fleet when the run hasn't fanned out yet.</li>
 * </ul>
 */
type DetailState =
  | { status: "loading" }
  | { status: "ok"; run: Run; lastRefreshed: Date }
  | { status: "error"; code: string; message: string };

const POLL_INTERVAL_MS = 5_000;

type PageTab = "insights" | "fleet" | "metadata" | "events";

const TABS: Array<{ id: PageTab; label: string }> = [
  { id: "insights", label: "Insights" },
  { id: "fleet",    label: "Worker Fleet" },
  { id: "metadata", label: "Metadata" },
  { id: "events",   label: "Events" },
];

/** Fold the per-row Drain, bulk Drain, and Stop test paths into one dialog target. */
type DrainTarget = { workerIds: string[]; mode: DrainMode } | null;

export function RunDetailPage() {
  const { runId = "" } = useParams<{ runId: string }>();
  const [state, setState] = useState<DetailState>({ status: "loading" });
  const [scaleUpOpen, setScaleUpOpen] = useState(false);
  const [drainTarget, setDrainTarget] = useState<DrainTarget>(null);
  const [abortOpen, setAbortOpen] = useState(false);
  const [pageTab, setPageTab] = useState<PageTab>("insights");

  const fetchOnce = useCallback(() => {
    if (!runId) return new AbortController();
    const ctl = new AbortController();
    runsApi
      .status(runId, ctl.signal)
      .then(async (status) => {
        const run = await runsApi.get(runId, ctl.signal);
        setState({
          status: "ok",
          run: { ...run, state: status.state, fleetMembers: status.members },
          lastRefreshed: new Date(),
        });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        const message = err instanceof Error ? err.message : String(err);
        const code =
          err && typeof err === "object" && "code" in err
            ? String((err as { code: unknown }).code)
            : "UNKNOWN";
        setState((prev) =>
          prev.status === "ok" ? prev : { status: "error", code, message },
        );
      });
    return ctl;
  }, [runId]);

  useEffect(() => {
    const ctl = fetchOnce();
    return () => ctl.abort();
  }, [fetchOnce]);

  const isTerminal =
    state.status === "ok" && isTerminalState(state.run.state);
  useInterval(fetchOnce, isTerminal ? null : POLL_INTERVAL_MS);

  if (state.status === "loading") return <p>loading…</p>;
  if (state.status === "error") {
    return (
      <section>
        <h1 className="mono">{runId}</h1>
        <p className="text--error">{state.code}: {state.message}</p>
        <p><Link to="/applications">← Back to runs</Link></p>
      </section>
    );
  }

  const run = state.run;
  const counts = countByCategory(run.fleetMembers);
  // 2026-05-15 — "Back to runs" goes to the application detail page
  // (which shows that app's runs list). Falls back to /applications for
  // legacy untagged runs that have no application binding.
  const backTo = run.application
    ? `/applications/${encodeURIComponent(run.application)}`
    : "/applications";

  return (
    <section className="runDetail">
      <header className="pageHeader runDetail__header">
        {/* 2026-05-16 — back link moved to the LEFT of the H1 to match
            NewRunPage's "← Back · Title" pattern. The previous top-right
            placement was inconsistent with the rest of the app. */}
        <div className="runDetail__headerLeft">
          <Link to={backTo} className="runDetail__backLink">
            ← Back to {run.application ?? "applications"}
          </Link>
          <h1>
            <span className="mono">{run.runId}</span>
            {/* Badge pinned to the H1 so it never reflows when other UI
                chrome (action buttons, etc.) is shown elsewhere. */}
            <span className={`badge badge--${badgeFor(run.state)}`} style={{ marginLeft: "0.6rem" }}>
              {run.state}
            </span>
          </h1>
        </div>
        {/* Header action slot — Abort run only (force stop / zombie cleanup,
            available even when the fleet is empty or stuck, which the
            Worker-Fleet "Stop test" graceful drain can't handle). It's
            non-terminal-only, so the slot is empty for terminal runs and the
            H1 badge never reflows. "Download results" moved to the tab bar,
            as a run-level, terminal-state action. */}
        {!isTerminal && (
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => setAbortOpen(true)}
          >
            Abort run
          </button>
        )}
      </header>

      <p className="runDetail__refreshed">
        {isTerminal
          ? "polling paused (terminal state)"
          : `auto-refreshing every ${POLL_INTERVAL_MS / 1000}s — last update ${state.lastRefreshed.toLocaleTimeString()}`}
      </p>

      <div className="runDetail__tabBar">
        <div className="runStreams__tabStrip" role="tablist" aria-label="Run detail section selector">
          {TABS.map((t) => {
            const selected = t.id === pageTab;
            return (
              <button
                key={t.id}
                type="button"
                role="tab"
                id={`runDetailTab-${t.id}`}
                aria-controls={`runDetailPanel-${t.id}`}
                aria-selected={selected}
                tabIndex={selected ? 0 : -1}
                className={selected ? "runStreams__tab runStreams__tab--active" : "runStreams__tab"}
                onClick={() => setPageTab(t.id)}
              >
                {t.label}
                {/* Worker Fleet tab carries a compact count breakdown so the
                    operator sees fleet shape at a glance from any tab. */}
                {t.id === "fleet" && counts.total > 0 && (
                  <span
                    className="chip chip--info"
                    style={{ marginLeft: "0.4rem", fontSize: "0.72rem" }}
                    aria-label={`${counts.total} workers; ${counts.active} active, ${counts.terminal} terminal`}
                  >
                    {counts.total}
                    {counts.active > 0 && counts.active !== counts.total && (
                      <> · {counts.active} active</>
                    )}
                  </span>
                )}
              </button>
            );
          })}
        </div>
        {/* Run-level actions, right-aligned on the tab row. "Download results"
            only when a clean COMPLETED run saved its JTLs (ABORTED/FAILED never
            offer a download that would 404). */}
        <div className="runDetail__tabBarActions">
          {run.saveResults && run.state === "COMPLETED" && (
            <a
              className="btn btn--ghost"
              href={`/api/v1/blob/run/${encodeURIComponent(run.runId)}/archive`}
              download
            >
              ↓ Download results
            </a>
          )}
        </div>
      </div>

      <div
        role="tabpanel"
        id={`runDetailPanel-${pageTab}`}
        aria-labelledby={`runDetailTab-${pageTab}`}
        tabIndex={0}
        className="runDetail__tabPanel"
      >
        {run.state === "PREPARING" && (
          <div className="emptyState" data-testid="provisioningPanel" aria-live="polite">
            <p><strong>Provisioning workers…</strong></p>
            <p className="ink-soft">{run.stateReason ?? "Waiting for the data centers to report the workers ready."}</p>
            <p className="ink-soft">Metrics appear once the test is running.</p>
          </div>
        )}
        {run.state !== "PREPARING" && pageTab === "insights" && (
          <RunStreamsPanel
            runId={run.runId}
            fleetMembers={run.fleetMembers}
            runState={run.state}
          />
        )}
        {run.state !== "PREPARING" && pageTab === "fleet" && (
          <FleetTab
            run={run}
            isTerminal={isTerminal}
            onAddWorkers={() => setScaleUpOpen(true)}
            onDrainSingle={(workerId) =>
              setDrainTarget({ workerIds: [workerId], mode: "single" })}
            onDrainBulk={(workerIds) =>
              setDrainTarget({ workerIds, mode: "bulk" })}
            onStopTest={(workerIds) =>
              setDrainTarget({ workerIds, mode: "stopTest" })}
          />
        )}
        {run.state !== "PREPARING" && pageTab === "metadata" && <MetadataTab run={run} />}
        {run.state !== "PREPARING" && pageTab === "events" && (
          <RunEventsTimeline runId={runId} isTerminal={isTerminal} />
        )}
      </div>

      <p style={{ marginTop: "1.5rem" }}>
        <Link to={backTo}>← Back to {run.application ?? "applications"}</Link>
      </p>

      {scaleUpOpen && (
        <ScaleUpRunModal
          run={run}
          onClose={() => setScaleUpOpen(false)}
          onSuccess={(updated) => {
            setState({ status: "ok", run: updated, lastRefreshed: new Date() });
          }}
        />
      )}

      {drainTarget != null && (
        <DrainDialog
          runId={run.runId}
          workerIds={drainTarget.workerIds}
          mode={drainTarget.mode}
          liveWorkerCount={counts.active}
          onClose={() => setDrainTarget(null)}
          onSuccess={(updated) => {
            setState({ status: "ok", run: updated, lastRefreshed: new Date() });
          }}
        />
      )}

      {abortOpen && (
        <AbortRunDialog
          runId={run.runId}
          activeWorkerCount={counts.active}
          onClose={() => setAbortOpen(false)}
          onSuccess={(updated) => {
            setState({ status: "ok", run: updated, lastRefreshed: new Date() });
          }}
        />
      )}
    </section>
  );
}

// ── Worker Fleet tab ──────────────────────────────────────────────────

type StateFilter = "all" | "active" | "terminal";

function FleetTab({
  run, isTerminal, onAddWorkers, onDrainSingle, onDrainBulk, onStopTest,
}: {
  run: Run;
  isTerminal: boolean;
  onAddWorkers: () => void;
  onDrainSingle: (workerId: string) => void;
  onDrainBulk: (workerIds: string[]) => void;
  onStopTest: (workerIds: string[]) => void;
}) {
  const [stateFilter, setStateFilter] = useState<StateFilter>("all");
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // MID-TEST-SCALING Phase E gate — Add workers only when the backend
  // would actually accept the call: run RUNNING + has-application.
  const canScaleUp =
    run.state === "RUNNING" &&
    run.application !== null && run.application !== undefined && run.application !== "";

  // Live workers — drainable via Stop test (RUNNING + ACCEPTED, same
  // gate as the per-row Drain button).
  const liveWorkerIds = useMemo(
    () => run.fleetMembers
      .filter((m) => isDrainable(m.state) && !isTerminal)
      .map((m) => m.workerId),
    [run.fleetMembers, isTerminal],
  );

  // Filter rows for display — actions still operate on the unfiltered set.
  const visibleRows = useMemo(() => {
    if (stateFilter === "all") return run.fleetMembers;
    if (stateFilter === "active") {
      return run.fleetMembers.filter((m) => !isMemberTerminal(m.state));
    }
    return run.fleetMembers.filter((m) => isMemberTerminal(m.state));
  }, [run.fleetMembers, stateFilter]);

  // Drainable IDs within the CURRENT FILTER (so "select all" only picks
  // workers that the operator can actually act on AND can see).
  const drainableVisibleIds = useMemo(
    () => visibleRows
      .filter((m) => isDrainable(m.state) && !isTerminal)
      .map((m) => m.workerId),
    [visibleRows, isTerminal],
  );

  // Clear stale selection — if the run rolled to terminal or a worker
  // we'd selected has now drained, drop it from the set.
  useEffect(() => {
    const stillValid = new Set(drainableVisibleIds);
    setSelected((prev) => {
      const next = new Set<string>();
      prev.forEach((id) => { if (stillValid.has(id)) next.add(id); });
      return next.size === prev.size ? prev : next;
    });
  }, [drainableVisibleIds]);

  function toggleOne(workerId: string, on: boolean) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (on) next.add(workerId); else next.delete(workerId);
      return next;
    });
  }

  function selectAll(on: boolean) {
    setSelected(on ? new Set(drainableVisibleIds) : new Set());
  }

  const allDrainableSelected =
    drainableVisibleIds.length > 0 &&
    drainableVisibleIds.every((id) => selected.has(id));

  return (
    <div className="fleetTab">
      <div className="fleetTab__toolbar">
        <div className="fleetTab__filterChips" role="group" aria-label="Filter workers by state">
          {(["all", "active", "terminal"] as StateFilter[]).map((f) => (
            <button
              key={f}
              type="button"
              className={
                "btn btn--sm" + (stateFilter === f ? " btn--primary" : "")
              }
              onClick={() => setStateFilter(f)}
              aria-pressed={stateFilter === f}
            >
              {f === "all" ? "All" : f === "active" ? "Active" : "Terminal"}
            </button>
          ))}
        </div>
        <span className="fleetTab__spacer" />
        {canScaleUp && (
          <button
            type="button"
            className="btn"
            onClick={onAddWorkers}
          >
            + Add workers
          </button>
        )}
        {liveWorkerIds.length > 0 && (
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => onStopTest(liveWorkerIds)}
          >
            Stop test
          </button>
        )}
      </div>

      {selected.size > 0 && (
        <div className="fleetTab__bulkBar" role="region" aria-label="Bulk action">
          <span>
            <strong>{selected.size}</strong> worker{selected.size === 1 ? "" : "s"} selected
          </span>
          <span className="fleetTab__spacer" />
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => onDrainBulk(Array.from(selected))}
          >
            Drain {selected.size} selected
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => setSelected(new Set())}
          >
            Clear selection
          </button>
        </div>
      )}

      {run.fleetMembers.length === 0 ? (
        <p className="ink-soft" style={{ marginTop: "1rem" }}>
          No workers yet — waiting for fan-out. The fleet table will populate
          once the global-orchestrator finishes claiming pods + dispatching
          start-test calls (typically &lt; 10 s).
        </p>
      ) : visibleRows.length === 0 ? (
        <p className="ink-soft" style={{ marginTop: "1rem" }}>
          No workers match the <code>{stateFilter}</code> filter.
          {" "}
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => setStateFilter("all")}>
            Show all
          </button>
        </p>
      ) : (
        <table className="runsTable">
          <thead>
            <tr>
              <th style={{ width: "1.5rem" }}>
                {drainableVisibleIds.length > 0 && (
                  <input
                    type="checkbox"
                    aria-label="Select all drainable workers"
                    checked={allDrainableSelected}
                    onChange={(e) => selectAll(e.target.checked)}
                  />
                )}
              </th>
              <th>Worker</th>
              <th>Region</th>
              <th>State</th>
              {/* Phase F2 — runsServed surfaced per member so the
                  operator can spot a pod near its recycle threshold
                  while a run is live. */}
              <th>Runs</th>
              <th>fanoutStatusCode</th>
              <th>State reason</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((m) => {
              const drainable = isDrainable(m.state) && !isTerminal;
              const isDrained = m.state === "DRAINED";
              const isChecked = selected.has(m.workerId);
              return (
                <tr
                  key={m.workerId}
                  className={isDrained ? "runsTable__row--drained" : ""}
                >
                  <td>
                    {drainable && (
                      <input
                        type="checkbox"
                        aria-label={`select worker ${m.workerId}`}
                        checked={isChecked}
                        onChange={(e) => toggleOne(m.workerId, e.target.checked)}
                      />
                    )}
                  </td>
                  <td className="mono">
                    {m.workerId}
                    {m.joinedAtSecond != null && m.joinedAtSecond > 0 && (
                      <span
                        className={`chip ${isDrained ? "chip--ok" : "chip--info"}`}
                        style={{ marginLeft: "0.4rem", fontSize: "0.72rem" }}
                        title={
                          isDrained
                            ? `joined ${m.joinedAtSecond}s after run start; drained later`
                            : `joined ${m.joinedAtSecond}s after run start`
                        }
                      >
                        {isDrained ? "drained" : "joined"} +{formatJoinOffset(m.joinedAtSecond)}
                      </span>
                    )}
                  </td>
                  <td>{m.region}</td>
                  <td>
                    <span className={`badge badge--${badgeFor(m.state)}`}>{m.state}</span>
                  </td>
                  <td className="mono">{m.runsServed ?? "—"}</td>
                  <td>{m.fanoutStatusCode ?? "—"}</td>
                  <td>
                    <code className="memberReason" title={m.stateReason ?? ""}>
                      {m.stateReason ?? "—"}
                    </code>
                  </td>
                  <td>
                    {drainable && (
                      <button
                        type="button"
                        className="btn btn--ghost btn--sm"
                        onClick={() => onDrainSingle(m.workerId)}
                        aria-label={`drain worker ${m.workerId}`}
                      >
                        Drain
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

// ── Metadata tab ──────────────────────────────────────────────────────

function MetadataTab({ run }: { run: Run }) {
  return (
    <dl className="defList">
      <dt>State reason</dt>
      <dd>{run.stateReason ?? "—"}</dd>
      <dt>Regions</dt>
      <dd><RegionBadgeList run={run} /></dd>
      <dt>Origin region</dt>
      <dd>
        <span className="ink-soft">{run.originRegion}</span>
        <small className="ink-soft"> &nbsp;— global-orchestrator's own region (where this run was launched from)</small>
      </dd>
      <dt>Test plan blob</dt>
      <dd className="mono">{run.testPlanBlobId}</dd>
      <dt>Data files blob</dt>
      <dd className="mono">{run.dataFilesBlobId ?? "—"}</dd>
      <dt>Initiated by</dt>
      <dd>{run.initiatedBy}</dd>
      <dt>Created</dt>
      <dd>{format(run.createdAt)}</dd>
      <dt>Started</dt>
      <dd>{format(run.startedAt)}</dd>
      <dt>Completed</dt>
      <dd>{format(run.completedAt)}</dd>
    </dl>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────

interface FleetCounts {
  total: number;
  active: number;
  terminal: number;
}

function countByCategory(members: RunFleetMember[]): FleetCounts {
  let active = 0;
  let terminal = 0;
  for (const m of members) {
    if (isMemberTerminal(m.state)) terminal++;
    else active++;
  }
  return { total: members.length, active, terminal };
}

function isMemberTerminal(state: MemberState): boolean {
  return state === "COMPLETED" || state === "FAILED"
      || state === "ABORTED"   || state === "DRAINED";
}

/** Drainable workers — RUNNING or ACCEPTED in a non-terminal run. */
function isDrainable(state: MemberState): boolean {
  return state === "RUNNING" || state === "ACCEPTED";
}

function formatJoinOffset(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  return `${Math.floor(seconds / 3600)}h`;
}

function badgeFor(state: string): "ok" | "warn" | "err" | "info" {
  switch (state) {
    case "RUNNING":
    case "STARTING":
    case "PREPARING":
    case "DRAINING":
    case "ACCEPTED":
    case "REQUESTED":
    case "PENDING":
      return "info";
    case "COMPLETED":
    case "DRAINED":
      return "ok";
    case "FAILED":
    case "ABORTED":
      return "err";
    default:
      return "warn";
  }
}

function isTerminalState(state: RunState): boolean {
  return state === "COMPLETED" || state === "FAILED" || state === "ABORTED";
}

function format(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}
