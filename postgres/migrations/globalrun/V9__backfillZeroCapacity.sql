-- V9__backfillZeroCapacity.sql — applied to the jmetercloud_globalrun database.
--
-- D-Capacity v2 polish backfill: applications registered before V8 + the
-- auto-seed change land without any capacity rows. The Capacity page
-- shows them with the misleading "No capacity allocated yet" empty
-- state, and the operator can't fix it through the UI (App settings
-- doesn't expose capacity post-polish; sponsor approval is the only
-- path to a non-zero ceiling). Backfill 0-capacity rows for the same
-- starter regions that the controller now auto-seeds on POST so every
-- registered app has the same shape.
--
-- Idempotent: the (applicationId, region) PK + the ON CONFLICT clause
-- mean re-running this against a DB that already has rows is a no-op.
-- Per-app behavior:
--   - app has no rows                → inserts both us-east + us-west at 0
--   - app has us-east only           → inserts us-west at 0; us-east left alone
--   - app has both already           → no rows touched

INSERT INTO "globalOrchestrator"."applicationCapacity"
       ("applicationId", "region", "maxAvailable")
SELECT a."applicationId", r.region, 0
  FROM "globalOrchestrator"."application" a
 CROSS JOIN (VALUES ('us-east'), ('us-west')) AS r(region)
ON CONFLICT ("applicationId", "region") DO NOTHING;
