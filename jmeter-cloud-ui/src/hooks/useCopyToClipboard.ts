import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Copy-to-clipboard with a self-clearing confirmation, for a button whose label
 * reports what happened ("Copy" → "Copied").
 *
 * <p>The write can genuinely fail — `navigator.clipboard` is absent outside a
 * secure context and the permission can be refused — so `status` carries
 * `"error"` and the caller must show it. A silent failure is worse than none
 * here: the operator walks away believing they have the text.
 */
export type CopyStatus = "idle" | "copied" | "error";

export interface UseCopyToClipboardResult {
  status: CopyStatus;
  copy: (text: string) => void;
}

export function useCopyToClipboard(resetMs = 2000): UseCopyToClipboardResult {
  const [status, setStatus] = useState<CopyStatus>("idle");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clear = useCallback(() => {
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  // Don't set state on an unmounted panel — the reset outlives a quick close.
  useEffect(() => clear, [clear]);

  const copy = useCallback(
    (text: string) => {
      clear();
      const settle = (next: CopyStatus) => {
        setStatus(next);
        timer.current = setTimeout(() => setStatus("idle"), resetMs);
      };
      const write = navigator.clipboard?.writeText;
      if (!write) {
        settle("error");
        return;
      }
      write.call(navigator.clipboard, text).then(
        () => settle("copied"),
        () => settle("error"),
      );
    },
    [clear, resetMs],
  );

  return { status, copy };
}
