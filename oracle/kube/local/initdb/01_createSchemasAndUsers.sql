-- 01_createSchemasAndUsers.sql — runs once, as SYS, on the first start of
-- the local Oracle Free container. It creates the instance-level objects
-- the Flyway migrations under oracle/migrations/ cannot: the two schema
-- owners and the four application users. Everything inside the schemas
-- (tables, packages, grants) is versioned by Flyway, connecting as each
-- owner.
--
-- On the target infrastructure this file is the DBA hand-off: run it once
-- against the platform's PDB with real passwords, then let the Flyway Job
-- run. Nothing else needs instance privileges.
--
-- Names are camelCase and double-quoted, the same rule as every identifier
-- in the platform; an unquoted name folds to UPPER here (Postgres folded to
-- lower). `metrics` is deliberately unquoted so that the schema-qualified
-- SQL `metrics."workerMetric"` resolves; the quoted users must be passed
-- WITH their quotes in a JDBC `user` property ("metricsWriter").

WHENEVER SQLERROR EXIT SQL.SQLCODE;
ALTER SESSION SET CONTAINER = FREEPDB1;

-- ── Schema owners (Flyway connects as these) ─────────────────────────
CREATE USER metrics IDENTIFIED BY "localdev"
    DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE SEQUENCE,
      CREATE VIEW, CREATE TRIGGER, CREATE TYPE TO metrics;

CREATE USER "globalOrchestrator" IDENTIFIED BY "localdev"
    DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE SEQUENCE,
      CREATE VIEW, CREATE TRIGGER, CREATE TYPE TO "globalOrchestrator";

-- ── Application users (CREATE SESSION only; object grants come from the
--    migrations, issued by the owner) ─────────────────────────────────
-- metricsWriter  — jmeter-metrics-consumer: INSERT/UPDATE on metrics.*
--                  + EXECUTE on the ingest and maintenance packages.
CREATE USER "metricsWriter" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "metricsWriter";

-- metricsReader  — jmeter-global-orchestrator's read pool: SELECT on the
--                  rollups only.
CREATE USER "metricsReader" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "metricsReader";

-- metricsPurger  — the orchestrator's purge pool only: SELECT + DELETE.
--                  Separate from both hot-path roles on purpose.
CREATE USER "metricsPurger" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "metricsPurger";

-- globalOrchestratorWriter — jmeter-global-orchestrator's run-state pool.
CREATE USER "globalOrchestratorWriter" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "globalOrchestratorWriter";

-- Local-dev convenience: none of these passwords expire or lock.
ALTER PROFILE DEFAULT LIMIT PASSWORD_LIFE_TIME UNLIMITED;

EXIT;
