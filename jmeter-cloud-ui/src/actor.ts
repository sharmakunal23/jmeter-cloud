/**
 * The operator's self-attested identity, cached in
 * localStorage and sent as the `X-Actor` header on every state-changing
 * request (see `runsApi` in `api/runs.ts`). The global-orchestrator records
 * it on the run's audit timeline.
 *
 * <p>No auth exists locally yet, so this is self-reported: the operator sets
 * their name once (via the header control) and it sticks. When cloud auth
 * lands the server derives the actor from the verified identity instead and
 * this header becomes advisory.
 */

const KEY = "jmeterCloud.actor";

/** The cached actor name, or null if the operator hasn't set one. */
export function getActor(): string | null {
  try {
    const v = localStorage.getItem(KEY);
    return v && v.trim() ? v.trim() : null;
  } catch {
    // Private-mode / disabled storage — degrade to anonymous.
    return null;
  }
}

/** Store (or clear, when passed null/blank) the actor name. */
export function setActor(name: string | null): void {
  try {
    if (name && name.trim()) localStorage.setItem(KEY, name.trim());
    else localStorage.removeItem(KEY);
  } catch {
    // Ignore — the request layer just falls back to anonymous.
  }
}
