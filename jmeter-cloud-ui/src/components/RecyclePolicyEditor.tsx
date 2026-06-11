import { useState } from "react";

import { applicationsApi, type Application } from "../api/applications";

/**
 * Worker lifecycle policy editor — lives at the top of the per-app Capacity
 * detail page. Simplified (2026-05-26) to three operator-facing choices:
 *
 *  - **Reuse** (`REUSE`) — workers live indefinitely; reused across runs.
 *  - **After every run** (`EVERY_RUN`) — drain the worker after each run and
 *    spin a fresh replacement, so a warm worker is always ready.
 *  - **Drain after every run** (`DRAIN_AFTER_RUN`) — drain the worker after
 *    each run with no replacement (cost-saving; re-provision on demand).
 *
 * The legacy threshold policies (`MAX_RUNS` / `MAX_AGE` / `BOTH`) are no
 * longer offered as choices, but stay valid at the data layer — an app still
 * on one renders an accurate read-only summary; editing migrates it to one of
 * the three above.
 */

export type RecyclePolicy =
  | "REUSE"
  | "MAX_RUNS"
  | "MAX_AGE"
  | "BOTH"
  | "EVERY_RUN"
  | "DRAIN_AFTER_RUN";

/** The three policies the picker offers, in display order. */
const PICKER_POLICIES = ["REUSE", "EVERY_RUN", "DRAIN_AFTER_RUN"] as const;
type PickerPolicy = (typeof PICKER_POLICIES)[number];

const POLICY_LABELS: Record<PickerPolicy, { label: string; help: string }> = {
  REUSE: {
    label: "Reuse",
    help: "Workers live indefinitely and are reused across runs.",
  },
  EVERY_RUN: {
    label: "After every run",
    help: "Drain the worker after each run and spin a fresh replacement — a warm worker stays ready.",
  },
  DRAIN_AFTER_RUN: {
    label: "Drain after every run",
    help: "Drain the worker after each run with no replacement — cheapest; re-provision on demand.",
  },
};

function isPickerPolicy(p: string): p is PickerPolicy {
  return (PICKER_POLICIES as readonly string[]).includes(p);
}

export interface RecyclePolicyEditorProps {
  app: Application;
  onSaved: (updated: Application) => void;
  onError: (message: string) => void;
}

export function RecyclePolicyEditor({ app, onSaved, onError }: RecyclePolicyEditorProps) {
  const current = (app.recyclePolicy as RecyclePolicy) ?? "REUSE";
  const [editing, setEditing] = useState(false);
  // The picker only offers the three simplified policies; an app still on a
  // legacy threshold policy starts the radio at REUSE (its true value still
  // shows in the read-only summary until the operator picks a new one).
  const [policy, setPolicy] = useState<PickerPolicy>(
    isPickerPolicy(current) ? current : "REUSE",
  );
  const [saving, setSaving] = useState(false);

  async function save() {
    setSaving(true);
    try {
      const updated = await applicationsApi.update(app.applicationId, {
        name: app.name,
        sealId: app.sealId ?? null,
        description: app.description ?? null,
        healthEndpoints: app.healthEndpoints ?? [],
        recyclePolicy: policy,
        // The three simplified policies take no thresholds.
        maxRunsPerPod: null,
        podMaxAgeHours: null,
      });
      onSaved(updated);
      setEditing(false);
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  function cancel() {
    setPolicy(isPickerPolicy(current) ? current : "REUSE");
    setEditing(false);
  }

  const summary = renderSummary(current, app.maxRunsPerPod ?? null, app.podMaxAgeHours ?? null);

  return (
    <section className="recyclePolicy" aria-label="Worker lifecycle policy">
      <header className="recyclePolicy__head">
        <div>
          <h3 className="recyclePolicy__title">Worker lifecycle policy</h3>
          {!editing && <small className="ink-soft">{summary}</small>}
        </div>
        {!editing && (
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => setEditing(true)}
          >Edit policy</button>
        )}
      </header>

      {editing && (
        <div className="recyclePolicy__body">
          <fieldset className="recyclePolicy__radios">
            <legend className="visuallyHidden">Worker lifecycle policy</legend>
            {PICKER_POLICIES.map((p) => (
              <label key={p} className="recyclePolicy__radioRow">
                <input
                  type="radio"
                  name="recyclePolicy"
                  value={p}
                  checked={policy === p}
                  onChange={() => setPolicy(p)}
                />
                <span className="recyclePolicy__radioLabel">{POLICY_LABELS[p].label}</span>
                <small className="ink-soft">{POLICY_LABELS[p].help}</small>
              </label>
            ))}
          </fieldset>

          <div className="recyclePolicy__actions">
            <button
              type="button"
              className="btn btn--primary"
              onClick={save}
              disabled={saving}
            >{saving ? "Saving…" : "Save policy"}</button>
            <button
              type="button"
              className="btn"
              onClick={cancel}
              disabled={saving}
            >Cancel</button>
          </div>
        </div>
      )}
    </section>
  );
}

function renderSummary(policy: RecyclePolicy, maxRuns: number | null, maxAge: number | null): string {
  switch (policy) {
    case "REUSE":           return "Reuse — workers reused across runs, never auto-recycled.";
    case "EVERY_RUN":       return "After every run — drain + spin a fresh replacement.";
    case "DRAIN_AFTER_RUN": return "Drain after every run — drain with no replacement.";
    // Legacy threshold policies (no longer offered; shown read-only for apps still on one).
    case "MAX_RUNS":        return `Recycle after ${maxRuns ?? "?"} runs (legacy policy).`;
    case "MAX_AGE":         return `Recycle after ${maxAge ?? "?"}h (legacy policy).`;
    case "BOTH":            return `Recycle after ${maxRuns ?? "?"} runs or ${maxAge ?? "?"}h (legacy policy).`;
  }
}
