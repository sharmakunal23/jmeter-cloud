-- V14__partitionMaintenanceGrants.sql — applied to the jmetercloud_metrics database.
--
-- PARTITION-MAINTENANCE (2026-07-24). The partition helpers created in V1
-- were only executable *in practice* by the migration/owner user
-- (jmetercloud): they ran with INVOKER rights, and CREATE TABLE / DROP
-- TABLE in schema metrics require ownership. Nothing called them on a
-- schedule, and the pre-seeded 8-week runway ran out once already —
-- ingest failed with missing-partition errors and the UI timeseries went
-- empty until an operator ran ensureUpcomingPartitions by hand.
--
-- The durable caller is now the metrics-consumer's PartitionMaintenanceJob
-- (runs at boot + daily cron, pg_try_advisory_xact_lock-guarded so consumer
-- replicas don't race). The consumer connects as "metricsWriter" in cloud
-- deployments, so the two entry-point functions become SECURITY DEFINER
-- (bodies execute with the owner's privileges) with EXECUTE granted to
-- "metricsWriter" alone:
--
--   - SET search_path = pg_catalog, pg_temp pins name resolution inside
--     the SECURITY DEFINER bodies. All object references in the bodies are
--     already schema-qualified; this is belt-and-braces against a hostile
--     caller search_path (standard SECURITY DEFINER hygiene).
--   - EXECUTE is REVOKEd from PUBLIC on all three helpers. Postgres grants
--     function EXECUTE to PUBLIC by default — harmless while the bodies
--     ran with caller rights (the DDL inside just failed for non-owners),
--     but not acceptable once they carry owner privileges.
--   - createWeeklyPartition stays un-granted: it is only reached through
--     ensureUpcomingPartitions, whose body already executes as the owner.

REVOKE EXECUTE ON FUNCTION metrics."createWeeklyPartition"(TIMESTAMPTZ) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION metrics."ensureUpcomingPartitions"(INTEGER)  FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION metrics."dropOldPartitions"(INTEGER)         FROM PUBLIC;

ALTER FUNCTION metrics."ensureUpcomingPartitions"(INTEGER)
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;
ALTER FUNCTION metrics."dropOldPartitions"(INTEGER)
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp;

GRANT EXECUTE ON FUNCTION metrics."ensureUpcomingPartitions"(INTEGER) TO "metricsWriter";
GRANT EXECUTE ON FUNCTION metrics."dropOldPartitions"(INTEGER)        TO "metricsWriter";
