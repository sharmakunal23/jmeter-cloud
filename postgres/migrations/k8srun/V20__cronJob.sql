-- V20__cronJob.sql — applied to the jmetercloud_globalrun database.
--
-- AUTOMATION Phase A+B (2026-05-27). Persistent CRON schedules + an
-- append-only per-fire history. Operators define a schedule (a saved Template
-- + a cron expression + a timezone); the global-orchestrator's DB-claim
-- scheduler (sweep/CronJobScheduler) fires it on time, launching a run via the
-- same RunService.startRun path a human-clicked launcher uses.
--
-- Scheduler design ("Why the poller"): a @Scheduled
-- tick claims due rows with SELECT … FOR UPDATE SKIP LOCKED — the same idiom
-- as the pod claim — so N global-orchestrator replicas can run side by side
-- with exactly-one-fire-per-row (HA with zero extra config; the deliberate
-- alternative to a Quartz cluster). nextFireAt is materialised here so a fire
-- missed during a restart is caught on the next tick (restart-safe) and
-- advanced to the next FUTURE slot (catch-up-once, never a backlog replay).
--
-- cronJob columns (the contract is taken verbatim from the UI stub at
-- jmeter-cloud-ui/src/api/automation.ts — do not drift the names):
--   cronJobId       — ULID PK. Generated once per schedule.
--   name            — operator label. UNIQUE per application.
--   applicationName — run.application key; validated against the registry.
--   templateBlobId  — document-service blob (X-Type=template); fetched at fire
--                     time and mapped to a StartRunRequest. No FK (the blob
--                     lives in a different service).
--   cronExpression  — raw operator string. 5-field unix ("0 2 * * *") or
--                     6-field (with seconds) — parsed server-side by
--                     service/CronSchedule over Spring's CronExpression.
--   timeZone        — IANA zone id; nextFireAt is computed in this zone.
--   enabled         — disabled rows are skipped by the sweep (and never claimed
--                     — the partial index below only covers enabled=true).
--   createdBy       — X-Actor header value at create time.
--   lastFiredAt / lastFiredRunId / lastFireStatus — last attempt's result
--                     (LAUNCHED / SKIPPED / FAILED). Surfaced in the UI list.
--   nextFireAt      — materialised next trigger time (UTC). The sweep's hot
--                     predicate; indexed below.
--   claimedAt       — in-flight fence. Stamped when the sweep claims a row;
--                     cleared when the fire records its outcome. A row whose
--                     claimedAt is older than the stale window (a replica that
--                     crashed mid-fire) is re-eligible.

CREATE TABLE "globalOrchestrator"."cronJob" (
    "cronJobId"       TEXT         NOT NULL PRIMARY KEY,
    "name"            TEXT         NOT NULL,
    "applicationName" TEXT         NOT NULL,
    "templateBlobId"  TEXT         NOT NULL,
    "cronExpression"  TEXT         NOT NULL,
    "timeZone"        TEXT         NOT NULL DEFAULT 'UTC',
    "enabled"         BOOLEAN      NOT NULL DEFAULT true,
    "createdBy"       TEXT,
    "createdAt"       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "lastFiredAt"     TIMESTAMPTZ,
    "lastFiredRunId"  TEXT,
    "lastFireStatus"  TEXT,
    "nextFireAt"      TIMESTAMPTZ,
    "claimedAt"       TIMESTAMPTZ,
    UNIQUE ("applicationName", "name")
);

COMMENT ON TABLE "globalOrchestrator"."cronJob" IS
    'AUTOMATION: persistent CRON schedules. A DB-claim @Scheduled sweep (FOR UPDATE SKIP LOCKED) fires due rows by launching a run from the templateBlobId. HA-safe across replicas; nextFireAt is restart-safe and advanced catch-up-once.';

-- Hot predicate for the sweep: WHERE enabled=true AND nextFireAt <= now().
-- Partial (enabled=true) so disabled rows never enter the index — the sweep
-- can't even see them.
CREATE INDEX "cronJob_nextFireAt_idx"
    ON "globalOrchestrator"."cronJob" ("nextFireAt")
    WHERE "enabled" = true;

-- Append-only per-fire audit. FK-less to both cronJob and run on purpose:
-- a schedule may be deleted (we keep its fire history for forensics) and runs
-- may be purged (the runId here may dangle). One row per fire ATTEMPT.
--   outcome ∈ LAUNCHED (run started) / SKIPPED (capacity 409/503 or a previous
--   fire's run still active) / FAILED (template unavailable or 5xx) / DISABLED
--   (operator turned it off inside the fire window).
CREATE TABLE "globalOrchestrator"."cronJobFireHistory" (
    "fireId"      TEXT         NOT NULL PRIMARY KEY,
    "cronJobId"   TEXT         NOT NULL,
    "firedAt"     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "outcome"     TEXT         NOT NULL,
    "runId"       TEXT,
    "errorReason" TEXT
);

COMMENT ON TABLE "globalOrchestrator"."cronJobFireHistory" IS
    'AUTOMATION: append-only one-row-per-fire-attempt audit (LAUNCHED/SKIPPED/FAILED/DISABLED). FK-less — survives schedule deletion + run purge.';

-- Per-schedule timeline, newest-first (the UI detail page's history panel).
CREATE INDEX "cronJobFireHistory_cronJobId_firedAt_idx"
    ON "globalOrchestrator"."cronJobFireHistory" ("cronJobId", "firedAt" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- globalOrchestratorWriter — used by jmeter-global-orchestrator.
-- cronJob is mutable (enable/disable, edit, recordFire) and deletable.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON "globalOrchestrator"."cronJob"
    TO "globalOrchestratorWriter";

-- Fire history is append-only (forensic integrity, like runEvent): SELECT +
-- INSERT only — the application role must not tamper with or erase past fires.
GRANT SELECT, INSERT
    ON "globalOrchestrator"."cronJobFireHistory"
    TO "globalOrchestratorWriter";
