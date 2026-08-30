import { useEffect, useMemo, useRef, useState } from "react";

import {
  GlobalOrchestratorError,
  runsApi,
  type FleetAllocationEntry,
  type MemberState,
  type RegionShortfall,
  type Run,
  type RunFleetMember,
  type ScaleUpRunResponse,
} from "../api/runs";
import { regionsApi, type RegionCapacity } from "../api/regions";
import { applicationsApi } from "../api/applications";
import { applicationGroupsApi } from "../api/applicationGroups";
import { FleetAllocationFormView } from "./FleetAllocationFormView";
import { GlobalPropertiesEditor } from "./GlobalPropertiesEditor";
import { RunStartProgress, type Stage } from "./RunStartProgress";

/**
 * Modal for adding workers to a RUNNING run.
 *
 * <p>Operator picks per-region counts via the same {@link FleetAllocationFormView}
 * widget the launcher uses, and edits the fleet-wide JMeter global properties
 * via the same {@link GlobalPropertiesEditor} (pre-filled with the launch-time
 * globals recovered from the existing fleet). New workers inherit the run's
 * test plan + data files + `saveResults` and get the edited globals.
 *
 * <p><b>Ceiling = remaining headroom, not the absolute max.</b> The +/- buttons
 * clamp at {@code maxAvailable − (this run's active workers in the region)}, so
 * a run with 2/5 workers can only add 3 — the operator can't pick a number the
 * capacity gate would reject.
 *
 * <p><b>Same staged progress + provisioning prompt as the launcher.</b> Submit
 * swaps the form for {@link RunStartProgress} (Provisioning → Distributing →
 * Starting JMeter → Verifying), driven by polling the NEW members to RUNNING.
 * A 503 shortfall surfaces the launcher's "provision missing workers" prompt
 * whose primary action retries with `spinShortfall: true` (spin the gap up to
 * the ceiling, then claim).
 *
 * <p>On success, calls {@code onSuccess(updatedRun)} so the caller can replace
 * the run state without waiting for the page's polling loop.
 */
export interface ScaleUpRunModalProps {
  run: Run;
  onClose: () => void;
  onSuccess: (run: Run) => void;
}

interface RegionsState {
  status: "loading" | "ok" | "error";
  regions: RegionCapacity[];
  message?: string;
}

interface SubmitState {
  status: "idle" | "submitting" | "error" | "ok";
  code?: string;
  message?: string;
  shortfall?: RegionShortfall[];
}

const TERMINAL_MEMBER_STATES: ReadonlySet<MemberState> = new Set<MemberState>([
  "COMPLETED", "FAILED", "ABORTED", "DRAINED",
]);
const isActiveMember = (s: MemberState) => !TERMINAL_MEMBER_STATES.has(s);

/** Canonical, key-order-independent serialisation of a property map. */
function canonical(props: Record<string, string>): string {
  return JSON.stringify(Object.keys(props).sort().map((k) => [k, props[k]]));
}

/**
 * Recover "the global properties set before the test" from the existing
 * fleet — the mode of the original-fleet members' property snapshots
 * (those with {@code joinedAtSecond == null}). Falls back to all members,
 * then to an empty map.
 */
function deriveGlobalProperties(members: RunFleetMember[]): Record<string, string> {
  const originals = members.filter((m) => m.joinedAtSecond == null);
  const pool = (originals.length > 0 ? originals : members).map((m) => m.properties ?? {});
  if (pool.length === 0) return {};
  const counts = new Map<string, number>();
  const repr = new Map<string, Record<string, string>>();
  let bestKey: string | null = null;
  let bestCount = 0;
  for (const props of pool) {
    const key = canonical(props);
    if (!repr.has(key)) repr.set(key, props);
    const next = (counts.get(key) ?? 0) + 1;
    counts.set(key, next);
    if (next > bestCount) {
      bestCount = next;
      bestKey = key;
    }
  }
  return bestKey ? repr.get(bestKey) ?? {} : {};
}

function scaleUpStages(spin: boolean): Stage[] {
  return [
    {
      id: "provisioning",
      label: "Provisioning workers",
      detail: spin ? "Spinning fresh pods to fill the shortfall" : undefined,
      status: spin ? "active" : "skipped",
    },
    { id: "workersReady",   label: "Claiming workers",                status: "active" },
    { id: "distributing",   label: "Distributing test plan + data files", status: "pending" },
    { id: "startingJmeter", label: "Starting JMeter",                 status: "pending" },
    { id: "verifying",      label: "Verifying new workers",           status: "pending" },
  ];
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export function ScaleUpRunModal({ run, onClose, onSuccess }: ScaleUpRunModalProps) {
  const runId = run.runId;
  const [regions, setRegions] = useState<RegionsState>({ status: "loading", regions: [] });
  // Per-region maxAvailable of this run's application group (the pool's ceiling).
  const [maxByRegion, setMaxByRegion] = useState<Record<string, number> | null>(null);
  const [allocation, setAllocation] = useState<FleetAllocationEntry[]>([]);
  const [globalProperties, setGlobalProperties] = useState<Record<string, string>>(
    () => deriveGlobalProperties(run.fleetMembers),
  );
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  // When non-null, the staged progress modal (RunStartProgress) is shown
  // instead of the form — same UX as the launcher.
  const [progressStages, setProgressStages] = useState<Stage[] | null>(null);
  const [progressError, setProgressError] = useState<string | null>(null);
  // The post-scale run snapshot from the scaleUp POST — used to update the
  // caller if the operator dismisses a stuck/failed verification.
  const addedRun = useRef<Run | null>(null);
  const lastOpts = useRef<{ bestEffort?: boolean; spinShortfall?: boolean }>({});

  // ESC closes the FORM only (the progress overlay locks ESC itself).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && progressStages === null) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, progressStages]);

  // Fetch the run's application → its group → the group's capacity grid (per-region maxAvailable).
  useEffect(() => {
    if (!run.application) { setMaxByRegion(null); return; }
    const ctl = new AbortController();
    (async () => {
      try {
        const apps = await applicationsApi.list(ctl.signal);
        const match = apps.find((a) => a.name === run.application);
        if (!match) { setMaxByRegion({}); return; }
        const group = await applicationGroupsApi.get(match.metricsGroupId, ctl.signal);
        const m: Record<string, number> = {};
        for (const c of group.capacity ?? []) m[c.region] = c.maxAvailable;
        setMaxByRegion(m);
      } catch {
        if (!ctl.signal.aborted) setMaxByRegion({});
      }
    })();
    return () => ctl.abort();
  }, [run.application]);

  // Poll regions every 5s — operator sees concurrent runs eating into
  // headroom while they're picking. Stops on unmount.
  const pollHandle = useRef<number | null>(null);
  useEffect(() => {
    let cancelled = false;
    async function refresh() {
      try {
        const data = await regionsApi.list();
        if (!cancelled) setRegions({ status: "ok", regions: data });
      } catch (err) {
        if (!cancelled) {
          setRegions({
            status: "error",
            regions: [],
            message: err instanceof Error ? err.message : String(err),
          });
        }
      }
    }
    void refresh();
    pollHandle.current = window.setInterval(() => { void refresh(); }, 5_000);
    return () => {
      cancelled = true;
      if (pollHandle.current != null) window.clearInterval(pollHandle.current);
    };
  }, []);

  // This run's currently-active workers per region — subtracted from the
  // per-region max so the ceiling is the REMAINING headroom (problem fix:
  // 2/5 running → can add 3, not 5).
  const activeByRegion = useMemo(() => {
    const m: Record<string, number> = {};
    for (const fm of run.fleetMembers) {
      if (isActiveMember(fm.state)) m[fm.region] = (m[fm.region] ?? 0) + 1;
    }
    return m;
  }, [run.fleetMembers]);

  // Cap-aware ceiling for the form: maxAvailable − alreadyActive, clamped at
  // 0. Undefined while capacity is still loading → form falls back to the
  // legacy idle-pod bound.
  const headroomByRegion = useMemo(() => {
    if (!maxByRegion) return undefined;
    const m: Record<string, number> = {};
    for (const [region, max] of Object.entries(maxByRegion)) {
      m[region] = Math.max(0, max - (activeByRegion[region] ?? 0));
    }
    return m;
  }, [maxByRegion, activeByRegion]);

  // Merge the group's capacity-grid regions into the live rollup so a region
  // with configured capacity but zero pods right now still shows up.
  const mergedRegions = useMemo(() => {
    const byRegion = new Map<string, RegionCapacity>();
    for (const r of regions.regions) byRegion.set(r.region, r);
    if (maxByRegion) {
      for (const region of Object.keys(maxByRegion)) {
        if (!byRegion.has(region)) {
          byRegion.set(region, { region, totalPods: 0, idlePods: 0, lostPods: 0 });
        }
      }
    }
    return Array.from(byRegion.values());
  }, [regions.regions, maxByRegion]);

  function addWorkers(region: string, n: number) {
    setAllocation((prev) => {
      const existing = prev.find((e) => e.region === region);
      if (existing) {
        return prev.map((e) =>
          e.region === region ? { ...e, count: e.count + n } : e,
        );
      }
      return [...prev, { region, count: n }];
    });
  }

  function removeWorkers(region: string, n: number) {
    setAllocation((prev) =>
      prev
        .map((e) => (e.region === region ? { ...e, count: Math.max(0, e.count - n) } : e))
        .filter((e) => e.count > 0),
    );
  }

  const totalToAdd = useMemo(
    () => allocation.reduce((acc, e) => acc + e.count, 0),
    [allocation],
  );

  const divergedCount = useMemo(() => {
    const g = canonical(globalProperties);
    return run.fleetMembers
      .filter((m) => m.joinedAtSecond == null)
      .filter((m) => canonical(m.properties ?? {}) !== g).length;
  }, [run.fleetMembers, globalProperties]);

  function patchStage(id: string, patch: Partial<Stage>) {
    setProgressStages((prev) => prev?.map((s) => (s.id === id ? { ...s, ...patch } : s)) ?? prev);
  }

  function markStageFailed(message: string) {
    setProgressError(message);
    setProgressStages((prev) => {
      if (!prev) return prev;
      let idx = prev.findIndex((s) => s.status === "active");
      if (idx < 0) idx = prev.findIndex((s) => s.status !== "done" && s.status !== "skipped");
      if (idx < 0) return prev;
      return prev.map((s, i) => (i === idx ? { ...s, status: "failed" as const } : s));
    });
  }

  /**
   * Poll the run until the NEW members reach RUNNING, advancing the
   * Distributing → Starting → Verifying stages. Returns "ok" once they're all
   * live, "failed" if a new worker rejected the start, "timeout" otherwise.
   */
  async function pollNewMembersLive(targetIds: string[]): Promise<"ok" | "failed" | "timeout"> {
    if (targetIds.length === 0) return "ok";
    const target = new Set(targetIds);
    for (let attempts = 0; attempts < 120; attempts++) {
      try {
        const snap = await runsApi.status(runId);
        const states = snap.members.filter((m) => target.has(m.workerId)).map((m) => m.state);
        if (states.length > 0) {
          const anyRunning = states.includes("RUNNING");
          const allRunning = states.every((s) => s === "RUNNING");
          const allTerminal = states.every((s) => s === "RUNNING" || TERMINAL_MEMBER_STATES.has(s));
          const anyFailed = states.includes("FAILED") || states.includes("ABORTED");
          const acceptedCount = states.filter(
            (s) => s === "ACCEPTED" || s === "RUNNING" || s === "DRAINING"
                || s === "COMPLETED" || s === "DRAINED",
          ).length;
          patchStage("distributing", { detail: `${acceptedCount}/${states.length} new workers ready` });

          if (anyFailed && !anyRunning && allTerminal) {
            patchStage("distributing", { status: "failed" });
            return "failed";
          }
          if (anyRunning) {
            patchStage("distributing", { status: "done" });
            await sleep(300);
            patchStage("startingJmeter", { status: "done" });
            patchStage("verifying", {
              status: "active",
              detail: `${states.filter((s) => s === "RUNNING").length}/${states.length} live`,
            });
          }
          if (allRunning) {
            patchStage("verifying", { status: "done" });
            await sleep(400);
            return "ok";
          }
        }
      } catch {
        // Transient — try again next tick.
      }
      await sleep(1500);
    }
    return "timeout";
  }

  async function send(opts: { bestEffort?: boolean; spinShortfall?: boolean }) {
    if (totalToAdd < 1) {
      setSubmit({
        status: "error",
        code: "INVALID_REQUEST",
        message: "Pick at least one worker to add.",
      });
      return;
    }
    lastOpts.current = opts;
    setProgressError(null);
    // Submitting → show the staged progress (the shortfall prompt only shows
    // on a settled `error`, so clearing the code here swaps the prompt for the
    // Provisioning stage during a spin retry).
    setSubmit({ status: "submitting" });
    setProgressStages(scaleUpStages(!!opts.spinShortfall));

    // Expand the edited globals into a per-pod snapshot for each region.
    const hasGlobals = Object.keys(globalProperties).length > 0;
    const allocations: FleetAllocationEntry[] = allocation.map((e) =>
      hasGlobals
        ? {
            ...e,
            perNodeProperties: Array.from({ length: e.count }, () => ({ ...globalProperties })),
          }
        : e,
    );
    const before = new Set(run.fleetMembers.map((m) => m.workerId));

    let resp: ScaleUpRunResponse;
    try {
      resp = await runsApi.scaleUp(runId, { allocations }, opts);
    } catch (err) {
      if (err instanceof GlobalOrchestratorError) {
        setSubmit({ status: "error", code: err.code, message: err.message, shortfall: err.shortfall });
        if (err.code === "INSUFFICIENT_CAPACITY") {
          // Surface the launcher's provisioning prompt (RunStartProgress reads
          // submit.code). Reset transient "active" stages so nothing spins
          // behind the prompt.
          setProgressStages((prev) =>
            prev?.map((s) => (s.status === "active" ? { ...s, status: "pending" as const } : s)) ?? prev,
          );
          return;
        }
      } else {
        setSubmit({ status: "error", code: "UNKNOWN", message: err instanceof Error ? err.message : String(err) });
      }
      markStageFailed(err instanceof Error ? err.message : String(err));
      return;
    }

    // POST accepted — claim + fan-out done. Advance stages and wait for the
    // new workers to come up.
    addedRun.current = resp.run;
    setSubmit({ status: "ok" });
    if (opts.spinShortfall) patchStage("provisioning", { status: "done", detail: undefined });
    patchStage("workersReady", { status: "done" });
    patchStage("distributing", { status: "active" });
    patchStage("startingJmeter", { status: "active" });

    const newIds = resp.run.fleetMembers
      .filter((m) => !before.has(m.workerId))
      .map((m) => m.workerId);
    const outcome = await pollNewMembersLive(newIds);

    if (outcome === "ok" || outcome === "timeout") {
      let finalRun = resp.run;
      try { finalRun = await runsApi.get(runId); } catch { /* keep POST snapshot */ }
      onSuccess(finalRun);
      onClose();
      return;
    }
    // outcome === "failed" — a new worker rejected the start. Leave the
    // failed stage up with a Dismiss that still surfaces the added workers.
    markStageFailed("A new worker failed to start — see the run-detail page for that worker.");
  }

  /**
   * Predict — from the latest region poll — whether a STRICT scaleUp would
   * come back 503. Returns per-region shortfall rows (pick vs currently-idle
   * pods) for regions short on idle capacity, or [] when every region has
   * enough idle pods to claim right now.
   *
   * <p>Lets us skip a doomed strict POST: if provisioning is already known to
   * be needed we surface the provisioning prompt directly instead of letting
   * the server reject the claim — which would otherwise leave a spurious
   * `rejected:INSUFFICIENT_CAPACITY` SCALE_UP event on the run's audit trail,
   * immediately followed by the provision retry's `ok`. (The launcher has no
   * such artifact: a strict 503 there rolls back the whole run, event and
   * all.) The genuine race — idle looked sufficient but a concurrent run
   * grabbed the pods first — still falls through to {@link send}'s 503 handler.
   */
  function predictShortfall(): RegionShortfall[] {
    const idleByRegion = new Map(mergedRegions.map((r) => [r.region, r.idlePods]));
    const rows: RegionShortfall[] = [];
    for (const e of allocation) {
      const idle = idleByRegion.get(e.region) ?? 0;
      if (e.count > idle) rows.push({ region: e.region, requested: e.count, claimed: idle });
    }
    return rows;
  }

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (totalToAdd < 1) {
      setSubmit({ status: "error", code: "INVALID_REQUEST", message: "Pick at least one worker to add." });
      return;
    }
    const predicted = predictShortfall();
    if (predicted.length > 0) {
      // We can already tell there aren't enough idle pods — surface the
      // provisioning prompt WITHOUT a doomed strict POST (which would record a
      // spurious rejected:INSUFFICIENT_CAPACITY event right before the retry).
      const gap = predicted.reduce((acc, r) => acc + Math.max(0, r.requested - r.claimed), 0);
      setProgressError(null);
      setProgressStages(scaleUpStages(false).map((s) =>
        s.status === "active" ? { ...s, status: "pending" as const } : s));
      setSubmit({
        status: "error",
        code: "INSUFFICIENT_CAPACITY",
        message: `${totalToAdd - gap} of the ${totalToAdd} workers you picked are ready — ${gap} need provisioning.`,
        shortfall: predicted,
      });
      return;
    }
    void send({});
  }

  const submitting = submit.status === "submitting";
  const canSubmit = !submitting && totalToAdd > 0;

  const inShortfall =
    submit.code === "INSUFFICIENT_CAPACITY" && submit.status === "error";
  const shortfallGap = (submit.shortfall ?? []).reduce(
    (acc, r) => acc + Math.max(0, r.requested - r.claimed),
    0,
  );

  // ── Staged progress / shortfall overlay (mirrors the launcher) ──────
  if (progressStages !== null) {
    const hasFailed = progressStages.some((s) => s.status === "failed");
    const backToForm = () => {
      setProgressStages(null);
      setProgressError(null);
      setSubmit({ status: "idle" });
    };
    return (
      <RunStartProgress
        open
        stages={progressStages}
        errorMessage={progressError}
        shortfallPrompt={
          inShortfall
            ? {
                rows: submit.shortfall ?? [],
                fallbackMessage:
                  submit.message ?? "Not enough idle workers to add the number you picked.",
                spinLabel:
                  shortfallGap > 0
                    ? `Provision ${shortfallGap} missing worker${shortfallGap === 1 ? "" : "s"} and add`
                    : "Provision missing workers and add",
                bestEffortLabel: "Add the workers that are ready",
                onSpinShortfall: () => { void send({ spinShortfall: true }); },
                onBestEffort: () => { void send({ bestEffort: true }); },
                onCancel: backToForm,
              }
            : null
        }
        onCancel={
          hasFailed
            ? () => {
                // The workers WERE added (the POST succeeded); surface them.
                if (addedRun.current) {
                  onSuccess(addedRun.current);
                  onClose();
                } else {
                  backToForm();
                }
              }
            : undefined
        }
        onRetry={
          // Retry only makes sense when the POST itself failed (nothing
          // added yet) — re-POSTing after a partial add would double-add.
          hasFailed && addedRun.current === null
            ? () => { void send(lastOpts.current); }
            : undefined
        }
      />
    );
  }

  return (
    <div className="modal__overlay" onClick={onClose}>
      <div
        className="modal modal--wide"
        role="dialog"
        aria-label="Add workers to run"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3>Add workers</h3>
            <small className="ink-soft">
              Claims additional pods from this run's per-application pool and
              fans out the same test plan + data files. New workers join with
              their counters at zero — surfaced on the row as a{" "}
              <span className="mono">joined +Xs</span> chip.
            </small>
          </div>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onClose}
            aria-label="Close"
          >×</button>
        </header>

        <form onSubmit={handleSubmit} className="modal__body" noValidate>
          <FleetAllocationFormView
            regions={mergedRegions}
            value={allocation}
            onAddWorkers={addWorkers}
            onRemoveWorkers={removeWorkers}
            maxByRegion={headroomByRegion}
            loading={regions.status === "loading"}
            error={regions.status === "error" ? regions.message ?? null : null}
          />

          {/* Global properties for the NEW workers — pre-filled with the
              run's launch-time globals, editable like the launcher. */}
          <GlobalPropertiesEditor
            value={globalProperties}
            onChange={setGlobalProperties}
            divergedCount={divergedCount}
          />

          {/* Save Results is inherited from the run, not chosen here. */}
          <p className="ink-soft scaleUp__inherited">
            <span>Save results: </span>
            <strong>{run.saveResults ? "On" : "Off"}</strong>
            <span>
              {run.saveResults
                ? " — new workers will upload their JTLs too, same as the rest of this run."
                : " — like the rest of this run, new workers won't upload results."}
            </span>
          </p>

          <footer className="modal__footer">
            <button type="button" className="btn" onClick={onClose}>Cancel</button>
            <button
              type="submit"
              className="btn btn--primary"
              disabled={!canSubmit}
              aria-busy={submitting}
            >
              {submitting
                ? "Adding…"
                : totalToAdd > 0
                ? `Add ${totalToAdd} worker${totalToAdd === 1 ? "" : "s"}`
                : "Add workers"}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}
