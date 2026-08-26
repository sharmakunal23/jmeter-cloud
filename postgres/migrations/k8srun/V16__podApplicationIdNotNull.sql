-- V16__podApplicationIdNotNull.sql — applied to the jmetercloud_globalrun database.
--
-- Capacity Phase 6b. Closes the deferred tail of the per-app
-- capacity rework by tightening "pod"."applicationId" to NOT NULL.
--
-- Context: V10 added the column as nullable so the legacy static
-- orchestrator-1 / orchestrator-2 compose pods (which booted without an
-- APPLICATION_ID env var) could keep registering during the Phase 1 → Phase 6
-- migration window. Phase 6 deleted those static services + their two
-- null-app rows; every pod on the host is now a per-app container spun by the
-- PodProvisioner and registered with applicationId set. With the legacy pool
-- gone, the "registered run claims its own pod / unregistered run claims the
-- null-app pool" duality collapses into one path (RunService now always uses
-- claimIdleByRegionAndApp), and POST /registerPod rejects a missing
-- applicationId with 400. The column invariant catches up here.
--
-- This migration is self-sufficient: it DELETEs any remaining null-app rows
-- before adding the constraint, so an environment that registered legacy pods
-- between V10 and this migration converges to the same NOT NULL end-state
-- without a manual cleanup step. (The pod table is small — one row per live
-- container — so the SET NOT NULL table scan is negligible.)

-- Drop the now-orphaned legacy rows. ON DELETE RESTRICT on the FK only guards
-- application deletes; deleting a pod row is always safe (the container, if it
-- ever existed, is long gone — these are the static orchestrator-1 / -2 rows).
DELETE FROM "globalOrchestrator"."pod"
    WHERE "applicationId" IS NULL;

ALTER TABLE "globalOrchestrator"."pod"
    ALTER COLUMN "applicationId" SET NOT NULL;

COMMENT ON COLUMN "globalOrchestrator"."pod"."applicationId" IS
    'Application this pod is bound to. NOT NULL since V16 (Phase 6b) — every pod is a per-app container; the legacy null-app pool was removed in Phase 6. Claim logic (claimIdleByRegionAndApp) and POST /registerPod both require it.';
