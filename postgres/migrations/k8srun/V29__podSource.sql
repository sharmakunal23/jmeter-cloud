-- V29__podSource.sql — applied to the jmetercloud_k8srun database.
--
-- STATIC-FLEET Phase 3: a pod row can now come from two places.
--
--   DYNAMIC — the control plane created the worker (PodSpinService →
--             PodProvisioner) and owns its lifecycle. Every row that
--             existed before this migration.
--   STATIC  — the operator deployed the worker themselves and DECLARED it
--             against an (application, region) via
--             PUT /api/v1/applications/{id}/capacity/{region}/pods/{podName}.
--             The control plane may use it but must never create, restart
--             or destroy it.
--
-- Why a column rather than inferring from the deployment-wide
-- PROVISIONING_MODE: the mode is a property of the *process*, the source is
-- a property of the *row*. They can legitimately disagree — an operator who
-- flips a previously-dynamic deployment to STATIC still has DYNAMIC rows
-- from before the flip, and those must not be probed as if they were
-- declared, nor silently adopted as operator-managed. Keeping it on the row
-- also means the liveness probe and the UI can answer "who owns this
-- worker?" without consulting config.
--
-- DEFAULT 'DYNAMIC' backfills every existing row correctly with no data
-- migration: everything registered before this migration was, by
-- definition, control-plane provisioned.

ALTER TABLE "globalOrchestrator"."pod"
    ADD COLUMN IF NOT EXISTS "source" TEXT NOT NULL DEFAULT 'DYNAMIC';

-- Named explicitly so a re-run is idempotent (V8 learned this the hard way
-- with V7's unnamed inline CHECK landing under a generated name).
ALTER TABLE "globalOrchestrator"."pod"
    DROP CONSTRAINT IF EXISTS "pod_source_check";

ALTER TABLE "globalOrchestrator"."pod"
    ADD CONSTRAINT "pod_source_check"
    CHECK ("source" IN ('DYNAMIC', 'STATIC'));

COMMENT ON COLUMN "globalOrchestrator"."pod"."source" IS
    'STATIC-FLEET — DYNAMIC: control-plane provisioned, lifecycle owned here. STATIC: operator-deployed and declared; the control plane uses it but never creates/restarts/destroys it, and StaticPodProbe (not heartbeats) keeps its liveness fresh.';

-- Deliberately NO index on "source". The pod table is small (hundreds of
-- rows at most) and hot-updated — every heartbeat and probe writes
-- lastHeartbeat. A seq scan for the probe's WHERE "source"='STATIC' every
-- 30 s costs nothing at this size, whereas an extra index on a
-- frequently-updated table is a standing write cost. Revisit only if the
-- fleet grows by an order of magnitude.

-- No new GRANTs needed: V2 already granted SELECT/INSERT/UPDATE/DELETE on
-- this table to globalOrchestratorWriter, and column additions inherit it.
