-- V19 — soft-delete ("hide") for runs.
--
-- Operators declutter their run lists without destroying data: a hidden run
-- drops out of the default listing, but its row, fleet members, audit-trail
-- events, and any saved results are RETAINED — reversible (a future restore /
-- the includeHidden listing can surface it again). Chosen over a hard DELETE so
-- the AUDIT-TRAIL invariant holds: the DELETE event recorded at hide time stays
-- valid (its runId FK is intact).
--
-- Only TERMINAL runs can be hidden — an active run is, by definition, still
-- important and pins live pods; the service enforces that (RUN_NOT_DELETABLE).
ALTER TABLE "globalOrchestrator"."run"
    ADD COLUMN "hiddenAt" TIMESTAMPTZ;

COMMENT ON COLUMN "globalOrchestrator"."run"."hiddenAt" IS
    'When the run was hidden (soft-deleted) by an operator; NULL = visible. Only terminal runs can be hidden. Reversible — the row/members/audit-trail are retained.';

-- The default runs listing filters `hiddenAt IS NULL`. A partial index keeps
-- that predicate cheap as hidden rows accumulate (mirrors run_active_idx).
CREATE INDEX "run_visible_createdAt_idx"
    ON "globalOrchestrator"."run" ("createdAt" DESC)
    WHERE "hiddenAt" IS NULL;
