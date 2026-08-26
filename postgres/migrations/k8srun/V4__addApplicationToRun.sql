-- V4__addApplicationToRun.sql — applied to the jmetercloud_globalrun database.
--
-- UI-D3 (Track UI-D, Application-centric IA): each run now carries the
-- `application` it was launched against, so the Applications tab can
-- filter `/api/v1/runs?application=<name>` without an expensive join
-- through the testPlan blob's tags. The value is supplied by the UI in
-- the StartRunRequest body — the launcher's application gate already
-- forces the operator to pick one before submitting.
--
-- NULL is allowed for legacy rows that predate this migration (and for
-- backend-driven runs that don't go through the launcher form). Filter
-- queries use IS NOT DISTINCT FROM semantics so an explicit
-- `?application=` query against a NULL row matches.

ALTER TABLE "globalOrchestrator"."run"
    ADD COLUMN IF NOT EXISTS "application" TEXT;

COMMENT ON COLUMN "globalOrchestrator"."run"."application" IS
    'UI-D3 — application name this run was launched against (mirrors the testPlan blob''s X-Application tag). NULL for legacy rows.';

-- B-tree index for the per-application listing query — small index
-- (cardinality ~30 apps × hundreds of runs each) and supports the
-- equality predicate emitted by RunController.listRuns(application).
CREATE INDEX IF NOT EXISTS "idx_run_application_createdAt"
    ON "globalOrchestrator"."run" ("application", "createdAt" DESC);
