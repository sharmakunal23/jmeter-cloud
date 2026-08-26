-- V17__reshapeFactTable.sql — applied to the jmetercloud_metrics database.
--
-- SCHEMA-OPT Phase 2 (postgres/docs/schemaOptimization.md §5.3). Rebuilds
-- metrics."workerMetric" in its target physical shape. Three things happen here,
-- and they are one migration because they are one table rewrite:
--
--   1. TYPES NARROW.  BIGINT/DOUBLE PRECISION where the value is provably a
--      small integer. JMeter's elapsed is integer milliseconds and HDRHistogram
--      returns longs, so the percentile columns were never fractional — DOUBLE
--      was storing 8 bytes to hold a number that fits in 4, *and* it was storing
--      it approximately. INTEGER is both half the width and exact.
--
--   2. COLUMNS REORDER.  Fixed-width first (8-byte, then 4-byte), varlena last.
--      That is the whole reason this is a rebuild and not a sequence of
--      ALTER COLUMN … TYPE: Postgres has no way to move an attribute, and
--      attribute order is what determines alignment padding. The new order is
--      4×BIGINT (32 B) + 8×INTEGER (32 B) = 64 B of fixed-width with **zero**
--      padding, then the five varlena columns at a 4-aligned offset.
--
--   3. THE DROPPED-COLUMN TAX IS REPAID.  V15 dropped five columns, and a
--      dropped attribute survives in the tuple descriptor forever — which is why
--      every row written since V15 carries a null bitmap it did not need, +8 B of
--      header. A rebuilt table has no dropped attributes and every column is
--      NOT NULL, so tuples go back to a bare 24-byte header.
--
-- SCHEMA CHANGES, precisely:
--
--   avgRespTimeMs DOUBLE  →  sumElapsedMs BIGINT   (F11 — see below)
--   throughput, errorCount        BIGINT → INTEGER  (2.1e9 samples per
--                                                    worker/label/SECOND)
--   p50Ms, p90Ms, p95Ms, p99Ms    DOUBLE → INTEGER  (exact; histogram ceiling
--                                                    is 3,600,000)
--   maxMs                         DOUBLE → INTEGER  (fed rawMaxMs since V15;
--                                                    INTEGER tops out at 24 days
--                                                    of elapsed on ONE sample)
--   activeThreads                 BIGINT → INTEGER  (see the SMALLINT note)
--   minMs                         DROPPED           (operator decision, below)
--   bytesReceived, bytesSent      BIGINT (kept)     (operator decision, below)
--   statusCodes JSONB, and the four identity columns — unchanged. Identity is
--   Phase 3's problem, and it is the big one (~35% of the heap).
--
-- F11 — WHY sumElapsedMs REPLACES avgRespTimeMs. The worker has always computed
-- an exact `sumElapsedMs` and then divided it away, sending only the mean; every
-- reader then multiplied it back by throughput to recover a sum it could
-- aggregate. Storing the mean was therefore a lossy round-trip of a number we
-- already had exactly, and it cost the same 8 bytes. Sums fold across workers,
-- labels and time without weighting drift. Means do not. The wire now carries
-- both — see the consumer's api/openapi.yaml — and the consumer stores the sum.
--
-- activeThreads is INTEGER, NOT the SMALLINT the plan proposed. Two reasons, and
-- the first is the real one: a 32,767 ceiling turns an out-of-range value into a
-- failed INSERT, which the worker sees as a 503 and retries from its disk buffer
-- *forever* — a poison envelope that never drains. Trading a permanent ingest
-- stall for two bytes is a bad trade. And it is not even two bytes: with the
-- column order above, 7×INTEGER + 1×SMALLINT pads back out to the same 32 B.
--
-- minMs is DROPPED and the byte counters are KEPT (operator decision, 2026-07-30,
-- reversing nothing — this is §5.6's own recommendation, made actionable):
-- bandwidth is a real load-testing signal and this schema is the only place it
-- could ever come from, and the rollups already carry the bytes so a future panel
-- has a source. A quantized minimum answers nothing p50 does not. Dropping it
-- destroys history irreversibly, which is exactly why the decision waited for the
-- rewrite that had to happen anyway.
--
-- ── Method: rename-out-of-the-way, rebuild, copy, drop ──────────────
-- The old objects are renamed with a "_preP2" suffix ONLY to free their names,
-- and dropped at the end of this file. Doing it in that order means the new
-- table is produced by a plain CREATE TABLE and therefore gets *exactly* the
-- object names a fresh V1→V17 install produces — no post-hoc renaming, no
-- divergence between a migrated database and a new one. That property is worth
-- more than the extra DDL: index names that differ by install history are the
-- kind of thing that is discovered years later, by an outage.
--
-- OPERATIONAL NOTE for a large deployment: the copy below is a full rewrite and
-- runs inside Flyway's transaction, so it needs a maintenance window
-- proportional to the table (at the §2 target workload, ~71 GB per run). If that
-- window is unacceptable, run `metrics."dropOldPartitions"(<small>)` first — the
-- rollups already hold everything the orchestrators read, so shrinking raw
-- history before the rewrite costs only Grafana's per-worker drill-down depth.

-- ════════════════════════════════════════════════════════════════════
-- 1. Free the names
-- ════════════════════════════════════════════════════════════════════
-- Indexes and tables are renamed separately because index names live in the
-- same schema-wide relation namespace as tables but are NOT touched by renaming
-- their parent — leave them and "CREATE TABLE … PRIMARY KEY" below collides on
-- "workerMetric_pkey". Constraint names need no such care: they are unique per
-- table, not per schema, so the new table may carry the same ones.
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT c.relname
        FROM   pg_catalog.pg_class c
        JOIN   pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE  n.nspname = 'metrics'
          AND  c.relkind IN ('i', 'I')          -- index, partitioned index
          AND  c.relname LIKE 'workerMetric%'
    LOOP
        EXECUTE format('ALTER INDEX metrics.%I RENAME TO %I',
                       rec.relname, rec.relname || '_preP2');
    END LOOP;

    FOR rec IN
        SELECT c.relname
        FROM   pg_catalog.pg_class c
        JOIN   pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE  n.nspname = 'metrics'
          AND  c.relkind IN ('r', 'p')          -- table, partitioned table
          AND  c.relname LIKE 'workerMetric%'
    LOOP
        EXECUTE format('ALTER TABLE metrics.%I RENAME TO %I',
                       rec.relname, rec.relname || '_preP2');
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 2. The new shape
-- ════════════════════════════════════════════════════════════════════
-- Column ORDER here is load-bearing, not cosmetic — see the header. Do not
-- append a new column to the bottom of this list without checking what it does
-- to alignment: an INTEGER appended after the varlena block costs 4 bytes of
-- padding on every row forever, where the same column placed with its own width
-- class costs nothing.
CREATE TABLE metrics."workerMetric" (
    -- 8-byte, 8-aligned: 32 B, no padding
    "windowSecond"     BIGINT  NOT NULL,
    "sumElapsedMs"     BIGINT  NOT NULL,   -- exact Σ elapsed ms (was avgRespTimeMs)
    "bytesReceived"    BIGINT  NOT NULL,
    "bytesSent"        BIGINT  NOT NULL,
    -- 4-byte, 4-aligned: 32 B, no padding
    "throughput"       INTEGER NOT NULL,
    "errorCount"       INTEGER NOT NULL,
    "p50Ms"            INTEGER NOT NULL,
    "p90Ms"            INTEGER NOT NULL,
    "p95Ms"            INTEGER NOT NULL,
    "p99Ms"            INTEGER NOT NULL,
    "maxMs"            INTEGER NOT NULL,   -- exact max since V15 (was rawMaxMs)
    "activeThreads"    INTEGER NOT NULL,
    -- varlena last, landing on a 4-aligned offset (64)
    "runId"            TEXT    NOT NULL,
    "workerId"         TEXT    NOT NULL,
    "label"            TEXT    NOT NULL,
    "region"           TEXT    NOT NULL,
    "statusCodes"      JSONB   NOT NULL DEFAULT '{}'::jsonb,
    -- The partition key MUST be in the primary key under declarative
    -- partitioning. The PK doubles as the producer→consumer idempotency
    -- contract — unchanged by this migration, so replayed envelopes from a
    -- worker's disk buffer still collapse to no-ops across the rewrite.
    PRIMARY KEY ("runId", "workerId", "label", "windowSecond")
) PARTITION BY RANGE ("windowSecond");

COMMENT ON TABLE metrics."workerMetric" IS
    'Per-second per-(runId, workerId, label) metric rows. Partitioned weekly on "windowSecond" (Unix epoch). Primary key matches the producer→consumer idempotency contract. SCHEMA-OPT Phase 2 shape: fixed-width columns first (zero alignment padding), integer types where the value is integral, sumElapsedMs instead of a derived mean. Every orchestrator read goes through the rollup tables, not this one — Grafana is the only remaining reader.';

COMMENT ON COLUMN metrics."workerMetric"."sumElapsedMs" IS
    'Exact total elapsed milliseconds across every sample in this window. Readers divide by "throughput" at the very end; storing the sum is what lets a reader fold across workers, labels and time without weighting drift.';
COMMENT ON COLUMN metrics."workerMetric"."maxMs" IS
    'Exact slowest sample in this window (the producer''s un-histogrammed rawMaxMs since V15) — NOT the HDRHistogram bucket edge.';

CREATE INDEX "workerMetric_runId_label_windowSecond_idx"
    ON metrics."workerMetric" ("runId", "label", "windowSecond");

-- ════════════════════════════════════════════════════════════════════
-- 3. Partitions: every week the old data occupies, plus the runway
-- ════════════════════════════════════════════════════════════════════
-- createWeeklyPartition() resolves metrics."workerMetric" dynamically, so it now
-- builds partitions of the NEW table, with the same names and the same
-- writer/reader/purger grants a fresh install gets.
DO $$
DECLARE
    rec   RECORD;
    built INTEGER := 0;
BEGIN
    FOR rec IN
        SELECT DISTINCT
               date_trunc('week', to_timestamp("windowSecond") AT TIME ZONE 'UTC')
                   AT TIME ZONE 'UTC' AS anchor
        FROM   metrics."workerMetric_preP2"
    LOOP
        PERFORM metrics."createWeeklyPartition"(rec.anchor);
        built := built + 1;
    END LOOP;
    RAISE NOTICE 'SCHEMA-OPT Phase 2: created % partition(s) to receive existing rows', built;
END $$;

-- Restore the 8-week forward runway the maintenance job expects. Harmless when
-- the loop above already made some of these (createWeeklyPartition is idempotent).
SELECT metrics."ensureUpcomingPartitions"(8);

-- ════════════════════════════════════════════════════════════════════
-- 4. Copy
-- ════════════════════════════════════════════════════════════════════
-- The only lossy conversion is sumElapsedMs, and it is lossy in exactly the way
-- the source data already was: round(mean × count) recovers the total that the
-- stored mean implied. There is no more precise answer available from a column
-- that threw the sum away. Rows written from here on carry the real sum.
--
-- round(…)::integer rather than a bare ::integer on the percentile columns:
-- these values are integral already (HDRHistogram returns longs), so this is a
-- statement of intent rather than a behaviour change — but it makes the cast
-- read as "these were always integers" instead of "truncate whatever is here".
INSERT INTO metrics."workerMetric" (
    "windowSecond", "sumElapsedMs", "bytesReceived", "bytesSent",
    "throughput", "errorCount", "p50Ms", "p90Ms", "p95Ms", "p99Ms",
    "maxMs", "activeThreads",
    "runId", "workerId", "label", "region", "statusCodes")
SELECT "windowSecond",
       round("avgRespTimeMs" * "throughput")::bigint,
       "bytesReceived",
       "bytesSent",
       "throughput"::integer,
       "errorCount"::integer,
       round("p50Ms")::integer,
       round("p90Ms")::integer,
       round("p95Ms")::integer,
       round("p99Ms")::integer,
       round("maxMs")::integer,
       "activeThreads"::integer,
       "runId", "workerId", "label", "region", "statusCodes"
FROM   metrics."workerMetric_preP2";

-- ════════════════════════════════════════════════════════════════════
-- 5. Drop the old table, and grant the new one
-- ════════════════════════════════════════════════════════════════════
-- CASCADE takes the renamed partitions and their indexes with it.
DROP TABLE metrics."workerMetric_preP2" CASCADE;

-- Parent-level grants. Partition-level grants were issued by
-- createWeeklyPartition() as each child was made (Postgres tracks
-- partitioned-parent and per-partition privileges separately — V1's trap).
GRANT INSERT, SELECT         ON metrics."workerMetric" TO "metricsWriter";
GRANT SELECT                 ON metrics."workerMetric" TO "metricsReader";
GRANT SELECT, DELETE         ON metrics."workerMetric" TO "metricsPurger";

-- ════════════════════════════════════════════════════════════════════
-- 6. Rollups: sumRtWeighted → sumElapsedMs, and it is now exact
-- ════════════════════════════════════════════════════════════════════
-- Same quantity, honest name. The column has always held "total elapsed ms",
-- but it was built as Σ(mean × count) — a reconstruction. It is now Σ(the real
-- sum), so the name that described the construction is retired.
--
-- Rows written before this migration keep their reconstructed values, which is
-- correct: that is the best those runs ever had, and re-deriving them would not
-- add information. No backfill.
--
-- One consequence, stated so it is not mistaken for a bug later: rebuilding a
-- PRE-Phase-2 run's rollups now sums per-row rounded totals where the stored
-- value summed unrounded ones, so the two can differ by up to half a
-- millisecond per raw row. Runs ingested from here on use the exact sum on both
-- paths and agree exactly — which is what the delta-vs-rebuild IT pins.
--
-- The column stays DOUBLE PRECISION deliberately, even though it now holds a
-- pure integer sum. Making it BIGINT would silently convert every reader's
-- `sum("sumElapsedMs") / sum("samples")` from float division into INTEGER
-- division — truncating average response time to whole milliseconds across four
-- repositories in two orchestrators. A double holds these magnitudes exactly
-- (well under 2^53); the truncation risk is not worth the zero bytes saved on a
-- 36 MB table.
ALTER TABLE metrics."runSecond" RENAME COLUMN "sumRtWeighted" TO "sumElapsedMs";
ALTER TABLE metrics."runLabel"  RENAME COLUMN "sumRtWeighted" TO "sumElapsedMs";

COMMENT ON COLUMN metrics."runSecond"."sumElapsedMs" IS
    'Σ elapsed ms across every sample folded into this (runId, windowSecond, region). Exact for rows ingested since SCHEMA-OPT Phase 2; reconstructed as Σ(avgRespTimeMs × throughput) for older ones. Divide by "samples" to get a mean.';
COMMENT ON COLUMN metrics."runLabel"."sumElapsedMs" IS
    'Σ elapsed ms across every sample of this (runId, label). Exact since SCHEMA-OPT Phase 2. Divide by "samples" to get a mean.';

-- ════════════════════════════════════════════════════════════════════
-- 7. rebuildRunRollups for the new raw shape
-- ════════════════════════════════════════════════════════════════════
-- Mechanically: "avgRespTimeMs" * "throughput" becomes "sumElapsedMs", and every
-- percentile product gains an explicit ::bigint cast on the left operand.
--
-- That cast is not decoration. Both operands are INTEGER now, and Postgres
-- evaluates integer × integer as integer — so a single row with a 3,600,000 ms
-- p99 and a four-digit throughput would overflow int4 and abort the statement.
-- Widening one side promotes the whole product to bigint. The same trap applies
-- to the consumer's delta CTE, which carries the same casts.
--
-- Still no division anywhere in here: this function only accumulates. Ratios are
-- derived by the readers, from the sums, at the end.
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
        "sumElapsedMs", "sumP50Weighted", "sumP90Weighted", "sumP95Weighted",
        "sumP99Weighted", "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent")
    SELECT "runId", "windowSecond", "region",
           count(*),
           COALESCE(sum("throughput"), 0)::bigint,
           COALESCE(sum("errorCount"), 0)::bigint,
           COALESCE(sum("sumElapsedMs"), 0),
           COALESCE(sum("p50Ms"::bigint * "throughput"), 0),
           COALESCE(sum("p90Ms"::bigint * "throughput"), 0),
           COALESCE(sum("p95Ms"::bigint * "throughput"), 0),
           COALESCE(sum("p99Ms"::bigint * "throughput"), 0),
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
        "runId", "label", "rowCount", "samples", "errors", "sumElapsedMs",
        "sumP50", "sumP90", "sumP95", "sumP99",
        "sumP50Weighted", "sumP90Weighted", "sumP95Weighted", "sumP99Weighted",
        "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent",
        "firstSecond", "lastSecond")
    SELECT "runId", "label",
           count(*),
           COALESCE(sum("throughput"), 0)::bigint,
           COALESCE(sum("errorCount"), 0)::bigint,
           COALESCE(sum("sumElapsedMs"), 0),
           COALESCE(sum("p50Ms"), 0),
           COALESCE(sum("p90Ms"), 0),
           COALESCE(sum("p95Ms"), 0),
           COALESCE(sum("p99Ms"), 0),
           COALESCE(sum("p50Ms"::bigint * "throughput"), 0),
           COALESCE(sum("p90Ms"::bigint * "throughput"), 0),
           COALESCE(sum("p95Ms"::bigint * "throughput"), 0),
           COALESCE(sum("p99Ms"::bigint * "throughput"), 0),
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

-- CREATE OR REPLACE keeps a function's ownership and ACL but NOT its
-- SECURITY DEFINER flag or its SET clauses — those are re-specified from
-- scratch on every replace. Without re-asserting them the body would run as
-- the caller, and the consumer (metricsWriter, deliberately without DELETE on
-- the rollups) would lose its repair path. The REVOKE/GRANT below are
-- redundant-but-explicit for the same reason V16 spelled them out.
REVOKE EXECUTE ON FUNCTION metrics."rebuildRunRollups"(TEXT) FROM PUBLIC;
ALTER FUNCTION metrics."rebuildRunRollups"(TEXT)
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;
GRANT EXECUTE ON FUNCTION metrics."rebuildRunRollups"(TEXT) TO "metricsWriter";
