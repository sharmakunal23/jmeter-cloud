-- V27__purgeAudit.sql — applied to jmetercloud_globalrun.
--
-- HARD-DELETE / purge. Two-tier delete: today's
-- DELETE "hides" a run/app (reversible, hiddenAt); the new purge PHYSICALLY
-- removes a hidden run/app and its artifacts (metrics rows, result blobs,
-- runTrend, aiResponse, and — for an app — its runs + pods). The run's own
-- runEvent audit trail is cascaded away with the run row, so the only place the
-- "who purged what, when" record can survive is OUTSIDE the deleted subtree.
-- That is this table: an append-only tombstone with NO FK to run/application
-- (deliberately — it must outlive them).
--
-- Two grants:
--   1. runTrend gains DELETE (V24 granted SELECT/INSERT only) so a run purge can
--      drop its frozen baseline row. (aiResponse already has DELETE from V26;
--      run/runFleetMember already have DELETE from V1; runEvent cascades from
--      the run row with the table owner's privileges, so no grant needed there.)
--   2. purgeAudit is SELECT + INSERT only — immutable tombstones, same posture
--      as runTrend (V24) and applicationHealthHistory (V23).

-- ── runTrend: allow the purge to drop a run's baseline row ──────────
GRANT DELETE ON "globalOrchestrator"."runTrend" TO "globalOrchestratorWriter";

-- ── purgeAudit — append-only tombstone for hard deletes ─────────────
CREATE TABLE "globalOrchestrator"."purgeAudit" (
    "purgeId"           TEXT         NOT NULL PRIMARY KEY,   -- ULID
    "targetType"        TEXT         NOT NULL,               -- 'run' | 'application'
    "targetId"          TEXT         NOT NULL,               -- runId or applicationId
    -- The run's application (run purge) or the app's archived name (app purge),
    -- kept for archaeology once the referenced row is gone.
    "applicationName"   TEXT,
    "actor"             TEXT         NOT NULL,               -- X-Actor (defaults 'anonymous')
    "reason"            TEXT,
    -- What the purge reclaimed. Nullable so a partial/failed step still records
    -- a tombstone with what it managed to remove.
    "metricRowsDeleted" BIGINT,
    "blobsDeleted"      INTEGER,
    "childRunsPurged"   INTEGER,                             -- app purge: # of runs swept
    "purgedAt"          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "details"           JSONB
);

COMMENT ON TABLE "globalOrchestrator"."purgeAudit" IS
    'HARD-DELETE tombstone: one append-only row per purge (run or application). No FK to run/application by design — it must survive the physical deletion of its target. Records who/what/when + what was reclaimed.';

-- Hot read: recent purges, newest first (a future "purge log" admin view).
CREATE INDEX "purgeAudit_purgedAt_idx"
    ON "globalOrchestrator"."purgeAudit" ("purgedAt" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- global-orch writes the tombstone and may read it back → SELECT + INSERT.
-- No UPDATE/DELETE: tombstones are immutable.
GRANT SELECT, INSERT
    ON "globalOrchestrator"."purgeAudit"
    TO "globalOrchestratorWriter";
