import { useEffect, useState } from "react";

import { automationReportsApi, type CronJobKind, type ReportPreview } from "../api/automation";

/**
 * Modal that shows what a report email will actually look like, so a
 * report is never sent blind. Fetches the rendered preview (subject + HTML) from
 * the backend and shows the HTML in a sandboxed `<iframe srcDoc>` so the email's
 * inline styles can't leak into (or inherit from) the app.
 *
 * `customSubject` / `customIntro` preview unsaved tailoring exactly as it will send.
 */

export interface EmailPreviewModalProps {
  kind: CronJobKind;
  customSubject?: string;
  customIntro?: string;
  onClose: () => void;
}

type State =
  | { status: "loading" }
  | { status: "ok"; preview: ReportPreview }
  | { status: "error"; message: string };

export function EmailPreviewModal({ kind, customSubject, customIntro, onClose }: EmailPreviewModalProps) {
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  useEffect(() => {
    const ctl = new AbortController();
    automationReportsApi.preview(kind, { customSubject, customIntro }, ctl.signal)
      .then((preview) => setState({ status: "ok", preview }))
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setState({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [kind, customSubject, customIntro]);

  return (
    <div className="modal__overlay" onClick={onClose}>
      <div
        className="modal modal--application emailPreview"
        role="dialog"
        aria-label="Email preview"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__header">
          <div>
            <h3>Email preview</h3>
            <small className="ink-soft">Exactly what recipients will receive on each fire.</small>
          </div>
          <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close">×</button>
        </header>

        <div className="modal__body">
          {state.status === "loading" && <p className="ink-soft">Rendering preview…</p>}
          {state.status === "error" && <p className="text--error">Couldn't render preview: {state.message}</p>}
          {state.status === "ok" && (
            <>
              <div className="emailPreview__subject">
                <span className="ink-soft">Subject</span>
                <strong>{state.preview.subject}</strong>
              </div>
              <iframe
                className="emailPreview__frame"
                title="Email body preview"
                sandbox=""
                srcDoc={state.preview.html}
              />
            </>
          )}
        </div>

        <footer className="modal__footer">
          <button type="button" className="btn btn--primary" onClick={onClose}>Close</button>
        </footer>
      </div>
    </div>
  );
}
