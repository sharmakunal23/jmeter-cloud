-- V21 — soft-delete ("hide") for applications.
--
-- Operators retire an application without destroying its history: a hidden app
-- drops out of every listing (applications list, launcher picker, capacity
-- matrix, health poller — all read ApplicationRepository.findAll), but its row,
-- run history, metrics, audit-trail events, and uploaded blobs are RETAINED. A
-- future purge job reclaims that data; we don't know how to best schedule it
-- yet, so soft-delete keeps everything addressable in the meantime.
--
-- The row persists, so the UNIQUE constraint on "name" (V5) keeps the name
-- RESERVED — re-registering the same name conflicts (409). This is deliberate:
-- once retired, a name can't be re-used (mirrors the V19 run-hide reasoning of
-- keeping referential history valid).
--
-- The per-app Kafka topics ARE still deleted at hide time (no consumer/producer
-- has any use for them after retirement) — that happens in ApplicationController,
-- not here. Only the registry row + Postgres data + blobs are retained.
--
-- Only apps with NO active (non-terminal) runs can be hidden — an app with live
-- runs would orphan them from navigation; the service enforces that (409
-- APPLICATION_HAS_ACTIVE_RUNS).
ALTER TABLE "globalOrchestrator"."application"
    ADD COLUMN "hiddenAt" TIMESTAMPTZ;

COMMENT ON COLUMN "globalOrchestrator"."application"."hiddenAt" IS
    'When the application was hidden (soft-deleted) by an operator; NULL = visible. The row, run history, metrics, audit events, and blobs are retained; the name stays reserved. Kafka topics are deleted at hide time.';

-- The default applications listing filters `hiddenAt IS NULL` and orders by
-- name. A partial index keeps that predicate cheap as hidden rows accumulate
-- (mirrors V19's run_visible_createdAt_idx).
CREATE INDEX "application_visible_name_idx"
    ON "globalOrchestrator"."application" ("name")
    WHERE "hiddenAt" IS NULL;
