/**
 * UX25 — Run-start progress modal. Surfaces what's happening behind the
 * scenes between the operator clicking "Start run" and the run being
 * live on its detail page.
 *
 * <p>Five stages, each driven by a distinct backend signal:
 * <ol>
 *   <li><b>Provisioning</b> — pods being spun (spinShortfall) or skipped
 *       when capacity was already there.</li>
 *   <li><b>Workers ready</b> — POST /api/v1/runs returned 201 (claim +
 *       fan-out done, members ACCEPTED).</li>
 *   <li><b>Distributing test plan</b> — members in ACCEPTED; pods are
 *       pulling testPlan + data files via document-service.</li>
 *   <li><b>Starting JMeter</b> — same window as #3 from the global's
 *       view; we collapse them with a small visual delay so each step
 *       gets its own checkmark moment.</li>
 *   <li><b>Verifying metrics</b> — at least one member is RUNNING;
 *       waiting for all members to confirm.</li>
 * </ol>
 *
 * <p>The modal is non-dismissable while stages are in flight. Failure
 * marks the current stage `failed` and unlocks a Dismiss + Retry path.
 */

import { useEffect } from "react";

import { ShortfallPrompt } from "./ShortfallPrompt";

export type StageStatus = "pending" | "active" | "done" | "skipped" | "failed";

export interface Stage {
  id: string;
  label: string;
  /** Optional sub-text shown beneath the label (e.g., "2/3 workers reachable"). */
  detail?: string;
  status: StageStatus;
}

export interface RunStartProgressProps {
  open: boolean;
  stages: Stage[];
  /** Free-form error rendered in the footer when any stage has failed. */
  errorMessage?: string | null;
  /** Shown only when at least one stage has failed. */
  onCancel?: () => void;
  /** Shown only when at least one stage has failed. */
  onRetry?: () => void;
  /**
   * UX28 — when the POST returns 503 INSUFFICIENT_CAPACITY, the modal
   * intercepts the stage list with this shortfall-recovery prompt
   * instead of the generic "Run start failed" UI. The two buttons map
   * to the same backend actions the in-page error pane used to offer:
   * `spinShortfall: true` (provision the missing pods then launch) and
   * `bestEffort: true` (proceed with whatever IDLE pods are available).
   *
   * <p>UX29 — `rows` carries the per-region breakdown so the modal can
   * render a compact table (Region · Need · Ready · Not ready) instead
   * of squashing it into one prose sentence. Falls back to
   * {@code fallbackMessage} when the server didn't structure the body.
   */
  shortfallPrompt?: {
    rows: { region: string; requested: number; claimed: number }[];
    fallbackMessage: string;
    onSpinShortfall: () => void;
    onBestEffort: () => void;
    onCancel: () => void;
    /** Override the primary button label (default: launch-flavored).
     *  The scale-up modal passes "Provision … and add". */
    spinLabel?: string;
    /** Override the secondary button label (default: launch-flavored). */
    bestEffortLabel?: string;
  } | null;
}

export function RunStartProgress({
  open, stages, errorMessage, onCancel, onRetry, shortfallPrompt,
}: RunStartProgressProps) {
  // ESC key lock — we don't want the operator to accidentally dismiss
  // mid-flight. The only escape valves are after a failure (Dismiss /
  // Retry buttons) or when the modal closes on its own post-success.
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape" && (onCancel || onRetry)) {
        e.preventDefault();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, onCancel, onRetry]);

  if (!open) return null;
  const hasFailed = stages.some((s) => s.status === "failed");

  // UX28 — when the POST returned INSUFFICIENT_CAPACITY, intercept the
  // stage list with a focused shortfall prompt. The operator picks an
  // action and the parent re-fires send(), which resets the stages.
  if (shortfallPrompt) {
    return (
      <div
        className="runStartProgress__backdrop"
        role="dialog"
        aria-modal="true"
        aria-label="Workers not ready"
      >
        <div className="runStartProgress">
          <ShortfallPrompt
            rows={shortfallPrompt.rows}
            fallbackMessage={shortfallPrompt.fallbackMessage}
            spinLabel={shortfallPrompt.spinLabel ?? "Provision missing workers and launch"}
            bestEffortLabel={shortfallPrompt.bestEffortLabel ?? "Launch with workers that are ready"}
            onSpinShortfall={shortfallPrompt.onSpinShortfall}
            onBestEffort={shortfallPrompt.onBestEffort}
            onCancel={shortfallPrompt.onCancel}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="runStartProgress__backdrop" role="dialog" aria-modal="true" aria-labelledby="runStartProgress-title">
      <div className="runStartProgress">
        <header className="runStartProgress__head">
          <h2 id="runStartProgress-title" className="runStartProgress__title">
            {hasFailed ? "Run start failed" : "Starting your run…"}
          </h2>
          <p className="ink-soft runStartProgress__subtitle">
            {hasFailed
              ? "One of the steps below didn't complete. Review and retry."
              : "This usually takes 5–30 seconds — keep this window open."}
          </p>
        </header>

        <ol className="runStartProgress__stages" aria-live="polite">
          {stages.map((stage) => (
            <li
              key={stage.id}
              className={`runStartProgress__stage runStartProgress__stage--${stage.status}`}
            >
              <span className="runStartProgress__icon" aria-hidden="true">
                <StageIcon status={stage.status} />
              </span>
              <span className="runStartProgress__label">
                <span className="runStartProgress__labelText">{stage.label}</span>
                {stage.detail && (
                  <span className="ink-soft runStartProgress__detail">{stage.detail}</span>
                )}
              </span>
              <span className="ink-soft runStartProgress__statusText">
                {statusText(stage.status)}
              </span>
            </li>
          ))}
        </ol>

        {errorMessage && (
          <div className="runStartProgress__error" role="alert">
            <strong>Error:</strong> {errorMessage}
          </div>
        )}

        {hasFailed && (onCancel || onRetry) && (
          <footer className="runStartProgress__actions">
            {onRetry && (
              <button type="button" className="btn btn--primary" onClick={onRetry}>
                Retry
              </button>
            )}
            {onCancel && (
              <button type="button" className="btn" onClick={onCancel}>
                Dismiss
              </button>
            )}
          </footer>
        )}
      </div>
    </div>
  );
}

function StageIcon({ status }: { status: StageStatus }) {
  switch (status) {
    case "done":    return <span className="runStartProgress__check">✓</span>;
    case "failed":  return <span className="runStartProgress__cross">✕</span>;
    case "skipped": return <span className="runStartProgress__skip">−</span>;
    case "active":  return <span className="runStartProgress__spinner" />;
    case "pending":
    default:        return <span className="runStartProgress__dot" />;
  }
}

function statusText(status: StageStatus): string {
  switch (status) {
    case "active":  return "in progress";
    case "done":    return "done";
    case "failed":  return "failed";
    case "skipped": return "not needed";
    case "pending":
    default:        return "";
  }
}
