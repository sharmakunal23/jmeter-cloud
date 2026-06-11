-- V7__perRegionCapacity.sql — applied to the jmetercloud_globalrun database.
--
-- D-Capacity v2: capacity moves from a one-dimensional per-app cap
-- (V6's `application.maxPods`) to a two-dimensional **per-app per-region
-- matrix**. Every (application, region) pair carries its own
-- `maxAvailable` so AWS-cost-bounded capacity can be reasoned about
-- region-by-region (us-east at 50, us-west at 20, etc).
--
-- Capacity is also no longer optional. Compute costs money; an
-- unbounded run could starve the platform. Apps must declare a budget
-- per region they expect to use; runs targeting an unconfigured region
-- get rejected.
--
-- The legacy `application.maxPods` column from V6 is dropped — it no
-- longer represents a meaningful cap once capacity is per-region.

ALTER TABLE "globalOrchestrator"."application"
    DROP CONSTRAINT IF EXISTS "chk_application_maxPods_positive";

ALTER TABLE "globalOrchestrator"."application"
    DROP COLUMN IF EXISTS "maxPods";

CREATE TABLE IF NOT EXISTS "globalOrchestrator"."applicationCapacity" (
    "applicationId"  TEXT         NOT NULL
        REFERENCES "globalOrchestrator"."application" ("applicationId") ON DELETE CASCADE,
    "region"         TEXT         NOT NULL,
    -- Per-region operator-set ceiling. Compute is paid for; this can't
    -- be NULL. Future: a request-more-capacity workflow lets the app
    -- sponsor approve raises.
    "maxAvailable"   INTEGER      NOT NULL CHECK ("maxAvailable" > 0 AND "maxAvailable" <= 1000),
    "createdAt"      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "updatedAt"      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY ("applicationId", "region")
);

COMMENT ON TABLE "globalOrchestrator"."applicationCapacity" IS
    'D-Capacity v2 — per-(application, region) operator-set max-pod budget. Enforced by RunService at run-launch.';

COMMENT ON COLUMN "globalOrchestrator"."applicationCapacity"."maxAvailable" IS
    'Total pods provisioned for this app in this region — AWS-bound, never NULL. Some may be off for cost-saving (READY TO USE is computed at read-time as min(maxAvailable - inUse, idleInRegion)).';

-- Permission grant — mirrors V1 / V2 / V5.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON "globalOrchestrator"."applicationCapacity"
    TO "globalOrchestratorWriter";
