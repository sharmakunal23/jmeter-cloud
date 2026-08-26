-- ⚠ NOT A LIVE MIGRATION — deferred, and reverted where it had been applied.
--
-- This was SCHEMA-OPT Phase 3. It was applied to the local metrics DB on
-- 2026-08-20, but no application code ever followed it: the metrics-consumer's
-- WorkerMetricWriter and the Grafana dashboard both still address the fact
-- table by "runId"/"workerId"/"label", which this migration removes. The result
-- was that ingest failed on every envelope with
--
--     ERROR: column "runId" of relation "workerMetric" does not exist
--
-- and 17 of the consumer's 19 integration tests failed against a clean
-- database. Reverted to the V17 (Phase 2) shape on 2026-08-26, preserving all
-- 6,202,437 rows by joining the dictionaries back.
--
-- To land Phase 3 properly, this file is the starting point — but it is only
-- the schema half. It needs, in the same change:
--   1. a consumer-side dictionary cache with insert-on-miss, binding
--      runKey/workerKey/labelId instead of the text identity;
--   2. the Grafana dashboard's panels joined through the three dimensions;
--   3. the soak (20 workers x 200 labels x 30 min) that has never been run —
--      the dictionary cache adds a first-sight-of-a-label write-contention
--      failure mode that only a soak surfaces.
--
-- Rationale, measurements and design: postgres/docs/schemaOptimization.md §5.4.
-- The original file follows unchanged below.
-- ────────────────────────────────────────────────────────────────────

-- V18__dictionaryEncodedIdentity.sql — applied to the jmetercloud_metrics database.
--
-- SCHEMA-OPT Phase 3 (postgres/docs/schemaOptimization.md §5.4). Replaces the
-- three identity TEXT columns on metrics."workerMetric" with surrogate integer
-- keys and three small dimension tables.
--
-- WHY THIS IS THE BIGGEST REMAINING WIN, measured rather than argued. Phase 2
-- narrowed every numeric column and reordered the tuple, and it moved the heap
-- 304 → 244 B/row — but the *index* bytes did not move at all (228.8 → 230.4
-- B/row). That is the whole case for this phase in one number: index keys are
-- 100% identity text, so no amount of numeric surgery could touch them, and
-- after Phase 2 they are ~47% of the table's total footprint. Strings are also
-- why the heap is still large: runId(26) + workerId(~22) + label(~28) is ~80 B
-- of the 244.
--
--   PK   ("runId","workerId","label","windowSecond")  → ~87 B of key
--        ("runKey","workerKey","labelId","windowSecond") → 20 B of key
--
-- ── The one design decision that is NOT the plan's ──────────────────
-- §5.4 proposed `workerKey SMALLINT` and `labelId SMALLINT`. They are INTEGER
-- here, for the same reason Phase 2 rejected SMALLINT for activeThreads: a
-- 32,767 ceiling is not a size limit, it is a **time bomb**. labelDict is global
-- and append-only, so a long-lived platform reaches 32,767 distinct labels
-- eventually; at that moment the identity sequence overflows, every INSERT
-- fails, the consumer returns 503, and every worker retries the same envelope
-- from its disk buffer forever. Ingest stops permanently and the fix is a
-- migration. The cost of avoiding that is 4 bytes per row against the ~178
-- bytes this phase saves.
--
-- ── What is deliberately NOT encoded ────────────────────────────────
-- "region" stays TEXT. It is only ~8 B/row and it is *functionally determined*
-- by workerId (pod names are {appName}-{region}-worker-{n}), so it could live in
-- workerDict instead — but nothing in the schema or the code ENFORCES that
-- dependency. If a workerId ever reported two regions, folding it into the
-- dimension would silently rewrite the region of historical rows. A 4% space
-- win is not worth building on an invariant we do not enforce.
--
-- The ROLLUP TABLES stay text-keyed. That is the blast-radius decision of this
-- phase: metrics."runSecond"/"runSecondStatus"/"runLabel" keep runId/label/region
-- as TEXT, so **neither orchestrator's read paths change at all** — they never
-- learn that the fact table was re-encoded. The rollups are ~36 MB against the
-- fact table's gigabytes, so encoding them would buy nothing and cost four
-- repositories' worth of joins in two services that must stay in parity.
-- rebuildRunRollups (below) joins the dimensions so the rollups keep their text.
--
-- ── New failure mode this phase introduces, and how it is bounded ───
-- The consumer must now resolve text → key before it can insert, which means it
-- can INSERT into a dimension. At run start, 20 workers all meet the same ~200
-- brand-new labels at once, so they contend on the same dictionary rows. Two
-- properties keep that bounded, and both live in the CONSUMER, not here:
--   1. resolution happens in its own SHORT transaction, before the metrics
--      INSERT — so contention is scoped to a tiny statement instead of holding
--      the whole ingest transaction open;
--   2. dimensions are APPEND-ONLY and keys are IMMUTABLE, so a cached mapping
--      can never go stale, and a dictionary row orphaned by a failed metrics
--      insert is harmless (it is just an unused id).
-- See DictionaryResolver in jmeter-metrics-consumer.

-- ════════════════════════════════════════════════════════════════════
-- 1. The dimensions
-- ════════════════════════════════════════════════════════════════════
-- GENERATED ALWAYS AS IDENTITY, not serial: the key must never be supplied by a
-- client, and identity columns make that a hard error rather than a convention.
--
-- The UNIQUE constraint on the natural key is doing real work — it is what makes
-- the consumer's INSERT … ON CONFLICT DO NOTHING resolution safe under the
-- concurrent first-sight-of-a-label race described above.
CREATE TABLE metrics."runDict" (
    "runKey" INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    "runId"  TEXT    NOT NULL UNIQUE
);

CREATE TABLE metrics."workerDict" (
    "workerKey" INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    "workerId"  TEXT    NOT NULL UNIQUE
);

CREATE TABLE metrics."labelDict" (
    "labelId" INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    "label"   TEXT    NOT NULL UNIQUE
);

COMMENT ON TABLE metrics."runDict" IS
    'SCHEMA-OPT Phase 3 dimension: runId text ↔ runKey. Append-only; keys are immutable, which is what makes the consumer''s in-memory cache safe to never invalidate.';
COMMENT ON TABLE metrics."workerDict" IS
    'SCHEMA-OPT Phase 3 dimension: workerId text ↔ workerKey. Append-only.';
COMMENT ON TABLE metrics."labelDict" IS
    'SCHEMA-OPT Phase 3 dimension: JMeter sampler label ↔ labelId. Append-only and GLOBAL (not per-run), which is why the key is INTEGER — a SMALLINT would overflow after 32,767 distinct labels and stop ingest permanently.';

-- ── Seed from the data that already exists ──────────────────────────
-- Must happen before the copy below, and in one pass each: the fact table has
-- millions of rows but only thousands of distinct identities.
INSERT INTO metrics."runDict" ("runId")
SELECT DISTINCT "runId" FROM metrics."workerMetric";
INSERT INTO metrics."workerDict" ("workerId")
SELECT DISTINCT "workerId" FROM metrics."workerMetric";
INSERT INTO metrics."labelDict" ("label")
SELECT DISTINCT "label" FROM metrics."workerMetric";

DO $$
BEGIN
    RAISE NOTICE 'SCHEMA-OPT Phase 3: seeded % run(s), % worker(s), % label(s)',
        (SELECT count(*) FROM metrics."runDict"),
        (SELECT count(*) FROM metrics."workerDict"),
        (SELECT count(*) FROM metrics."labelDict");
END $$;

-- The consumer resolves by natural key on every cache miss, so those lookups
-- must be index-only. The UNIQUE constraints above already provide the index;
-- this is just the note that they are load-bearing for reads, not only for the
-- concurrency guarantee.

GRANT SELECT, INSERT ON metrics."runDict"    TO "metricsWriter";
GRANT SELECT, INSERT ON metrics."workerDict" TO "metricsWriter";
GRANT SELECT, INSERT ON metrics."labelDict"  TO "metricsWriter";
GRANT SELECT ON metrics."runDict"    TO "metricsReader";
GRANT SELECT ON metrics."workerDict" TO "metricsReader";
GRANT SELECT ON metrics."labelDict"  TO "metricsReader";
-- The purge resolves runId → runKey before deleting, and the dictionary sweeper
-- (in dropOldRollups, below) removes orphaned rows.
GRANT SELECT, DELETE ON metrics."runDict"    TO "metricsPurger";
GRANT SELECT ON metrics."workerDict" TO "metricsPurger";
GRANT SELECT ON metrics."labelDict"  TO "metricsPurger";

-- ════════════════════════════════════════════════════════════════════
-- 2. Free the fact table's names (same method as V17 — see its header)
-- ════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT c.relname
        FROM   pg_catalog.pg_class c
        JOIN   pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE  n.nspname = 'metrics'
          AND  c.relkind IN ('i', 'I')
          AND  c.relname LIKE 'workerMetric%'
    LOOP
        EXECUTE format('ALTER INDEX metrics.%I RENAME TO %I',
                       rec.relname, rec.relname || '_preP3');
    END LOOP;

    FOR rec IN
        SELECT c.relname
        FROM   pg_catalog.pg_class c
        JOIN   pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE  n.nspname = 'metrics'
          AND  c.relkind IN ('r', 'p')
          AND  c.relname LIKE 'workerMetric%'
    LOOP
        EXECUTE format('ALTER TABLE metrics.%I RENAME TO %I',
                       rec.relname, rec.relname || '_preP3');
    END LOOP;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- 3. The keyed shape
-- ════════════════════════════════════════════════════════════════════
-- Column order still matters exactly as much as it did in V17: 8-byte block,
-- then 4-byte block, then varlena. The three new keys join the 4-byte block —
-- 4 BIGINT (32 B) + 11 INTEGER (44 B) = 76 B, still zero padding, and the two
-- remaining varlena columns land on a 4-aligned offset.
--
-- No FOREIGN KEYs to the dimensions. Deliberate: an FK would make every one of
-- the ~154M inserts per run take a row-share lock on the parent dimension row,
-- which is exactly the hot contention this phase must avoid — and it would buy
-- nothing, because the only writer resolves the key from the dimension one
-- statement earlier. The purge's dictionary sweeper is what keeps them
-- consistent from the other direction.
CREATE TABLE metrics."workerMetric" (
    -- 8-byte, 8-aligned: 32 B
    "windowSecond"     BIGINT  NOT NULL,
    "sumElapsedMs"     BIGINT  NOT NULL,
    "bytesReceived"    BIGINT  NOT NULL,
    "bytesSent"        BIGINT  NOT NULL,
    -- 4-byte, 4-aligned: 44 B
    "runKey"           INTEGER NOT NULL,
    "workerKey"        INTEGER NOT NULL,
    "labelId"          INTEGER NOT NULL,
    "throughput"       INTEGER NOT NULL,
    "errorCount"       INTEGER NOT NULL,
    "p50Ms"            INTEGER NOT NULL,
    "p90Ms"            INTEGER NOT NULL,
    "p95Ms"            INTEGER NOT NULL,
    "p99Ms"            INTEGER NOT NULL,
    "maxMs"            INTEGER NOT NULL,
    "activeThreads"    INTEGER NOT NULL,
    -- varlena last
    "region"           TEXT    NOT NULL,
    "statusCodes"      JSONB   NOT NULL DEFAULT '{}'::jsonb,
    -- Same idempotency contract as before, expressed in keys. The partition key
    -- must be part of it under declarative partitioning.
    PRIMARY KEY ("runKey", "workerKey", "labelId", "windowSecond")
) PARTITION BY RANGE ("windowSecond");

COMMENT ON TABLE metrics."workerMetric" IS
    'Per-second per-(run, worker, label) metric rows, identity dictionary-encoded since SCHEMA-OPT Phase 3 — join metrics."runDict"/"workerDict"/"labelDict" to read it. Partitioned weekly on "windowSecond". The PK is still the producer→consumer idempotency contract, now in surrogate keys. Every orchestrator read goes through the (text-keyed) rollup tables; Grafana is the only reader of this table.';

CREATE INDEX "workerMetric_runKey_labelId_windowSecond_idx"
    ON metrics."workerMetric" ("runKey", "labelId", "windowSecond");

-- ════════════════════════════════════════════════════════════════════
-- 4. Partitions + copy
-- ════════════════════════════════════════════════════════════════════
DO $$
DECLARE
    rec   RECORD;
    built INTEGER := 0;
BEGIN
    FOR rec IN
        SELECT DISTINCT
               date_trunc('week', to_timestamp("windowSecond") AT TIME ZONE 'UTC')
                   AT TIME ZONE 'UTC' AS anchor
        FROM   metrics."workerMetric_preP3"
    LOOP
        PERFORM metrics."createWeeklyPartition"(rec.anchor);
        built := built + 1;
    END LOOP;
    RAISE NOTICE 'SCHEMA-OPT Phase 3: created % partition(s) to receive existing rows', built;
END $$;

SELECT metrics."ensureUpcomingPartitions"(8);

-- Lossless: every text value has a dictionary row (seeded above from this exact
-- table), so the joins cannot drop a row. An INNER JOIN is still the honest
-- choice — if one somehow did not match, losing the row loudly in a count check
-- beats silently writing a NULL key into a NOT NULL column.
INSERT INTO metrics."workerMetric" (
    "windowSecond", "sumElapsedMs", "bytesReceived", "bytesSent",
    "runKey", "workerKey", "labelId",
    "throughput", "errorCount", "p50Ms", "p90Ms", "p95Ms", "p99Ms",
    "maxMs", "activeThreads", "region", "statusCodes")
SELECT w."windowSecond", w."sumElapsedMs", w."bytesReceived", w."bytesSent",
       rd."runKey", wd."workerKey", ld."labelId",
       w."throughput", w."errorCount", w."p50Ms", w."p90Ms", w."p95Ms", w."p99Ms",
       w."maxMs", w."activeThreads", w."region", w."statusCodes"
FROM   metrics."workerMetric_preP3" w
JOIN   metrics."runDict"    rd ON rd."runId"    = w."runId"
JOIN   metrics."workerDict" wd ON wd."workerId" = w."workerId"
JOIN   metrics."labelDict"  ld ON ld."label"    = w."label";

-- Prove the copy was lossless before dropping the source. A mismatch here means
-- a join dropped rows, and the right response is to abort the migration with the
-- old table still intact rather than to discover it later.
DO $$
DECLARE
    before_rows BIGINT;
    after_rows  BIGINT;
BEGIN
    SELECT count(*) INTO before_rows FROM metrics."workerMetric_preP3";
    SELECT count(*) INTO after_rows  FROM metrics."workerMetric";
    IF before_rows <> after_rows THEN
        RAISE EXCEPTION
            'SCHEMA-OPT Phase 3 copy lost rows: % before, % after (a dictionary join did not match)',
            before_rows, after_rows;
    END IF;
    RAISE NOTICE 'SCHEMA-OPT Phase 3: re-encoded % row(s), none lost', after_rows;
END $$;

DROP TABLE metrics."workerMetric_preP3" CASCADE;

GRANT INSERT, SELECT ON metrics."workerMetric" TO "metricsWriter";
GRANT SELECT         ON metrics."workerMetric" TO "metricsReader";
GRANT SELECT, DELETE ON metrics."workerMetric" TO "metricsPurger";

-- ════════════════════════════════════════════════════════════════════
-- 5. rebuildRunRollups — joins the dimensions, still emits TEXT
-- ════════════════════════════════════════════════════════════════════
-- The signature does not change: callers still pass a runId, because the
-- rollups, the purge and the ITs all speak text. Only the inside knows about
-- keys. If the run has no dictionary row (a runId that never ingested), the
-- lookup yields NULL, every filter matches nothing, and the function correctly
-- rebuilds three empty rollups.
--
-- The percentile products keep their ::bigint casts for exactly the reason V17
-- documented: both operands are INTEGER and int*int overflows int4.
CREATE OR REPLACE FUNCTION metrics."rebuildRunRollups"(p_runId TEXT)
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE
    seconds_built BIGINT;
    v_runKey      INTEGER;
BEGIN
    SELECT "runKey" INTO v_runKey FROM metrics."runDict" WHERE "runId" = p_runId;

    DELETE FROM metrics."runSecond"       WHERE "runId" = p_runId;
    DELETE FROM metrics."runSecondStatus" WHERE "runId" = p_runId;
    DELETE FROM metrics."runLabel"        WHERE "runId" = p_runId;

    INSERT INTO metrics."runSecond" (
        "runId", "windowSecond", "region", "rowCount", "samples", "errors",
        "sumElapsedMs", "sumP50Weighted", "sumP90Weighted", "sumP95Weighted",
        "sumP99Weighted", "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent")
    SELECT p_runId, "windowSecond", "region",
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
    WHERE  "runKey" = v_runKey
    GROUP  BY "windowSecond", "region";

    INSERT INTO metrics."runSecondStatus" (
        "runId", "windowSecond", "region", "code", "n")
    SELECT p_runId, w."windowSecond", w."region", j.key,
           COALESCE(sum((j.value)::bigint), 0)::bigint
    FROM   metrics."workerMetric" w,
           LATERAL jsonb_each_text(w."statusCodes") AS j
    WHERE  w."runKey" = v_runKey
    GROUP  BY w."windowSecond", w."region", j.key;

    INSERT INTO metrics."runLabel" (
        "runId", "label", "rowCount", "samples", "errors", "sumElapsedMs",
        "sumP50", "sumP90", "sumP95", "sumP99",
        "sumP50Weighted", "sumP90Weighted", "sumP95Weighted", "sumP99Weighted",
        "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent",
        "firstSecond", "lastSecond")
    SELECT p_runId, ld."label",
           count(*),
           COALESCE(sum(w."throughput"), 0)::bigint,
           COALESCE(sum(w."errorCount"), 0)::bigint,
           COALESCE(sum(w."sumElapsedMs"), 0),
           COALESCE(sum(w."p50Ms"), 0),
           COALESCE(sum(w."p90Ms"), 0),
           COALESCE(sum(w."p95Ms"), 0),
           COALESCE(sum(w."p99Ms"), 0),
           COALESCE(sum(w."p50Ms"::bigint * w."throughput"), 0),
           COALESCE(sum(w."p90Ms"::bigint * w."throughput"), 0),
           COALESCE(sum(w."p95Ms"::bigint * w."throughput"), 0),
           COALESCE(sum(w."p99Ms"::bigint * w."throughput"), 0),
           COALESCE(max(w."maxMs"), 0),
           COALESCE(max(w."activeThreads"), 0)::bigint,
           COALESCE(sum(w."bytesReceived"), 0)::bigint,
           COALESCE(sum(w."bytesSent"), 0)::bigint,
           COALESCE(min(w."windowSecond"), 0)::bigint,
           COALESCE(max(w."windowSecond"), 0)::bigint
    FROM   metrics."workerMetric" w
    JOIN   metrics."labelDict" ld ON ld."labelId" = w."labelId"
    WHERE  w."runKey" = v_runKey
    GROUP  BY ld."label";

    SELECT count(*) INTO seconds_built
    FROM   metrics."runSecond" WHERE "runId" = p_runId;
    RETURN seconds_built;
END;
$$;

COMMENT ON FUNCTION metrics."rebuildRunRollups" IS
    'Recomputes all three rollup tables for one runId from metrics."workerMetric". Takes and emits TEXT — the dictionary encoding is internal. Idempotent (delete-then-rebuild). Returns the number of runSecond rows built.';

-- CREATE OR REPLACE keeps ownership and ACL but discards SECURITY DEFINER and
-- SET clauses (V17's header explains the consequence). Re-assert.
REVOKE EXECUTE ON FUNCTION metrics."rebuildRunRollups"(TEXT) FROM PUBLIC;
ALTER FUNCTION metrics."rebuildRunRollups"(TEXT)
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;
GRANT EXECUTE ON FUNCTION metrics."rebuildRunRollups"(TEXT) TO "metricsWriter";

-- ════════════════════════════════════════════════════════════════════
-- 6. Dictionary retention
-- ════════════════════════════════════════════════════════════════════
-- Without this, the dimensions become the one thing in this database that grows
-- forever — the exact failure the rollup sweeper was added to prevent in V16,
-- reintroduced one level down. runDict is the one that actually accumulates
-- (one row per run, ever); workerDict and labelDict are bounded by how many
-- distinct workers and endpoints the platform has ever seen, which is small and
-- genuinely reusable, so they are deliberately NOT swept — deleting a label id
-- that comes back tomorrow would just churn the id space.
--
-- A runDict row is removable only when nothing references it: no fact rows and
-- no rollup rows. Checked with NOT EXISTS rather than a join so the planner can
-- stop at the first match.
CREATE OR REPLACE FUNCTION metrics."dropOrphanedRunDict"()
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE
    removed BIGINT;
BEGIN
    DELETE FROM metrics."runDict" d
    WHERE NOT EXISTS (SELECT 1 FROM metrics."workerMetric" w WHERE w."runKey" = d."runKey")
      AND NOT EXISTS (SELECT 1 FROM metrics."runSecond"    r WHERE r."runId"  = d."runId")
      AND NOT EXISTS (SELECT 1 FROM metrics."runLabel"     l WHERE l."runId"  = d."runId");
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END;
$$;

COMMENT ON FUNCTION metrics."dropOrphanedRunDict" IS
    'Removes runDict rows no longer referenced by any fact or rollup row (i.e. whose run was purged or aged out). Called by the metrics-consumer PartitionMaintenanceJob. workerDict/labelDict are deliberately not swept — their entries are small, bounded and reused.';

REVOKE EXECUTE ON FUNCTION metrics."dropOrphanedRunDict"() FROM PUBLIC;
ALTER FUNCTION metrics."dropOrphanedRunDict"()
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;
GRANT EXECUTE ON FUNCTION metrics."dropOrphanedRunDict"() TO "metricsWriter";
