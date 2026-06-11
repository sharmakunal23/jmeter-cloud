-- V2__podRegistry.sql — applied to the jmetercloud_globalrun database.
--
-- Step 15: Pod registration + heartbeats. Replaces the static
-- LOCAL_ORCHESTRATOR_URLS env var with a registry of self-registered
-- pods, each pinging the global-orchestrator every 30 s. A background
-- sweeper marks pods LOST if no heartbeat for 90 s. Run-launch claims
-- IDLE pods via SELECT … FOR UPDATE SKIP LOCKED.

-- ── Pod table ───────────────────────────────────────────────────────
-- One row per local-orchestrator pod that has registered with this
-- global. Identified by podId (Kubernetes pod name / Docker hostname,
-- equal to WorkerMetric.workerId so the join with metrics."workerMetric"
-- works).
CREATE TABLE "globalOrchestrator"."pod" (
    "podId"          TEXT         NOT NULL PRIMARY KEY,
    "region"         TEXT         NOT NULL,
    -- Where the global can reach this pod's REST API. Filled in by
    -- the registering pod itself (it knows its own service name in
    -- K8s, or its container hostname in Docker).
    "baseUrl"        TEXT         NOT NULL,
    -- Lifecycle:
    --   IDLE — registered, recent heartbeat, free to be claimed.
    --   LOST — heartbeat older than the configured sweep window.
    -- BUSY-ness is derived from the runFleetMember table at claim
    -- time (NOT EXISTS active reservation), not stored here. That
    -- keeps heartbeats and run-coordination decoupled — a pod
    -- finishing a run doesn't need a separate "I'm IDLE again" call.
    "state"          TEXT         NOT NULL,
    "lastHeartbeat"  TIMESTAMPTZ  NOT NULL,
    "registeredAt"   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE "globalOrchestrator"."pod" IS
    'Step 15 pod registry. One row per local-orchestrator pod; updated by POST /api/v1/registerPod + /heartbeat. Background sweeper flips state to LOST when heartbeat is stale.';

-- Hot path: claim IDLE pods (sorted by freshest heartbeat).
CREATE INDEX "pod_state_lastHeartbeat_idx"
    ON "globalOrchestrator"."pod" ("state", "lastHeartbeat" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
GRANT SELECT, INSERT, UPDATE, DELETE
    ON "globalOrchestrator"."pod"
    TO "globalOrchestratorWriter";
