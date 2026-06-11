-- V1__metricsSchema.sql — applied to the jmetercloud_metrics database.
--
-- Creates the partitioned metrics."workerMetric" table that the
-- jmeter-metrics-consumer writes per-second WorkerMetric records into.
-- Partition strategy is RANGE on "windowSecond" (Unix epoch second),
-- one partition per ISO week. The
-- idempotency key is ("runId", "workerId", "label", "windowSecond") —
-- that becomes the primary key here so duplicate Kafka deliveries land
-- as ON CONFLICT DO NOTHING no-ops.
--
-- Identifiers use the project-wide camelCase convention.
-- That requires Postgres-style double-quoting; unquoted identifiers
-- would be folded to lowercase and break the contract.

-- ── Schema ──────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS metrics;
GRANT USAGE ON SCHEMA metrics TO "metricsWriter", "metricsReader";

-- ── Parent partitioned table ────────────────────────────────────────
-- One row per ("runId", "workerId", "label", "windowSecond") tuple.
-- Columns map 1:1 to kafka/schemas/WorkerMetric.avsc; types chosen to
-- preserve precision (BIGINT for counters, DOUBLE PRECISION for the
-- HDRHistogram percentile outputs, JSONB for the statusCodes map).
CREATE TABLE metrics."workerMetric" (
    "runId"            TEXT             NOT NULL,
    "workerId"         TEXT             NOT NULL,
    "label"            TEXT             NOT NULL,
    "windowSecond"     BIGINT           NOT NULL,
    "windowTimestamp"  TEXT             NOT NULL,
    "region"           TEXT             NOT NULL,
    "throughput"       BIGINT           NOT NULL,
    "errorCount"       BIGINT           NOT NULL,
    "errorRate"        DOUBLE PRECISION NOT NULL,
    "p50Ms"            DOUBLE PRECISION NOT NULL,
    "p90Ms"            DOUBLE PRECISION NOT NULL,
    "p95Ms"            DOUBLE PRECISION NOT NULL,
    "p99Ms"            DOUBLE PRECISION NOT NULL,
    "minMs"            DOUBLE PRECISION NOT NULL,
    "maxMs"            DOUBLE PRECISION NOT NULL,
    "rawMaxMs"         BIGINT           NOT NULL,
    "bytesReceived"    BIGINT           NOT NULL,
    "bytesSent"        BIGINT           NOT NULL,
    "statusCodes"      JSONB            NOT NULL DEFAULT '{}'::jsonb,
    "activeThreads"    BIGINT           NOT NULL,
    -- Server-side write timestamp — useful for end-to-end latency
    -- (Kafka produce time → row visible in Postgres) telemetry. Not
    -- part of the Avro schema.
    "ingestedAt"       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- The partition key MUST be part of the primary key in declarative
    -- partitioning. Composite PK doubles as the idempotency contract.
    PRIMARY KEY ("runId", "workerId", "label", "windowSecond")
) PARTITION BY RANGE ("windowSecond");

COMMENT ON TABLE metrics."workerMetric" IS
    'Per-second per-(runId, workerId, label) metric rows. Partitioned weekly on "windowSecond" (Unix epoch). Primary key matches the producer→consumer idempotency contract; duplicate Kafka deliveries land as ON CONFLICT DO NOTHING.';

-- ── Indexes ─────────────────────────────────────────────────────────
-- The PK already covers ("runId", "workerId", "label", "windowSecond")
-- queries. Add a secondary index for the common analytical access
-- pattern: drill into a specific run's per-label timeseries across the
-- whole fleet.
CREATE INDEX "workerMetric_runId_label_windowSecond_idx"
    ON metrics."workerMetric" ("runId", "label", "windowSecond");

-- The Step 9 plan specified a "partial index on latest 4 weeks". With
-- declarative partitioning, partition pruning realizes the same
-- benefit: queries with WHERE "windowSecond" > <4-weeks-ago> only
-- touch the 4 most-recent partitions. No partial-index needed; PG14+
-- handles the pruning automatically. (A fixed-boundary partial index
-- would go stale in 4 weeks and require a periodic REINDEX.)

-- ── Partition management functions ──────────────────────────────────

-- Creates a single weekly partition for the ISO week containing the
-- given timestamp. Idempotent — if the partition already exists, no-op.
-- Naming: workerMetric_<isoYear>w<isoWeek>, e.g. workerMetric_2026w19.
CREATE OR REPLACE FUNCTION metrics."createWeeklyPartition"(p_anchor TIMESTAMPTZ)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    week_start_ts  TIMESTAMPTZ;
    week_end_ts    TIMESTAMPTZ;
    iso_year       INTEGER;
    iso_week       INTEGER;
    partition_name TEXT;
    from_epoch     BIGINT;
    to_epoch       BIGINT;
BEGIN
    -- ISO week starts on Monday 00:00:00 UTC.
    week_start_ts  := date_trunc('week', p_anchor AT TIME ZONE 'UTC')
                          AT TIME ZONE 'UTC';
    week_end_ts    := week_start_ts + INTERVAL '7 days';
    iso_year       := EXTRACT(isoyear FROM week_start_ts);
    iso_week       := EXTRACT(week    FROM week_start_ts);
    partition_name := format('workerMetric_%sw%s',
                              iso_year, lpad(iso_week::text, 2, '0'));
    from_epoch     := EXTRACT(epoch FROM week_start_ts)::BIGINT;
    to_epoch       := EXTRACT(epoch FROM week_end_ts)::BIGINT;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS metrics.%I '
        'PARTITION OF metrics."workerMetric" '
        'FOR VALUES FROM (%L) TO (%L)',
        partition_name, from_epoch, to_epoch);

    -- IMPORTANT: GRANT on the partitioned parent does NOT propagate to
    -- partitions in Postgres — privileges on partitions are tracked
    -- separately. Re-issue the parent's grants on each new child so the
    -- per-app users can access it. (Caught during the Step 11 metrics-
    -- consumer write IT — INSERT through the parent failed with
    -- "permission denied for table workerMetric_<isoWeek>".)
    --
    -- metricsWriter needs SELECT in addition to INSERT because the
    -- consumer's INSERT statement uses `ON CONFLICT … DO NOTHING`,
    -- which probes the unique-key index and therefore requires SELECT
    -- on the target partition.
    EXECUTE format(
        'GRANT INSERT, SELECT ON metrics.%I TO "metricsWriter"', partition_name);
    EXECUTE format(
        'GRANT SELECT ON metrics.%I TO "metricsReader"', partition_name);

    RETURN partition_name;
END;
$$;

COMMENT ON FUNCTION metrics."createWeeklyPartition" IS
    'Creates a weekly partition of metrics."workerMetric" for the ISO week containing the given timestamp. Idempotent.';

-- Pre-creates the next N weekly partitions, starting with this week.
-- Call from a periodic job (cron, K8s CronJob, pg_cron) to keep ahead
-- of incoming writes. Default N=8 — 2 months of headroom is comfortable
-- for any reasonable cron cadence.
CREATE OR REPLACE FUNCTION metrics."ensureUpcomingPartitions"(p_weeks INTEGER DEFAULT 8)
RETURNS SETOF TEXT LANGUAGE plpgsql AS $$
DECLARE
    i INTEGER;
BEGIN
    FOR i IN 0 .. (p_weeks - 1) LOOP
        RETURN NEXT metrics."createWeeklyPartition"(now() + (i || ' weeks')::INTERVAL);
    END LOOP;
END;
$$;

COMMENT ON FUNCTION metrics."ensureUpcomingPartitions" IS
    'Pre-creates the next N weekly partitions of metrics."workerMetric". Call periodically to keep ahead of new writes.';

-- Drops weekly partitions older than the configured retention window
-- (default 52 weeks). For longer retention, roll up to
-- per-minute aggregates in a separate table rather than keep raw rows.
CREATE OR REPLACE FUNCTION metrics."dropOldPartitions"(p_keep_weeks INTEGER DEFAULT 52)
RETURNS SETOF TEXT LANGUAGE plpgsql AS $$
DECLARE
    cutoff_epoch BIGINT;
    rec          RECORD;
BEGIN
    cutoff_epoch := EXTRACT(epoch FROM (now() - (p_keep_weeks || ' weeks')::INTERVAL))::BIGINT;

    FOR rec IN
        SELECT child.relname AS name
        FROM   pg_inherits
        JOIN   pg_class parent ON pg_inherits.inhparent = parent.oid
        JOIN   pg_class child  ON pg_inherits.inhrelid  = child.oid
        JOIN   pg_namespace ns ON parent.relnamespace   = ns.oid
        WHERE  ns.nspname     = 'metrics'
          AND  parent.relname = 'workerMetric'
    LOOP
        DECLARE
            upper_bound BIGINT;
            bound_text  TEXT;
        BEGIN
            -- Parse the FROM/TO of each child partition. pg_get_expr
            -- returns the human-readable bound; we extract the upper
            -- bound integer.
            SELECT pg_catalog.pg_get_expr(c.relpartbound, c.oid)
              INTO bound_text
              FROM pg_catalog.pg_class c
             WHERE c.relname = rec.name;

            -- Bound text looks like: FOR VALUES FROM ('1700000000') TO ('1700604800')
            upper_bound := substring(bound_text FROM 'TO \(''([0-9]+)''')::BIGINT;

            IF upper_bound IS NOT NULL AND upper_bound <= cutoff_epoch THEN
                EXECUTE format('DROP TABLE metrics.%I', rec.name);
                RETURN NEXT rec.name;
            END IF;
        END;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION metrics."dropOldPartitions" IS
    'Drops weekly partitions of metrics."workerMetric" whose entire range falls outside the retention window (default 52 weeks). Returns the dropped partition names.';

-- ── Seed: pre-create partitions covering "now" + 8 weeks ahead ──────
-- So the first metrics-consumer write after the migration always lands
-- in an existing partition without triggering an on-demand creation.
-- Operational responsibility: run "SELECT metrics.\"ensureUpcomingPartitions\"(8);"
-- weekly via cron / pg_cron / K8s CronJob to stay ahead.
SELECT metrics."ensureUpcomingPartitions"(8);

-- ── GRANTs ──────────────────────────────────────────────────────────
-- metricsWriter — used by jmeter-metrics-consumer. INSERT + SELECT only;
-- no UPDATE / DELETE. UPDATE would imply duplicate-handling beyond the
-- ON CONFLICT DO NOTHING contract; DELETE is the partition-drop path
-- which only the operator (jmetercloud user) executes. SELECT is needed
-- because `INSERT … ON CONFLICT DO NOTHING` probes the unique index for
-- conflicts.
--
-- Postgres treats partitioned-parent privileges and per-partition
-- privileges separately, so we GRANT on every existing partition too.
-- The "createWeeklyPartition" helper above re-issues the same grants
-- on each new partition so future writes don't run into the same
-- "permission denied" trap.
GRANT INSERT, SELECT ON metrics."workerMetric" TO "metricsWriter";
GRANT SELECT ON metrics."workerMetric" TO "metricsReader";

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT child.relname AS name
        FROM   pg_inherits
        JOIN   pg_class parent ON pg_inherits.inhparent = parent.oid
        JOIN   pg_class child  ON pg_inherits.inhrelid  = child.oid
        JOIN   pg_namespace ns ON parent.relnamespace   = ns.oid
        WHERE  ns.nspname     = 'metrics'
          AND  parent.relname = 'workerMetric'
    LOOP
        EXECUTE format(
            'GRANT INSERT, SELECT ON metrics.%I TO "metricsWriter"', rec.name);
        EXECUTE format(
            'GRANT SELECT ON metrics.%I TO "metricsReader"', rec.name);
    END LOOP;
END $$;

-- Function execution: only the migration user calls these
-- (operationally — via pg_cron or a manual session). Per-app users
-- don't need EXECUTE.
