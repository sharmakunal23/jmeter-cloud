import { useEffect, useMemo, useState } from "react";

import type { MemberState, RunFleetMember } from "../api/runs";
import { LogTailPanel } from "./LogTailPanel";

/**
 * The Console and Logs tabs' shared shell: a worker selector over the fleet
 * and one {@link LogTailPanel} for the chosen worker — never more than one
 * stream is polled at a time, whatever the fleet size. Console and Logs
 * remember their worker independently, per run.
 */
export interface StreamTabPanelProps {
  runId: string;
  /** localStorage namespace so console + logs remember independent worker selections. */
  panelKey: "console" | "logs";
  streamSource: "console" | "jmeter";
  fleetMembers: RunFleetMember[];
  /** Whole-run terminal flag — set when state ∈ {COMPLETED, FAILED, ABORTED}. */
  runTerminal: boolean;
  emptyHint: string;
}

export function StreamTabPanel({
  runId, panelKey, streamSource, fleetMembers, runTerminal, emptyHint,
}: StreamTabPanelProps) {
  // Default selection: first ACCEPTED/RUNNING member; otherwise first
  // member of any state. Persisted per (runId, panelKey).
  const defaultWorkerId = useMemo(() => pickDefaultWorker(fleetMembers), [fleetMembers]);

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

  // Once a pod has reached COMPLETED / FAILED / ABORTED its log file and
  // stdout ring buffer are frozen — stop polling it even mid-run (a drain).
  const selectedMember = fleetMembers.find((m) => m.workerId === workerId);
  const memberTerminal = selectedMember ? isTerminalMemberState(selectedMember.state) : false;
  const terminal = runTerminal || memberTerminal;

  if (fleetMembers.length === 0 || !workerId) {
    return <p className="runDetail__embedHint">{emptyHint}</p>;
  }

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
        // Pause + Refresh only matter while the pod is producing output.
        showRefreshControls={selectedMember?.state === "RUNNING"}
      />
    </div>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────

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
