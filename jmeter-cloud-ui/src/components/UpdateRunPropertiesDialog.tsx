import { useEffect, useMemo, useState, type FormEvent } from "react";

import {
  GlobalOrchestratorError,
  runsApi,
  type Run,
  type UpdateRunPropertiesResult,
} from "../api/runs";
import { GlobalPropertiesEditor } from "./GlobalPropertiesEditor";
import { Modal } from "./Modal";

/**
 * UX-DYNAMICS T5 — pushes JMeter property values to one or more RUNNING
 * workers in one shot (the hub relays to each worker's BeanShell server).
 * Per-worker outcomes render in place; a partial failure keeps the dialog
 * open for retry, full success hands the count to {@code onSuccess}.
 */
export interface UpdateRunPropertiesDialogProps {
  run: Run;
  onClose: () => void;
  /** Every targeted worker acked — parent toasts and closes. */
  onSuccess: (appliedWorkers: number) => void;
}

const ACTIVE_STATES = new Set(["ACCEPTED", "RUNNING"]);

export function UpdateRunPropertiesDialog({ run, onClose, onSuccess }: UpdateRunPropertiesDialogProps) {
  const targets = useMemo(
    () => run.fleetMembers.filter((m) => ACTIVE_STATES.has(m.state)),
    [run.fleetMembers],
  );
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(targets.map((t) => t.workerId)),
  );

  // A background poll can drain/fail a member while the dialog is open —
  // prune the selection to the live target set so a stale id can never ride
  // into the request (or trick the all-selected shorthand below).
  useEffect(() => {
    setSelected((prev) => {
      const live = new Set(targets.map((t) => t.workerId));
      const next = new Set([...prev].filter((id) => live.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [targets]);
  const [properties, setProperties] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [results, setResults] = useState<UpdateRunPropertiesResult["results"] | null>(null);

  // Membership, not size — a same-size set of different workers is not "all".
  const allSelected = targets.length > 0 && targets.every((t) => selected.has(t.workerId));
  const canSend = !submitting && selected.size > 0 && Object.keys(properties).length > 0;

  function toggle(workerId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(workerId)) next.delete(workerId); else next.add(workerId);
      return next;
    });
  }

  function toggleAll() {
    setSelected(allSelected ? new Set() : new Set(targets.map((t) => t.workerId)));
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSend) return;
    setSubmitting(true);
    setError(null);
    try {
      const resp = await runsApi.updateProperties(run.runId, {
        // ALWAYS explicit: the server-side "omit = all active" shorthand
        // could sweep in a scale-up joiner the operator never saw ticked.
        workerIds: Array.from(selected),
        properties,
      });
      setResults(resp.results);
      const failed = resp.results.filter((r) => !r.ok).length;
      if (failed === 0) {
        onSuccess(resp.results.length);
      }
      // Partial/failed: stay open — the per-worker list shows what to retry.
    } catch (err) {
      if (err instanceof GlobalOrchestratorError) {
        setError(`${err.code}: ${err.message}`);
      } else {
        setError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title="Update properties"
      infoTip={<>Pushes property values to the selected running workers — only plan values read through {"${__P(name)}"} at runtime react; plain -J reads were fixed at JMeter startup.</>}
      infoTipExample={"duration=${__P(rampSeconds,60)}"}
      width="form"
      onClose={onClose}
      closeDisabled={submitting}
    >
      <form onSubmit={handleSubmit} className="modal__body createApp" noValidate>
        <fieldset className="updateProps__workers">
          <legend>
            Workers
            <label className="checkboxRow updateProps__selectAll">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={toggleAll}
                aria-label="Select all workers"
                disabled={submitting}
              />
              <span>Select all</span>
            </label>
          </legend>
          {targets.map((m) => (
            <label key={m.workerId} className="checkboxRow updateProps__workerRow">
              <input
                type="checkbox"
                checked={selected.has(m.workerId)}
                onChange={() => toggle(m.workerId)}
                disabled={submitting}
              />
              <span className="mono">{m.workerId}</span>
              <span className="ink-soft">· {m.region}</span>
            </label>
          ))}
        </fieldset>

        <GlobalPropertiesEditor
          value={properties}
          onChange={setProperties}
          title="Properties to send"
          hint="Applied live to the selected workers."
        />

        {results && (
          <ul className="updateProps__results" aria-label="Per-worker results">
            {results.map((r) => (
              <li key={r.workerId} className={r.ok ? undefined : "text--error"}>
                {r.ok ? "✓" : "✗"} <span className="mono">{r.workerId}</span>
                {!r.ok && r.error ? ` — ${r.error}` : ""}
              </li>
            ))}
          </ul>
        )}
        {error && <div className="formError" role="alert">{error}</div>}

        <Modal.Footer>
          <button type="button" className="btn" onClick={onClose} disabled={submitting}>Cancel</button>
          <button
            type="submit"
            className="btn btn--primary"
            disabled={!canSend}
            aria-busy={submitting}
          >
            {submitting ? "Sending…" : "Send properties"}
          </button>
        </Modal.Footer>
      </form>
    </Modal>
  );
}
