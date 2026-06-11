import { useEffect, useRef } from "react";

/**
 * Re-runs {@code callback} every {@code delayMs} milliseconds, with the
 * standard React-friendly pattern: capture the latest callback in a ref
 * so closures over stale state never fire, and clean up the timer when
 * the component unmounts or the delay changes.
 *
 * <p>Pass {@code delayMs = null} to pause the interval (e.g., when a
 * detail page sees a terminal run state — no point polling further).
 */
export function useInterval(callback: () => void, delayMs: number | null): void {
  const savedCallback = useRef(callback);

  useEffect(() => {
    savedCallback.current = callback;
  }, [callback]);

  useEffect(() => {
    if (delayMs === null) return;
    const id = setInterval(() => savedCallback.current(), delayMs);
    return () => clearInterval(id);
  }, [delayMs]);
}
