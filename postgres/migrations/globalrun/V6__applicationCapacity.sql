-- V6__applicationCapacity.sql — applied to the jmetercloud_globalrun database.
--
-- D-Capacity: per-application max concurrent pods. Enforced by RunService
-- at run-launch — `(currentlyAllocatedPodsForApp + requestedPods) > maxPods`
-- now rejects with 409 CAPACITY_EXCEEDED instead of silently letting one
-- chatty app starve the others.
--
-- NULL `maxPods` means "no per-app cap" — preserves backward-compat for
-- rows registered before this migration. The launcher form treats NULL
-- as "unlimited" but flags it as a config gap on the Capacity Overview
-- so operators can set a real number when they're ready.

ALTER TABLE "globalOrchestrator"."application"
    ADD COLUMN IF NOT EXISTS "maxPods" INTEGER;

COMMENT ON COLUMN "globalOrchestrator"."application"."maxPods" IS
    'D-Capacity — max concurrent pods this application can claim across all in-flight runs. NULL = unlimited (legacy default).';

-- Sanity check: maxPods must be positive when set. Lets the launcher
-- assume any non-NULL value is enforceable without bounds-checking.
ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "chk_application_maxPods_positive"
    CHECK ("maxPods" IS NULL OR "maxPods" > 0);
