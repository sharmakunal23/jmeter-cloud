import { useEffect, useId, useRef, type ReactNode, type RefObject } from "react";

import { InfoTip } from "./InfoTip";

/**
 * The one modal shell: overlay, header (title + optional ⓘ InfoTip), Esc /
 * overlay / × dismissal, focus trap + restore, and consistent aria wiring
 * (`aria-labelledby` on the title, `aria-describedby` on the InfoTip text).
 *
 * Two body modes: pass `footer` and children render inside `.modal__body`
 * with the footer below; omit `footer` and children render raw so a form
 * dialog can own `<form className="modal__body">…<Modal.Footer>…</Modal.Footer></form>`
 * and keep Enter-submit semantics.
 */
export type ModalWidth = "confirm" | "form" | "regions" | "chart";

export interface ModalProps {
  title: ReactNode;
  /** ≤1-sentence description shown behind the ⓘ icon beside the title. */
  infoTip?: ReactNode;
  /** Optional example line inside the InfoTip (rendered monospace). */
  infoTipExample?: ReactNode;
  /** Width tier — confirm 42rem (default) or form 56rem; regions/chart keep their special sizes. */
  width?: ModalWidth;
  onClose: () => void;
  /** Blocks Esc / overlay / × while an irreversible call is in flight. */
  closeDisabled?: boolean;
  /** Extra class on the shell for per-dialog styling hooks (e.g. "emailPreview"). */
  className?: string;
  /** When set, children render inside `.modal__body` and this below it. */
  footer?: ReactNode;
  initialFocusRef?: RefObject<HTMLElement>;
  children: ReactNode;
}

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function ModalImpl({
  title, infoTip, infoTipExample, width = "confirm", onClose, closeDisabled = false,
  className, footer, initialFocusRef, children,
}: ModalProps) {
  const shellRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const describeId = useId();

  const closeRef = useRef(onClose);
  useEffect(() => { closeRef.current = onClose; });
  const closeDisabledRef = useRef(closeDisabled);
  useEffect(() => { closeDisabledRef.current = closeDisabled; });

  // Esc closes; Tab loops inside the shell.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !closeDisabledRef.current) {
        closeRef.current();
        return;
      }
      if (e.key === "Tab" && shellRef.current) {
        const nodes = shellRef.current.querySelectorAll<HTMLElement>(FOCUSABLE);
        if (nodes.length === 0) return;
        const first = nodes[0];
        const last = nodes[nodes.length - 1];
        const active = document.activeElement;
        if (e.shiftKey && (active === first || !shellRef.current.contains(active))) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && active === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // What had focus before the modal opened — read at first render, i.e. before
  // React commits and runs any child's autoFocus.
  const previousFocus = useRef(document.activeElement as HTMLElement | null);

  // Focus on mount (initialFocusRef → whatever React autoFocus already focused
  // inside the shell → the shell itself), restore on unmount. React renders no
  // [autofocus] attribute — it calls .focus() at commit — so "already inside"
  // is the only reliable autoFocus signal.
  useEffect(() => {
    const target =
      initialFocusRef?.current
      ?? (shellRef.current?.contains(document.activeElement) ? null : shellRef.current);
    target?.focus();
    const previous = previousFocus.current;
    return () => { previous?.focus?.(); };
    // Mount-only on purpose — refocusing on re-render would fight the user.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      className="modal__overlay"
      role="presentation"
      onClick={() => { if (!closeDisabled) onClose(); }}
    >
      <div
        ref={shellRef}
        tabIndex={-1}
        className={`modal modal--${width}${className ? ` ${className}` : ""}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={infoTip ? describeId : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div className="modal__titleRow">
            <h3 id={titleId}>{title}</h3>
            {infoTip && (
              <InfoTip id={describeId} label={`About ${typeof title === "string" ? title : "this dialog"}`} example={infoTipExample}>
                {infoTip}
              </InfoTip>
            )}
          </div>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={onClose}
            disabled={closeDisabled}
            aria-label="Close"
          >×</button>
        </header>
        {footer !== undefined ? (
          <>
            <div className="modal__body">{children}</div>
            <footer className="modal__footer">{footer}</footer>
          </>
        ) : (
          children
        )}
      </div>
    </div>
  );
}

function ModalFooter({ children }: { children: ReactNode }) {
  return <footer className="modal__footer">{children}</footer>;
}

export const Modal = Object.assign(ModalImpl, { Footer: ModalFooter });
