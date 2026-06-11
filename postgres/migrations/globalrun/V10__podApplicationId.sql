-- V10__podApplicationId.sql — applied to the jmetercloud_globalrun database.
--
-- Phase 1 of the capacity rework (per-app pod ownership). Today every
-- registered pod is shared across all applications — RunService.claimIdle*
-- picks any IDLE pod regardless of which app the run targets. The capacity
-- rework binds pods to a single application so the UI's per-app capacity
-- view reflects real container ownership: containers named
-- {appName}-{region}-worker-{n} are spun up dynamically by the
-- global-orchestrator's PodProvisioner and registered with this
-- applicationId set.
--
-- Migration shape:
--   - Add applicationId TEXT NULL with FK to application(applicationId).
--   - Stays NULL during the migration window so the static
--     orchestrator-1 / orchestrator-2 compose pods (which boot without
--     APPLICATION_ID set) keep registering and don't crash existing tests.
--   - Phase 6 deletes those static rows + a follow-up migration (V11)
--     tightens applicationId to NOT NULL once the only pods left are
--     the per-app ones.
--   - ON DELETE RESTRICT — deleting an application with live pods is
--     a foot-gun; the operator must drain its pods first.
--
-- New claim path lands in Phase 4 (claimIdleByRegionAndApp). This
-- migration is column-only; no query changes here.

ALTER TABLE "globalOrchestrator"."pod"
    ADD COLUMN IF NOT EXISTS "applicationId" TEXT;

ALTER TABLE "globalOrchestrator"."pod"
    ADD CONSTRAINT "pod_applicationId_fk"
    FOREIGN KEY ("applicationId")
    REFERENCES "globalOrchestrator"."application" ("applicationId")
    ON DELETE RESTRICT;

COMMENT ON COLUMN "globalOrchestrator"."pod"."applicationId" IS
    'Application this pod is bound to. NULL only during the Phase 1 → Phase 6 migration window (legacy static pods registered without APPLICATION_ID). Phase 4 claim logic filters on this column; Phase 6 + V11 enforce NOT NULL.';

-- Supports the Phase 4 claim query: (applicationId, region, state, lastHeartbeat).
-- Same shape as the existing pod_state_lastHeartbeat_idx but app-scoped.
CREATE INDEX IF NOT EXISTS "pod_application_region_state_idx"
    ON "globalOrchestrator"."pod" ("applicationId", "region", "state", "lastHeartbeat" DESC);
