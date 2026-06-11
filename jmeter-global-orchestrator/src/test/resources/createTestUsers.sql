-- Pre-creates the per-app users that the canonical
-- postgres/migrations/{globalrun,metrics}/V1__*.sql GRANTs to.
-- Without these, `GRANT … TO "<role>"` fails because the role doesn't
-- exist in a vanilla Testcontainers Postgres.
CREATE USER "globalOrchestratorWriter" WITH PASSWORD 'test';
CREATE USER "metricsReader"            WITH PASSWORD 'test';
-- Added 2026-05-10 for MetricsTimeseriesIT (HM-1) which migrates the
-- metrics schema as well — V1__metricsSchema.sql grants INSERT/SELECT
-- to metricsWriter on every workerMetric partition.
CREATE USER "metricsWriter"            WITH PASSWORD 'test';
-- Added for the HARD-DELETE / purge track:
-- metrics V13__metricsPurgeGrants.sql GRANTs SELECT/DELETE on workerMetric to
-- metricsPurger; RunPurgeIT opens the metricsPurge datasource as this role.
CREATE USER "metricsPurger"            WITH PASSWORD 'test';
