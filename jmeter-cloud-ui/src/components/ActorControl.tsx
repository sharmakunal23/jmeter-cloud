import { useState } from "react";

import { getActor, setActor } from "../actor";

/**
 * Header control for the operator's self-attested identity.
 * Until cloud auth lands there is no logged-in user, so the operator sets
 * their name once; it's cached in localStorage and sent as `X-Actor` on every
 * run action (start / scale up / scale down / drain) and shows up on the run's
 * audit timeline. Unset → the control reads "Set your name" as a nudge, and
 * actions are recorded as `anonymous`.
 */
export function ActorControl() {
  const [actor, setActorState] = useState<string | null>(() => getActor());

  const change = () => {
    const next = window.prompt(
      "Your name — recorded on the run actions you take (start / scale / drain):",
      actor ?? "",
    );
    if (next === null) return; // cancelled
    const trimmed = next.trim();
    setActor(trimmed || null);
    setActorState(trimmed || null);
  };

  return (
    <button
      type="button"
      className={actor ? "actorControl" : "actorControl actorControl--unset"}
      onClick={change}
      title="Set the name recorded on run actions you take"
    >
      {actor ? <strong>{actor}</strong> : "Set your name"}
    </button>
  );
}
