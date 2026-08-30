-- 01_createSchemasAndUsers.sql — runs once, as SYS, on the first start of
-- the local Oracle Free container. It creates the instance-level objects
-- the Flyway migrations under oracle/migrations/ cannot: the schema owner
-- and the application users. Everything inside the schema (tables,
-- procedures, grants) is versioned by Flyway, connecting as the owner.
--
-- On the target infrastructure this file is the DBA hand-off: run it once
-- against the platform's PDB with real passwords, then let the Flyway Job
-- run. Nothing else needs instance privileges.
--
-- One schema, CARDZATE_DB_GRAF — the hosted environment's metrics schema
-- reproduced verbatim, plus the platform's ORCH_* control-plane tables.
-- Every identifier, usernames included, is UPPER_SNAKE and unquoted.

WHENEVER SQLERROR EXIT SQL.SQLCODE;
ALTER SESSION SET CONTAINER = FREEPDB1;

-- ── Schema owner (Flyway and the metrics-consumer connect as it) ──────
-- Owns the shared dimensions, GROUP_REGISTRY, every <GROUP_ID>_METRICS
-- table and the ORCH_* tables; CREATE JOB is for the per-group nightly
-- DBMS_SCHEDULER job (archive → prune → stats).
CREATE USER CARDZATE_DB_GRAF IDENTIFIED BY "localdev"
    DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, ALTER SESSION, CREATE TABLE, CREATE PROCEDURE,
      CREATE SEQUENCE, CREATE VIEW, CREATE TRIGGER, CREATE TYPE, CREATE JOB
      TO CARDZATE_DB_GRAF;

-- ── Application users (CREATE SESSION only; object grants come from the
--    migrations and the rendered group bundles, issued by the owner) ────
-- METRICS_READER — jmeter-global-orchestrator's read pool: SELECT on the
--                  shared dimensions and every group's fact tables (the
--                  same grant list a Grafana user gets).
CREATE USER METRICS_READER IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO METRICS_READER;

-- METRICS_PURGER — the orchestrator's purge pool only: SELECT + DELETE on
--                  the fact tables and the run-scoped dimensions.
CREATE USER METRICS_PURGER IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO METRICS_PURGER;

-- GLOBAL_ORCHESTRATOR_WRITER — jmeter-global-orchestrator's run-state pool
--                  (DML on the ORCH_* tables, EXECUTE on ORCH_CLAIMS).
CREATE USER GLOBAL_ORCHESTRATOR_WRITER IDENTIFIED BY "localdev";
GRANT CREATE SESSION TO GLOBAL_ORCHESTRATOR_WRITER;

-- Local-dev convenience: none of these passwords expire or lock.
ALTER PROFILE DEFAULT LIMIT PASSWORD_LIFE_TIME UNLIMITED;

EXIT;
