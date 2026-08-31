import type { RecyclePolicy } from "../api/applicationGroups";

import { InfoTip } from "./InfoTip";

/**
 * The worker pool's lifecycle policy — a group setting (the pool is the
 * group's), edited inside the application-groups dialog. Three operator-facing
 * choices:
 *
 *  - **Reuse** (`REUSE`) — workers live indefinitely; reused across runs.
 *  - **After every run** (`EVERY_RUN`) — drain the worker after each run and
 *    spin a fresh replacement, so a warm worker is always ready.
 *  - **Drain after every run** (`DRAIN_AFTER_RUN`) — drain the worker after
 *    each run with no replacement (cost-saving; re-provision on demand).
 *
 * The legacy threshold policies (`MAX_RUNS` / `MAX_AGE` / `BOTH`) are no
 * longer offered as choices, but stay valid at the data layer — a group still
 * on one renders an accurate read-only summary; editing migrates it to one of
 * the three above. `alwaysOn` sits beside the policy: it exempts the group's
 * workers from scheduled drain-region jobs.
 */
export type { RecyclePolicy };

/** The three policies the picker offers, in display order. */
export const PICKER_POLICIES = ["REUSE", "EVERY_RUN", "DRAIN_AFTER_RUN"] as const;
export type PickerPolicy = (typeof PICKER_POLICIES)[number];

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

export function isPickerPolicy(p: string | null | undefined): p is PickerPolicy {
  return p != null && (PICKER_POLICIES as readonly string[]).includes(p);
}

/** The picker's value for a stored policy — a legacy threshold policy starts the radio at Reuse. */
export function pickerPolicyOf(p: RecyclePolicy | null | undefined): PickerPolicy {
  return isPickerPolicy(p) ? p : "REUSE";
}

export interface PodPolicyValue {
  recyclePolicy: PickerPolicy;
  alwaysOn: boolean;
}

export interface PodPolicyFieldsProps {
  /** Distinguishes the radio group + ids when several forms are on one page. */
  idPrefix: string;
  value: PodPolicyValue;
  onChange: (next: PodPolicyValue) => void;
  disabled?: boolean;
}

/** The policy radios + the always-on checkbox, fully controlled. */
export function PodPolicyFields({ idPrefix, value, onChange, disabled = false }: PodPolicyFieldsProps) {
  return (
    <div className="recyclePolicy__body">
      <fieldset className="recyclePolicy__radios">
        <legend>Worker lifecycle policy</legend>
        {PICKER_POLICIES.map((p) => (
          <label key={p} className="recyclePolicy__radioRow">
            <input
              type="radio"
              name={`${idPrefix}RecyclePolicy`}
              value={p}
              checked={value.recyclePolicy === p}
              onChange={() => onChange({ ...value, recyclePolicy: p })}
              disabled={disabled}
            />
            <span className="recyclePolicy__radioLabel">{POLICY_LABELS[p].label}</span>
            <small className="ink-soft">{POLICY_LABELS[p].help}</small>
          </label>
        ))}
      </fieldset>
      <div className="formField">
        <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
          <label htmlFor={`${idPrefix}AlwaysOn`} style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input
              id={`${idPrefix}AlwaysOn`}
              type="checkbox"
              checked={value.alwaysOn}
              onChange={(e) => onChange({ ...value, alwaysOn: e.target.checked })}
              disabled={disabled}
              style={{ width: "auto" }}
            />
            Always on (production-like)
          </label>
          <InfoTip label="About always on">
            Scheduled drain-region automation jobs skip this group's workers (never
            auto-drained for overnight cost saving) — provision + launch jobs are unaffected.
          </InfoTip>
        </div>
      </div>
    </div>
  );
}

/** One line for a group's stored policy, thresholds included for the legacy ones. */
export function policySummary(
  policy: RecyclePolicy | null | undefined, maxRuns: number | null | undefined, maxAge: number | null | undefined,
): string {
  switch (policy ?? "REUSE") {
    case "REUSE":           return "Reuse — workers reused across runs, never auto-recycled.";
    case "EVERY_RUN":       return "After every run — drain + spin a fresh replacement.";
    case "DRAIN_AFTER_RUN": return "Drain after every run — drain with no replacement.";
    // Legacy threshold policies (no longer offered; shown read-only for groups still on one).
    case "MAX_RUNS":        return `Recycle after ${maxRuns ?? "?"} runs (legacy policy).`;
    case "MAX_AGE":         return `Recycle after ${maxAge ?? "?"}h (legacy policy).`;
    case "BOTH":            return `Recycle after ${maxRuns ?? "?"} runs or ${maxAge ?? "?"}h (legacy policy).`;
  }
}
