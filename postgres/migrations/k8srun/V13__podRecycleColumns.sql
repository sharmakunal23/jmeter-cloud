-- V13__podRecycleColumns.sql — applied to the jmetercloud_globalrun database.
--
-- WORKER-HYGIENE Phase B (2026-05-16). Adds the three columns the recycle
-- lifecycle (Phase D) will key on, without yet introducing any new pod
-- state value:
--
--   runsServed   — bumped to N+1 inside the run-claim transaction. Phase D's
--                  reconciler compares it against application.maxRunsPerPod
--                  to decide "this pod has done enough; recycle on idle."
--   imageDigest  — captured at container-create time so the reconciler can
--                  diff against `docker image inspect jmeter-local-orchestrator:dev`
--                  to detect "the image was rebuilt; pods need replacement
--                  before they can answer new endpoints." Eliminates the
--                  2026-05-15 "drain has no effect" footgun.
--   provisionedAt — wall-clock at container-create time. Distinct from
--                  registeredAt (which gets reset by re-register on local-orch
--                  restart) — Phase D's max-age check anchors on creation,
--                  not registration.
--
-- DRAINING_FOR_RECYCLE state value is NOT added in this migration. We add
-- it in Phase D when the reconciler actually uses it — keeping the enum
-- surface minimal until the corresponding code lands.
--
-- All three columns are nullable / defaulted so the migration is a no-op
-- against existing rows: pre-Phase-B pods have imageDigest=NULL +
-- provisionedAt=NULL, and the reconciler treats NULL as "unknown, skip
-- the recycle check this pass."

ALTER TABLE "globalOrchestrator"."pod"
    ADD COLUMN IF NOT EXISTS "runsServed"    BIGINT       NOT NULL DEFAULT 0;

ALTER TABLE "globalOrchestrator"."pod"
    ADD COLUMN IF NOT EXISTS "imageDigest"   TEXT         NULL;

ALTER TABLE "globalOrchestrator"."pod"
    ADD COLUMN IF NOT EXISTS "provisionedAt" TIMESTAMPTZ  NULL;

COMMENT ON COLUMN "globalOrchestrator"."pod"."runsServed" IS
    'WORKER-HYGIENE Phase B: count of runs claimed against this pod. Bumped inside the run-claim transaction so the value is consistent with runFleetMember insert rate.';

COMMENT ON COLUMN "globalOrchestrator"."pod"."imageDigest" IS
    'WORKER-HYGIENE Phase B: sha256 digest of the image the container was created from. Set at create-time by CapacityController.spin; NULL for legacy pods registered before Phase B.';

COMMENT ON COLUMN "globalOrchestrator"."pod"."provisionedAt" IS
    'WORKER-HYGIENE Phase B: wall-clock when the container was created. Distinct from registeredAt (which resets on local-orch restart). NULL for legacy pods.';
