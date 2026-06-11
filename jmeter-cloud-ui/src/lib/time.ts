/**
 * Shared relative-time formatting. Several pages had near-identical local copies of
 * `formatRelative` / `formatFuture`; this is the single source of truth for the
 * Automation + Home surfaces (other pages keep their local copies for now).
 *
 * Pairs with `lib/cron.ts` `formatInZone` — use that for an *absolute* wall-clock in a
 * given timezone, and these for a *relative* ("3m ago" / "in 4h") gloss.
 */

/**
 * "just now" / "3s ago" / "5m ago" / "2h ago" / "4d ago" for a past instant.
 * Accepts an ISO string or a `Date` (callers held both shapes before this was shared).
 */
export function formatRelative(input: string | Date): string {
  const then = typeof input === "string" ? new Date(input).getTime() : input.getTime();
  const sec = Math.round((Date.now() - then) / 1000);
  if (sec < 5) return "just now";
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return `${Math.round(hr / 24)}d ago`;
}

/** "in 30s" / "in 5m" / "in 2h" / "in 3d" for a future ISO timestamp; "imminent" if due. */
export function formatFuture(iso: string): string {
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return "imminent";
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `in ${sec}s`;
  const min = Math.round(sec / 60);
  if (min < 60) return `in ${min}m`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `in ${hr}h`;
  return `in ${Math.round(hr / 24)}d`;
}