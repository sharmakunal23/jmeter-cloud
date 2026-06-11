-- V12__addJoinedAtSecond.sql — applied to the jmetercloud_metrics database.
--
-- MID-TEST-SCALING Phase D — adds the per-row joinedAtSecond column so the
-- consumer can persist the WorkerMetricBatch envelope's join-second epoch
-- (added in Phase C / Avro schema) into a queryable column. Lets future
-- per-second fleet rollups answer "which members were live at second X?"
-- without joining back through globalOrchestrator.runFleetMember.
--
-- Semantics:
--   - 0 → original-fleet member (joined at run start; the existing
--         windowSecond + run.startedAt are sufficient to derive presence).
--   - > 0 → mid-test scale-up joiner; the value is seconds-since-run-start
--         at which this worker began emitting metrics.
--
-- Backward-compatibility:
--   - DEFAULT 0 means existing rows (pre-V12) silently get 0, which is
--     the correct semantic for original-fleet — no backfill needed.
--   - The Avro schema's `default: 0` (added in Phase C) lets pre-Phase-C
--     consumers and producers keep working without coordination.
--
-- Performance: ALTER ADD COLUMN with a constant DEFAULT is a metadata-
-- only change in PG11+ (no table rewrite). Cheap on the partitioned
-- parent — the ALTER cascades to existing weekly partitions and applies
-- to future ones via the existing createWeeklyPartition helper.

-- Parent table — propagates to existing + future partitions.
ALTER TABLE metrics."workerMetric"
    ADD COLUMN "joinedAtSecond" BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN metrics."workerMetric"."joinedAtSecond" IS
    'MID-TEST-SCALING Phase D — seconds since run.startedAt at which the emitting worker joined the run. 0 for original-fleet workers; > 0 for mid-test scale-up joiners. Source: WorkerMetricBatch.joinedAtSecond (Phase C envelope field).';
