import { describeCron, formatInZone, zoneLabel } from "../lib/cron";
import { formatFuture } from "../lib/time";

/**
 * Two small presentational cells shared by every Automation table (per-app detail
 * + platform reports). Goal: operators read a schedule in plain language, not raw
 * cron, and see the next fire as a real wall-clock time in the schedule's own zone
 * — never a UTC string or a confusing relative-only value.
 */

/** Plain-language cadence ("Every weekday at 9:00 AM") with the raw cron + zone as
 *  secondary detail. Falls back to the raw expression when it can't be named. */
export function ScheduleCell({ cron, timeZone }: { cron: string; timeZone: string }) {
  const human = describeCron(cron);
  return (
    <div className="scheduleCell">
      {human ? (
        <span className="scheduleCell__human">{human}</span>
      ) : (
        <code className="mono scheduleCell__human">{cron}</code>
      )}
      <small className="scheduleCell__meta ink-soft" title={zoneLabel(timeZone)}>
        {human && <code className="mono">{cron}</code>}
        {human ? " · " : ""}{timeZone}
      </small>
    </div>
  );
}

/**
 * Color-coded enabled/disabled pill that doubles as the toggle — replaces the
 * old ENABLED column + separate Enable/Disable button. Green dot = on (next fire
 * is scheduled); grey = off. Clicking flips it.
 */
export function EnableToggle({ enabled, busy = false, onToggle }: {
  enabled: boolean;
  busy?: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      className={`enableToggle ${enabled ? "enableToggle--on" : "enableToggle--off"}`}
      onClick={onToggle}
      disabled={busy}
      aria-pressed={enabled}
      title={enabled ? "Enabled — click to disable" : "Disabled — click to enable"}
    >
      <span className="enableToggle__dot" aria-hidden="true" />
      {enabled ? "Enabled" : "Disabled"}
    </button>
  );
}

/** Absolute next fire in the schedule's zone + a relative gloss. "—" when disabled
 *  or no upcoming fire. */
export function NextFireCell({ nextFireAt, timeZone, enabled }: {
  nextFireAt?: string | null;
  timeZone: string;
  enabled: boolean;
}) {
  if (!enabled || !nextFireAt) return <span className="ink-soft">—</span>;
  return (
    <div className="nextFireCell">
      <span>{formatInZone(new Date(nextFireAt), timeZone)}</span>
      <small className="ink-soft">{formatFuture(nextFireAt)}</small>
    </div>
  );
}
