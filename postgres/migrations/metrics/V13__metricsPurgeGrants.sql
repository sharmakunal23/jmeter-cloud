-- V13__metricsPurgeGrants.sql — applied to the jmetercloud_metrics database.
--
-- HARD-DELETE / purge. The operator-driven purge
-- of a hidden run physically removes its per-second rows from
-- metrics."workerMetric" to reclaim space. The global-orchestrator's metrics
-- READ pool is metricsReader + setReadOnly(true), and the consumer's metricsWriter
-- role was deliberately denied DELETE (V1's grant comment: "DELETE is the
-- partition-drop path which only the operator executes"). So the purge gets its
-- own least-privilege role — metricsPurger (created in initdb) — with SELECT +
-- DELETE only. global-orch opens a SEPARATE read-write pool as this role
-- (metricsPurgeDataSource), used exclusively by the purge path; the hot read
-- pool stays read-only, so the boundary the platform set is preserved.
--
-- DELETE … WHERE "runId" = ? cannot prune by partition ("runId" is not the
-- partition key — "windowSecond" is), so it scans every weekly partition and
-- leaves dead tuples that autovacuum reclaims. That's acceptable for a targeted
-- operator action; bulk growth is still bounded by dropOldPartitions(). SELECT is
-- granted alongside DELETE because the WHERE clause reads "runId".
--
-- Postgres tracks partitioned-parent and per-partition privileges separately, so
-- we GRANT on the parent AND every existing partition, and re-issue the grant on
-- each future partition by extending createWeeklyPartition() (mirrors how V1
-- handles metricsWriter/metricsReader).

-- ── Ensure the role exists ──────────────────────────────────────────
-- initdb's CREATE USER "metricsPurger" only runs on a FRESH data dir, so a
-- cluster whose volume predates this role would fail every GRANT below with
-- "role metricsPurger does not exist" — wedging the whole flyway-migrate job.
-- This idempotent guard makes the migration self-sufficient: a no-op when the
-- role already exists (fresh local volume via initdb, or cloud where IaC
-- pre-creates roles before migrating), and otherwise creates it with the
-- local-dev password (the only context this branch runs in). Mirrors initdb.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metricsPurger') THEN
        CREATE ROLE "metricsPurger" LOGIN PASSWORD 'localdev';
    END IF;
END $$;

-- ── Schema usage ────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA metrics TO "metricsPurger";

-- ── Parent + existing partitions ────────────────────────────────────
GRANT SELECT, DELETE ON metrics."workerMetric" TO "metricsPurger";

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
            'GRANT SELECT, DELETE ON metrics.%I TO "metricsPurger"', rec.name);
    END LOOP;
END $$;

-- ── Extend createWeeklyPartition() to grant the purge role on new children ──
-- CREATE OR REPLACE keeps the V1 body verbatim and adds one GRANT so future
-- partitions are purgeable without another migration (same pattern V1 uses for
-- metricsWriter/metricsReader).
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

    -- GRANTs do NOT propagate from parent to partition (see V1); re-issue here.
    EXECUTE format(
        'GRANT INSERT, SELECT ON metrics.%I TO "metricsWriter"', partition_name);
    EXECUTE format(
        'GRANT SELECT ON metrics.%I TO "metricsReader"', partition_name);
    -- HARD-DELETE — the purge role needs SELECT + DELETE on every partition.
    EXECUTE format(
        'GRANT SELECT, DELETE ON metrics.%I TO "metricsPurger"', partition_name);

    RETURN partition_name;
END;
$$;
