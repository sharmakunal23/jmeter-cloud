-- V2__addAvgRespTimeMs.sql — applied to the jmetercloud_metrics database.
--
-- HM-1A. Adds `avgRespTimeMs` to
-- metrics."workerMetric" so the historical Avg Response Time chart in
-- the run-detail Metrics tab can show a TRUE mean (sum of sample
-- elapsed / sample count) instead of TPS-weighting the per-window P50,
-- which would produce a misleading flat line in the presence of
-- outliers.
--
-- The column is populated by jmeter-metrics-consumer from each Avro
-- WorkerMetric record's avgRespTimeMs field (added in the same step
-- to kafka/schemas/WorkerMetric.avsc + the local-orch aggregator).
--
-- Backward-compatibility:
--   - DEFAULT 0 means existing rows (pre-V2 runs) silently get 0.
--     The HM-1 query treats SUM(avgRespTimeMs * throughput) = 0 as
--     "no avg data" and the UI surfaces a small hint instead of
--     rendering a flat zero line.
--   - The Avro schema's `default: 0.0` lets pre-HM-1A consumers skip
--     the field without crashing if they're rolled out late.
--
-- Performance: ALTER ADD COLUMN with a constant DEFAULT is a metadata-
-- only change in PG11+ (no table rewrite). Cheap on the partitioned
-- parent.

-- Parent table — propagates to existing + future partitions.
ALTER TABLE metrics."workerMetric"
    ADD COLUMN "avgRespTimeMs" DOUBLE PRECISION NOT NULL DEFAULT 0;

COMMENT ON COLUMN metrics."workerMetric"."avgRespTimeMs" IS
    'True mean elapsed time (ms) for the (worker, label, second) window: sum of sample elapsed / sample count. Distinct from p50Ms (median) — moves with outliers, where percentiles do not. Default 0 for pre-HM-1A rows.';
