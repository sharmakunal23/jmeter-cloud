import { useEffect, useState } from "react";

import {
  GlobalOrchestratorError,
  runsApi,
  type Run,
  type ScaleDownRunResponse,
} from "../api/runs";

/**
 * MID-TEST-SCALING Phase F + smoke-fix-2 (2026-05-15) — unified drain
 * confirmation dialog. Replaces the original {@code DrainWorkerDialog}
 * (single-worker only) so the UI ships ONE drain modal that scales
 * from 1 worker → N selected → "stop the entire test".
 *
 * <p>Modes (all use the same {@code POST /api/v1/runs/{runId}/scaleDown}
 * endpoint with a {@code workerIds} list — backend already supports
 * multi-workerIds idempotently):
 * <ul>
 *   <li><b>single</b> — per-row "Drain" click. Title "Drain worker?",
 *       body singular, button "Drain worker".</li>
 *   <li><b>bulk</b> — operator selected ≥ 2 rows via checkboxes.
 *       Title "Drain N workers?", body counts the selection +
 *       remaining live workers, button "Drain N workers".</li>
 *   <li><b>stopTest</b> — operator clicked "Stop test"; targets the
 *       full live-worker set. Title "Stop the test?", body explains
 *       run will end COMPLETED when all members terminate, button
 *       "Stop test".</li>
 * </ul>
 *
 * <p>The drain itself is graceful — JMeter's TCP shutdown port lets
 * in-flight samplers complete; each worker lands in {@code DRAINED}
 * (or {@code ABORTED} with reason {@code drainTimeoutExpired} if the
 * drain budget elapses, default 60 s on the local-orch).
 *
 * <p>Run state stays {@code RUNNING} during drain — terminates only
 * when ALL members are terminal. {@code stopTest} mode targets every
 * live worker so the run rolls up to terminal once they all DRAINED.
 *
 * <p>On success, {@code onSuccess(updatedRun)} is called so the page
 * can replace its state without waiting for the next poll.
 */
export type DrainMode = "single" | "bulk" | "stopTest";

export interface DrainDialogProps {
  runId: string;
  workerIds: string[];
  mode: DrainMode;
  /** Total live workers in the run — drives the "continues with N-Y" hint for bulk/single modes. */
  liveWorkerCount: number;
  onClose: () => void;
  onSuccess: (run: Run) => void;
}

interface SubmitState {
  status: "idle" | "submitting" | "error";
  code?: string;
  message?: string;
  /** Per-worker rejection reasons when the call returned 200 but every target was skipped. */
  skipped?: Array<{ workerId: string; reason: string }>;
}

export function DrainDialog({
  runId, workerIds, mode, liveWorkerCount, onClose, onSuccess,
}: DrainDialogProps) {
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // ESC closes — same dismiss UX as the rest of the modal family.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function handleConfirm() {
    setSubmit({ status: "submitting" });
    try {
      const resp: ScaleDownRunResponse = await runsApi.scaleDown(
        runId,
        { workerIds },
      );
      // 2026-05-15 (smoke fix): the backend returns 200 even when every
      // target was skipped (e.g., 404 from a worker pod running an older
      // image without the drain endpoint). Originally we treated 200 as
      // unconditional success and closed silently, so the operator
      // clicked Drain, the dialog disappeared, and nothing visibly
      // changed — confusing. Now: ALL-skipped → surface the rejections;
      // operator gets the WHY before they re-attempt.
      if (resp.drained.length === 0 && resp.skipped.length > 0) {
        setSubmit({
          status: "error",
          code: "ALL_SKIPPED",
          message:
            "No workers were drained — every target was rejected by its local orchestrator. " +
            "If your worker pods were spun up before a recent jmeter-local-orchestrator image " +
            "rebuild, recycle them: open the Capacity tab → Drain All Ready → Provision N Worker(s) " +
            "for fresh pods on the new image.",
          skipped: resp.skipped,
        });
        // Update the page state with whatever the backend returned even
        // on all-skipped — keeps the run snapshot fresh.
        onSuccess(resp.run);
        return;
      }
      onSuccess(resp.run);
      onClose();
    } catch (err) {
      if (err instanceof GlobalOrchestratorError) {
        setSubmit({ status: "error", code: err.code, message: err.message });
      } else {
        setSubmit({
          status: "error",
          code: "UNKNOWN",
          message: err instanceof Error ? err.message : String(err),
        });
      }
    }
  }

  const submitting = submit.status === "submitting";
  const targetCount = workerIds.length;
  const remaining = Math.max(0, liveWorkerCount - targetCount);

  // Mode-specific copy.
  const title =
    mode === "stopTest" ? "Stop the test?"
    : targetCount === 1 ? "Drain worker?"
    : `Drain ${targetCount} workers?`;

  const subtitle =
    mode === "stopTest" ? `${targetCount} live worker${targetCount === 1 ? "" : "s"}`
    : targetCount === 1 ? <span className="mono">{workerIds[0]}</span>
    : `${targetCount} selected`;

  const buttonLabel =
    submitting ? (mode === "stopTest" ? "Stopping…" : "Draining…")
    : mode === "stopTest" ? "Stop test"
    : targetCount === 1 ? "Drain worker"
    : `Drain ${targetCount} workers`;

  return (
    <div className="modal__overlay" role="presentation" onClick={onClose}>
      <div
        className="modal modal--application"
        role="dialog"
        aria-modal="true"
        aria-labelledby="drainDialogTitle"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3 id="drainDialogTitle">{title}</h3>
            <small className="ink-soft">{subtitle}</small>
          </div>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onClose}
            aria-label="Close"
          >×</button>
        </header>

        <div className="modal__body">
          <p>
            Sends a graceful drain to JMeter's shutdown port on{" "}
            {mode === "stopTest" || targetCount > 1
              ? <>each of the <strong>{targetCount}</strong> targeted workers</>
              : "the worker"}.
            In-flight samplers complete; no new ones start.
            {targetCount === 1
              ? <> The worker lands in <span className="badge badge--ok" style={{ marginLeft: "0.3rem" }}>DRAINED</span> on clean exit (or <span className="badge badge--err">ABORTED</span> with reason <span className="mono">drainTimeoutExpired</span> if the drain budget elapses, default 60 s).</>
              : <> Each worker lands in <span className="badge badge--ok" style={{ marginLeft: "0.3rem" }}>DRAINED</span> on clean exit (or <span className="badge badge--err">ABORTED</span> on drain-timeout).</>
            }
          </p>
          {mode === "stopTest" ? (
            <p className="ink-soft" style={{ fontSize: "0.85rem" }}>
              The run will roll up to{" "}
              <span className="badge badge--ok">COMPLETED</span> once every
              member terminates. DRAINED counts as a successful end.
            </p>
          ) : (
            <p className="ink-soft" style={{ fontSize: "0.85rem" }}>
              The run continues with{" "}
              <strong>{remaining} worker{remaining === 1 ? "" : "s"}</strong>{" "}
              and stays <span className="badge badge--info">RUNNING</span> —
              it terminates only when every member terminates.
            </p>
          )}
          {submit.status === "error" && (
            <div className="formError" role="alert">
              <strong>{submit.code}</strong>: {submit.message}
              {submit.skipped && submit.skipped.length > 0 && (
                <details style={{ marginTop: "0.5rem" }}>
                  <summary style={{ cursor: "pointer" }}>
                    Per-worker rejections ({submit.skipped.length})
                  </summary>
                  <ul style={{ margin: "0.4rem 0 0 1rem", padding: 0, fontSize: "0.82rem" }}>
                    {submit.skipped.map((s) => (
                      <li key={s.workerId}>
                        <span className="mono">{s.workerId}</span>: {s.reason}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </div>
          )}
        </div>

        <footer className="modal__footer">
          <button
            type="button"
            className="btn"
            onClick={onClose}
            disabled={submitting}
          >Cancel</button>
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => { void handleConfirm(); }}
            disabled={submitting || targetCount === 0}
            aria-busy={submitting}
          >
            {buttonLabel}
          </button>
        </footer>
      </div>
    </div>
  );
}
