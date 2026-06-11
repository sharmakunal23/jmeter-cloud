-- V26__aiResponse.sql — applied to jmetercloud_globalrun.
--
-- AI-1 / AI-2 — durable cache for Claude-generated run
-- insights and two-run comparison narratives.
--
-- WHY a Postgres table, not the Redis cache layer:
-- a cache MISS here costs a real Claude API bill, not just a re-query. For an
-- immutable TERMINAL run the inputs (timeseries, rollup, state) never change,
-- so the response is immutable too — it must survive a Redis flush / app
-- restart so refreshing the page never re-bills. Redis (ephemeral, 1 h TTL) is
-- right for the cheap-to-recompute metrics surfaces; this is not.
--
-- WHY globalrun, not metrics: the metrics datasource is READ-ONLY
-- (metricsReader). The orchestrator composes + writes these rows, so the
-- writer must be globalOrchestratorWriter and the table lives here — same
-- reasoning as runTrend (V24).
--
-- Caching is gated TERMINAL-ONLY in the service (active runs are never
-- persisted — their inputs are still moving). The 30-day TTL is enforced on
-- read (rows older than the cutoff are treated as a miss → re-bill → upsert);
-- a daily @Scheduled prune reclaims the space. Bumping a prompt template bumps
-- "promptVersion", which is part of the key, so old responses age out rather
-- than being served against a newer prompt.

CREATE TABLE "globalOrchestrator"."aiResponse" (
    "kind"          TEXT        NOT NULL,   -- 'runInsights' | 'compareInsights'
    "cacheKey"      TEXT        NOT NULL,   -- runId, or sorted "idA|idB" for a compare
    "promptVersion" TEXT        NOT NULL,   -- e.g. 'v1' — bump to invalidate
    "response"      JSONB       NOT NULL,   -- { summary, findings[] } (kind-specific shape)
    "model"         TEXT        NOT NULL,   -- model id that produced it
    "tokensIn"      INTEGER     NOT NULL,
    "tokensOut"     INTEGER     NOT NULL,
    "createdAt"     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY ("kind", "cacheKey", "promptVersion")
);

COMMENT ON TABLE "globalOrchestrator"."aiResponse" IS
    'AI-1/AI-2: durable cache of Claude-generated run insights + comparison narratives, keyed (kind, cacheKey, promptVersion). Terminal-run-only; 30-day TTL enforced on read + daily prune. A miss costs a Claude bill, so this must survive a Redis flush — hence Postgres, not the ephemeral Redis cache layer.';

-- Prune sweep does a range scan on createdAt; keep it cheap.
CREATE INDEX "aiResponse_createdAt_idx"
    ON "globalOrchestrator"."aiResponse" ("createdAt");

-- ── GRANTs ──────────────────────────────────────────────────────────
-- global-orch reads (cache hit), inserts/updates (upsert on miss), and deletes
-- (daily TTL prune) → full DML. No other role touches this table.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON "globalOrchestrator"."aiResponse"
    TO "globalOrchestratorWriter";
