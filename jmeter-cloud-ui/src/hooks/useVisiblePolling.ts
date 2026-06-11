import { useEffect, useRef, useState, type RefObject } from "react";

/**
 * Drop-in replacement for {@link import("./useInterval").useInterval} that
 * pauses the polling timer when any of four gates closes:
 *
 * 1. `delayMs === null`         — caller-driven hard stop (terminal run state).
 * 2. `opts.paused === true`     — operator's explicit pause toggle.
 * 3. `document.hidden`          — browser tab is not in the foreground.
 * 4. `targetRef` !intersecting  — wrapped element scrolled out of viewport
 *                                 (only when `targetRef` is provided).
 *
 * Designed for the run-detail Console / Logs tabs: at fleet scale (50-100
 * pods) we only want to poll the actively-viewed stream and stop fanning
 * requests out the moment the operator's attention moves elsewhere.
 *
 * Returns `{isPaused, pauseReason}` so the UI can render a small badge
 * explaining *why* the timer is stopped — invaluable when the operator
 * wonders "did the panel break, or did I just minimize the window?"
 *
 * Precedence of `pauseReason` (most user-meaningful first):
 *   manual → delayNull → documentHidden → offscreen → null (running).
 *
 * The hook never fires the callback synchronously on mount — initial
 * fetches stay the caller's responsibility (typically a sibling
 * `useEffect`). The interval is purely the *refresh* cadence.
 */
export type PauseReason =
  | "manual"
  | "delayNull"
  | "documentHidden"
  | "offscreen"
  | null;

export interface UseVisiblePollingOptions {
  /** Element ref to watch with IntersectionObserver. Omitted = no offscreen gate. */
  targetRef?: RefObject<HTMLElement | null>;
  /** Operator-level pause toggle. */
  paused?: boolean;
  /** Optional tag for debugging — currently unused at runtime. */
  name?: string;
}

export interface UseVisiblePollingResult {
  isPaused: boolean;
  pauseReason: PauseReason;
}

export function useVisiblePolling(
  callback: () => void,
  delayMs: number | null,
  opts: UseVisiblePollingOptions = {},
): UseVisiblePollingResult {
  const { targetRef, paused = false } = opts;

  // Latest-callback ref so a stale closure can't fire after the consumer
  // re-renders with new state.
  const savedCallback = useRef(callback);
  useEffect(() => { savedCallback.current = callback; }, [callback]);

  // ── Gate: document.visibilityState ────────────────────────────────
  // Initial value: in the browser, read it once; in SSR (no document),
  // assume visible so the timer starts on hydration.
  const [documentHidden, setDocumentHidden] = useState<boolean>(() =>
    typeof document !== "undefined" && document.visibilityState === "hidden",
  );
  useEffect(() => {
    if (typeof document === "undefined") return;
    const onChange = () => setDocumentHidden(document.visibilityState === "hidden");
    document.addEventListener("visibilitychange", onChange);
    return () => document.removeEventListener("visibilitychange", onChange);
  }, []);

  // ── Gate: IntersectionObserver on targetRef ──────────────────────
  // Initial value: assume on-screen until proven otherwise — the first
  // IO callback fires synchronously after observe() so the truth lands
  // before the first interval tick.
  const [offscreen, setOffscreen] = useState<boolean>(false);
  useEffect(() => {
    if (!targetRef) {
      // No ref → no offscreen gate; make sure stale state from a
      // previous targetRef doesn't linger.
      setOffscreen(false);
      return;
    }
    if (typeof IntersectionObserver === "undefined") return;
    const el = targetRef.current;
    if (!el) return;

    const io = new IntersectionObserver(
      (entries) => {
        // Only one entry — the element we observed.
        const entry = entries[0];
        if (entry) setOffscreen(!entry.isIntersecting);
      },
      { threshold: 0, rootMargin: "0px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [targetRef]);

  // ── Combined pause decision ──────────────────────────────────────
  // Precedence order matters for the badge label — the user sees only
  // the *primary* reason, not a list.
  let pauseReason: PauseReason = null;
  if (paused)                         pauseReason = "manual";
  else if (delayMs === null)          pauseReason = "delayNull";
  else if (documentHidden)            pauseReason = "documentHidden";
  else if (targetRef && offscreen)    pauseReason = "offscreen";

  const isPaused = pauseReason !== null;

  // ── Single setInterval lifecycle ────────────────────────────────
  // Re-fires when any gating dependency flips. When all gates are open,
  // the next callback fires after one full delayMs window (no immediate
  // tick) — the consumer's own useEffect handles the initial load.
  useEffect(() => {
    if (isPaused || delayMs === null) return;
    const id = window.setInterval(() => {
      savedCallback.current();
    }, delayMs);
    return () => window.clearInterval(id);
  }, [isPaused, delayMs]);

  return { isPaused, pauseReason };
}
