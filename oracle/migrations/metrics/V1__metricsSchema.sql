-- V1__metricsSchema.sql — the metrics schema, re-baselined for Oracle
-- (ORACLE-MIGRATION OM-2, 2026-08-28). Applied by the schema owner
-- (`metrics`); the application users only receive what is granted at the
-- bottom. Written to the 19c-compatible subset and validated on 23ai Free.
--
-- Two facts every reader of this file needs:
--   • Retention is a partition DROP. Every time-keyed table is INTERVAL-
--     partitioned by "windowSecond" (Unix epoch seconds) in 7-day buckets;
--     Oracle creates the partition on the first insert into a new week, so
--     there is no runway to maintain and nothing that can lapse.
--   • The rollups are exact deltas of rows that actually landed. Ingest and
--     rebuild share ONE aggregation (mergeStaged), fed from the staging
--     tables — see metricsIngest below for the exactly-once argument.
--
-- Identifiers are camelCase and double-quoted (unquoted folds to UPPER).
-- Every VARCHAR2 declares CHAR semantics: the instance default is BYTE.

-- ═══════════════════════════════════════════════════════════════════════
-- Raw fact tables — one row per (run, worker, label, second)
-- ═══════════════════════════════════════════════════════════════════════

-- The primary key is a LOCAL index: it contains the partition key, so a
-- partition drop takes its index slice with it and no global index ever
-- goes UNUSABLE. Lookups by "runId" alone probe every partition's slice
-- (~52/yr at full retention) — acceptable for rebuild and purge, the only
-- readers of this table.
CREATE TABLE metrics."workerMetric" (
    "runId"          VARCHAR2(64 CHAR)  NOT NULL,
    "workerId"       VARCHAR2(64 CHAR)  NOT NULL,
    "label"          VARCHAR2(255 CHAR) NOT NULL,
    "windowSecond"   NUMBER(10)         NOT NULL,
    "region"         VARCHAR2(64 CHAR)  NOT NULL,
    "throughput"     NUMBER(10)         NOT NULL,   -- samples in this second
    "errorCount"     NUMBER(10)         NOT NULL,
    "sumElapsedMs"   NUMBER(19)         NOT NULL,   -- exact Σ elapsed ms; divide by throughput at the end
    "p50Ms"          NUMBER(10)         NOT NULL,
    "p90Ms"          NUMBER(10)         NOT NULL,
    "p95Ms"          NUMBER(10)         NOT NULL,
    "p99Ms"          NUMBER(10)         NOT NULL,
    "maxMs"          NUMBER(10)         NOT NULL,   -- the exact slowest sample, not a histogram bucket edge
    "activeThreads"  NUMBER(10)         NOT NULL,
    "bytesReceived"  NUMBER(19)         NOT NULL,
    "bytesSent"      NUMBER(19)         NOT NULL,
    CONSTRAINT "workerMetric_pk"
        PRIMARY KEY ("runId", "workerId", "label", "windowSecond") USING INDEX LOCAL,
    -- Backstops for the consumer's edge validation. A violation here is a
    -- rejected batch that the worker would replay forever, so the consumer
    -- must answer 400 for the same conditions before the rows get this far.
    CONSTRAINT "workerMetric_window_chk"  CHECK ("windowSecond" > 0),
    CONSTRAINT "workerMetric_counts_chk"  CHECK ("throughput" >= 0 AND "errorCount" >= 0 AND "errorCount" <= "throughput"),
    CONSTRAINT "workerMetric_nonneg_chk"  CHECK ("sumElapsedMs" >= 0 AND "p50Ms" >= 0 AND "p90Ms" >= 0
                                                 AND "p95Ms" >= 0 AND "p99Ms" >= 0 AND "maxMs" >= 0
                                                 AND "activeThreads" >= 0 AND "bytesReceived" >= 0 AND "bytesSent" >= 0)
)
PARTITION BY RANGE ("windowSecond") INTERVAL (604800)
    (PARTITION "p0" VALUES LESS THAN (0));

COMMENT ON TABLE metrics."workerMetric" IS
    'Per-second per-(runId, workerId, label) samples. Interval-partitioned weekly on "windowSecond"; the PK is the ingest idempotency key. Read only by rebuildRunRollups and the run purge — every orchestrator read uses the rollups.';

-- Status codes, one row per code — the JSON map the wire carries is
-- unrolled at the edge so no JSON function runs on the hot path and the
-- rebuild is a plain GROUP BY. "region" is denormalised in so the rollup
-- MERGE needs no join. No foreign key to "workerMetric": the staging prune
-- keeps the two in step, and an FK across two interval-partitioned tables
-- would make every partition drop a two-step dance.
CREATE TABLE metrics."workerMetricStatus" (
    "runId"          VARCHAR2(64 CHAR)  NOT NULL,
    "workerId"       VARCHAR2(64 CHAR)  NOT NULL,
    "label"          VARCHAR2(255 CHAR) NOT NULL,
    "windowSecond"   NUMBER(10)         NOT NULL,
    "region"         VARCHAR2(64 CHAR)  NOT NULL,
    "code"           VARCHAR2(128 CHAR) NOT NULL,   -- JMeter responseCode verbatim ("200", "Non HTTP response code: …")
    "n"              NUMBER(10)         NOT NULL,
    CONSTRAINT "workerMetricStatus_pk"
        PRIMARY KEY ("runId", "workerId", "label", "windowSecond", "code") USING INDEX LOCAL,
    CONSTRAINT "workerMetricStatus_n_chk" CHECK ("n" > 0)
)
PARTITION BY RANGE ("windowSecond") INTERVAL (604800)
    (PARTITION "p0" VALUES LESS THAN (0));

COMMENT ON TABLE metrics."workerMetricStatus" IS
    'Per-second per-(runId, workerId, label) response-code counts, one row per code. Same partitioning and lifetime as "workerMetric".';

-- ═══════════════════════════════════════════════════════════════════════
-- Rollups — what every orchestrator read actually queries
-- ═══════════════════════════════════════════════════════════════════════
-- Every column is a component SUM (or a MAX/MIN), never a ratio: readers
-- fold across regions, labels and seconds and divide at the very end.
-- NUMBER arithmetic is exact, so the sums are exact.

CREATE TABLE metrics."runSecond" (
    "runId"            VARCHAR2(64 CHAR) NOT NULL,
    "windowSecond"     NUMBER(10)        NOT NULL,
    "region"           VARCHAR2(64 CHAR) NOT NULL,
    "rowCount"         NUMBER(10)        NOT NULL,   -- raw rows folded in
    "samples"          NUMBER(19)        NOT NULL,   -- Σ throughput
    "errors"           NUMBER(19)        NOT NULL,   -- Σ errorCount
    "sumElapsedMs"     NUMBER(19)        NOT NULL,   -- Σ sumElapsedMs
    "sumP50Weighted"   NUMBER(19)        NOT NULL,   -- Σ p50Ms × throughput
    "sumP90Weighted"   NUMBER(19)        NOT NULL,
    "sumP95Weighted"   NUMBER(19)        NOT NULL,
    "sumP99Weighted"   NUMBER(19)        NOT NULL,
    "maxMs"            NUMBER(10)        NOT NULL,   -- MAX
    "maxActiveThreads" NUMBER(10)        NOT NULL,   -- MAX
    "bytesReceived"    NUMBER(19)        NOT NULL,
    "bytesSent"        NUMBER(19)        NOT NULL,
    CONSTRAINT "runSecond_pk"
        PRIMARY KEY ("runId", "windowSecond", "region") USING INDEX LOCAL
)
PARTITION BY RANGE ("windowSecond") INTERVAL (604800)
    (PARTITION "p0" VALUES LESS THAN (0));

COMMENT ON TABLE metrics."runSecond" IS
    'One row per (runId, windowSecond, region) folding every worker and label. Component sums only. Maintained as exactly-once deltas by metricsIngest.ingestStaged; rebuilt by metricsIngest.rebuildRunRollups.';

CREATE TABLE metrics."runSecondStatus" (
    "runId"          VARCHAR2(64 CHAR)  NOT NULL,
    "windowSecond"   NUMBER(10)         NOT NULL,
    "region"         VARCHAR2(64 CHAR)  NOT NULL,
    "code"           VARCHAR2(128 CHAR) NOT NULL,
    "n"              NUMBER(19)         NOT NULL,
    CONSTRAINT "runSecondStatus_pk"
        PRIMARY KEY ("runId", "windowSecond", "region", "code") USING INDEX LOCAL
)
PARTITION BY RANGE ("windowSecond") INTERVAL (604800)
    (PARTITION "p0" VALUES LESS THAN (0));

COMMENT ON TABLE metrics."runSecondStatus" IS
    'Per-(runId, windowSecond, region, code) sample counts. Exact codes preserved.';

-- Whole-run per-label totals. Carries BOTH the unweighted and the
-- throughput-weighted percentile numerators so the aggregate report and
-- the per-label rollup reproduce their pre-rollup numbers exactly; the
-- second bounds are what lets the run purge prune its DELETE to the
-- partitions a run actually touched.
CREATE TABLE metrics."runLabel" (
    "runId"            VARCHAR2(64 CHAR)  NOT NULL,
    "label"            VARCHAR2(255 CHAR) NOT NULL,
    "rowCount"         NUMBER(19)         NOT NULL,
    "samples"          NUMBER(19)         NOT NULL,
    "errors"           NUMBER(19)         NOT NULL,
    "sumElapsedMs"     NUMBER(19)         NOT NULL,
    "sumP50"           NUMBER(19)         NOT NULL,   -- Σ p50Ms (unweighted)
    "sumP90"           NUMBER(19)         NOT NULL,
    "sumP95"           NUMBER(19)         NOT NULL,
    "sumP99"           NUMBER(19)         NOT NULL,
    "sumP50Weighted"   NUMBER(19)         NOT NULL,   -- Σ p50Ms × throughput
    "sumP90Weighted"   NUMBER(19)         NOT NULL,
    "sumP95Weighted"   NUMBER(19)         NOT NULL,
    "sumP99Weighted"   NUMBER(19)         NOT NULL,
    "maxMs"            NUMBER(10)         NOT NULL,
    "maxActiveThreads" NUMBER(10)         NOT NULL,
    "bytesReceived"    NUMBER(19)         NOT NULL,
    "bytesSent"        NUMBER(19)         NOT NULL,
    "firstSecond"      NUMBER(10)         NOT NULL,   -- MIN windowSecond
    "lastSecond"       NUMBER(10)         NOT NULL,   -- MAX windowSecond
    CONSTRAINT "runLabel_pk" PRIMARY KEY ("runId", "label")
);

-- Drives the retention DELETE (this table has no time partition key).
CREATE INDEX metrics."runLabel_lastSecond_idx" ON metrics."runLabel" ("lastSecond");

COMMENT ON TABLE metrics."runLabel" IS
    'One row per (runId, label) for the whole run: totals, both percentile numerators, and the windowSecond bounds the purge uses to prune. Unpartitioned; retention is a DELETE on "lastSecond".';

-- ═══════════════════════════════════════════════════════════════════════
-- Staging — session-private, cleared on commit or rollback
-- ═══════════════════════════════════════════════════════════════════════
-- The consumer batch-inserts a chunk here (one JDBC round-trip), then
-- calls ingestStaged. Same column list as the raw tables so the rebuild
-- can stage straight from them.

CREATE GLOBAL TEMPORARY TABLE metrics."workerMetricStage" (
    "runId"          VARCHAR2(64 CHAR)  NOT NULL,
    "workerId"       VARCHAR2(64 CHAR)  NOT NULL,
    "label"          VARCHAR2(255 CHAR) NOT NULL,
    "windowSecond"   NUMBER(10)         NOT NULL,
    "region"         VARCHAR2(64 CHAR)  NOT NULL,
    "throughput"     NUMBER(10)         NOT NULL,
    "errorCount"     NUMBER(10)         NOT NULL,
    "sumElapsedMs"   NUMBER(19)         NOT NULL,
    "p50Ms"          NUMBER(10)         NOT NULL,
    "p90Ms"          NUMBER(10)         NOT NULL,
    "p95Ms"          NUMBER(10)         NOT NULL,
    "p99Ms"          NUMBER(10)         NOT NULL,
    "maxMs"          NUMBER(10)         NOT NULL,
    "activeThreads"  NUMBER(10)         NOT NULL,
    "bytesReceived"  NUMBER(19)         NOT NULL,
    "bytesSent"      NUMBER(19)         NOT NULL
) ON COMMIT DELETE ROWS;

CREATE GLOBAL TEMPORARY TABLE metrics."workerMetricStatusStage" (
    "runId"          VARCHAR2(64 CHAR)  NOT NULL,
    "workerId"       VARCHAR2(64 CHAR)  NOT NULL,
    "label"          VARCHAR2(255 CHAR) NOT NULL,
    "windowSecond"   NUMBER(10)         NOT NULL,
    "region"         VARCHAR2(64 CHAR)  NOT NULL,
    "code"           VARCHAR2(128 CHAR) NOT NULL,
    "n"              NUMBER(10)         NOT NULL
) ON COMMIT DELETE ROWS;

-- One row, locked FOR UPDATE SKIP LOCKED by whichever consumer replica
-- runs retention; transaction-scoped, needs no DBMS_LOCK grant.
CREATE TABLE metrics."maintenanceLock" (
    "name" VARCHAR2(32 CHAR) NOT NULL,
    CONSTRAINT "maintenanceLock_pk" PRIMARY KEY ("name")
);
INSERT INTO metrics."maintenanceLock" ("name") VALUES ('retention');

-- ═══════════════════════════════════════════════════════════════════════
-- metricsIngest — exactly-once ingest and the rebuild that shares its code
-- ═══════════════════════════════════════════════════════════════════════

CREATE OR REPLACE PACKAGE metrics."metricsIngest" AUTHID DEFINER AS

    -- Lands the caller's staged chunk (one row per primary key): rows
    -- already present (a worker replaying its disk buffer) are pruned from
    -- the stage, the rest are inserted, and the rollup deltas are merged
    -- from exactly the rows that were inserted. p_landed is that row count.
    -- A duplicate that a concurrent replica inserts between the prune and
    -- the insert raises ORA-00001 and the whole transaction rolls back —
    -- that abort is the mechanism, so never hint IGNORE_ROW_ON_DUPKEY_INDEX
    -- on the insert.
    PROCEDURE "ingestStaged"(p_landed OUT NUMBER);

    -- Deletes and recomputes all three rollups for one run from the raw
    -- tables, through the same MERGE the ingest path uses. p_seconds is
    -- the number of "runSecond" rows built. Refuses (ORA-20002) when the raw
    -- rows no longer cover the run's rollup window — raw retention is shorter
    -- than rollup retention, and a rebuild from partial raw would silently
    -- shrink a run that the rollups still describe in full.
    PROCEDURE "rebuildRunRollups"(p_runId IN VARCHAR2, p_seconds OUT NUMBER);

END "metricsIngest";
/

CREATE OR REPLACE PACKAGE BODY metrics."metricsIngest" AS

    PROCEDURE "clearStage" IS
    BEGIN
        DELETE FROM metrics."workerMetricStage";
        DELETE FROM metrics."workerMetricStatusStage";
    END "clearStage";

    -- The one aggregation. Ingest feeds it the rows it just inserted;
    -- rebuild feeds it a run's raw rows. There is no second copy to drift.
    --
    -- MERGE is not atomic against a concurrent inserter: two chunks for the
    -- same second both find no rollup row, both take the INSERT branch, and
    -- the loser raises ORA-00001 once the winner commits (1.1 % of ingest
    -- POSTs at 8 workers). One retry policy covers all three MERGEs: roll
    -- back to the savepoint so no partial delta survives, then merge again —
    -- the row is committed now, so the retry matches and updates.
    PROCEDURE "mergeStaged" IS
    BEGIN
      FOR attempt IN 1 .. 8 LOOP
        SAVEPOINT "beforeRollups";
        BEGIN
        MERGE INTO metrics."runSecond" t
        USING (
            SELECT "runId", "windowSecond", "region",
                   COUNT(*)                        AS "rowCount",
                   SUM("throughput")               AS "samples",
                   SUM("errorCount")               AS "errors",
                   SUM("sumElapsedMs")             AS "sumElapsedMs",
                   SUM("p50Ms" * "throughput")     AS "sumP50Weighted",
                   SUM("p90Ms" * "throughput")     AS "sumP90Weighted",
                   SUM("p95Ms" * "throughput")     AS "sumP95Weighted",
                   SUM("p99Ms" * "throughput")     AS "sumP99Weighted",
                   MAX("maxMs")                    AS "maxMs",
                   MAX("activeThreads")            AS "maxActiveThreads",
                   SUM("bytesReceived")            AS "bytesReceived",
                   SUM("bytesSent")                AS "bytesSent"
            FROM   metrics."workerMetricStage"
            GROUP  BY "runId", "windowSecond", "region"
        ) s
        ON (t."runId" = s."runId" AND t."windowSecond" = s."windowSecond" AND t."region" = s."region")
        WHEN MATCHED THEN UPDATE SET
            t."rowCount"         = t."rowCount"         + s."rowCount",
            t."samples"          = t."samples"          + s."samples",
            t."errors"           = t."errors"           + s."errors",
            t."sumElapsedMs"     = t."sumElapsedMs"     + s."sumElapsedMs",
            t."sumP50Weighted"   = t."sumP50Weighted"   + s."sumP50Weighted",
            t."sumP90Weighted"   = t."sumP90Weighted"   + s."sumP90Weighted",
            t."sumP95Weighted"   = t."sumP95Weighted"   + s."sumP95Weighted",
            t."sumP99Weighted"   = t."sumP99Weighted"   + s."sumP99Weighted",
            t."maxMs"            = GREATEST(t."maxMs", s."maxMs"),
            t."maxActiveThreads" = GREATEST(t."maxActiveThreads", s."maxActiveThreads"),
            t."bytesReceived"    = t."bytesReceived"    + s."bytesReceived",
            t."bytesSent"        = t."bytesSent"        + s."bytesSent"
        WHEN NOT MATCHED THEN INSERT (
            "runId", "windowSecond", "region", "rowCount", "samples", "errors",
            "sumElapsedMs", "sumP50Weighted", "sumP90Weighted", "sumP95Weighted",
            "sumP99Weighted", "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent")
        VALUES (
            s."runId", s."windowSecond", s."region", s."rowCount", s."samples", s."errors",
            s."sumElapsedMs", s."sumP50Weighted", s."sumP90Weighted", s."sumP95Weighted",
            s."sumP99Weighted", s."maxMs", s."maxActiveThreads", s."bytesReceived", s."bytesSent");

        MERGE INTO metrics."runSecondStatus" t
        USING (
            SELECT "runId", "windowSecond", "region", "code", SUM("n") AS "n"
            FROM   metrics."workerMetricStatusStage"
            GROUP  BY "runId", "windowSecond", "region", "code"
        ) s
        ON (t."runId" = s."runId" AND t."windowSecond" = s."windowSecond"
            AND t."region" = s."region" AND t."code" = s."code")
        WHEN MATCHED THEN UPDATE SET t."n" = t."n" + s."n"
        WHEN NOT MATCHED THEN INSERT ("runId", "windowSecond", "region", "code", "n")
        VALUES (s."runId", s."windowSecond", s."region", s."code", s."n");

        MERGE INTO metrics."runLabel" t
        USING (
            SELECT "runId", "label",
                   COUNT(*)                        AS "rowCount",
                   SUM("throughput")               AS "samples",
                   SUM("errorCount")               AS "errors",
                   SUM("sumElapsedMs")             AS "sumElapsedMs",
                   SUM("p50Ms")                    AS "sumP50",
                   SUM("p90Ms")                    AS "sumP90",
                   SUM("p95Ms")                    AS "sumP95",
                   SUM("p99Ms")                    AS "sumP99",
                   SUM("p50Ms" * "throughput")     AS "sumP50Weighted",
                   SUM("p90Ms" * "throughput")     AS "sumP90Weighted",
                   SUM("p95Ms" * "throughput")     AS "sumP95Weighted",
                   SUM("p99Ms" * "throughput")     AS "sumP99Weighted",
                   MAX("maxMs")                    AS "maxMs",
                   MAX("activeThreads")            AS "maxActiveThreads",
                   SUM("bytesReceived")            AS "bytesReceived",
                   SUM("bytesSent")                AS "bytesSent",
                   MIN("windowSecond")             AS "firstSecond",
                   MAX("windowSecond")             AS "lastSecond"
            FROM   metrics."workerMetricStage"
            GROUP  BY "runId", "label"
        ) s
        ON (t."runId" = s."runId" AND t."label" = s."label")
        WHEN MATCHED THEN UPDATE SET
            t."rowCount"         = t."rowCount"         + s."rowCount",
            t."samples"          = t."samples"          + s."samples",
            t."errors"           = t."errors"           + s."errors",
            t."sumElapsedMs"     = t."sumElapsedMs"     + s."sumElapsedMs",
            t."sumP50"           = t."sumP50"           + s."sumP50",
            t."sumP90"           = t."sumP90"           + s."sumP90",
            t."sumP95"           = t."sumP95"           + s."sumP95",
            t."sumP99"           = t."sumP99"           + s."sumP99",
            t."sumP50Weighted"   = t."sumP50Weighted"   + s."sumP50Weighted",
            t."sumP90Weighted"   = t."sumP90Weighted"   + s."sumP90Weighted",
            t."sumP95Weighted"   = t."sumP95Weighted"   + s."sumP95Weighted",
            t."sumP99Weighted"   = t."sumP99Weighted"   + s."sumP99Weighted",
            t."maxMs"            = GREATEST(t."maxMs", s."maxMs"),
            t."maxActiveThreads" = GREATEST(t."maxActiveThreads", s."maxActiveThreads"),
            t."bytesReceived"    = t."bytesReceived"    + s."bytesReceived",
            t."bytesSent"        = t."bytesSent"        + s."bytesSent",
            t."firstSecond"      = LEAST(t."firstSecond", s."firstSecond"),
            t."lastSecond"       = GREATEST(t."lastSecond", s."lastSecond")
        WHEN NOT MATCHED THEN INSERT (
            "runId", "label", "rowCount", "samples", "errors", "sumElapsedMs",
            "sumP50", "sumP90", "sumP95", "sumP99",
            "sumP50Weighted", "sumP90Weighted", "sumP95Weighted", "sumP99Weighted",
            "maxMs", "maxActiveThreads", "bytesReceived", "bytesSent",
            "firstSecond", "lastSecond")
        VALUES (
            s."runId", s."label", s."rowCount", s."samples", s."errors", s."sumElapsedMs",
            s."sumP50", s."sumP90", s."sumP95", s."sumP99",
            s."sumP50Weighted", s."sumP90Weighted", s."sumP95Weighted", s."sumP99Weighted",
            s."maxMs", s."maxActiveThreads", s."bytesReceived", s."bytesSent",
            s."firstSecond", s."lastSecond");
        EXIT;
        EXCEPTION
            WHEN DUP_VAL_ON_INDEX THEN
                ROLLBACK TO SAVEPOINT "beforeRollups";
                IF attempt = 8 THEN RAISE; END IF;
        END;
      END LOOP;
    END "mergeStaged";

    PROCEDURE "ingestStaged"(p_landed OUT NUMBER) IS
    BEGIN
        -- The caller stages one row per primary key (WorkerMetricWriter
        -- de-duplicates before staging); a duplicate here would fail the
        -- insert below and become a 503 the worker replays.

        -- 1. Replays: anything already landed leaves the stage, and its
        --    status rows with it.
        DELETE FROM metrics."workerMetricStage" s
        WHERE  EXISTS (SELECT 1 FROM metrics."workerMetric" w
                       WHERE w."runId" = s."runId" AND w."workerId" = s."workerId"
                         AND w."label" = s."label" AND w."windowSecond" = s."windowSecond");
        DELETE FROM metrics."workerMetricStatusStage" ss
        WHERE  NOT EXISTS (SELECT 1 FROM metrics."workerMetricStage" s
                           WHERE s."runId" = ss."runId" AND s."workerId" = ss."workerId"
                             AND s."label" = ss."label" AND s."windowSecond" = ss."windowSecond");

        -- 2. Land what is left. ORA-00001 here means a concurrent replica
        --    won the race for a key: the transaction rolls back, the caller
        --    answers 503, the worker replays, and step 1 drops the row.
        INSERT INTO metrics."workerMetric" (
            "runId", "workerId", "label", "windowSecond", "region",
            "throughput", "errorCount", "sumElapsedMs",
            "p50Ms", "p90Ms", "p95Ms", "p99Ms", "maxMs", "activeThreads",
            "bytesReceived", "bytesSent")
        SELECT "runId", "workerId", "label", "windowSecond", "region",
               "throughput", "errorCount", "sumElapsedMs",
               "p50Ms", "p90Ms", "p95Ms", "p99Ms", "maxMs", "activeThreads",
               "bytesReceived", "bytesSent"
        FROM   metrics."workerMetricStage";
        p_landed := SQL%ROWCOUNT;

        INSERT INTO metrics."workerMetricStatus" (
            "runId", "workerId", "label", "windowSecond", "region", "code", "n")
        SELECT "runId", "workerId", "label", "windowSecond", "region", "code", "n"
        FROM   metrics."workerMetricStatusStage";

        -- 3. Deltas from exactly the rows that landed. The stage tables are
        --    ON COMMIT DELETE ROWS; the caller commits right after this call.
        "mergeStaged";
    END "ingestStaged";

    PROCEDURE "rebuildRunRollups"(p_runId IN VARCHAR2, p_seconds OUT NUMBER) IS
        v_rawRows    NUMBER;
        v_rawFirst   NUMBER;
        v_rawLast    NUMBER;
        v_first      NUMBER;
        v_last       NUMBER;
    BEGIN
        -- Raw retention (days) is shorter than rollup retention (weeks): once
        -- the raw rows are gone or partial, the rollups are the only complete
        -- record and must not be rebuilt from what is left.
        SELECT COUNT(*), MIN("windowSecond"), MAX("windowSecond")
          INTO v_rawRows, v_rawFirst, v_rawLast
          FROM metrics."workerMetric" WHERE "runId" = p_runId;
        SELECT MIN("firstSecond"), MAX("lastSecond")
          INTO v_first, v_last
          FROM metrics."runLabel" WHERE "runId" = p_runId;
        IF v_first IS NOT NULL AND (v_rawRows = 0 OR v_rawFirst > v_first OR v_rawLast < v_last) THEN
            RAISE_APPLICATION_ERROR(-20002,
                'rebuildRunRollups refused for ' || p_runId
                || ': raw rows no longer cover the rollup window ('
                || NVL(TO_CHAR(v_rawFirst), 'none') || '..' || NVL(TO_CHAR(v_rawLast), 'none')
                || ' vs ' || v_first || '..' || v_last || ') — raw retention is shorter than rollup retention');
        END IF;

        "clearStage";
        DELETE FROM metrics."runSecond"       WHERE "runId" = p_runId;
        DELETE FROM metrics."runSecondStatus" WHERE "runId" = p_runId;
        DELETE FROM metrics."runLabel"        WHERE "runId" = p_runId;

        INSERT INTO metrics."workerMetricStage" (
            "runId", "workerId", "label", "windowSecond", "region",
            "throughput", "errorCount", "sumElapsedMs",
            "p50Ms", "p90Ms", "p95Ms", "p99Ms", "maxMs", "activeThreads",
            "bytesReceived", "bytesSent")
        SELECT "runId", "workerId", "label", "windowSecond", "region",
               "throughput", "errorCount", "sumElapsedMs",
               "p50Ms", "p90Ms", "p95Ms", "p99Ms", "maxMs", "activeThreads",
               "bytesReceived", "bytesSent"
        FROM   metrics."workerMetric"
        WHERE  "runId" = p_runId;

        INSERT INTO metrics."workerMetricStatusStage" (
            "runId", "workerId", "label", "windowSecond", "region", "code", "n")
        SELECT "runId", "workerId", "label", "windowSecond", "region", "code", "n"
        FROM   metrics."workerMetricStatus"
        WHERE  "runId" = p_runId;

        "mergeStaged";
        "clearStage";

        SELECT COUNT(*) INTO p_seconds FROM metrics."runSecond" WHERE "runId" = p_runId;
    END "rebuildRunRollups";

END "metricsIngest";
/

-- ═══════════════════════════════════════════════════════════════════════
-- metricsRetention — partition drops, run by one consumer replica at a time
-- ═══════════════════════════════════════════════════════════════════════

CREATE OR REPLACE PACKAGE metrics."metricsRetention" AUTHID DEFINER AS

    -- Drops every interval partition of the named table whose upper bound
    -- is at or below p_cutoffSecond. Returns the dropped partition names
    -- comma-joined, NULL when none. Runs as an AUTONOMOUS transaction so
    -- the DDL's implicit commit cannot release the caller's lock on
    -- "maintenanceLock" mid-pass.
    FUNCTION "dropPartitionsBefore"(p_table IN VARCHAR2, p_cutoffSecond IN NUMBER)
        RETURN VARCHAR2;

    -- Raw tables: keep p_keepDays of "workerMetric" + "workerMetricStatus".
    PROCEDURE "dropOldRaw"(p_keepDays IN NUMBER, p_dropped OUT VARCHAR2);

    -- Rollups: keep p_keepWeeks of "runSecond" + "runSecondStatus" (partition
    -- drops) and of "runLabel" (a DELETE on "lastSecond", in the caller's
    -- transaction; p_runLabelRows is its row count).
    PROCEDURE "dropOldRollups"(p_keepWeeks IN NUMBER, p_dropped OUT VARCHAR2,
                               p_runLabelRows OUT NUMBER);

    -- Current UTC time as Unix epoch seconds.
    FUNCTION "epochNow" RETURN NUMBER;

END "metricsRetention";
/

CREATE OR REPLACE PACKAGE BODY metrics."metricsRetention" AS

    FUNCTION "epochNow" RETURN NUMBER IS
    BEGIN
        RETURN ROUND((CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE) - DATE '1970-01-01') * 86400);
    END "epochNow";

    FUNCTION "dropPartitionsBefore"(p_table IN VARCHAR2, p_cutoffSecond IN NUMBER)
        RETURN VARCHAR2 IS
        PRAGMA AUTONOMOUS_TRANSACTION;
        v_dropped VARCHAR2(4000);
        v_high    NUMBER;
    BEGIN
        IF p_table NOT IN ('workerMetric', 'workerMetricStatus', 'runSecond', 'runSecondStatus') THEN
            RAISE_APPLICATION_ERROR(-20001, 'not a retention-managed table: ' || p_table);
        END IF;
        -- HIGH_VALUE is a LONG; fetching it through a cursor record converts
        -- it to VARCHAR2, which TO_NUMBER can read. Interval partitions carry
        -- system names (SYS_P…), so they are found by bound, never by name.
        -- "p0" (bound 0) is the anchor and can never be dropped.
        FOR rec IN (SELECT partition_name, high_value
                    FROM   user_tab_partitions
                    WHERE  table_name = p_table
                    ORDER  BY partition_position) LOOP
            v_high := TO_NUMBER(rec.high_value);
            IF v_high > 0 AND v_high <= p_cutoffSecond THEN
                EXECUTE IMMEDIATE 'ALTER TABLE metrics."' || p_table || '" DROP PARTITION "'
                                  || rec.partition_name || '"';
                v_dropped := CASE WHEN v_dropped IS NULL THEN rec.partition_name
                                  ELSE v_dropped || ',' || rec.partition_name END;
            END IF;
        END LOOP;
        COMMIT;   -- an autonomous transaction must end explicitly even when no DDL ran
        RETURN v_dropped;
    END "dropPartitionsBefore";

    PROCEDURE "dropOldRaw"(p_keepDays IN NUMBER, p_dropped OUT VARCHAR2) IS
        v_cutoff NUMBER := "epochNow" - p_keepDays * 86400;
        v_a      VARCHAR2(4000);
        v_b      VARCHAR2(4000);
    BEGIN
        v_a := "dropPartitionsBefore"('workerMetric',       v_cutoff);
        v_b := "dropPartitionsBefore"('workerMetricStatus', v_cutoff);
        p_dropped := CASE WHEN v_a IS NULL THEN v_b WHEN v_b IS NULL THEN v_a ELSE v_a || ',' || v_b END;
    END "dropOldRaw";

    PROCEDURE "dropOldRollups"(p_keepWeeks IN NUMBER, p_dropped OUT VARCHAR2,
                               p_runLabelRows OUT NUMBER) IS
        v_cutoff NUMBER := "epochNow" - p_keepWeeks * 604800;
        v_a      VARCHAR2(4000);
        v_b      VARCHAR2(4000);
    BEGIN
        v_a := "dropPartitionsBefore"('runSecond',       v_cutoff);
        v_b := "dropPartitionsBefore"('runSecondStatus', v_cutoff);
        p_dropped := CASE WHEN v_a IS NULL THEN v_b WHEN v_b IS NULL THEN v_a ELSE v_a || ',' || v_b END;
        DELETE FROM metrics."runLabel" WHERE "lastSecond" < v_cutoff;
        p_runLabelRows := SQL%ROWCOUNT;
    END "dropOldRollups";

END "metricsRetention";
/

-- ═══════════════════════════════════════════════════════════════════════
-- Grants — least privilege; the packages run with the owner's rights
-- ═══════════════════════════════════════════════════════════════════════

-- metricsWriter (jmeter-metrics-consumer): stages rows, calls the packages,
-- takes the retention lock. It cannot touch the raw or rollup tables
-- directly — every write goes through metricsIngest.
GRANT INSERT ON metrics."workerMetricStage"       TO "metricsWriter";
GRANT INSERT ON metrics."workerMetricStatusStage" TO "metricsWriter";
GRANT SELECT, UPDATE ON metrics."maintenanceLock" TO "metricsWriter";
GRANT EXECUTE ON metrics."metricsIngest"          TO "metricsWriter";
GRANT EXECUTE ON metrics."metricsRetention"       TO "metricsWriter";

-- metricsReader (jmeter-global-orchestrator's read pool): the rollups are
-- what it queries; the raw tables are granted for operator diagnostics.
GRANT SELECT ON metrics."runSecond"          TO "metricsReader";
GRANT SELECT ON metrics."runSecondStatus"    TO "metricsReader";
GRANT SELECT ON metrics."runLabel"           TO "metricsReader";
GRANT SELECT ON metrics."workerMetric"       TO "metricsReader";
GRANT SELECT ON metrics."workerMetricStatus" TO "metricsReader";

-- metricsPurger (the orchestrator's purge pool only): removes one run's
-- rows from every table, pruning by the bounds in "runLabel".
GRANT SELECT, DELETE ON metrics."workerMetric"       TO "metricsPurger";
GRANT SELECT, DELETE ON metrics."workerMetricStatus" TO "metricsPurger";
GRANT SELECT, DELETE ON metrics."runSecond"          TO "metricsPurger";
GRANT SELECT, DELETE ON metrics."runSecondStatus"    TO "metricsPurger";
GRANT SELECT, DELETE ON metrics."runLabel"           TO "metricsPurger";
