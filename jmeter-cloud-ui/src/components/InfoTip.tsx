import { useEffect, useId, useRef, useState, type ReactNode } from "react";

/**
 * The "ⓘ" info icon + click-toggle popover — the app's one help affordance,
 * generalized from ScheduleBuilder's cron-examples icon. Content must be at
 * most one sentence (plus an optional example); anything longer belongs in
 * the body of the surface it describes.
 *
 * The content stays in the DOM while closed — `hidden`, which keeps it out of
 * the tab order yet still valid as an `aria-describedby` target (hidden
 * referenced nodes contribute their text per the accname spec) — so a parent
 * pointing `aria-describedby` at {@link InfoTipProps.id} always resolves.
 */
export interface InfoTipProps {
  /** Accessible trigger name, e.g. "About Save as template". */
  label: string;
  /** The one-sentence description; a function form receives `close` for
   *  interactive content (e.g. pick-an-example buttons). */
  children: ReactNode | ((close: () => void) => ReactNode);
  /** Optional example line, rendered monospace under the description. */
  example?: ReactNode;
  /** Id of the content node — pass to point `aria-describedby` at it. */
  id?: string;
}

export function InfoTip({ label, children, example, id }: InfoTipProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLSpanElement>(null);
  const autoId = useId();
  const contentId = id ?? `${autoId}-infoTip`;

  // Close on outside click and on Escape. Escape is captured so it closes the
  // tip *instead of* a hosting modal (whose listener runs in the bubble phase).
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey, { capture: true });
    return () => {
      document.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey, { capture: true });
    };
  }, [open]);

  const content = typeof children === "function" ? children(() => setOpen(false)) : children;

  return (
    <span
      className="infoTip"
      ref={rootRef}
      onBlur={(e) => {
        // Keyboard path: focus moving out (Tab onward, a dialog stacking on
        // top) closes the tip — an open-but-hidden tip would otherwise swallow
        // the next Escape in its capture-phase handler.
        if (e.relatedTarget instanceof Element && rootRef.current
            && !rootRef.current.contains(e.relatedTarget)) {
          setOpen(false);
        }
      }}
    >
      <button
        type="button"
        className="infoIcon"
        aria-label={label}
        aria-expanded={open}
        aria-controls={contentId}
        onClick={() => setOpen((v) => !v)}
      >
        ⓘ
      </button>
      <span id={contentId} className="infoTip__pop" hidden={!open} role="note">
        {content}
        {example !== undefined && <span className="infoTip__example">{example}</span>}
      </span>
    </span>
  );
}
