-- V24__runTrend.sql — applied to jmetercloud_globalrun.
--
-- AUTOMATION Phase F (foundational for the Phase D daily perf-test report).
-- One frozen aggregate row per COMPLETED run — a cheap baseline so the daily
-- report can compute "this app's last 24h vs its 7-day baseline" without
-- scanning the per-second metrics."workerMetric" table on every send.
--
-- WHY globalrun, not metrics: the design doc sketched metrics."runTrend", but
-- the global-orchestrator's metrics datasource is READ-ONLY (metricsReader
-- role, setReadOnly(true) in DataSourceConfig). The snapshot is written by
-- global-orch when it observes a run reach COMPLETED (refreshAndGet) — a
-- transition the metrics-consumer never sees — so the writer must be the
-- globalOrchestratorWriter role and the table must live here.
--
-- The snapshot is terminal-only and written exactly once (the refreshAndGet
-- terminal-transition fence guarantees a single emit; the INSERT is also
-- ON CONFLICT DO NOTHING for belt-and-suspenders). A run with no metric rows
-- yet (metrics-consumer lag at the exact terminal moment) is simply not
-- snapshotted rather than recorded as a misleading all-zeros baseline.

CREATE TABLE "globalOrchestrator"."runTrend" (
    "runId"           TEXT             NOT NULL PRIMARY KEY,
    "applicationName" TEXT,                      -- nullable: a run may be untagged
    "p50Ms"           DOUBLE PRECISION NOT NULL,
    "p95Ms"           DOUBLE PRECISION NOT NULL,
    "p99Ms"           DOUBLE PRECISION NOT NULL,
    "errorRate"       DOUBLE PRECISION NOT NULL, -- 0..1 (errors / samples)
    "throughputRps"   DOUBLE PRECISION NOT NULL, -- samples / wall-clock span seconds
    "completedAt"     TIMESTAMPTZ      NOT NULL DEFAULT now()
);

COMMENT ON TABLE "globalOrchestrator"."runTrend" IS
    'AUTOMATION Phase F: one frozen aggregate row per COMPLETED run (p50/p95/p99/errorRate/throughputRps), written by global-orch on the run-terminal transition. Powers the daily perf-report 7-day baseline without scanning metrics."workerMetric".';

-- Hot read for the daily report: an application's recent rows, newest first.
CREATE INDEX "runTrend_app_completedAt_idx"
    ON "globalOrchestrator"."runTrend" ("applicationName", "completedAt" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- global-orch writes the snapshot and reads the baseline → SELECT + INSERT.
-- No UPDATE/DELETE: rows are immutable once written (a future retention sweep
-- can be granted DELETE then, like applicationHealthHistory in V23).
GRANT SELECT, INSERT
    ON "globalOrchestrator"."runTrend"
    TO "globalOrchestratorWriter";
