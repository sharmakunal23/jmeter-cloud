import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";

import { runsApi, GlobalOrchestratorError, type FleetAllocationEntry,
         type RegionShortfall, type StartRunRequest, type WorkerStatus } from "../api/runs";
import { blobsApi, type BlobMetadata } from "../api/blobs";
import { regionsApi, type RegionCapacity } from "../api/regions";
import { applicationsApi, type Application } from "../api/applications";
// (D-RunLauncher rework — applicationsApi/ApplicationPicker are gone;
//  the launcher's app comes from the URL via useParams.)
import { FleetFlowDiagram, workerName } from "../components/FleetFlowDiagram";
import { FleetAllocationFormView } from "../components/FleetAllocationFormView";
// (AllocationOverview removed in D-Capacity v2 — the dedicated /capacity tab
//  now owns the per-region capacity surface.)
import { GlobalPropertiesEditor } from "../components/GlobalPropertiesEditor";
import { NodePropertiesDrawer } from "../components/NodePropertiesDrawer";
import { RunStartProgress, type Stage } from "../components/RunStartProgress";
import { SaveTemplateDialog } from "../components/SaveTemplateDialog";
import { templatesApi, type TemplateBody } from "../api/templates";
import { deriveStatus, type SubmitChipState } from "../components/RunSummaryChips";
// VizPanelToolbar removed from the launcher; Hide Controls is now
// hosted by FleetAllocationFormView. The view-mode toggle (Flow/Form)
// is also gone — both render together as a hybrid view.
import { useInterval } from "../hooks/useInterval";
import { useKeyboardToggle } from "../hooks/useKeyboardToggle";

/**
 * New-run launcher form. Track F (Step 27) replaced the single
 * `fleetSize` + `region` text inputs with a dual-mode allocation
 * widget driven off `GET /api/v1/regions`. The submit body uses
 * `fleetAllocation` exclusively.
 *
 * <p>On INSUFFICIENT_CAPACITY, the structured `shortfall` is passed
 * back to the widget so the affected region cards/rows highlight, and
 * a "Retry with what's available" button surfaces — clicking it
 * resubmits with `?bestEffort=true`.
 */
type SubmitState =
  | { status: "idle" }
  | { status: "submitting" }
  | { status: "error"; code: string; message: string;
      shortfall?: RegionShortfall[]; lastRequest?: StartRunRequest };

type BlobsState =
  | { status: "loading" }
  | { status: "ok"; testPlans: BlobMetadata[]; dataFiles: BlobMetadata[] }
  | { status: "error"; message: string };

type RegionsState =
  | { status: "loading" }
  | { status: "ok"; regions: RegionCapacity[] }
  | { status: "error"; message: string };

// (D-RunLauncher rework — applications-fetch + ApplicationsState removed;
//  the launcher's app comes from the URL now.)

// View-mode + controls-hidden state lifted out of the panel.
// localStorage keys reuse the existing one for view mode (so prior
// selections still apply) and add a new key for the form-pane toggle.
const CONTROLS_HIDDEN_STORAGE_KEY = "jmeterCloud.newRun.controlsHidden";

function readStoredControlsHidden(): boolean {
  try { return localStorage.getItem(CONTROLS_HIDDEN_STORAGE_KEY) === "1"; }
  catch { return false; }
}

// SummarizeShortfall removed; the shortfall is now
// rendered as a table inside the RunStartProgress modal, not as a
// string in the in-page error pane.

export function NewRunPage() {
  const navigate = useNavigate();
  // Step 28 — application gate. Until the user picks an app, the
  // test-plan and data-files dropdowns stay disabled.
  // D-RunLauncher rework — `application` comes from the URL (`/applications/:appName/runs/new`).
  // The form no longer carries an inline ApplicationPicker; the chosen
  // app is the URL context and is surfaced as a pill next to Status in
  // the header. `setApplication` is preserved as a no-op so any
  // historical setter calls inside the form still type-check; the
  // useState is replaced by URL-derived state below.
  const { appName: urlAppName = "" } = useParams<{ appName: string }>();
  const application = decodeURIComponent(urlAppName);
  // D5 — `?template=<blobId>` hydrates the form from a saved template.
  const [searchParams] = useSearchParams();
  const templateBlobId = searchParams.get("template");
  // D5 — Save Template modal visibility.
  const [saveTemplateOpen, setSaveTemplateOpen] = useState(false);
  const [savedTemplateId, setSavedTemplateId] = useState<string | null>(null);
  const [testPlanBlobId, setTestPlanBlobId] = useState("");
  const [dataFilesBlobId, setDataFilesBlobId] = useState("");
  const [allocation, setAllocation] = useState<FleetAllocationEntry[]>([]);
  // Fleet-wide JMeter property defaults — every worker inherits these
  // unless its per-pod override (perNodeProperties) sets the same key.
  const [globalProperties, setGlobalProperties] = useState<Record<string, string>>({});
  const [labelFilter, setLabelFilter] = useState("");
  const [saveResults, setSaveResults] = useState(false);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  // Multi-stage progress modal shown while a run starts. Null
  // means the modal is closed.
  const [progressStages, setProgressStages] = useState<Stage[] | null>(null);
  const [progressError, setProgressError] = useState<string | null>(null);
  const [blobs, setBlobs] = useState<BlobsState>({ status: "ok", testPlans: [], dataFiles: [] });
  const [regions, setRegions] = useState<RegionsState>({ status: "loading" });
  // Per-region maxAvailable for the URL-bound app. Drives the +/-
  // ceiling so the operator can pick up to the policy cap (not just the
  // currently-IDLE count). Stays null until the apps list loads.
  const [appCapacityMap, setAppCapacityMap] = useState<Record<string, number> | null>(null);

  // Re-load blobs whenever the chosen application changes. Empty app =
  // gate is closed → no blobs fetched.
  //
  // D5 fix — only clear selections on a *subsequent* application change
  // (operator navigates app A → app B in the same browser tab). On the
  // initial mount we MUST NOT clear, otherwise the template-hydrate
  // effect below would race the blobs effect: whichever resolves last
  // wins, and the user sees their saved testPlanBlobId silently wiped.
  // (This was the "saved template didn't keep the test plan" bug.)
  const blobsClearedOnAppChangeRef = useRef(false);

  /**
   * Re-loadable blob fetch. The initial-mount effect calls this
   * with `clearSelections=false` so a hydrated template's blobId is
   * preserved; the post-upload path calls it with `false` too so the
   * just-uploaded blob the parent has already pre-selected stays.
   * Application-change still clears selections (separate flag).
   */
  async function refreshBlobs() {
    if (!application) {
      setBlobs({ status: "ok", testPlans: [], dataFiles: [] });
      return;
    }
    setBlobs({ status: "loading" });
    try {
      const [plans, datas] = await Promise.all([
        blobsApi.list({ type: "testPlan", application, limit: 200 }),
        blobsApi.list({ type: "dataFiles", application, limit: 200 }),
      ]);
      setBlobs({ status: "ok", testPlans: plans.items, dataFiles: datas.items });
    } catch (err: unknown) {
      setBlobs({ status: "error", message: err instanceof Error ? err.message : String(err) });
    }
  }

  useEffect(() => {
    if (!application) {
      setBlobs({ status: "ok", testPlans: [], dataFiles: [] });
      return;
    }
    setBlobs({ status: "loading" });
    const ctl = new AbortController();
    Promise.all([
      blobsApi.list({ type: "testPlan", application, limit: 200 }, ctl.signal),
      blobsApi.list({ type: "dataFiles", application, limit: 200 }, ctl.signal),
    ])
      .then(([plans, datas]) => {
        setBlobs({ status: "ok", testPlans: plans.items, dataFiles: datas.items });
        // Clear stale selections only after the FIRST app change — never
        // on initial mount (template hydration may have just landed values).
        if (blobsClearedOnAppChangeRef.current) {
          setTestPlanBlobId("");
          setDataFilesBlobId("");
        }
        blobsClearedOnAppChangeRef.current = true;
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setBlobs({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [application]);

  // D5 — hydrate the form from a saved template when ?template=<blobId>
  // is in the URL. Runs once per page-load; the template's `application`
  // is expected to match the URL's :appName but isn't enforced (the
  // operator can still edit any field after hydration).
  useEffect(() => {
    if (!templateBlobId) return;
    const ctl = new AbortController();
    templatesApi.load(templateBlobId, ctl.signal)
      .then((tpl: TemplateBody) => {
        setTestPlanBlobId(tpl.testPlanBlobId);
        if (tpl.dataFilesBlobId) setDataFilesBlobId(tpl.dataFilesBlobId);
        if (tpl.fleetAllocation) {
          setAllocation(tpl.fleetAllocation);
          // Rebuild the parallel per-region worker-status array so the fleet
          // diagram's status chips render for hydrated workers (the worker
          // rows themselves render off `allocation`, but the chips read this
          // map). All workers start READY pre-launch — same as addWorkers().
          const statuses: Record<string, WorkerStatus[]> = {};
          for (const e of tpl.fleetAllocation) {
            statuses[e.region] = Array.from({ length: e.count }, () => "READY" as WorkerStatus);
          }
          setWorkerStatuses(statuses);
        }
        if (tpl.globalProperties) setGlobalProperties(tpl.globalProperties);
        if (tpl.labelFilter !== undefined) setLabelFilter(tpl.labelFilter);
        if (tpl.saveResults !== undefined) setSaveResults(tpl.saveResults);
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        // Soft-fail: leave the form in its blank default; the operator
        // can still launch a run from scratch. Log so it's visible.
        // eslint-disable-next-line no-console
        console.warn("template hydration failed:", err);
      });
    return () => ctl.abort();
  }, [templateBlobId]);

  // Initial regions load + 5 s refresh while the page is open.
  async function refreshRegions() {
    try {
      const data = await regionsApi.list();
      setRegions({ status: "ok", regions: data });
    } catch (err) {
      setRegions({ status: "error", message: err instanceof Error ? err.message : String(err) });
    }
  }
  useEffect(() => { void refreshRegions(); }, []);
  useInterval(() => { void refreshRegions(); }, 5000);

  // Fetch the URL-bound application's capacity grid once on mount
  // (and whenever the URL app changes). The cap-aware +/- ceiling reads
  // maxByRegion from this map; missing app or missing capacity row falls
  // back to the legacy idlePods-based bound.
  useEffect(() => {
    if (!application) { setAppCapacityMap(null); return; }
    const ctl = new AbortController();
    applicationsApi.list(ctl.signal)
      .then((apps: Application[]) => {
        const match = apps.find((a) => a.name === application);
        if (!match || !match.capacity) { setAppCapacityMap({}); return; }
        const m: Record<string, number> = {};
        for (const c of match.capacity) m[c.region] = c.maxAvailable;
        setAppCapacityMap(m);
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        // Soft-fail: leave the ceiling at the legacy idlePods bound. The
        // launcher form is still usable; the operator just sees the
        // pre-UX1 cap behavior.
        // eslint-disable-next-line no-console
        console.warn("application capacity fetch failed:", err);
        setAppCapacityMap({});
      });
    return () => ctl.abort();
  }, [application]);

  // Per-region worker status arrays — parallel to allocation[].perNodeProperties.
  // Pre-launch all workers are READY; post-launch this'll be driven by the
  // global-orch's heartbeat snapshots.
  const [workerStatuses, setWorkerStatuses] = useState<Record<string, WorkerStatus[]>>({});

  // Step 31 — drawer state.
  const [drawer, setDrawer] = useState<{ region: string; nodeIndex: number } | null>(null);
  function openDrawer(region: string, nodeIndex: number) {
    setDrawer({ region, nodeIndex });
  }

  /**
   * Add N workers to a region. Each new worker's perNodeProperties[i]
   * is a **snapshot** of the current globalProperties at click time —
   * subsequent global-property edits do NOT mutate this snapshot.
   * Clamps to the region's remaining idle capacity.
   */
  function addWorkers(region: string, n: number) {
    if (n < 1) return;
    // Clamp at the app's per-region maxAvailable (the policy cap),
    // not the live IDLE-pod count. Picking more than IDLE-now is fine —
    // the shortfall flow handles the gap via spinShortfall. Picking
    // more than maxAvailable is rejected by the backend; clamp here so
    // the UI matches the contract.
    const ceiling = appCapacityMap?.[region]
        ?? regionsList.find((r) => r.region === region)?.idlePods
        ?? 0;
    setAllocation((prev) => {
      const existing = prev.find((e) => e.region === region);
      const currentCount = existing?.count ?? 0;
      const room = Math.max(0, ceiling - currentCount);
      const toAdd = Math.min(n, room);
      if (toAdd === 0) return prev;
      const snapshot = { ...globalProperties };
      const newProps = Array.from({ length: toAdd }, () => ({ ...snapshot }));
      if (existing) {
        return prev.map((e) =>
          e.region === region
            ? {
                ...e,
                count: e.count + toAdd,
                perNodeProperties: [...(e.perNodeProperties ?? []), ...newProps],
              }
            : e,
        );
      }
      const fresh: FleetAllocationEntry = {
        region,
        count: toAdd,
        perNodeProperties: newProps,
      };
      return [...prev, fresh].sort((a, b) => a.region.localeCompare(b.region));
    });
    setWorkerStatuses((prev) => {
      const cur = prev[region] ?? [];
      const currentCount = cur.length;
      const room = Math.max(0, ceiling - currentCount);
      const toAdd = Math.min(n, room);
      if (toAdd === 0) return prev;
      return {
        ...prev,
        [region]: [...cur, ...Array.from({ length: toAdd }, () => "READY" as WorkerStatus)],
      };
    });
  }

  /**
   * Remove N workers from a region — from the tail of the array.
   * Pre-launch this is a state edit; once mid-test scaling lands the
   * backend will mark removed workers as DRAINING and reap them once
   * their JMeter process exits cleanly.
   */
  function removeWorkers(region: string, n: number) {
    if (n < 1) return;
    setAllocation((prev) =>
      prev.flatMap((e) => {
        if (e.region !== region) return [e];
        const newCount = Math.max(0, e.count - n);
        if (newCount === 0) return [];
        const newProps = (e.perNodeProperties ?? []).slice(0, newCount);
        return [{ ...e, count: newCount, perNodeProperties: newProps }];
      }),
    );
    setWorkerStatuses((prev) => {
      const cur = prev[region] ?? [];
      const sliced = cur.slice(0, Math.max(0, cur.length - n));
      if (sliced.length === 0) {
        const { [region]: _drop, ...rest } = prev;
        return rest;
      }
      return { ...prev, [region]: sliced };
    });
  }

  function saveProps(props: Record<string, string>, applyToAll: boolean) {
    if (!drawer) return;
    setAllocation((prev) =>
      prev.map((e) => {
        if (e.region !== drawer.region) return e;
        const next: Record<string, string>[] = (e.perNodeProperties ?? []).slice();
        while (next.length < e.count) next.push({});
        if (applyToAll) {
          for (let i = 0; i < e.count; i++) next[i] = { ...props };
        } else {
          next[drawer.nodeIndex] = props;
        }
        return { ...e, perNodeProperties: next };
      }),
    );
    setDrawer(null);
  }

  function getDrawerInitial(): Record<string, string> {
    if (!drawer) return {};
    const entry = allocation.find((e) => e.region === drawer.region);
    // Every worker has a snapshot since the snapshot-on-add change; if
    // somehow missing (older state), fall back to current globals.
    return entry?.perNodeProperties?.[drawer.nodeIndex] ?? { ...globalProperties };
  }

  function getDrawerRegionTotal(): number {
    if (!drawer) return 0;
    return allocation.find((e) => e.region === drawer.region)?.count ?? 0;
  }

  const totalPods = allocation.reduce((acc, e) => acc + e.count, 0);
  const appError = !application ? "pick an application first" : null;
  const planError = !testPlanBlobId ? "test plan is required" : null;
  const allocError = totalPods < 1 ? "allocate at least 1 worker" : null;
  const dupError = hasDuplicates(allocation) ? "duplicate region in allocation" : null;
  const canSubmit = !appError && !planError && !allocError && !dupError && submit.status !== "submitting";
  const appGateOpen = !!application;

  function buildRequest(): StartRunRequest {
    const labels = labelFilter.split(",").map((s) => s.trim()).filter(Boolean);
    // No merge: each worker's perNodeProperties[i] is already a complete
    // snapshot (taken when the worker was added, plus any drawer edits).
    // Trim trailing empty maps so the wire stays compact.
    const cleanAllocation: FleetAllocationEntry[] = allocation.map((e) => {
      const props = (e.perNodeProperties ?? []).slice();
      while (props.length > 0 && Object.keys(props[props.length - 1] ?? {}).length === 0) {
        props.pop();
      }
      return { ...e, perNodeProperties: props.length > 0 ? props : undefined };
    });
    const body: StartRunRequest = {
      testPlanBlobId,
      // Persist the selected application on the run record so
      // the Applications tab can filter without joining through document-service.
      application: application.trim() || undefined,
      fleetAllocation: cleanAllocation,
      labelFilter: labels.length > 0 ? labels : undefined,
      saveResults: saveResults || undefined,
      // initiatedBy is no longer collected here — the global-orchestrator
      // derives it from the X-Actor header (the cached operator name).
    };
    if (dataFilesBlobId) body.dataFilesBlobId = dataFilesBlobId;
    return body;
  }

  /**
   * Count of workers whose snapshot diverges from the *current* globals.
   * Drives the reminder note in <GlobalPropertiesEditor> so the operator
   * knows changes only affect future workers.
   */
  const divergedCount = useMemo(() => {
    const globalsKey = JSON.stringify(globalProperties);
    let n = 0;
    for (const e of allocation) {
      for (const snap of e.perNodeProperties ?? []) {
        if (JSON.stringify(snap) !== globalsKey) n++;
      }
    }
    return n;
  }, [allocation, globalProperties]);

  /** UX25 — initial five-stage state for the progress modal. */
  function initialStages(req: StartRunRequest): Stage[] {
    return [
      {
        id: "provisioning",
        label: "Provisioning workers",
        // When spinShortfall is on, this stage actually spins pods —
        // worth a "spinning…" subtext so the operator knows where the
        // 10–30 s wait is going.
        detail: req.spinShortfall ? "Spinning fresh pods to fill the shortfall" : undefined,
        status: req.spinShortfall ? "active" : "skipped",
      },
      { id: "workersReady",   label: "Workers ready",          status: "active" },
      { id: "distributing",   label: "Distributing test plan", status: "pending" },
      { id: "startingJmeter", label: "Starting JMeter",        status: "pending" },
      { id: "verifying",      label: "Verifying metrics",      status: "pending" },
    ];
  }

  function patchStage(id: string, patch: Partial<Stage>): void {
    setProgressStages((prev) => prev?.map((s) => (s.id === id ? { ...s, ...patch } : s)) ?? prev);
  }

  /**
   * Poll the run-status endpoint after the run is created, and
   * advance stages 3/4/5 based on observed member states.
   *
   * <p>Mapping (we can't distinguish "uploading artifacts" from
   * "starting JMeter" at the global level — the pod's local
   * TestRunManager handles both inside its 202 ACCEPTED handler — so
   * we collapse stages 3 and 4 with a short visual delay so each gets
   * its checkmark moment):
   * <ul>
   *   <li>Members all PENDING/REQUESTED → keep stage 3 active.</li>
   *   <li>Any member ACCEPTED → stage 3 active.</li>
   *   <li>Any member RUNNING → stage 3 done; 400 ms later, stage 4 done; stage 5 active.</li>
   *   <li>All members RUNNING → stage 5 done; 600 ms settle, then navigate.</li>
   *   <li>All members terminal-with-failure → mark current stage failed.</li>
   * </ul>
   */
  async function pollUntilLive(runId: string, navigateOnDone: () => void) {
    let attempts = 0;
    while (attempts < 120 /* ~3 minutes */) {
      attempts++;
      try {
        const snap = await runsApi.status(runId);
        const states = snap.members.map((m) => m.state);
        const anyRunning = states.includes("RUNNING");
        const allRunning = states.length > 0 && states.every((s) => s === "RUNNING");
        const anyFailed = states.includes("FAILED");
        const allTerminal = states.length > 0 && states.every(
          (s) => s === "RUNNING" || s === "COMPLETED" || s === "FAILED"
              || s === "ABORTED" || s === "DRAINED",
        );

        // Stage 3 detail: how many workers have accepted the start.
        const acceptedCount = states.filter(
          (s) => s === "ACCEPTED" || s === "RUNNING" || s === "DRAINING"
              || s === "COMPLETED" || s === "DRAINED",
        ).length;
        patchStage("distributing", {
          detail: states.length > 0
            ? `${acceptedCount}/${states.length} workers ready`
            : undefined,
        });

        if (anyFailed && !anyRunning && allTerminal) {
          patchStage("distributing", { status: "failed" });
          setProgressError(`A worker rejected the start: ${snap.stateReason ?? "see run-detail page"}`);
          return;
        }

        if (anyRunning) {
          // Stages 3 & 4 done; flash them as a pair with a small visual gap.
          patchStage("distributing", { status: "done" });
          await sleep(400);
          patchStage("startingJmeter", { status: "done" });
          patchStage("verifying", {
            status: "active",
            detail: `${states.filter((s) => s === "RUNNING").length}/${states.length} workers live`,
          });
        }

        if (allRunning) {
          patchStage("verifying", { status: "done", detail: undefined });
          await sleep(600);
          navigateOnDone();
          return;
        }
      } catch {
        // Transient — try again next tick.
      }
      await sleep(1500);
    }
    // Timed out — leave the modal open with a soft warning; the run
    // does exist on the run-detail page and the operator can navigate
    // there manually.
    setProgressError("Took longer than expected to reach RUNNING. The run still exists — open the run-detail page from the runs list.");
    patchStage("verifying", { status: "failed" });
  }

  async function send(req: StartRunRequest, opts: { bestEffort?: boolean } = {}) {
    setSubmit({ status: "submitting" });
    setProgressError(null);
    setProgressStages(initialStages(req));
    try {
      const run = await runsApi.start(req, opts);
      // POST returned. Provisioning + workersReady are done. Stage 3 active.
      setProgressStages((prev) => prev?.map((s) => {
        if (s.id === "provisioning")  return { ...s, status: s.status === "skipped" ? "skipped" : "done" };
        if (s.id === "workersReady")  return { ...s, status: "done" };
        if (s.id === "distributing")  return { ...s, status: "active" };
        if (s.id === "startingJmeter") return { ...s, status: "active" };
        return s;
      }) ?? prev);
      const appSeg = encodeURIComponent(application.trim() || "_");
      await pollUntilLive(run.runId, () => navigate(`/applications/${appSeg}/runs/${run.runId}`));
    } catch (err) {
      if (err instanceof GlobalOrchestratorError) {
        setSubmit({
          status: "error",
          code: err.code,
          message: err.message,
          shortfall: err.shortfall,
          lastRequest: req,
        });
      } else {
        setSubmit({
          status: "error",
          code: "NETWORK_ERROR",
          message: err instanceof Error ? err.message : String(err),
        });
      }
      // INSUFFICIENT_CAPACITY isn't a hard failure; it's a
      // recoverable shortfall the operator can resolve by spinning the
      // gap or accepting bestEffort. Leave the stages alone (we never
      // really got started) and let the modal swap to the shortfall
      // prompt via the shortfallPrompt prop. Other errors mark the
      // current active stage failed as before.
      if (err instanceof GlobalOrchestratorError && err.code === "INSUFFICIENT_CAPACITY") {
        // Stages were briefly "active"; reset them to pending so the
        // modal's shortfall branch isn't confused by an active spinner
        // behind it (the branch doesn't render the list, but this
        // keeps the state consistent for the post-action restart).
        setProgressStages((prev) => prev?.map((s) =>
          s.status === "active" ? { ...s, status: "pending" as const } : s,
        ) ?? prev);
        return;
      }
      setProgressStages((prev) => {
        if (!prev) return prev;
        const idx = prev.findIndex((s) => s.status === "active");
        if (idx < 0) return prev;
        return prev.map((s, i) => (i === idx ? { ...s, status: "failed" as const } : s));
      });
      setProgressError(err instanceof Error ? err.message : String(err));
    }
  }

  function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit) return;
    await send(buildRequest());
  }

  // HandleSpinShortfall / handleRetryBestEffort previously
  // backed the in-page WORKERS NOT READY buttons. Both are now invoked
  // directly inline from the progress modal's shortfallPrompt prop, so
  // the named wrappers are gone.

  // Dev-only idlePods clamp — lets the operator exercise the diagram with
  // up to 20 pods per region without spinning up that many local-orchestrator
  // replicas. Production builds (`import.meta.env.DEV === false`) keep the
  // real /regions value untouched.
  // The global /regions rollup only returns regions where at
  // least one pod is registered. For a fresh app with a 4-region
  // capacity grid but no pods yet, three of those regions would be
  // missing from the launcher entirely. Merge the app's capacity-grid
  // regions in with idlePods=0/totalPods=0 so the operator can still
  // pick workers for them (the shortfall flow handles the spin).
  const regionsList = useMemo(() => {
    const raw = regions.status === "ok" ? regions.regions : [];
    // Index existing rollup rows by region for O(1) merge.
    const byRegion = new Map<string, RegionCapacity>();
    for (const r of raw) byRegion.set(r.region, r);
    // Augment with every region declared in the app's capacity grid.
    if (appCapacityMap) {
      for (const region of Object.keys(appCapacityMap)) {
        if (!byRegion.has(region)) {
          byRegion.set(region, {
            region,
            totalPods: 0,
            idlePods: 0,
            lostPods: 0,
          });
        }
      }
    }
    const merged = Array.from(byRegion.values());
    if (!import.meta.env.DEV) return merged;
    return merged.map((r) => ({
      ...r,
      idlePods: Math.max(r.idlePods, 20),
      totalPods: Math.max(r.totalPods, 20),
    }));
  }, [regions, appCapacityMap]);

  // UI-C2.b polish: the redundant chip strip (TOTAL PODS / REGIONS /
  // PLAN / STATUS) collapsed into a single Status badge in the header.
  // The other counts are visible in the diagram / form view themselves;
  // re-stating them above wasted screen real estate.
  const chipSubmit: SubmitChipState =
    submit.status === "submitting" ? { status: "submitting" }
    : submit.status === "error"    ? { status: "error", code: submit.code }
    : { status: "idle" };
  const headerStatus = deriveStatus({
    application,
    planSelected: !!testPlanBlobId,
    totalPods,
    submit: chipSubmit,
  });

  // Viz-toolbar state. Form-pane visibility persists across reloads;
  // the `[` keybind toggles controls visibility unless the operator is
  // typing into a form field. View-mode toggle (Flow/Form) restored in
  // The diagram is the default; the Form view is a
  // table-style alternative for keyboard-heavy operators.
  const [controlsHidden, setControlsHidden] = useState<boolean>(readStoredControlsHidden);
  useEffect(() => {
    try { localStorage.setItem(CONTROLS_HIDDEN_STORAGE_KEY, controlsHidden ? "1" : "0"); }
    catch { /* ignore */ }
  }, [controlsHidden]);

  // ViewMode/setViewMode were used by the now-gone VizPanelToolbar.
  // We keep readStoredViewMode + VIEW_MODE_STORAGE_KEY off the export
  // surface so stale localStorage values don't matter; nothing reads them.

  useKeyboardToggle("[", () => setControlsHidden((h) => !h));

  return (
    <section className={`newRunLayout${controlsHidden ? " newRunLayout--controlsHidden" : ""}`}>
      <header className="pageHeader newRunLayout__header newRunLayout__header--inline">
        <div className="newRunLayout__headerLeft">
          {/* UX15 — Back link moved to the left of the title so the header
              left-side reads "← Back · Title · App · Status". Mirrors the
              standard "back-then-title" pattern. */}
          <Link
            to={application ? `/applications/${encodeURIComponent(application)}` : "/applications"}
            className="newRunLayout__backLink"
          >← Back to {application || "applications"}</Link>
          <h1>Start a new run</h1>
          {application && (
            <Link
              to={`/applications/${encodeURIComponent(application)}`}
              className="newRunLayout__appPill mono"
              title={`Back to ${application} detail`}
            >
              {application}
            </Link>
          )}
          <span
            className={`newRunLayout__statusBadge newRunLayout__statusBadge--${headerStatus.variant}`}
            role="status"
            aria-live="polite"
          >
            {headerStatus.label}
          </span>
        </div>
        <div className="newRunLayout__headerRight">
          {/* UX15 — Hide Controls escape valve. Lives in the always-visible
              header so the operator can bring the form column back even
              after collapsing it. (Previously the toggle was inside the
              form column itself — collapsing it stranded the user.) */}
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => setControlsHidden((h) => !h)}
            title={controlsHidden ? "Show form pane (keyboard: [)" : "Hide form pane (keyboard: [)"}
            aria-pressed={controlsHidden}
          >
            {controlsHidden ? "▸ Show Controls" : "◂ Hide Controls"}
          </button>
          <button
            type="button"
            className="btn"
            onClick={() => setSaveTemplateOpen(true)}
            title="Save the current launcher state for reuse later"
            disabled={!testPlanBlobId}
          >
            Save Template
          </button>
          <button
            type="submit"
            form="newRunForm"
            className="btn btn--primary"
            disabled={!canSubmit}
            aria-busy={submit.status === "submitting"}
          >
            {submit.status === "submitting" ? "Starting…" : "Start run"}
          </button>
          {/* UX23 — Cancel button removed; the "← Back to {app}" link in
              the header-left navigates to the same place. */}
        </div>
      </header>

      <form id="newRunForm" onSubmit={handleSubmit} className="runForm newRunLayout__form" noValidate>
        {/* D-RunLauncher rework — application no longer picked inline.
            It comes from the URL (`/applications/:appName/runs/new`) and
            is surfaced as a pill in the page header. The hidden field
            below preserves form-state semantics for any code path that
            inspects the form. */}
        <input type="hidden" name="application" value={application} readOnly />

        <div className="formField">
          <label htmlFor="testPlanBlobId">Test plan *</label>
          <div className="blobPickerRow">
            <BlobSelect
              id="testPlanBlobId"
              value={testPlanBlobId}
              onChange={setTestPlanBlobId}
              blobs={blobs.status === "ok" ? blobs.testPlans : []}
              placeholder={appGateOpen ? "— pick a plan —" : "— pick an application first —"}
              loading={blobs.status === "loading"}
              error={blobs.status === "error" ? blobs.message : null}
              disabled={!appGateOpen}
              required
            />
            {/* UX13 — inline upload, no Documents-page round-trip. */}
            <InlineBlobUpload
              accept=".jmx,application/xml,text/xml"
              type="testPlan"
              application={application}
              disabled={!appGateOpen}
              onUploaded={async (b) => {
                setTestPlanBlobId(b.blobId);
                await refreshBlobs();
              }}
            />
          </div>
          {!appGateOpen && (
            <small className="ink-soft">Pick an application above to unlock this dropdown.</small>
          )}
          {planError && submit.status === "error" && (
            <p className="text--error" role="alert">{planError}</p>
          )}
        </div>

        <div className="formField">
          <label htmlFor="dataFilesBlobId">Data files</label>
          <small className="ink-soft">Multiple files? Bundle them into a single .zip.</small>
          <div className="blobPickerRow">
            <BlobSelect
              id="dataFilesBlobId"
              value={dataFilesBlobId}
              onChange={setDataFilesBlobId}
              blobs={blobs.status === "ok" ? blobs.dataFiles : []}
              placeholder={appGateOpen ? "— optional, none —" : "— pick an application first —"}
              loading={blobs.status === "loading"}
              error={blobs.status === "error" ? blobs.message : null}
              disabled={!appGateOpen}
            />
            <InlineBlobUpload
              accept=".zip,.csv,application/zip,text/csv"
              type="dataFiles"
              application={application}
              disabled={!appGateOpen}
              onUploaded={async (b) => {
                setDataFilesBlobId(b.blobId);
                await refreshBlobs();
              }}
            />
          </div>
        </div>

        <div className="formField formField--checkbox">
          <label htmlFor="saveResults" className="checkboxRow">
            <input
              id="saveResults"
              type="checkbox"
              checked={saveResults}
              onChange={(e) => setSaveResults(e.target.checked)}
            />
            <span>Save results</span>
          </label>
        </div>

        {/* UX11 — Fleet allocation form lifted out of the right pane so
            the diagram can use the full reclaimed width. Sits between
            file pickers and Global Properties per UX11 decision.
            UX15 — Hide Controls toggle removed from this view; lives in
            the page header now so it stays reachable when the form
            column is collapsed. */}
        <FleetAllocationFormView
          regions={regionsList}
          value={allocation}
          workerStatuses={workerStatuses}
          onAddWorkers={addWorkers}
          onRemoveWorkers={removeWorkers}
          maxByRegion={appCapacityMap ?? undefined}
          shortfall={submit.status === "error" ? submit.shortfall : undefined}
          loading={regions.status === "loading"}
          error={regions.status === "error" ? regions.message : null}
        />

        {allocError && submit.status === "error" && (
          <p className="text--error" role="alert">{allocError}</p>
        )}
        {dupError && (
          <p className="text--error" role="alert">{dupError}</p>
        )}

        {/* UX29 — the in-page error pane is hidden for
            INSUFFICIENT_CAPACITY; the progress modal is the single
            source for that warning (with the per-region table + the
            two action buttons). Other error codes still surface here
            so a NETWORK_ERROR or unknown code isn't lost. */}
        {submit.status === "error" && submit.code !== "INSUFFICIENT_CAPACITY" && (
          <div className="formError" role="alert">
            <strong>{submit.code}</strong>: {submit.message}
          </div>
        )}

        <GlobalPropertiesEditor
          value={globalProperties}
          onChange={setGlobalProperties}
          divergedCount={divergedCount}
        />

        {/* AllocationOverview was here pre-D-Capacity v2; the dedicated
            /capacity tab now owns the per-region capacity surface so the
            launcher form stays focused on what's specific to *this* run.

            Label filter sits below — optional run-metadata, not central to
            the fleet decision. "Initiated by" was removed: the run's
            initiator is now derived from the operator's name (the X-Actor
            header set via the header control), not asked for here. */}
        <div className="formField">
          <label htmlFor="labelFilter">Label filter</label>
          <input
            id="labelFilter"
            type="text"
            value={labelFilter}
            onChange={(e) => setLabelFilter(e.target.value)}
            placeholder="comma-separated, e.g. GET /api/foo, POST /api/bar"
            autoComplete="off"
          />
          <small>Optional — focus the run on specific JMeter sampler labels.</small>
        </div>
      </form>

      <div className="newRunLayout__viz">
        {/* UX11 — right pane is now diagram-only. The allocation form
            lives in the left form column above Global Properties. */}
        <FleetFlowDiagram
          regions={regionsList}
          applicationName={application}
          value={allocation}
          workerStatuses={workerStatuses}
          onWorkerClick={openDrawer}
          maxByRegion={appCapacityMap ?? undefined}
          shortfall={submit.status === "error" ? submit.shortfall : undefined}
          loading={regions.status === "loading"}
          error={regions.status === "error" ? regions.message : null}
        />
      </div>

      {drawer && (
        <NodePropertiesDrawer
          region={drawer.region}
          nodeIndex={drawer.nodeIndex}
          totalNodesInRegion={getDrawerRegionTotal()}
          workerName={workerName(application, drawer.region, drawer.nodeIndex + 1)}
          initialProperties={getDrawerInitial()}
          onSave={saveProps}
          onClose={() => setDrawer(null)}
        />
      )}

      {/* UX25 — multi-stage progress modal during the run-start
          handshake. Non-dismissable while stages are in flight; the
          only exit valves are after a failure (Dismiss / Retry) or
          when stage 5 completes (auto-closes via navigation).
          UX28 — when INSUFFICIENT_CAPACITY, the modal swaps to a
          shortfall prompt so the operator gets the right action
          (Spin / bestEffort) instead of a generic "failed" footer. */}
      <RunStartProgress
        open={progressStages !== null}
        stages={progressStages ?? []}
        errorMessage={progressError}
        shortfallPrompt={
          progressStages !== null
            && submit.status === "error"
            && submit.code === "INSUFFICIENT_CAPACITY"
            && submit.lastRequest
            ? {
                rows: submit.shortfall ?? [],
                fallbackMessage: submit.message,
                onSpinShortfall: () => { void send({ ...submit.lastRequest!, spinShortfall: true }); },
                onBestEffort:   () => { void send(submit.lastRequest!, { bestEffort: true }); },
                onCancel:       () => { setProgressStages(null); setProgressError(null); },
              }
            : null
        }
        onCancel={progressStages?.some((s) => s.status === "failed")
          ? () => { setProgressStages(null); setProgressError(null); }
          : undefined}
        onRetry={progressStages?.some((s) => s.status === "failed") && submit.status === "error" && submit.lastRequest
          ? () => { void send(submit.lastRequest!); }
          : undefined}
      />

      {saveTemplateOpen && (
        <SaveTemplateDialog
          body={{
            v: 1,
            application,
            testPlanBlobId,
            dataFilesBlobId: dataFilesBlobId || undefined,
            fleetAllocation: allocation,
            globalProperties:
              Object.keys(globalProperties).length > 0 ? globalProperties : undefined,
            labelFilter: labelFilter || undefined,
            saveResults: saveResults || undefined,
          }}
          onSaved={(blobId) => {
            setSavedTemplateId(blobId);
            setSaveTemplateOpen(false);
          }}
          onClose={() => setSaveTemplateOpen(false)}
        />
      )}

      {savedTemplateId && (
        <div className="formError" role="status" style={{
          position: "fixed", bottom: "1rem", right: "1rem",
          background: "rgba(16, 185, 129, 0.10)", color: "#047857",
          border: "1px solid rgba(16, 185, 129, 0.30)", maxWidth: "320px",
        }}>
          Template saved.{" "}
          <Link to="/templates" style={{ color: "inherit", fontWeight: 600 }}>
            Browse templates →
          </Link>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => setSavedTemplateId(null)}
            aria-label="Dismiss"
            style={{ marginLeft: "0.5rem", padding: "0 0.4rem" }}
          >×</button>
        </div>
      )}
    </section>
  );
}

function hasDuplicates(allocation: FleetAllocationEntry[]): boolean {
  const seen = new Set<string>();
  for (const e of allocation) {
    if (seen.has(e.region)) return true;
    seen.add(e.region);
  }
  return false;
}

interface BlobSelectProps {
  id: string;
  value: string;
  onChange: (next: string) => void;
  blobs: BlobMetadata[];
  placeholder: string;
  loading: boolean;
  error: string | null;
  required?: boolean;
  /** Step 28 — closed when no application is selected. */
  disabled?: boolean;
}

function BlobSelect({ id, value, onChange, blobs, placeholder, loading, error, required, disabled }: BlobSelectProps) {
  return (
    <select
      id={id}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={loading || disabled}
      required={required}
    >
      <option value="">{loading ? "loading…" : error ? `error: ${error}` : placeholder}</option>
      {blobs.map((b) => (
        <option key={b.blobId} value={b.blobId}>
          {b.name ?? b.blobId} ({formatBytes(b.sizeBytes)}) — {b.blobId.slice(0, 8)}…
        </option>
      ))}
    </select>
  );
}

// (D-RunLauncher rework — ApplicationPicker removed; the launcher's
//  app comes from the URL path `/applications/:appName/runs/new`.)

/**
 * Inline file upload alongside the BlobSelect dropdowns.
 * Removes the friction of "leave this page → go to /documents → upload
 * → come back → re-pick the just-uploaded blob." The upload tags the
 * blob with the launcher's current application + the requested type so
 * the next list-refresh surfaces it under the right picker.
 */
interface InlineBlobUploadProps {
  accept: string;
  type: "testPlan" | "dataFiles";
  application: string;
  disabled?: boolean;
  onUploaded: (b: BlobMetadata) => void | Promise<void>;
}

function InlineBlobUpload({ accept, type, application, disabled, onUploaded }: InlineBlobUploadProps) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [state, setState] = useState<
    | { kind: "idle" }
    | { kind: "uploading"; pct: number }
    | { kind: "error"; message: string }
  >({ kind: "idle" });

  const pickAndUpload = () => inputRef.current?.click();

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // reset so re-picking the same file fires onChange
    if (!file || !application) return;
    setState({ kind: "uploading", pct: 0 });
    try {
      const blob = await blobsApi.upload(file, {
        type,
        application,
        name: file.name,
        onProgress: (sent, total) =>
          setState({ kind: "uploading", pct: total > 0 ? Math.round((sent / total) * 100) : 0 }),
      });
      setState({ kind: "idle" });
      await onUploaded(blob);
    } catch (err: unknown) {
      setState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }

  return (
    <span className="blobPickerRow__upload">
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        className="visuallyHidden"
        onChange={handleFileChange}
        disabled={disabled}
      />
      <button
        type="button"
        className="btn btn--ghost blobPickerRow__uploadBtn"
        onClick={pickAndUpload}
        disabled={disabled || state.kind === "uploading"}
        title={
          disabled
            ? "Pick an application first"
            : `Upload a new ${type === "testPlan" ? ".jmx" : "data files"} for ${application}`
        }
      >
        {state.kind === "uploading"
          ? `Uploading… ${state.pct}%`
          : "Upload"}
      </button>
      {state.kind === "error" && (
        <small className="text--error blobPickerRow__uploadError" role="alert">
          {state.message}
        </small>
      )}
    </span>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}
