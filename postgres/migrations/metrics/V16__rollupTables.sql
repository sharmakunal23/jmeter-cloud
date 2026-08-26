-- V16__rollupTables.sql — applied to the jmetercloud_metrics database.
--
-- SCHEMA-OPT Phase 1 (2026-07-29). Adds the three derived tables that take the
-- orchestrator's metric reads off metrics."workerMetric" entirely. Full costing
-- in postgres/docs/schemaOptimization.md; the problem in one paragraph:
--
--   Every reader of workerMetric immediately aggregates the worker and label
--   dimensions away, and there was nothing to read but the raw per-(worker,
--   label, second) fact rows. At 20 workers × 200 labels a live Metrics tab
--   re-aggregated ~5,130,000 rows (~1.5 GB, twice, plus 10.3M jsonb_each_text
--   calls) EVERY 5 SECONDS, and the whole-test queries crossed the read pool's
--   30 s statement_timeout — so the "Whole test" chart, the runTrend snapshot
--   and AI insights did not get slow, they FAILED.
--
-- After this migration the same live poll reads ~1,800 rows and the whole-test
-- paths read ≤ 54,000. Raw rows keep their idempotency role and remain the
-- source of truth these tables are derived from; nothing reads them on a hot
-- path anymore.
--
-- ── Why sums and never ratios ───────────────────────────────────────
-- Every column here is an ADDITIVE component (counts and sums), never a derived
-- ratio. Two reasons, and both are load-bearing:
--   1. Correctness. A mean of per-row rates is not the rate of the whole, and a
--      mean of percentiles is not a percentile. Storing sums lets a reader fold
--      across regions, labels or time and only then divide, which is exactly
--      what MetricsTimeseriesRepository already does internally to avoid
--      weighting drift.
--   2. Mergeability. Additive columns can be maintained as deltas (see below),
--      which is what makes exactly-once incremental maintenance possible.
-- The two "…Weighted" and plain "…" percentile sums exist because the platform
-- reads percentiles BOTH ways today: rollupByLabel reports an unweighted
-- avg(p50Ms) while runAggregate reports a throughput-weighted mean. Keeping both
-- numerators reproduces today's numbers exactly — this migration is not the
-- place to silently change a number an operator has been reading. (The proper
-- fix for percentile aggregation is a mergeable sketch; that is a Phase 2+
-- decision, deliberately not smuggled in here.)
--
-- ── How they are maintained: exactly-once deltas ────────────────────
-- The consumer maintains these in the SAME STATEMENT as the raw INSERT:
--
--   WITH ins AS (INSERT INTO "workerMetric" … ON CONFLICT DO NOTHING RETURNING …)
--   , sec AS (INSERT INTO "runSecond" SELECT … FROM ins GROUP BY … ON CONFLICT DO UPDATE …)
--   …
--
-- The trap this avoids: the raw insert is idempotent via ON CONFLICT DO NOTHING,
-- but a rollup "+= delta" is NOT — a retried envelope (the worker's disk-buffer
-- sweeper re-POSTs after a lost ack) would double-count. RETURNING on a
-- DO NOTHING insert yields ONLY the rows that actually landed, so the delta is
-- exactly-once for free, in one round-trip, in one transaction. It is also
-- correct for arbitrarily LATE arrivals, which a watermark-based background
-- aggregator would silently miss.
--
-- ── fillfactor + autovacuum ─────────────────────────────────────────
-- These tables are update-heavy by design: 20 workers folding into the same
-- (runId, windowSecond, region) row means ~20 updates/second on one row. None of
-- the updated columns are indexed, so Postgres can use HOT updates and keep the
-- new tuple version on the same page — fillfactor 70 reserves the room for that,
-- and the lower autovacuum scale factors keep the dead versions from
-- accumulating. Without both, these small hot tables would bloat far faster than
-- the big append-only one.

-- ── Per-(run, second, region) fleet rollup ──────────────────────────
-- Serves: GET /runs/{id}/timeseries (the 5 s live poll), its byRegion split, and
-- the max("windowSecond") probe that resolves the "last 30 m" window.
--
-- Key order is (runId, windowSecond, region), NOT (runId, region, windowSecond).
-- That is deliberate: with runId fixed by equality, windowSecond is then the
-- index's next ordering column, so `max("windowSecond") WHERE "runId" = ?`
-- collapses to a backward index scan + Limit 1. Under the other order the
-- planner cannot use the MIN/MAX shortcut — which is precisely the bug this
-- migration is fixing on the raw table, where the index is
-- ("runId","label","windowSecond") and that probe degenerates into scanning
-- every index entry for the run, across every partition, on every poll.
CREATE TABLE metrics."runSecond" (
    "runId"             TEXT             NOT NULL,
    "windowSecond"      BIGINT           NOT NULL,
    "region"            TEXT             NOT NULL,
    -- Raw rows folded into this bucket. Diagnostic, and the denominator for any
    -- unweighted mean a future reader wants.
    "rowCount"          BIGINT           NOT NULL,
    "samples"           BIGINT           NOT NULL,   -- Σ throughput
    "errors"            BIGINT           NOT NULL,   -- Σ errorCount
    "sumRtWeighted"     DOUBLE PRECISION NOT NULL,   -- Σ (avgRespTimeMs × throughput)
    "sumP50Weighted"    DOUBLE PRECISION NOT NULL,
    "sumP90Weighted"    DOUBLE PRECISION NOT NULL,
    "sumP95Weighted"    DOUBLE PRECISION NOT NULL,
    "sumP99Weighted"    DOUBLE PRECISION NOT NULL,
    "maxMs"             DOUBLE PRECISION NOT NULL,   -- GREATEST across the bucket
    "maxActiveThreads"  BIGINT           NOT NULL,
    "bytesReceived"     BIGINT           NOT NULL,
    "bytesSent"         BIGINT           NOT NULL,
    PRIMARY KEY ("runId", "windowSecond", "region")
) WITH (fillfactor = 70,
        autovacuum_vacuum_scale_factor  = 0.05,
        autovacuum_analyze_scale_factor = 0.05);

COMMENT ON TABLE metrics."runSecond" IS
    'SCHEMA-OPT Phase 1 rollup: one row per (runId, windowSecond, region), folding every worker and label. Additive component sums only — readers derive ratios after folding. Maintained as exactly-once deltas by the metrics-consumer in the same statement as the raw INSERT; rebuildable from raw via metrics."rebuildRunRollups".';

-- ── Per-(run, second, region, responseCode) rollup ──────────────────
-- Serves the status-code series on the Metrics tab. Exact response codes are
-- preserved (JMeter codes are free-form strings — "401" and "Non HTTP response
-- code: …" are both real), so this replaces 10.3M jsonb_each_text calls per poll
-- with an integer column, WITHOUT collapsing the codes into 2xx/3xx/4xx/5xx
-- buckets and changing what the chart shows.
CREATE TABLE metrics."runSecondStatus" (
    "runId"        TEXT   NOT NULL,
    "windowSecond" BIGINT NOT NULL,
    "region"       TEXT   NOT NULL,
    "code"         TEXT   NOT NULL,
    "n"            BIGINT NOT NULL,
    PRIMARY KEY ("runId", "windowSecond", "region", "code")
) WITH (fillfactor = 70,
        autovacuum_vacuum_scale_factor  = 0.05,
        autovacuum_analyze_scale_factor = 0.05);

COMMENT ON TABLE metrics."runSecondStatus" IS
    'SCHEMA-OPT Phase 1 rollup: per-(runId, windowSecond, region, responseCode) sample counts, unrolled from workerMetric."statusCodes" at write time. Exact codes preserved.';

-- ── Per-(run, label) whole-test rollup ──────────────────────────────
-- Serves GET /runs/{id}/metrics (rollupByLabel) and, summed across labels, the
-- runTrend snapshot's runAggregate. Both previously scanned every row of the run.
-- 200 rows per run, so the column count here is free.
CREATE TABLE metrics."runLabel" (
    "runId"            TEXT             NOT NULL,
    "label"            TEXT             NOT NULL,
    "rowCount"         BIGINT           NOT NULL,
    "samples"          BIGINT           NOT NULL,
    "errors"           BIGINT           NOT NULL,
    "sumRtWeighted"    DOUBLE PRECISION NOT NULL,
    -- Unweighted numerators — reproduce today's avg("pNN") in rollupByLabel.
    "sumP50"           DOUBLE PRECISION NOT NULL,
    "sumP90"           DOUBLE PRECISION NOT NULL,
    "sumP95"           DOUBLE PRECISION NOT NULL,
    "sumP99"           DOUBLE PRECISION NOT NULL,
    -- Throughput-weighted numerators — reproduce today's runAggregate.
    "sumP50Weighted"   DOUBLE PRECISION NOT NULL,
    "sumP90Weighted"   DOUBLE PRECISION NOT NULL,
    "sumP95Weighted"   DOUBLE PRECISION NOT NULL,
    "sumP99Weighted"   DOUBLE PRECISION NOT NULL,
    "maxMs"            DOUBLE PRECISION NOT NULL,
    "maxActiveThreads" BIGINT           NOT NULL,
    "bytesReceived"    BIGINT           NOT NULL,
    "bytesSent"        BIGINT           NOT NULL,
    "firstSecond"      BIGINT           NOT NULL,   -- LEAST across the run
    "lastSecond"       BIGINT           NOT NULL,   -- GREATEST across the run
    PRIMARY KEY ("runId", "label")
) WITH (fillfactor = 70,
        autovacuum_vacuum_scale_factor  = 0.05,
        autovacuum_analyze_scale_factor = 0.05);

COMMENT ON TABLE metrics."runLabel" IS
    'SCHEMA-OPT Phase 1 rollup: one row per (runId, label) for the whole run. Carries BOTH unweighted and throughput-weighted percentile numerators so rollupByLabel and runAggregate reproduce their pre-rollup numbers exactly. Also supplies the windowSecond bounds the run purge uses to prune its DELETE to the partitions a run actually touched.';

-- ── Retention support ───────────────────────────────────────────────
-- BRIN, not btree. These are range-scanned only by the retention sweeper below,
-- never for point lookups (every read is runId-scoped and served by the PK), and
-- rows land in roughly windowSecond order — which is what BRIN is for. A btree
-- here would cost gigabytes at scale AND break HOT updates by indexing a column
-- the delta path touches; BRIN costs kilobytes and does neither.
CREATE INDEX "runSecond_windowSecond_brin"
    ON metrics."runSecond" USING brin ("windowSecond");
CREATE INDEX "runSecondStatus_windowSecond_brin"
    ON metrics."runSecondStatus" USING brin ("windowSecond");
CREATE INDEX "runLabel_lastSecond_brin"
    ON metrics."runLabel" USING brin ("lastSecond");

-- ── GRANTs ──────────────────────────────────────────────────────────
-- metricsWriter needs UPDATE as well as INSERT here (it did not on the raw
-- table): ON CONFLICT DO UPDATE is an UPDATE, and its SET expressions read the
-- existing row, so SELECT is required too.
GRANT INSERT, SELECT, UPDATE ON metrics."runSecond"       TO "metricsWriter";
GRANT INSERT, SELECT, UPDATE ON metrics."runSecondStatus" TO "metricsWriter";
GRANT INSERT, SELECT, UPDATE ON metrics."runLabel"        TO "metricsWriter";

GRANT SELECT ON metrics."runSecond"       TO "metricsReader";
GRANT SELECT ON metrics."runSecondStatus" TO "metricsReader";
GRANT SELECT ON metrics."runLabel"        TO "metricsReader";

-- The run purge must remove a run's rollup rows too, or purging would reclaim
-- the raw rows and leave the charts intact — worse than not purging at all.
GRANT SELECT, DELETE ON metrics."runSecond"       TO "metricsPurger";
GRANT SELECT, DELETE ON metrics."runSecondStatus" TO "metricsPurger";
GRANT SELECT, DELETE ON metrics."runLabel"        TO "metricsPurger";

-- ── Rebuild from raw ────────────────────────────────────────────────
-- The second implementation of the aggregation, and it exists for three
-- distinct reasons:
--   1. Backfill. Runs that predate this migration have raw rows and no rollup
--      rows; without this they would render as empty charts once the readers
--      move over. Called unconditionally at the bottom of this file.
--   2. Repair. If a rollup ever drifts from raw — a bug, a partial restore — this
--      is the recovery path, and it is idempotent (delete-then-rebuild).
--   3. Test seeding. The orchestrator ITs insert raw fixture rows and call this,
--      so they exercise reads against rollups without duplicating the
--      aggregation in test code.
--
-- Because it is a SECOND implementation of the same arithmetic as the consumer's
-- delta CTE, the two are pinned together by an IT that ingests a batch, rebuilds,
-- and asserts the tables are identical. If you change the aggregation on one
-- side, that test is what tells you about the other.
--
-- SECURITY DEFINER (mirrors V14's partition helpers): the body needs DELETE on
-- the rollups, which metricsWriter is deliberately not granted, and the consumer
-- connects as metricsWriter in cloud deployments. search_path is pinned and every
-- reference is schema-qualified.
CREATE OR REPLACE FUNCTION metrics."rebuildRunRollups"(p_runId TEXT)
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE
    seconds_built BIGINT;
BEGIN
    DELETE FROM metrics."runSecond"       WHERE "runId" = p_runId;
    DELETE FROM metrics."runSecondStatus" WHERE "runId" = p_runId;
    DELETE FROM metrics."runLabel"        WHERE "runId" = p_runId;

    INSERT INTO metrics."runSecond" (
        "runId", "windowSecond", "region", "rowCount", "samples", "errors",
        "sumRtWeighted", "sumP50Weighted", "sumP90Weighted", "sumP95Weighted",
        "sumP99Weighted", "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent")
    SELECT "runId", "windowSecond", "region",
           count(*),
           COALESCE(sum("throughput"), 0)::bigint,
           COALESCE(sum("errorCount"), 0)::bigint,
           COALESCE(sum("avgRespTimeMs" * "throughput"), 0),
           COALESCE(sum("p50Ms" * "throughput"), 0),
           COALESCE(sum("p90Ms" * "throughput"), 0),
           COALESCE(sum("p95Ms" * "throughput"), 0),
           COALESCE(sum("p99Ms" * "throughput"), 0),
           COALESCE(max("maxMs"), 0),
           COALESCE(max("activeThreads"), 0)::bigint,
           COALESCE(sum("bytesReceived"), 0)::bigint,
           COALESCE(sum("bytesSent"), 0)::bigint
    FROM   metrics."workerMetric"
    WHERE  "runId" = p_runId
    GROUP  BY "runId", "windowSecond", "region";

    INSERT INTO metrics."runSecondStatus" (
        "runId", "windowSecond", "region", "code", "n")
    SELECT w."runId", w."windowSecond", w."region", j.key,
           COALESCE(sum((j.value)::bigint), 0)::bigint
    FROM   metrics."workerMetric" w,
           LATERAL jsonb_each_text(w."statusCodes") AS j
    WHERE  w."runId" = p_runId
    GROUP  BY w."runId", w."windowSecond", w."region", j.key;

    INSERT INTO metrics."runLabel" (
        "runId", "label", "rowCount", "samples", "errors", "sumRtWeighted",
        "sumP50", "sumP90", "sumP95", "sumP99",
        "sumP50Weighted", "sumP90Weighted", "sumP95Weighted", "sumP99Weighted",
        "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent",
        "firstSecond", "lastSecond")
    SELECT "runId", "label",
           count(*),
           COALESCE(sum("throughput"), 0)::bigint,
           COALESCE(sum("errorCount"), 0)::bigint,
           COALESCE(sum("avgRespTimeMs" * "throughput"), 0),
           COALESCE(sum("p50Ms"), 0),
           COALESCE(sum("p90Ms"), 0),
           COALESCE(sum("p95Ms"), 0),
           COALESCE(sum("p99Ms"), 0),
           COALESCE(sum("p50Ms" * "throughput"), 0),
           COALESCE(sum("p90Ms" * "throughput"), 0),
           COALESCE(sum("p95Ms" * "throughput"), 0),
           COALESCE(sum("p99Ms" * "throughput"), 0),
           COALESCE(max("maxMs"), 0),
           COALESCE(max("activeThreads"), 0)::bigint,
           COALESCE(sum("bytesReceived"), 0)::bigint,
           COALESCE(sum("bytesSent"), 0)::bigint,
           COALESCE(min("windowSecond"), 0)::bigint,
           COALESCE(max("windowSecond"), 0)::bigint
    FROM   metrics."workerMetric"
    WHERE  "runId" = p_runId
    GROUP  BY "runId", "label";

    SELECT count(*) INTO seconds_built
    FROM   metrics."runSecond" WHERE "runId" = p_runId;
    RETURN seconds_built;
END;
$$;

COMMENT ON FUNCTION metrics."rebuildRunRollups" IS
    'Recomputes all three rollup tables for one runId from metrics."workerMetric". Idempotent (delete-then-rebuild). Returns the number of runSecond rows built. Uses: backfill of pre-V16 runs, repair after drift, and IT fixture seeding.';

-- ── Rollup retention ────────────────────────────────────────────────
-- Raw rows age out by partition drop (dropOldPartitions). The rollups are not
-- partitioned — they are small and always read runId-scoped — so without this
-- they would be the one thing in the metrics DB that grows forever, which would
-- have quietly converted a bounded store into an unbounded one.
--
-- Same 52-week default as the raw retention for now. Phase 4 of the schema plan
-- deliberately DIVERGES them (raw 7–30 days, rollups 52 weeks), at which point
-- this function keeps its default and dropOldPartitions gets a shorter one.
CREATE OR REPLACE FUNCTION metrics."dropOldRollups"(p_keep_weeks INTEGER DEFAULT 52)
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE
    cutoff_epoch BIGINT;
    removed      BIGINT := 0;
    n            BIGINT;
BEGIN
    cutoff_epoch := EXTRACT(epoch FROM (now() - (p_keep_weeks || ' weeks')::INTERVAL))::BIGINT;

    DELETE FROM metrics."runSecond" WHERE "windowSecond" < cutoff_epoch;
    GET DIAGNOSTICS n = ROW_COUNT;  removed := removed + n;

    DELETE FROM metrics."runSecondStatus" WHERE "windowSecond" < cutoff_epoch;
    GET DIAGNOSTICS n = ROW_COUNT;  removed := removed + n;

    -- lastSecond, not firstSecond: a run is only past retention once its NEWEST
    -- data is.
    DELETE FROM metrics."runLabel" WHERE "lastSecond" < cutoff_epoch;
    GET DIAGNOSTICS n = ROW_COUNT;  removed := removed + n;

    RETURN removed;
END;
$$;

COMMENT ON FUNCTION metrics."dropOldRollups" IS
    'Deletes rollup rows older than the retention window (default 52 weeks). Called by the metrics-consumer PartitionMaintenanceJob alongside dropOldPartitions. Rollups are unpartitioned, so this is a DELETE, not a partition drop.';

REVOKE EXECUTE ON FUNCTION metrics."rebuildRunRollups"(TEXT)   FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION metrics."dropOldRollups"(INTEGER)   FROM PUBLIC;

ALTER FUNCTION metrics."rebuildRunRollups"(TEXT)
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;
ALTER FUNCTION metrics."dropOldRollups"(INTEGER)
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;

GRANT EXECUTE ON FUNCTION metrics."rebuildRunRollups"(TEXT) TO "metricsWriter";
GRANT EXECUTE ON FUNCTION metrics."dropOldRollups"(INTEGER) TO "metricsWriter";

-- ── Backfill every existing run ─────────────────────────────────────
-- UNCONDITIONAL, and that is a deliberate reliability choice rather than an
-- oversight about cost. Two invariants depend on rollup coverage being complete
-- for every run that has raw rows:
--   * the readers no longer consult raw, so a run with partial rollups would
--     render partial charts — silently wrong is worse than visibly empty;
--   * the run purge prunes its DELETE to the windowSecond bounds recorded in
--     runLabel, so partial coverage would mean narrow bounds and orphaned raw
--     rows that nothing can ever find again.
-- A run with NO rollup rows is safe (the purge falls back to an unbounded
-- DELETE and the charts show the normal empty shape). A run with SOME is not.
-- Backfilling everything here is what makes "some" impossible.
--
-- Cost: one aggregate pass over the existing raw table. On a dev or CI volume
-- that is instant. On a large historical dataset it is not — if you are applying
-- this to a cluster with billions of rows, apply V16 with this block commented
-- out, run the same loop out-of-band at your own pace (it is idempotent), and
-- deploy the new orchestrator/consumer images only once it has finished. The
-- NOTICE per run is there so a long run is observable rather than a hang.
DO $$
DECLARE
    rec  RECORD;
    n    BIGINT;
    runs INTEGER := 0;
BEGIN
    FOR rec IN SELECT DISTINCT "runId" FROM metrics."workerMetric" LOOP
        n := metrics."rebuildRunRollups"(rec."runId");
        runs := runs + 1;
        RAISE NOTICE 'V16 backfill: runId=% → % runSecond rows', rec."runId", n;
    END LOOP;
    RAISE NOTICE 'V16 backfill complete: % run(s)', runs;
END $$;
