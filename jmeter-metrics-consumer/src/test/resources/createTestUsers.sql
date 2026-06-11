-- Pre-creates the per-app users that the canonical
-- postgres/migrations/metrics/V1__metricsSchema.sql GRANTs to. Without
-- this, `GRANT INSERT … TO "metricsWriter"` fails because the role
-- doesn't exist in a vanilla Testcontainers Postgres. Mirrors the
-- production createDatabases.sql in initdb/.
CREATE USER "metricsWriter" WITH PASSWORD 'test';
CREATE USER "metricsReader" WITH PASSWORD 'test';
