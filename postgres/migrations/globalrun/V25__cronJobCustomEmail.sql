-- V25__cronJobCustomEmail.sql — applied to jmetercloud_globalrun.
--
-- AUTOMATION UX overhaul (2026-05-28): let operators lightly tailor the report
-- emails (INFRA_READINESS / DAILY_REPORT) without an email-template engine —
-- an optional custom subject line and a short intro/note rendered above the
-- report body. Both are nullable; null = the composer's default subject and no
-- intro. Only the report cron kinds use them; the columns stay generic.
--
-- No new grants: cronJob already has SELECT/INSERT/UPDATE/DELETE for
-- globalOrchestratorWriter (V20).

ALTER TABLE "globalOrchestrator"."cronJob"
    ADD COLUMN "customSubject" TEXT,   -- overrides the default email subject when set
    ADD COLUMN "customIntro"   TEXT;   -- short note rendered above the report body

COMMENT ON COLUMN "globalOrchestrator"."cronJob"."customSubject" IS
    'AUTOMATION: optional custom email subject for report kinds; null → composer default.';
COMMENT ON COLUMN "globalOrchestrator"."cronJob"."customIntro" IS
    'AUTOMATION: optional intro/note rendered above the report body for report kinds.';
