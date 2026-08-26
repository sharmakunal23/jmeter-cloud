-- V28__applicationHealthHistoryPurgeGrant.sql — applied to jmetercloud_globalrun.
--
-- HARD-DELETE / purge Phase 2 — application purge.
-- Purging a hidden application physically removes the app row and everything
-- bound to it. Most of that is already covered:
--   • applicationCapacity — FK ON DELETE CASCADE to application (V7), cascades.
--   • pod                 — globalOrchestratorWriter already has DELETE (V2);
--                           the purge deletes pod rows explicitly BEFORE the app
--                           row because pod.applicationId is ON DELETE RESTRICT.
--   • application         — globalOrchestratorWriter already has DELETE (V5).
-- The only gap is applicationHealthHistory, created SELECT/INSERT-only (V23,
-- append-only). The purge deletes the app's transition log, so grant DELETE.
GRANT DELETE
    ON "globalOrchestrator"."applicationHealthHistory"
    TO "globalOrchestratorWriter";
