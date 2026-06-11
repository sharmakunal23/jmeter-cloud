import { useEffect, useRef } from "react";

/**
 * Document-level keyboard shortcut listener with the standard "don't
 * swallow keys while the operator is typing into a form field" guard.
 * Used for IDE-style toggles like `[` to collapse a side panel — the
 * shortcut fires when the page itself has focus but stays out of the
 * way when a `<input>` / `<textarea>` / `<select>` / contenteditable
 * element is the active target.
 *
 * <p>SSR-safe — guarded by `typeof document` check so the hook is a
 * no-op during server render. Listener attaches once and the latest
 * callback is read from a ref, so re-renders of the consumer don't
 * thrash the event listener.
 */
export interface UseKeyboardToggleOptions {
  /** When true, the shortcut is disabled. Useful for modal-open states. */
  disabled?: boolean;
}

export function useKeyboardToggle(
  key: string,
  onToggle: () => void,
  opts: UseKeyboardToggleOptions = {},
): void {
  const { disabled = false } = opts;

  const savedCallback = useRef(onToggle);
  useEffect(() => { savedCallback.current = onToggle; }, [onToggle]);

  useEffect(() => {
    if (disabled) return;
    if (typeof document === "undefined") return;

    function handleKey(e: KeyboardEvent) {
      if (e.key !== key) return;
      // Modifier-key combinations are reserved for browser / OS
      // shortcuts — never repurpose them for an in-page toggle.
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (isFormFieldTarget(e.target)) return;
      e.preventDefault();
      savedCallback.current();
    }

    document.addEventListener("keydown", handleKey);
    return () => document.removeEventListener("keydown", handleKey);
  }, [key, disabled]);
}

/** True when the event target is something the user is actively typing into. */
function isFormFieldTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
  // `isContentEditable` is the right runtime check in real browsers but
  // jsdom doesn't compute it from the attribute (no layout engine), so
  // also check the attribute directly. Either signal closes the gate.
  if (target.isContentEditable) return true;
  const attr = target.getAttribute("contenteditable");
  if (attr === "" || attr === "true" || attr === "plaintext-only") return true;
  return false;
}
