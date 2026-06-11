/**
 * Shared "Workers not ready" prompt shown when a claim (run launch OR
 * mid-test scale-up) returns 503 INSUFFICIENT_CAPACITY. Renders the
 * per-region shortfall table + the three recovery actions:
 *
 *   - Provision (spinShortfall): spin the missing pods up to the
 *     per-(app, region) max capacity, then retry.
 *   - Proceed with what's ready (bestEffort): take whatever IDLE pods
 *     were available.
 *   - Cancel / back.
 *
 * <p>Presentational only — no backdrop/modal chrome (the caller owns
 * that), so it drops into both the launcher's {@code RunStartProgress}
 * modal and the {@code ScaleUpRunModal}. The verb on the two primary
 * buttons differs per context ("…and launch" vs "…and add"), so the
 * labels are props.
 */
export interface ShortfallPromptProps {
  /** Per-region breakdown from the 503 body. Empty → fall back to prose. */
  rows: { region: string; requested: number; claimed: number }[];
  /** Shown when the server didn't structure the shortfall body. */
  fallbackMessage: string;
  /** Primary button label, e.g. "Provision missing workers and launch". */
  spinLabel: string;
  /** Secondary button label, e.g. "Launch with workers that are ready". */
  bestEffortLabel: string;
  onSpinShortfall: () => void;
  onBestEffort: () => void;
  onCancel: () => void;
  /** Disables the action buttons while a re-submit is in flight. */
  busy?: boolean;
}

export function ShortfallPrompt({
  rows, fallbackMessage, spinLabel, bestEffortLabel,
  onSpinShortfall, onBestEffort, onCancel, busy = false,
}: ShortfallPromptProps) {
  const hasRows = rows.length > 0;
  return (
    <div className="shortfallPrompt">
      <h2 className="runStartProgress__title runStartProgress__title--warn">
        Workers not ready
      </h2>
      {hasRows ? (
        <table className="runStartProgress__shortfallTable" aria-label="Per-region shortfall">
          <thead>
            <tr>
              <th>Region</th>
              <th>Need</th>
              <th>Ready</th>
              <th>Not ready</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.region}>
                <td className="mono">{r.region}</td>
                <td className="mono">{r.requested}</td>
                <td className="mono">{r.claimed}</td>
                <td className="mono runStartProgress__shortfallGap">
                  {Math.max(0, r.requested - r.claimed)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="runStartProgress__subtitle">{fallbackMessage}</p>
      )}
      <footer className="runStartProgress__actions">
        <button
          type="button"
          className="btn btn--primary"
          onClick={onSpinShortfall}
          disabled={busy}
        >{spinLabel}</button>
        <button
          type="button"
          className="btn"
          onClick={onBestEffort}
          disabled={busy}
        >{bestEffortLabel}</button>
        <button
          type="button"
          className="btn btn--ghost"
          onClick={onCancel}
          disabled={busy}
        >Cancel</button>
      </footer>
    </div>
  );
}
