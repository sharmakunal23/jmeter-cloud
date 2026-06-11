-- V14__applicationRecyclePolicy.sql — applied to the jmetercloud_globalrun database.
--
-- WORKER-HYGIENE Phase C (2026-05-16). Adds the per-application recycle
-- policy fields the Phase D reconciler will read to decide "when should
-- this pod be drain-and-replaced":
--
--   recyclePolicy   — one of REUSE / MAX_RUNS / MAX_AGE / BOTH / EVERY_RUN.
--                     REUSE preserves today's "long-lived pod" behavior.
--                     EVERY_RUN forces a recycle after every single run
--                     (regression-baseline use case where any state
--                     contamination would invalidate the result).
--   maxRunsPerPod   — claim count threshold; recycle after runsServed >= N.
--                     Required when policy ∈ {MAX_RUNS, BOTH}; NULL otherwise.
--   podMaxAgeHours  — wall-clock threshold anchored on pod.provisionedAt;
--                     recycle after age >= N hours.
--                     Required when policy ∈ {MAX_AGE, BOTH}; NULL otherwise.
--
-- Existing application rows backfill to REUSE / NULL / NULL via the
-- DEFAULT — zero behavior change for any app registered before Phase C.
-- The "safer default" (MAX_RUNS=20 for new apps) was considered and
-- rejected: backward-compat wins; operators opt-in via PUT.
--
-- CHECK constraint enforces the cross-field invariant so a misbuilt PUT
-- can never persist nonsense (e.g. policy=MAX_RUNS with maxRunsPerPod
-- null, which would have the reconciler hit a NPE on the threshold
-- comparison). The application-layer validator (ApplicationController)
-- catches these with friendlier 400 messages first; the CHECK is the
-- defense-in-depth gate.

ALTER TABLE "globalOrchestrator"."application"
    ADD COLUMN IF NOT EXISTS "recyclePolicy"  TEXT NOT NULL DEFAULT 'REUSE';

ALTER TABLE "globalOrchestrator"."application"
    ADD COLUMN IF NOT EXISTS "maxRunsPerPod"  INTEGER NULL;

ALTER TABLE "globalOrchestrator"."application"
    ADD COLUMN IF NOT EXISTS "podMaxAgeHours" INTEGER NULL;

-- Enum membership.
ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "application_recyclePolicy_check"
    CHECK ("recyclePolicy" IN ('REUSE', 'MAX_RUNS', 'MAX_AGE', 'BOTH', 'EVERY_RUN'));

-- Per-policy threshold required/forbidden rules. Each clause is one
-- policy's contract.
ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "application_recycleThresholds_check"
    CHECK (
        ("recyclePolicy" = 'REUSE'     AND "maxRunsPerPod" IS NULL AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'EVERY_RUN' AND "maxRunsPerPod" IS NULL AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'MAX_RUNS'  AND "maxRunsPerPod" IS NOT NULL AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'MAX_AGE'   AND "maxRunsPerPod" IS NULL AND "podMaxAgeHours" IS NOT NULL)
     OR ("recyclePolicy" = 'BOTH'      AND "maxRunsPerPod" IS NOT NULL AND "podMaxAgeHours" IS NOT NULL)
    );

-- Bounds. Permissive caps so the operator picks "reasonable for my app"
-- without bumping into a tight ceiling. 10000 runs per pod and 720
-- hours (30 days) are both well past any real-world use; values higher
-- than that almost certainly indicate "operator forgot a decimal."
ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "application_maxRunsPerPod_range_check"
    CHECK ("maxRunsPerPod" IS NULL OR ("maxRunsPerPod" >= 1 AND "maxRunsPerPod" <= 10000));

ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "application_podMaxAgeHours_range_check"
    CHECK ("podMaxAgeHours" IS NULL OR ("podMaxAgeHours" >= 1 AND "podMaxAgeHours" <= 720));

COMMENT ON COLUMN "globalOrchestrator"."application"."recyclePolicy" IS
    'WORKER-HYGIENE Phase C: pod recycle policy. REUSE (default) = no recycle; MAX_RUNS / MAX_AGE / BOTH = threshold-based; EVERY_RUN = paranoid mode (recycle after each run).';

COMMENT ON COLUMN "globalOrchestrator"."application"."maxRunsPerPod" IS
    'WORKER-HYGIENE Phase C: max runs a pod serves before being recycled. Required for MAX_RUNS and BOTH; NULL otherwise.';

COMMENT ON COLUMN "globalOrchestrator"."application"."podMaxAgeHours" IS
    'WORKER-HYGIENE Phase C: max pod age (vs pod.provisionedAt) before being recycled. Required for MAX_AGE and BOTH; NULL otherwise.';
