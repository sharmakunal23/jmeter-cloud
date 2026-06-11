-- V17__recyclePolicyDrainAfterRun.sql — applied to the jmetercloud_globalrun database.
--
-- Adds the DRAIN_AFTER_RUN recycle policy (UX simplification, 2026-05-26).
-- Operators asked to collapse the policy picker to three clear choices:
--   Reuse            → REUSE          (keep the worker between runs)
--   After Every Run  → EVERY_RUN      (drain + spin a fresh replacement)
--   Drain after run  → DRAIN_AFTER_RUN (drain + NO replacement — cost-saving)
--
-- DRAIN_AFTER_RUN is the only new behavior: unlike EVERY_RUN (a 1-for-1
-- drain-and-replace that keeps a warm worker ready), it tears the worker
-- down after each run and leaves the slot empty; the operator re-provisions
-- on demand. Like REUSE / EVERY_RUN it takes no thresholds.
--
-- This migration only widens the V14 CHECK constraints — the threshold
-- policies (MAX_RUNS / MAX_AGE / BOTH) stay valid at the data layer even
-- though the simplified UI no longer offers them as new choices, so any app
-- already on a threshold policy keeps working and round-trips through edits.
-- A landed migration is never edited; this rolls the change forward.

-- Enum membership — add DRAIN_AFTER_RUN.
ALTER TABLE "globalOrchestrator"."application"
    DROP CONSTRAINT IF EXISTS "application_recyclePolicy_check";
ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "application_recyclePolicy_check"
    CHECK ("recyclePolicy" IN ('REUSE', 'MAX_RUNS', 'MAX_AGE', 'BOTH', 'EVERY_RUN', 'DRAIN_AFTER_RUN'));

-- Per-policy threshold rules — DRAIN_AFTER_RUN forbids both thresholds
-- (same contract as REUSE / EVERY_RUN).
ALTER TABLE "globalOrchestrator"."application"
    DROP CONSTRAINT IF EXISTS "application_recycleThresholds_check";
ALTER TABLE "globalOrchestrator"."application"
    ADD CONSTRAINT "application_recycleThresholds_check"
    CHECK (
        ("recyclePolicy" = 'REUSE'           AND "maxRunsPerPod" IS NULL     AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'EVERY_RUN'       AND "maxRunsPerPod" IS NULL     AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'DRAIN_AFTER_RUN' AND "maxRunsPerPod" IS NULL     AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'MAX_RUNS'        AND "maxRunsPerPod" IS NOT NULL AND "podMaxAgeHours" IS NULL)
     OR ("recyclePolicy" = 'MAX_AGE'         AND "maxRunsPerPod" IS NULL     AND "podMaxAgeHours" IS NOT NULL)
     OR ("recyclePolicy" = 'BOTH'            AND "maxRunsPerPod" IS NOT NULL AND "podMaxAgeHours" IS NOT NULL)
    );

COMMENT ON COLUMN "globalOrchestrator"."application"."recyclePolicy" IS
    'Worker lifecycle policy. REUSE (default) = no recycle; MAX_RUNS / MAX_AGE / BOTH = threshold-based (legacy; not offered in the simplified UI but still valid at the data layer); EVERY_RUN = drain + replace after each run; DRAIN_AFTER_RUN = drain + NO replacement after each run (cost-saving).';
