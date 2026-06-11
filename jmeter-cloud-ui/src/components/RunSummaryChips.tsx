import type { FleetAllocationEntry } from "../api/runs";

/**
 * UI-5 — at-a-glance summary above the `/runs/new` form. Mirrors the
 * pgMustard reference screenshot's `Plan Cost · Nodes · Max Depth`
 * pattern but with this app's dimensions: total pods, regions, plan
 * name, status. Reads directly from the form's existing state — no
 * shadow store, so the chips stay in lockstep with the stepper widget,
 * the visualization panel, and the overview pane on every keystroke.
 *
 * <p>The Status chip is the most user-meaningful — it's where validation
 * and submit feedback land. Carries `aria-live="polite"` so screen
 * readers hear "Ready" / "Insufficient capacity" / "Submitting…" as the
 * operator fills the form.
 */
export type SubmitChipState =
  | { status: "idle" }
  | { status: "submitting" }
  | { status: "error"; code: string };

export interface RunSummaryChipsProps {
  application: string;
  /** Selected plan's `name` (or `null` when none chosen). */
  planName: string | null;
  /** Whether a plan blob has been selected — separate from the name lookup. */
  planSelected: boolean;
  allocation: FleetAllocationEntry[];
  submit: SubmitChipState;
}

const PLAN_NAME_MAX_CHARS = 24;

export function RunSummaryChips({
  application, planName, planSelected, allocation, submit,
}: RunSummaryChipsProps) {
  const totalPods = allocation.reduce((acc, e) => acc + e.count, 0);
  const regionCount = allocation.filter((e) => e.count > 0).length;
  const status = deriveStatus({
    application, planSelected, totalPods, submit,
  });

  return (
    <div className="runSummaryChips" aria-label="Run summary">
      <Chip label="Total workers" value={String(totalPods)} mono />
      <Chip label="Regions"    value={String(regionCount)} mono />
      <Chip
        label="Plan"
        value={planName ? truncate(planName, PLAN_NAME_MAX_CHARS) : "—"}
        title={planName ?? undefined}
      />
      <Chip
        label="Status"
        value={status.label}
        variant={status.variant}
        live="polite"
      />
    </div>
  );
}

interface ChipProps {
  label: string;
  value: string;
  /** Render the value in a monospace font so numeric chips align column-wise. */
  mono?: boolean;
  /** Optional native title (full text shown on hover when value is truncated). */
  title?: string;
  /** Status-style colour variant (default = neutral pill). */
  variant?: "ready" | "needs" | "error" | "submitting";
  /** Set on the value span so SR announces changes (used by Status only). */
  live?: "off" | "polite" | "assertive";
}

function Chip({ label, value, mono, title, variant, live }: ChipProps) {
  const className = variant
    ? `runSummaryChip runSummaryChip--${variant}`
    : "runSummaryChip";
  return (
    <span
      className={className}
      role={live ? "status" : undefined}
      title={title}
    >
      <span className="runSummaryChip__label">{label}</span>
      <strong
        className={mono ? "runSummaryChip__value mono" : "runSummaryChip__value"}
        aria-live={live}
      >
        {value}
      </strong>
    </span>
  );
}

export interface DerivedStatus {
  label: string;
  variant: "ready" | "needs" | "error" | "submitting";
}

export function deriveStatus({
  application, planSelected, totalPods, submit,
}: {
  application: string;
  planSelected: boolean;
  totalPods: number;
  submit: SubmitChipState;
}): DerivedStatus {
  // Submit feedback wins over local validation — the operator just hit
  // Start, the result is the relevant signal.
  if (submit.status === "submitting") {
    return { label: "Submitting…", variant: "submitting" };
  }
  if (submit.status === "error") {
    if (submit.code === "INSUFFICIENT_CAPACITY") {
      return { label: "Insufficient capacity", variant: "error" };
    }
    return { label: submit.code || "Error", variant: "error" };
  }
  // Local validation — ordered from earliest gate to last so the chip
  // tells the operator what to do next.
  if (!application)            return { label: "Needs application", variant: "needs" };
  if (!planSelected)           return { label: "Needs plan",        variant: "needs" };
  if (totalPods < 1)           return { label: "Needs fleet",       variant: "needs" };
  return { label: "Ready", variant: "ready" };
}

function truncate(s: string, n: number): string {
  return s.length <= n ? s : s.slice(0, n - 1) + "…";
}
