-- V8__capacityAllowZero.sql — applied to the jmetercloud_globalrun database.
--
-- D-Capacity v2 polish: allow `applicationCapacity.maxAvailable = 0` so a
-- newly-registered application can land with capacity rows seeded at 0
-- (no allocation yet, can't run); the operator then uses the
-- "Request more capacity" workflow to ask the sponsor for a real
-- ceiling. Previously the column required strictly > 0, forcing the
-- create flow to either skip the capacity grid or commit a real value
-- before the sponsor's approval was on file.
--
-- The unnamed inline CHECK from V7 lands in pg_constraint as
-- `applicationCapacity_maxAvailable_check`; drop + re-add with the
-- relaxed lower bound. RunService still rejects runs that would push
-- inUse past maxAvailable so a 0-cap row is the same shape as
-- "no capacity yet" — the operator just sees the explicit 0 in the UI.

ALTER TABLE "globalOrchestrator"."applicationCapacity"
    DROP CONSTRAINT IF EXISTS "applicationCapacity_maxAvailable_check";

ALTER TABLE "globalOrchestrator"."applicationCapacity"
    ADD CONSTRAINT "applicationCapacity_maxAvailable_check"
    CHECK ("maxAvailable" >= 0 AND "maxAvailable" <= 1000);
