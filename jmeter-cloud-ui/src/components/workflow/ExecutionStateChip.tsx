import type { ExecutionState, TaskState } from "../../api/workflows";

/**
 * The one place a workflow state becomes a colour and a word, so an execution's
 * chip, a task row and a canvas node can never disagree about what "failed"
 * looks like.
 *
 * <p>SKIPPED reads "Not executed": the state means the branch above it failed
 * or was skipped, and "Skipped" sounded like someone chose to skip it.
 */
const TONE: Record<string, string> = {
  RUNNING: "chip--info",
  AWAITING_APPROVAL: "chip--warn",
  PENDING: "chip--muted",
  SUCCEEDED: "chip--ok",
  FAILED: "chip--err",
  CANCELLED: "chip--muted",
  SKIPPED: "chip--muted",
};

const LABEL: Record<string, string> = {
  RUNNING: "Running",
  AWAITING_APPROVAL: "Waiting for approval",
  PENDING: "Pending",
  SUCCEEDED: "Succeeded",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
  SKIPPED: "Not executed",
};

export function toneFor(state: ExecutionState | TaskState): string {
  return TONE[state] ?? "chip--muted";
}

export function labelFor(state: ExecutionState | TaskState): string {
  return LABEL[state] ?? state;
}

export function ExecutionStateChip({ state }: { state: ExecutionState | TaskState }) {
  return <span className={`chip ${toneFor(state)}`}>{labelFor(state)}</span>;
}
