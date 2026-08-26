import { useEffect, useRef, useState, type RefObject } from "react";

/**
 * Drop-in replacement for `useInterval` that pauses the timer when any of four
 * gates closes: `delayMs === null` (caller's hard stop, e.g. a terminal run),
 * an explicit `paused` toggle, a backgrounded tab, or — when `targetRef` is
 * given — the element scrolling out of the viewport.
 *
 * It exists because at 50-100 pods the run-detail Console and Logs tabs would
 * otherwise fan a request per stream regardless of what the operator is
 * actually looking at.
 *
 * Returns `{isPaused, pauseReason}` so the UI can say *why* it stopped —
 * "did the panel break, or did I just minimize the window?" Precedence runs
 * most-user-meaningful first: manual, delayNull, documentHidden, offscreen.
 *
 * <p>The callback never fires synchronously on mount; the interval is only the
 * refresh cadence, so the initial fetch stays the caller's job.
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
