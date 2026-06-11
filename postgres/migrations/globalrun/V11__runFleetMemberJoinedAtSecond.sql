-- V11__runFleetMemberJoinedAtSecond.sql — applied to the jmetercloud_globalrun database.
--
-- MID-TEST-SCALING Phase A — adds joinedAtSecond to runFleetMember so
-- mid-test scale-up workers can be distinguished from original-fleet
-- members by their epoch.
--
-- Semantics:
--   - NULL  → original-fleet member (joined at run start; the existing
--             createdAt timestamp is the epoch).
--   - >= 0  → seconds since run.startedAt at which this worker joined
--             (mid-test scale-up). Stamped server-side by the scaleUp
--             endpoint inside the same transaction as the INSERT, so
--             the value is monotonic with createdAt.
--
-- Why a column on runFleetMember rather than a derived value:
--   - Preserves the join epoch even after the worker terminates, so
--     post-hoc analysis can attribute a metric to "members live during
--     window X" without joining back through createdAt + startedAt
--     (which can drift if the run was scheduled but slow to dispatch).
--   - The Phase D consumer change will propagate this into the metrics
--     row so per-second rollups can sum over the live-at-second-X set.
--
-- No index — joinedAtSecond is read with the rest of the row; never
-- queried on its own.

ALTER TABLE "globalOrchestrator"."runFleetMember"
    ADD COLUMN IF NOT EXISTS "joinedAtSecond" BIGINT;

COMMENT ON COLUMN "globalOrchestrator"."runFleetMember"."joinedAtSecond" IS
    'MID-TEST-SCALING Phase A — NULL for original-fleet members; >=0 for scale-up joiners (seconds since run.startedAt).';
