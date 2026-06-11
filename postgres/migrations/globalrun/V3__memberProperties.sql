-- V3__memberProperties.sql — applied to the jmetercloud_globalrun database.
--
-- Track G (Step 31): per-node JMeter -J properties. Each runFleetMember
-- can carry a Map<String,String> of properties forwarded to the child
-- JMeter process at launch (e.g. {REGION: "us-east-1", USER_OFFSET: "0"}).
-- Stored as JSONB so the run-detail page can show "what this node was
-- launched with" after the run completes.
--
-- Data type: JSONB (not JSON) — JSONB indexes faster, deduplicates keys,
-- and the validation cost happens at INSERT time (where we have the
-- bandwidth) instead of every SELECT.

ALTER TABLE "globalOrchestrator"."runFleetMember"
    ADD COLUMN IF NOT EXISTS "properties" JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN "globalOrchestrator"."runFleetMember"."properties" IS
    'Step 31 — per-node JMeter -J properties snapshot at launch. Map<String,String> serialised as JSON object. Validated by jmeter-local-orchestrator''s StartTestRequest before INSERT (key regex + value caps).';

-- No new index. Properties are read with the rest of the row (no
-- per-property query path); a JSONB GIN index would be wasted weight.
