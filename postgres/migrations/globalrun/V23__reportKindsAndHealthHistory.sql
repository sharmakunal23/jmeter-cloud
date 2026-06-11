-- V23__reportKindsAndHealthHistory.sql — applied to jmetercloud_globalrun.
--
-- AUTOMATION Phase E (2026-05-27, goal #2: daily infra-readiness email) +
-- forward room for Phase D (goal #1: daily perf-test report). Adds two
-- platform-wide report cron kinds and the health-transition log the readiness
-- email reads.
--
-- Report cron kinds are SINGLETONS that span the whole platform (not one app),
-- so a report cronJob row has applicationName NULL + no template/region. They
-- still ride the existing DB-claim scheduler (HA-safe: each daily report fires
-- exactly once across replicas) and gain enable/disable/fireNow/history for
-- free. recipients is a comma-separated list (falls back to the
-- AUTOMATION_REPORT_RECIPIENTS env when blank).

-- ── cronJob: nullable applicationName + recipients + report kinds ────

ALTER TABLE "globalOrchestrator"."cronJob"
    ALTER COLUMN "applicationName" DROP NOT NULL,
    ADD COLUMN "recipients" TEXT;   -- comma-separated emails (report kinds)

-- Replace the V22 enum guard to admit the two report kinds.
ALTER TABLE "globalOrchestrator"."cronJob"
    DROP CONSTRAINT "cronJob_kind_chk";
ALTER TABLE "globalOrchestrator"."cronJob"
    ADD CONSTRAINT "cronJob_kind_chk" CHECK ("kind" IN (
        'LAUNCH_RUN', 'DRAIN_REGION', 'PROVISION_REGION',
        'INFRA_READINESS', 'DAILY_REPORT'));

-- Replace the V22 per-kind field guard: per-app kinds need applicationName
-- (+ template or region); report kinds need none of those.
ALTER TABLE "globalOrchestrator"."cronJob"
    DROP CONSTRAINT "cronJob_kindFields_chk";
ALTER TABLE "globalOrchestrator"."cronJob"
    ADD CONSTRAINT "cronJob_kindFields_chk" CHECK (
        ("kind" = 'LAUNCH_RUN'
            AND "applicationName" IS NOT NULL AND "templateBlobId" IS NOT NULL)
        OR ("kind" IN ('DRAIN_REGION', 'PROVISION_REGION')
            AND "applicationName" IS NOT NULL AND "region" IS NOT NULL)
        OR ("kind" IN ('INFRA_READINESS', 'DAILY_REPORT'))
    );

COMMENT ON COLUMN "globalOrchestrator"."cronJob"."recipients" IS
    'AUTOMATION Phase E: comma-separated report email recipients (report kinds). Blank → AUTOMATION_REPORT_RECIPIENTS env fallback.';

-- Platform (null-app) schedule names must be unique among themselves. The V20
-- UNIQUE(applicationName, name) treats NULL apps as distinct, so it does NOT
-- prevent two null-app rows sharing a name — this partial index does.
CREATE UNIQUE INDEX "cronJob_platformName_uq"
    ON "globalOrchestrator"."cronJob" ("name")
    WHERE "applicationName" IS NULL;

-- ── applicationHealthHistory — health-transition log ────────────────
-- One row per status CHANGE (not every 30 s poll), written by
-- ApplicationHealthPoller when an app's aggregate status differs from the
-- previous. The infra-readiness email reads the last 24 h of transitions to
-- compute "down for how long" windows. Lives in globalrun (same DB as the
-- application row the poller writes) so the tee shares the poll's connection.

CREATE TABLE "globalOrchestrator"."applicationHealthHistory" (
    "historyId"     TEXT         NOT NULL PRIMARY KEY,   -- ULID
    "applicationId" TEXT         NOT NULL,
    "status"        TEXT         NOT NULL,               -- HEALTHY/DEGRADED/UNHEALTHY/UNKNOWN
    "changedAt"     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "details"       JSONB
);

COMMENT ON TABLE "globalOrchestrator"."applicationHealthHistory" IS
    'AUTOMATION Phase E: append-only per-application health-transition log (status changes only). The daily infra-readiness email reads the last 24h to compute downtime windows.';

-- Hot read: last 24h of transitions for one app, newest first.
CREATE INDEX "applicationHealthHistory_app_changedAt_idx"
    ON "globalOrchestrator"."applicationHealthHistory" ("applicationId", "changedAt" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- cronJob already has SELECT/INSERT/UPDATE/DELETE (V20); the ADD COLUMN +
-- constraint swaps need no new grant. Health history is append-only here
-- (a future retention sweep can be granted DELETE then).
GRANT SELECT, INSERT
    ON "globalOrchestrator"."applicationHealthHistory"
    TO "globalOrchestratorWriter";
