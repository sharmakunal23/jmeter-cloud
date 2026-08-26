-- V22__cronJobKindAndAlwaysOn.sql — applied to jmetercloud_globalrun.
--
-- AUTOMATION Phase C (2026-05-27). Extends the CRON-schedule surface from "fire
-- a saved template" only (Phase A+B's LAUNCH_RUN) to the operator's goal #4:
-- bringing the worker fleet up in the morning and draining it overnight for
-- cost saving.
--
-- cronJob.kind discriminator:
--   LAUNCH_RUN       — Phase A+B behaviour: fire a saved template via
--                      RunService.startRun. Requires templateBlobId; region
--                      is null (the run picks regions from the template's
--                      fleetAllocation).
--   DRAIN_REGION     — drain every IDLE pod in (application, region) without
--                      replacement, via PodRecycler.recycle(..., DRAIN_AFTER_RUN).
--                      Skips IN_USE pods (the existing recycler safeguard) +
--                      no-ops when the application is alwaysOn. Requires
--                      region; templateBlobId is null.
--   PROVISION_REGION — spin pods to bring (application, region) back to
--                      applicationCapacity.maxAvailable via PodSpinService.spin.
--                      Requires region; templateBlobId is null.
--
-- Operators schedule a (DRAIN_REGION at 19:00, PROVISION_REGION at 06:00) pair
-- per (app, region) and skip the DRAIN_REGION on production-like apps by
-- flipping application.alwaysOn.
--
-- application.alwaysOn:
--   false (default) — DRAIN_REGION jobs fire normally for this app.
--   true            — DRAIN_REGION jobs SKIP for this app (the schedule still
--                     records a fire-history row with the reason). PROVISION
--                     and LAUNCH paths are unaffected — they don't pose a
--                     cost-saving risk to a production app.

-- ── cronJob kind / region + nullable templateBlobId ──────────────────

ALTER TABLE "globalOrchestrator"."cronJob"
    ADD COLUMN "kind"   TEXT NOT NULL DEFAULT 'LAUNCH_RUN',
    ADD COLUMN "region" TEXT;

-- LAUNCH_RUN keeps templateBlobId required; DRAIN/PROVISION don't use one.
-- Drop the NOT NULL, then add a per-kind CHECK so existing LAUNCH_RUN rows are
-- still self-consistent (every row in the table at migration time was a
-- LAUNCH_RUN with a non-null templateBlobId — the default-kind backfill above
-- preserves that invariant).
ALTER TABLE "globalOrchestrator"."cronJob"
    ALTER COLUMN "templateBlobId" DROP NOT NULL;

ALTER TABLE "globalOrchestrator"."cronJob"
    ADD CONSTRAINT "cronJob_kindFields_chk" CHECK (
        ("kind" = 'LAUNCH_RUN'
            AND "templateBlobId" IS NOT NULL)
        OR ("kind" IN ('DRAIN_REGION', 'PROVISION_REGION')
            AND "region"         IS NOT NULL)
    );

-- Enum-style guard. The controller validates too; the DB CHECK is a safety net
-- so a bad row can't sneak in via direct SQL or a future controller bug.
ALTER TABLE "globalOrchestrator"."cronJob"
    ADD CONSTRAINT "cronJob_kind_chk"
        CHECK ("kind" IN ('LAUNCH_RUN', 'DRAIN_REGION', 'PROVISION_REGION'));

COMMENT ON COLUMN "globalOrchestrator"."cronJob"."kind" IS
    'AUTOMATION Phase C: dispatch — LAUNCH_RUN | DRAIN_REGION | PROVISION_REGION.';
COMMENT ON COLUMN "globalOrchestrator"."cronJob"."region" IS
    'AUTOMATION Phase C: target region for DRAIN_REGION / PROVISION_REGION. Null for LAUNCH_RUN (template fleetAllocation drives regions).';

-- ── application.alwaysOn ────────────────────────────────────────────

ALTER TABLE "globalOrchestrator"."application"
    ADD COLUMN "alwaysOn" BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN "globalOrchestrator"."application"."alwaysOn" IS
    'AUTOMATION Phase C: when true, DRAIN_REGION scheduled jobs SKIP for this app (production-like). PROVISION_REGION + LAUNCH_RUN unaffected.';

-- GRANTs: the existing role already has SELECT/INSERT/UPDATE/DELETE on cronJob
-- and on application — no GRANT change needed for ADD COLUMN.
