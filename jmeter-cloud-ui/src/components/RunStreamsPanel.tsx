import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";

import type { MemberState, RunFleetMember, RunState } from "../api/runs";
import { LogTailPanel } from "./LogTailPanel";
import { MetricsTabPanel } from "./MetricsTabPanel";

/**
 * Tabbed run-detail streams pane. Replaces the previous
 * stacked-per-pod log panels + metrics iframe layout with a single
 * tab strip:
 *
 * <ul>
 *   <li><b>Metrics</b> — the native uPlot panel (formerly an embedded iframe). Frozen via
 *       {@code src=""} swap when paused so the iframe stops its own
 *       internal polling (the real cost saver — chart auto-refresh
 *       fires every configured interval whether the user is looking or
 *       not).</li>
 *   <li><b>Console</b> — orchestrator's in-memory ring of the JMeter
 *       child's stdout/stderr. Worker selector inside.</li>
 *   <li><b>Logs</b> — tail of {@code jmeter.log} on disk (JMeter's
 *       own log4j output). Worker selector inside.</li>
 * </ul>
 *
 * <p><b>Fleet-scale behaviour.</b> Inactive panels fully unmount, so
 * their useEffects + timers don't run — at 100 pods, the page polls
 * exactly one stream at a time (the active worker × active tab) instead
 * of fanning 100 requests per pod every 2 s. Combined with the
 * visibility-aware polling hook, the network goes silent the
 * moment the operator's attention moves away.
 */
type TabId = "metrics" | "console" | "logs";

const ALL_TABS: ReadonlyArray<{ id: TabId; label: string }> = [
  { id: "metrics", label: "Metrics" },
  { id: "console", label: "Console" },
  { id: "logs",    label: "Logs"    },
] as const;

// Console + Logs only make sense while the run is producing output.
// Once terminal, the pod's ring buffer + jmeter.log are likely already
// being recycled for the next test (orchestrator is single-tenant per
// pod), so showing those tabs would surface stale or wrong-run content.
// We hide them entirely instead of rendering a stale snapshot.
const TERMINAL_TABS: ReadonlyArray<{ id: TabId; label: string }> = [
  { id: "metrics", label: "Metrics" },
] as const;

const ACTIVE_TAB_STORAGE_KEY = "jmeterCloud.runDetailTab";

interface RunStreamsPanelProps {
  runId: string;
  fleetMembers: RunFleetMember[];
  runState: RunState;
}

export function RunStreamsPanel({ runId, fleetMembers, runState }: RunStreamsPanelProps) {
  const [activeTab, setActiveTab] = useState<TabId>(() => readActiveTab());
  useEffect(() => { writeActiveTab(activeTab); }, [activeTab]);

  const tabRefs = useRef<Map<TabId, HTMLButtonElement | null>>(new Map());

  // Left/Right cycle, Home/End jump to ends. Keyboard nav is the only
  // way for axe-core / screen-reader users to move around the strip.
  function onTabKey(e: KeyboardEvent<HTMLButtonElement>, currentTabs: ReadonlyArray<{ id: TabId; label: string }>) {
    const idx = currentTabs.findIndex((t) => t.id === activeTab);
    if (idx === -1) return;
    let next = idx;
    switch (e.key) {
      case "ArrowRight": next = (idx + 1) % currentTabs.length;          break;
      case "ArrowLeft":  next = (idx - 1 + currentTabs.length) % currentTabs.length; break;
      case "Home":       next = 0;                                       break;
      case "End":        next = currentTabs.length - 1;                  break;
      default: return;
    }
    e.preventDefault();
    const target = currentTabs[next];
    if (!target) return;
    setActiveTab(target.id);
    tabRefs.current.get(target.id)?.focus();
  }

  const runTerminal = isTerminalRunState(runState);
  const tabs = runTerminal ? TERMINAL_TABS : ALL_TABS;

  // If the user was on Console / Logs and the run just transitioned to
  // terminal, snap back to Metrics — the tab they were on is gone.
  useEffect(() => {
    if (!tabs.some((t) => t.id === activeTab)) setActiveTab("metrics");
  }, [tabs, activeTab]);

  return (
    <section className="runStreams" aria-label="Live streams">
      <div className="runStreams__tabStrip" role="tablist" aria-label="Run stream selector">
        {tabs.map((t) => {
          const selected = t.id === activeTab;
          return (
            <button
              key={t.id}
              ref={(el) => { tabRefs.current.set(t.id, el); }}
              type="button"
              role="tab"
              id={`runStreamsTab-${t.id}`}
              aria-controls={`runStreamsPanel-${t.id}`}
              aria-selected={selected}
              tabIndex={selected ? 0 : -1}
              className={selected ? "runStreams__tab runStreams__tab--active" : "runStreams__tab"}
              onClick={() => setActiveTab(t.id)}
              onKeyDown={(e) => onTabKey(e, tabs)}
            >
              {t.label}
            </button>
          );
        })}
      </div>

      <div
        role="tabpanel"
        id={`runStreamsPanel-${activeTab}`}
        aria-labelledby={`runStreamsTab-${activeTab}`}
        tabIndex={0}
        className="runStreams__panel"
      >
        {/* Conditional render — inactive panels fully unmount so their
            polling effects don't run. This is the load-bearing
            mechanism for fleet-scale safety. */}
        {activeTab === "metrics" && <MetricsTabPanel runId={runId} runState={runState} />}
        {activeTab === "console" && (
          <StreamTabPanel
            runId={runId}
            panelKey="console"
            streamSource="console"
            fleetMembers={fleetMembers}
            runTerminal={runTerminal}
            emptyHint="No fleet members yet — the run hasn't been fanned out to any workers."
          />
        )}
        {activeTab === "logs" && (
          <StreamTabPanel
            runId={runId}
            panelKey="logs"
            streamSource="jmeter"
            fleetMembers={fleetMembers}
            runTerminal={runTerminal}
            emptyHint="No fleet members yet — the run hasn't been fanned out to any workers."
          />
        )}
      </div>
    </section>
  );
}

// ── Console + Logs (shared shell) ─────────────────────────────────────

interface StreamTabPanelProps {
  runId: string;
  /** localStorage namespace so console + logs remember independent worker selections. */
  panelKey: "console" | "logs";
  streamSource: "console" | "jmeter";
  fleetMembers: RunFleetMember[];
  /** Whole-run terminal flag — set when state ∈ {COMPLETED, FAILED, ABORTED}. */
  runTerminal: boolean;
  emptyHint: string;
}

function StreamTabPanel({
  runId, panelKey, streamSource, fleetMembers, runTerminal, emptyHint,
}: StreamTabPanelProps) {
  // Default selection: first ACCEPTED/RUNNING member; otherwise first
  // member of any state. Persisted per (runId, panelKey).
  const defaultWorkerId = useMemo(
    () => pickDefaultWorker(fleetMembers),
    [fleetMembers],
  );

  const [workerId, setWorkerId] = useState<string>(() => {
    const stored = readWorkerSelection(runId, panelKey);
    if (stored && fleetMembers.some((m) => m.workerId === stored)) return stored;
    return defaultWorkerId;
  });

  // Keep the selection valid as the fleet evolves (mid-run scale-up adds /
  // drains members mid-run).
  useEffect(() => {
    if (workerId && fleetMembers.some((m) => m.workerId === workerId)) return;
    setWorkerId(defaultWorkerId);
  }, [defaultWorkerId, fleetMembers, workerId]);

  useEffect(() => {
    if (workerId) writeWorkerSelection(runId, panelKey, workerId);
  }, [runId, panelKey, workerId]);

  // Per-member terminal awareness — once a specific pod has reached
  // COMPLETED / FAILED / ABORTED, its log file and stdout ring buffer
  // are frozen, so further polling fetches the same bytes forever. Stop
  // the timer for that pod even if the run-level state is still RUNNING
  // (e.g. a mid-run drain).
  const selectedMember = fleetMembers.find((m) => m.workerId === workerId);
  const memberTerminal = selectedMember
    ? isTerminalMemberState(selectedMember.state)
    : false;
  const terminal = runTerminal || memberTerminal;

  if (fleetMembers.length === 0 || !workerId) {
    return <p className="runDetail__embedHint">{emptyHint}</p>;
  }

  // Live-vs-terminal counts — surfaces "5 live · 2 completed" so the
  // operator can see at a glance how many pods are still producing.
  const counts = countMemberStates(fleetMembers);

  return (
    <div className="streamPanel">
      <div className="streamPanel__workerBar">
        <label>
          Worker&nbsp;
          <select value={workerId} onChange={(e) => setWorkerId(e.target.value)}>
            {fleetMembers.map((m) => (
              <option key={m.workerId} value={m.workerId}>
                {m.workerId} · {m.region} · {m.state}{isTerminalMemberState(m.state) ? " (terminal)" : ""}
              </option>
            ))}
          </select>
        </label>
        <small className="ink-soft">
          {counts.live} live · {counts.terminal} completed/failed · {counts.pending} pending
        </small>
        <small className="ink-soft">
          Polls one stream at a time across the fleet — switch the worker
          to inspect a different pod.
        </small>
      </div>
      <LogTailPanel
        // key forces a clean re-mount on worker change so the auto-scroll
        // sticky-state and tail buffer don't bleed across pods.
        key={`${panelKey}:${workerId}`}
        runId={runId}
        workerId={workerId}
        streamSource={streamSource}
        terminal={terminal}
        // Pause + Refresh controls only matter while the pod is actively
        // producing output. For PENDING / REQUESTED / ACCEPTED / terminal
        // states there's nothing to pause or refresh — hide them and let
        // the operator focus on what's there.
        showRefreshControls={selectedMember?.state === "RUNNING"}
      />
    </div>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────

function isTerminalRunState(state: RunState): boolean {
  return state === "COMPLETED" || state === "FAILED" || state === "ABORTED";
}

function isTerminalMemberState(state: MemberState): boolean {
  return state === "COMPLETED" || state === "FAILED" || state === "ABORTED";
}

function pickDefaultWorker(members: RunFleetMember[]): string {
  if (members.length === 0) return "";
  const live = members.find((m) => m.state === "ACCEPTED" || m.state === "RUNNING");
  return (live ?? members[0])!.workerId;
}

interface MemberStateCounts {
  live: number;      // ACCEPTED, RUNNING — still producing log output
  terminal: number;  // COMPLETED, FAILED, ABORTED — frozen, no point polling
  pending: number;   // PENDING, REQUESTED — not started yet
}

function countMemberStates(members: RunFleetMember[]): MemberStateCounts {
  let live = 0, terminal = 0, pending = 0;
  for (const m of members) {
    if (isTerminalMemberState(m.state)) terminal++;
    else if (m.state === "ACCEPTED" || m.state === "RUNNING") live++;
    else pending++;
  }
  return { live, terminal, pending };
}

function readActiveTab(): TabId {
  try {
    const v = window.localStorage.getItem(ACTIVE_TAB_STORAGE_KEY);
    if (v === "metrics" || v === "console" || v === "logs") return v;
  } catch { /* private mode or storage disabled */ }
  return "metrics";
}

function writeActiveTab(tab: TabId): void {
  try { window.localStorage.setItem(ACTIVE_TAB_STORAGE_KEY, tab); }
  catch { /* private mode or storage disabled */ }
}

function workerStorageKey(runId: string, panelKey: string): string {
  return `jmeterCloud.runStreams.${panelKey}.worker.${runId}`;
}

function readWorkerSelection(runId: string, panelKey: string): string | null {
  try { return window.localStorage.getItem(workerStorageKey(runId, panelKey)); }
  catch { return null; }
}

function writeWorkerSelection(runId: string, panelKey: string, workerId: string): void {
  try { window.localStorage.setItem(workerStorageKey(runId, panelKey), workerId); }
  catch { /* private mode or storage disabled */ }
}
