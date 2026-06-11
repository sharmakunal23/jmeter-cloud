import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

/**
 * Shared toast — a compact, auto-dismissing notification matching the Capacity
 * pages (floating bottom-right overlay, `.toast` CSS in styles.css). Use this
 * for transient action feedback ("Schedule enabled", "Could not fire") instead
 * of persistent inline banners that linger until the next interaction.
 *
 * `useToast()` owns the state + a 6s auto-dismiss (cleared on a new toast and on
 * unmount); `<ToastView>` renders it with the same markup the Capacity pages use.
 */

export interface ToastAction {
  label: string;
  href: string;
}

export interface Toast {
  variant: "ok" | "warn" | "err";
  text: string;
  detail?: string;
  /** Optional follow-up CTA rendered inside the toast (e.g. "Open run →"). */
  action?: ToastAction;
}

const AUTO_DISMISS_MS = 6000;

export function useToast(autoDismissMs = AUTO_DISMISS_MS) {
  const [toast, setToast] = useState<Toast | null>(null);
  const timer = useRef<number | null>(null);

  const clear = useCallback(() => {
    if (timer.current != null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  const dismiss = useCallback(() => {
    clear();
    setToast(null);
  }, [clear]);

  const showToast = useCallback((t: Toast) => {
    clear();
    setToast(t);
    timer.current = window.setTimeout(() => {
      setToast((cur) => (cur === t ? null : cur));
      timer.current = null;
    }, autoDismissMs);
  }, [autoDismissMs, clear]);

  // Cancel a pending dismiss if the component unmounts.
  useEffect(() => clear, [clear]);

  return { toast, showToast, dismiss };
}

export function ToastView({ toast, onDismiss }: { toast: Toast | null; onDismiss: () => void }) {
  if (!toast) return null;
  return (
    <div role="status" aria-live="polite" className={`toast toast--${toast.variant}`}>
      <div className="toast__body" onClick={onDismiss}>
        <strong>{toast.text}</strong>
        {toast.detail && <div className="toast__detail">{toast.detail}</div>}
      </div>
      {toast.action && (
        <Link to={toast.action.href} className="toast__cta" onClick={onDismiss}>
          {toast.action.label}
        </Link>
      )}
    </div>
  );
}
