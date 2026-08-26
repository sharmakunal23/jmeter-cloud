-- V18__runSaveResults.sql — applied to the jmetercloud_globalrun database.
--
-- "Save Results" feature: when a run is launched with saveResults=true, each
-- worker zips + uploads its JTL to the Document Service on a clean COMPLETE,
-- and the operator can download all results for the run in one zip. This
-- column records the operator's intent at launch so the UI can show a
-- "Download results" button on the run-detail page without a Document Service
-- round-trip. Defaults false → zero behavior change for existing rows + runs
-- that don't opt in.

ALTER TABLE "globalOrchestrator"."run"
    ADD COLUMN IF NOT EXISTS "saveResults" BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN "globalOrchestrator"."run"."saveResults" IS
    'Save Results: true → each worker uploads its gzipped JTL to the Document Service (X-Type=result, tagged with application + runId + workerId) on COMPLETE; downloadable as one combined zip per run.';
