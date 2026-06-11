-- V1__globalOrchestratorSchema.sql — applied to the
-- jmetercloud_globalrun database.
--
-- Owns fleet-wide run state for the global orchestrator. Two tables:
--
--   "globalOrchestrator"."run"
--       One row per global-orchestrator-initiated run. Holds the
--       run-level identity, requested test plan, lifecycle timestamps,
--       and aggregate state across the fleet.
--
--   "globalOrchestrator"."runFleetMember"
--       One row per (run, local-orchestrator-pod). Tracks each pod's
--       lifecycle within the fleet run — what local pod ID was assigned,
--       its current state, when it started/finished.
--
-- Identifiers use the project-wide camelCase convention; double-quoting
-- preserves it across Postgres's default lowercase folding.

-- ── Schema ──────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS "globalOrchestrator";
GRANT USAGE ON SCHEMA "globalOrchestrator" TO "globalOrchestratorWriter";

-- ── Run table ───────────────────────────────────────────────────────
CREATE TABLE "globalOrchestrator"."run" (
    "runId"             TEXT         NOT NULL PRIMARY KEY,
    -- The region where this run was started. Multi-region writes route
    -- to whichever region's global-orchestrator owns the request; in
    -- Phase 3 (Aurora Global) this is always written to the primary
    -- region's cluster. Aurora replicates to the secondary read-only.
    "originRegion"      TEXT         NOT NULL,
    -- Reference to the test plan blob in the document-service. This is
    -- the document-service blobId, not a file path. The local
    -- orchestrators fetch it via the document-service's REST API.
    "testPlanBlobId"    TEXT         NOT NULL,
    -- Optional reference to the data-files blob (a zip).
    "dataFilesBlobId"   TEXT,
    -- Whoever initiated the run — UI user, CI job, etc. Free-form
    -- string; auth identity model TBD per the cloud-migration doc.
    "initiatedBy"       TEXT         NOT NULL,
    -- Aggregate state across the fleet. Lifecycle:
    --   PREPARING → STARTING → RUNNING → DRAINING → COMPLETED
    --     │           │          │          │          └─ all members terminal
    --     │           │          │          └─ at least one member draining
    --     │           │          └─ at least one member running
    --     │           └─ fan-out POST /test in progress
    --     └─ initial; row written, fan-out not yet started
    -- Or: FAILED, ABORTED — terminal.
    "state"             TEXT         NOT NULL,
    -- Optional human reason (e.g. "kafka_unreachable", "user_aborted").
    -- Surface in the UI alongside terminal states.
    "stateReason"       TEXT,
    "createdAt"         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "startedAt"         TIMESTAMPTZ,
    "completedAt"       TIMESTAMPTZ
);

COMMENT ON TABLE "globalOrchestrator"."run" IS
    'One row per global-orchestrator-initiated fleet run. Owned by the global-orchestrator service; queried by the UI and the metrics-consumer for cross-reference.';

-- Most-recent-runs is a hot UI query; index on createdAt DESC.
CREATE INDEX "run_createdAt_desc_idx"
    ON "globalOrchestrator"."run" ("createdAt" DESC);

-- Active-run lookups — partial index on non-terminal states.
-- IMMUTABLE-safe (uses literal state values).
CREATE INDEX "run_active_idx"
    ON "globalOrchestrator"."run" ("createdAt" DESC)
    WHERE "state" NOT IN ('COMPLETED', 'FAILED', 'ABORTED');

-- ── runFleetMember table ────────────────────────────────────────────
CREATE TABLE "globalOrchestrator"."runFleetMember" (
    "runId"             TEXT         NOT NULL,
    -- The pod identity assigned to this run. Matches WorkerMetric.workerId
    -- in the Kafka stream so cross-table joins work.
    "workerId"          TEXT         NOT NULL,
    "region"            TEXT         NOT NULL,
    -- Member-side lifecycle. Independent of the parent run's aggregate
    -- state — the parent rolls up across all members.
    --   PENDING → REQUESTED → ACCEPTED → RUNNING → COMPLETED|FAILED|ABORTED
    "state"             TEXT         NOT NULL,
    "stateReason"       TEXT,
    -- HTTP status returned by the POST /api/v1/test fan-out call.
    "fanoutStatusCode"  INTEGER,
    -- Best-effort URL to the per-pod orchestrator's REST API at fan-out
    -- time (resolved from K8s Service Discovery). Useful for ad-hoc
    -- debugging via curl.
    "podBaseUrl"        TEXT,
    "createdAt"         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    "startedAt"         TIMESTAMPTZ,
    "completedAt"       TIMESTAMPTZ,
    PRIMARY KEY ("runId", "workerId"),
    -- ON DELETE CASCADE so dropping a run also drops its fleet members.
    -- The global-orchestrator never deletes runs at the data-model
    -- level; this is for cleanup paths (test-data resets, GDPR-style
    -- erasures).
    FOREIGN KEY ("runId") REFERENCES "globalOrchestrator"."run" ("runId") ON DELETE CASCADE
);

COMMENT ON TABLE "globalOrchestrator"."runFleetMember" IS
    'Per-pod child rows for each global run. Tracks each local-orchestrator''s lifecycle within the fleet. Joins to metrics."workerMetric" on ("runId", "workerId").';

-- Index for querying a run's members in fleet-state order.
CREATE INDEX "runFleetMember_state_idx"
    ON "globalOrchestrator"."runFleetMember" ("runId", "state");

-- Index for "what runs has this worker pod participated in?" — useful
-- when chasing a misbehaving pod across multiple recent runs.
CREATE INDEX "runFleetMember_workerId_createdAt_idx"
    ON "globalOrchestrator"."runFleetMember" ("workerId", "createdAt" DESC);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- globalOrchestratorWriter — used by jmeter-global-orchestrator.
-- Full read-write within its own schema. INSERT/UPDATE/DELETE/SELECT.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON "globalOrchestrator"."run", "globalOrchestrator"."runFleetMember"
    TO "globalOrchestratorWriter";
