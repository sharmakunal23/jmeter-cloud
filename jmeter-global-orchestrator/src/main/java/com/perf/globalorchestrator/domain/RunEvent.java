package com.perf.globalorchestrator.domain;

import java.time.Instant;
import java.util.Map;

/**
 * One append-only audit event for a state-changing operator
 * action against a run. Persisted to {@code ORCH_RUN_EVENT} by
 * {@link com.perf.globalorchestrator.repo.RunEventRepository}.
 *
 * @param eventId     ULID, generated once per operator action. Idempotency
 *                    key — a retried request reuses it so the repository's
 *                    {@code ON CONFLICT DO NOTHING} drops the duplicate.
 * @param runId       the run this event belongs to (FK → run.runId).
 * @param eventType   what happened (see {@link RunEventType}).
 * @param actor       who triggered it (X-Actor header; default "anonymous").
 * @param actorSource how the actor identity was learned: "anonymous" /
 *                    "headerActor" / "oidcSubject" / "iamRole".
 * @param payload     per-event-type contract (no PII). Serialised to JSONB.
 * @param result      "ok" / "partial" / "rejected:CODE".
 * @param occurredAt  wall-clock at the action.
 */
public record RunEvent(
        String eventId,
        String runId,
        RunEventType eventType,
        String actor,
        String actorSource,
        Map<String, Object> payload,
        String result,
        Instant occurredAt) {
}
