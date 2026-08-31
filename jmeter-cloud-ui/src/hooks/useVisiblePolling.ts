import { useEffect, useRef, useState, type RefObject } from "react";

/** Why the timer is stopped, most user-meaningful first. */
export type PauseReason =
  | "manual"
  | "delayNull"
  | "documentHidden"
  | "offscreen"
  | null;

/**
 * A resume refetch is skipped when the last fetch is younger than this — an
 * alt-tab flurry collapses to one request, while anything actually stale
 * refreshes at once. Capped by `delayMs` so a fast poller is never throttled
 * below its own cadence.
 */
export const REFRESH_ON_RESUME_MIN_AGE_MS = 2_000;

export interface UseVisiblePollingOptions {
  /** Element ref to watch with IntersectionObserver. Omitted = no offscreen gate. */
  targetRef?: RefObject<HTMLElement | null>;
  /** Operator-level pause toggle. */
  paused?: boolean;
  /** Optional tag for debugging — currently unused at runtime. */
  name?: string;
  /** Refetch as soon as a visibility gate reopens. Default true. */
  refreshOnResume?: boolean;
  /** Ceiling on the error backoff multiplier. Default 5 (a 5 s poll degrades to 25 s). */
  maxBackoffFactor?: number;
}

export interface UseVisiblePollingResult {
  isPaused: boolean;
  pauseReason: PauseReason;
}

/**
 * The one polling primitive. A timer that runs only while the user can
 * actually see the thing it refreshes, refetches the moment they look again,
 * and backs off when the backend is failing.
 *
 * Four gates pause it: `delayMs === null` (caller's hard stop, e.g. a terminal
 * run), an explicit `paused` toggle, a backgrounded tab, or — when `targetRef`
 * is given — the element scrolling out of the viewport.
 *
 * It exists because at 50-100 pods the run-detail Console and Logs tabs would
 * otherwise fan a request per stream regardless of what the operator is
 * actually looking at.
 *
 * Returns `{isPaused, pauseReason}` so the UI can say *why* it stopped —
 * "did the panel break, or did I just minimize the window?" Precedence runs
 * most-user-meaningful first: manual, delayNull, documentHidden, offscreen.
 *
 * <p>Two behaviours a caller must know about:
 *
 * <p><b>The callback never fires on mount</b> — the interval is only the
 * refresh cadence, so the initial fetch stays the caller's job. It *does* fire
 * immediately when a visibility gate reopens (see {@link REFRESH_ON_RESUME_MIN_AGE_MS}),
 * because a paused view that resumes on the next full period shows stale data
 * for up to one whole interval.
 *
 * <p><b>Backoff is opt-in by returning a promise.</b> A callback that returns a
 * rejected promise doubles the interval, up to `maxBackoffFactor`, and the
 * first success resets it — so a struggling backend gets less traffic, not
 * more. A callback returning `void` (including `() =&gt; { void load(); }`,
 * which discards its promise) never backs off.
 */
export function useVisiblePolling(
  // `unknown`, not `void`: several callers return the AbortController they set
  // up. Only a thenable return participates in the backoff below.
  callback: () => unknown,
  delayMs: number | null,
  opts: UseVisiblePollingOptions = {},
): UseVisiblePollingResult {
  const { targetRef, paused = false, refreshOnResume = true, maxBackoffFactor = 5 } = opts;

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

  // ── Error backoff ────────────────────────────────────────────────
  // The multiplier is state, not a ref: it has to re-run the interval effect
  // below so the *next* wait is the longer one.
  const [backoffFactor, setBackoffFactor] = useState(1);
  const lastFireAt = useRef(0);

  const fire = useRef<() => void>(() => {});
  fire.current = () => {
    lastFireAt.current = Date.now();
    let result: unknown;
    try {
      result = savedCallback.current();
    } catch {
      // A callback that throws synchronously is as failed as one that rejects.
      setBackoffFactor((f) => Math.min(f * 2, maxBackoffFactor));
      return;
    }
    if (result && typeof (result as Promise<unknown>).then === "function") {
      (result as Promise<unknown>).then(
        () => setBackoffFactor(1),
        () => setBackoffFactor((f) => Math.min(f * 2, maxBackoffFactor)),
      );
    }
  };

  // ── Refetch the moment a visibility gate reopens ─────────────────
  // Only on a transition, never on mount: `wasGated` starts at the mount-time
  // value, so a view that mounts visible does not double-fetch alongside its
  // caller's own initial load. `paused` and `delayMs === null` are deliberately
  // NOT resume triggers — those are the caller saying "stop", and a terminal
  // run has nothing to refetch.
  const visibilityGated = documentHidden || (!!targetRef && offscreen);
  const wasGated = useRef(visibilityGated);
  useEffect(() => {
    const previously = wasGated.current;
    wasGated.current = visibilityGated;
    if (!refreshOnResume) return;
    if (!previously || visibilityGated) return;      // not a gated → visible transition
    if (paused || delayMs === null) return;          // the caller stopped it for another reason
    const minAge = Math.min(REFRESH_ON_RESUME_MIN_AGE_MS, delayMs);
    if (Date.now() - lastFireAt.current < minAge) return;
    fire.current();
  }, [visibilityGated, refreshOnResume, paused, delayMs]);

  // ── Single setInterval lifecycle ────────────────────────────────
  // Re-fires when any gating dependency flips. When all gates are open,
  // the next callback fires after one full delay window (no immediate
  // tick) — the consumer's own useEffect handles the initial load.
  const effectiveDelay = delayMs === null ? null : delayMs * backoffFactor;
  useEffect(() => {
    if (isPaused || effectiveDelay === null) return;
    const id = window.setInterval(() => {
      fire.current();
    }, effectiveDelay);
    return () => window.clearInterval(id);
  }, [isPaused, effectiveDelay]);

  return { isPaused, pauseReason };
}
