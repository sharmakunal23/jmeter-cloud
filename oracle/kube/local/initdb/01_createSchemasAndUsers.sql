-- 01_createSchemasAndUsers.sql — the instance-level objects the Flyway
-- migrations under oracle/migrations/ cannot create: the schema owner and
-- the three application users. Everything inside the schema (tables,
-- procedures, grants) is versioned by Flyway, connecting as the owner.
--
-- Locally it runs once, as SYS, on the first start of the Oracle Free
-- container. On the target infrastructure it is the DBA hand-off: run it
-- as SYS against the platform's PDB with real passwords, the PDB's service
-- name on the ALTER SESSION line and the tablespace of your choice, then
-- let the Flyway Job run. It is re-runnable — a user that already exists
-- is kept (ORA-01920 swallowed) — so the same file upgrades a database
-- that already has the owner.
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
BEGIN
    EXECUTE IMMEDIATE 'CREATE USER CARDZATE_DB_GRAF IDENTIFIED BY "localdev"
        DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS';
EXCEPTION WHEN OTHERS THEN
    IF SQLCODE <> -1920 THEN RAISE; END IF;
END;
/
GRANT CREATE SESSION, ALTER SESSION, CREATE TABLE, CREATE PROCEDURE,
      CREATE SEQUENCE, CREATE VIEW, CREATE TRIGGER, CREATE TYPE, CREATE JOB
      TO CARDZATE_DB_GRAF;

-- ── Application users (CREATE SESSION only; object grants come from the
--    migrations and the rendered group bundles, issued by the owner) ────
-- METRICS_READER — jmeter-global-orchestrator's read pool: SELECT on the
--                  shared dimensions and every group's fact tables (the
--                  same grant list a Grafana user gets).
BEGIN
    EXECUTE IMMEDIATE 'CREATE USER METRICS_READER IDENTIFIED BY "localdev"';
EXCEPTION WHEN OTHERS THEN
    IF SQLCODE <> -1920 THEN RAISE; END IF;
END;
/
GRANT CREATE SESSION TO METRICS_READER;

-- METRICS_PURGER — the orchestrator's purge pool only: SELECT + DELETE on
--                  the fact tables and the run-scoped dimensions.
BEGIN
    EXECUTE IMMEDIATE 'CREATE USER METRICS_PURGER IDENTIFIED BY "localdev"';
EXCEPTION WHEN OTHERS THEN
    IF SQLCODE <> -1920 THEN RAISE; END IF;
END;
/
GRANT CREATE SESSION TO METRICS_PURGER;

-- GLOBAL_ORCHESTRATOR_WRITER — jmeter-global-orchestrator's run-state pool
--                  (DML on the ORCH_* tables, EXECUTE on ORCH_CLAIMS).
BEGIN
    EXECUTE IMMEDIATE 'CREATE USER GLOBAL_ORCHESTRATOR_WRITER IDENTIFIED BY "localdev"';
EXCEPTION WHEN OTHERS THEN
    IF SQLCODE <> -1920 THEN RAISE; END IF;
END;
/
GRANT CREATE SESSION TO GLOBAL_ORCHESTRATOR_WRITER;

-- ── LOCAL DEV ONLY — omit on the target: it changes the PDB's DEFAULT
--    profile so none of these passwords expire or lock. ─────────────────
ALTER PROFILE DEFAULT LIMIT PASSWORD_LIFE_TIME UNLIMITED;

EXIT;
