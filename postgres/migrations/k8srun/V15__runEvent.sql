-- V15__runEvent.sql — applied to the jmetercloud_globalrun database.
--
-- AUDIT-TRAIL Phase A (2026-05-26). One append-only row per state-changing
-- operator action against a run. Answers the operator's incident-response
-- question "who started / stopped / scaled this run, and when?" — today only
-- run.initiatedBy (who launched) and a running, self-overwriting
-- run.stateReason survive, so intermediate mutations are lost.
--
-- Columns:
--   eventId      — ULID PK. Generated per operator action; a retried request
--                  reuses the same id so the INSERT … ON CONFLICT DO NOTHING
--                  in RunEventRepository silently drops the duplicate
--                  (decision #10, idempotent on a synthetic key).
--   runId        — FK → run.runId ON DELETE CASCADE. When a run is purged
--                  (test-data reset / GDPR erasure) its events go with it,
--                  same lifecycle as runFleetMember.
--   eventType    — RUN_START / SCALE_UP / SCALE_DOWN / DRAIN_WORKER / ABORT /
--                  STOP. Stored as the enum .name(); see domain/RunEventType.
--   actor        — who triggered the action. Read from the X-Actor request
--                  header (default 'anonymous'); the cloud auth filter will
--                  later override from the verified OIDC subject / IAM role.
--   actorSource  — how the server learned the actor: 'anonymous' (header
--                  absent), 'headerActor' (self-attested), 'oidcSubject' /
--                  'iamRole' (verified, cloud profile). Lets future auditors
--                  weigh trust.
--   payload      — JSONB per-event-type contract (no PII; decision #6). Shapes
--                  mirrored in TS types.
--   result       — 'ok' (full success) / 'partial' (bestEffort partial grant)
--                  / 'rejected:CODE' (action refused; the code is the same
--                  ErrorResponse.code the caller saw).
--   occurredAt   — wall-clock at the action. now() default is a safety net;
--                  the service binds it explicitly so tests are deterministic.

CREATE TABLE "globalOrchestrator"."runEvent" (
    "eventId"      TEXT         NOT NULL PRIMARY KEY,
    "runId"        TEXT         NOT NULL,
    "eventType"    TEXT         NOT NULL,
    "actor"        TEXT         NOT NULL DEFAULT 'anonymous',
    "actorSource"  TEXT         NOT NULL DEFAULT 'anonymous',
    "payload"      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    "result"       TEXT         NOT NULL,
    "occurredAt"   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ON DELETE CASCADE so purging a run drops its audit trail too. Same
    -- shape as runFleetMember's FK — the global-orchestrator never deletes
    -- runs at the data-model level; this is for explicit cleanup paths.
    FOREIGN KEY ("runId") REFERENCES "globalOrchestrator"."run" ("runId") ON DELETE CASCADE
);

COMMENT ON TABLE "globalOrchestrator"."runEvent" IS
    'AUDIT-TRAIL: append-only per-action audit log for run mutations. One row per operator-initiated state change (start / scaleUp / scaleDown / drain / abort / stop). Forensic-first; complementary to the OBSERVABILITY traceId on the same request.';

-- Per-run timeline — the hot read path (GET /runs/{runId}/events, reverse-chrono).
CREATE INDEX "runEvent_runId_occurredAt_idx"
    ON "globalOrchestrator"."runEvent" ("runId", "occurredAt" DESC);

-- Cross-run "show me every SCALE_DOWN in the last hour" forensic sweep.
CREATE INDEX "runEvent_eventType_occurredAt_idx"
    ON "globalOrchestrator"."runEvent" ("eventType", "occurredAt" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- globalOrchestratorWriter — used by jmeter-global-orchestrator.
-- SELECT + INSERT only. UPDATE / DELETE are deliberately withheld: this is
-- a forensic audit table and the application role must not be able to
-- tamper with or erase events (decision #5, append-only integrity). The
-- ON DELETE CASCADE run-purge still works — PostgreSQL executes referential
-- actions via internal RI triggers that bypass the invoker's privilege check.
GRANT SELECT, INSERT
    ON "globalOrchestrator"."runEvent"
    TO "globalOrchestratorWriter";
