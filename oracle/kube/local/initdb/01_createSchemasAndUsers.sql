-- 01_createSchemasAndUsers.sql — runs once, as SYS, on the first start of
-- the local Oracle Free container. It creates the instance-level objects
-- the Flyway migrations under oracle/migrations/ cannot: the two schema
-- owners and the application users. Everything inside the schemas
-- (tables, procedures, grants) is versioned by Flyway, connecting as each
-- owner.
--
-- On the target infrastructure this file is the DBA hand-off: run it once
-- against the platform's PDB with real passwords, then let the Flyway Job
-- run. Nothing else needs instance privileges.
--
-- CARDZATE_DB_GRAF is the metrics schema — the hosted environment's schema
-- reproduced verbatim (UPPER_SNAKE, unquoted). The metrics-consumer connects
-- AS this owner, like the hosted proxy client, so its SQL uses unqualified
-- names. "globalOrchestrator" and its users are quoted camelCase; pass the
-- quoted names WITH their quotes in a JDBC `user` property.

WHENEVER SQLERROR EXIT SQL.SQLCODE;
ALTER SESSION SET CONTAINER = FREEPDB1;

-- ── Schema owners (Flyway connects as these) ─────────────────────────
-- CARDZATE_DB_GRAF owns the shared dimensions, GROUP_REGISTRY and every
-- <GROUP_ID>_METRICS table; CREATE JOB is for the per-group nightly
-- DBMS_SCHEDULER job (archive → prune → stats).
CREATE USER CARDZATE_DB_GRAF IDENTIFIED BY "localdev"
    DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, ALTER SESSION, CREATE TABLE, CREATE PROCEDURE,
      CREATE SEQUENCE, CREATE VIEW, CREATE TRIGGER, CREATE TYPE, CREATE JOB
      TO CARDZATE_DB_GRAF;

CREATE USER "globalOrchestrator" IDENTIFIED BY "localdev"
    DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE SEQUENCE,
      CREATE VIEW, CREATE TRIGGER, CREATE TYPE TO "globalOrchestrator";

-- ── Application users (CREATE SESSION only; object grants come from the
--    migrations and the rendered group bundles, issued by the owner) ────
-- metricsReader  — jmeter-global-orchestrator's read pool: SELECT on the
--                  shared dimensions and every group's fact tables (the
--                  same grant list a Grafana user gets).
CREATE USER "metricsReader" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "metricsReader";

-- metricsPurger  — the orchestrator's purge pool only: SELECT + DELETE on
--                  the fact tables and the run-scoped dimensions.
CREATE USER "metricsPurger" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "metricsPurger";

-- globalOrchestratorWriter — jmeter-global-orchestrator's run-state pool.
CREATE USER "globalOrchestratorWriter" IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO "globalOrchestratorWriter";

-- Local-dev convenience: none of these passwords expire or lock.
ALTER PROFILE DEFAULT LIMIT PASSWORD_LIFE_TIME UNLIMITED;

EXIT;
