-- V9__automationSections.sql — Automation becomes group-scoped (AUTOMATION-3, 2026-08-31).
--
-- A schedule now names an application GROUP, never an application. Two of the
-- three surviving kinds already worked that way and only used the application
-- as a lookup hop to reach its group; the third, LAUNCH_RUN, is replaced by
-- LAUNCH_WORKFLOW, because a one-node workflow IS "fire a saved template" and
-- one way to schedule work is less to explain than two.
--
-- KIND after this migration:
--   LAUNCH_WORKFLOW           (GROUP_ID, WORKFLOW_ID)
--   SCALE_OUT / SCALE_IN      (GROUP_ID, REGION)   -- were PROVISION_REGION / DRAIN_REGION
--   INFRA_READINESS / DAILY_REPORT                 -- platform-wide, recipients only
--
-- Nothing is destroyed: a row this migration cannot carry forward is copied to
-- ORCH_CRON_JOB_RETIRED with the reason, so an operator can re-create it rather
-- than discover it silently gone.

-- ═══════════════════════════════════════════════════════════════════════
-- 1. The archive — rows the new shape cannot express.
-- ═══════════════════════════════════════════════════════════════════════
CREATE TABLE ORCH_CRON_JOB_RETIRED (
    CRON_JOB_ID        VARCHAR2(64 CHAR)   NOT NULL,
    NAME               VARCHAR2(255 CHAR)  NOT NULL,
    KIND               VARCHAR2(32 CHAR)   NOT NULL,
    APPLICATION_NAME   VARCHAR2(255 CHAR),
    TEMPLATE_BLOB_ID   VARCHAR2(64 CHAR),
    REGION             VARCHAR2(64 CHAR),
    CRON_EXPRESSION    VARCHAR2(128 CHAR)  NOT NULL,
    TIME_ZONE          VARCHAR2(64 CHAR)   NOT NULL,
    ENABLED            NUMBER(1)           NOT NULL,
    RECIPIENTS         VARCHAR2(4000 CHAR),
    CREATED_BY         VARCHAR2(255 CHAR),
    CREATED_AT         TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    RETIRED_AT         TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    RETIRED_REASON     VARCHAR2(255 CHAR)  NOT NULL,
    CONSTRAINT ORCH_CRON_JOB_RETIRED_PK PRIMARY KEY (CRON_JOB_ID)
);
COMMENT ON TABLE ORCH_CRON_JOB_RETIRED IS
    'Schedules V9 could not carry into the group-scoped shape, kept verbatim so an operator can re-create them. Never read by the application.';

-- A LAUNCH_RUN row cannot be converted automatically: the new shape needs a
-- workflow, and building one needs the template's fleetAllocation, which lives
-- in a document-service blob this migration cannot read.
INSERT INTO ORCH_CRON_JOB_RETIRED
    (CRON_JOB_ID, NAME, KIND, APPLICATION_NAME, TEMPLATE_BLOB_ID, REGION,
     CRON_EXPRESSION, TIME_ZONE, ENABLED, RECIPIENTS, CREATED_BY, CREATED_AT, RETIRED_REASON)
SELECT CRON_JOB_ID, NAME, KIND, APPLICATION_NAME, TEMPLATE_BLOB_ID, REGION,
       CRON_EXPRESSION, TIME_ZONE, ENABLED, RECIPIENTS, CREATED_BY, CREATED_AT,
       'LAUNCH_RUN retired — re-create as a LAUNCH_WORKFLOW schedule on a one-step workflow'
FROM   ORCH_CRON_JOB
WHERE  KIND = 'LAUNCH_RUN';

-- A scale row whose application is no longer registered has no group to move to.
INSERT INTO ORCH_CRON_JOB_RETIRED
    (CRON_JOB_ID, NAME, KIND, APPLICATION_NAME, TEMPLATE_BLOB_ID, REGION,
     CRON_EXPRESSION, TIME_ZONE, ENABLED, RECIPIENTS, CREATED_BY, CREATED_AT, RETIRED_REASON)
SELECT j.CRON_JOB_ID, j.NAME, j.KIND, j.APPLICATION_NAME, j.TEMPLATE_BLOB_ID, j.REGION,
       j.CRON_EXPRESSION, j.TIME_ZONE, j.ENABLED, j.RECIPIENTS, j.CREATED_BY, j.CREATED_AT,
       'application not registered — no group to scope the schedule to'
FROM   ORCH_CRON_JOB j
WHERE  j.KIND IN ('DRAIN_REGION', 'PROVISION_REGION')
AND    NOT EXISTS (SELECT 1 FROM ORCH_APPLICATION a WHERE a.NAME = j.APPLICATION_NAME);

DELETE FROM ORCH_CRON_JOB
WHERE  CRON_JOB_ID IN (SELECT CRON_JOB_ID FROM ORCH_CRON_JOB_RETIRED);

-- ═══════════════════════════════════════════════════════════════════════
-- 2. The new shape.
-- ═══════════════════════════════════════════════════════════════════════
ALTER TABLE ORCH_CRON_JOB ADD (
    GROUP_ID    VARCHAR2(64 CHAR),   -- the owning application group; NULL only for the report kinds
    WORKFLOW_ID VARCHAR2(64 CHAR)    -- LAUNCH_WORKFLOW only; deliberately no FK (see below)
);

-- The scale kinds already operated on the application's group — this only
-- removes the hop.
UPDATE ORCH_CRON_JOB j
SET    j.GROUP_ID = (SELECT a.METRICS_GROUP_ID FROM ORCH_APPLICATION a WHERE a.NAME = j.APPLICATION_NAME)
WHERE  j.KIND IN ('DRAIN_REGION', 'PROVISION_REGION');

-- Direction, not verb: "scale out" and "scale in" are what an operator calls
-- these, and they read as a pair in the UI's Platform infrastructure section.
UPDATE ORCH_CRON_JOB SET KIND = 'SCALE_OUT' WHERE KIND = 'PROVISION_REGION';
UPDATE ORCH_CRON_JOB SET KIND = 'SCALE_IN'  WHERE KIND = 'DRAIN_REGION';

-- A fire now starts a workflow EXECUTION, never a run directly, so the column
-- that records what it produced is renamed to match what it holds.
ALTER TABLE ORCH_CRON_JOB RENAME COLUMN LAST_FIRED_RUN_ID TO LAST_FIRED_EXECUTION_ID;
ALTER TABLE ORCH_CRON_JOB_FIRE_HISTORY RENAME COLUMN RUN_ID TO EXECUTION_ID;

-- ═══════════════════════════════════════════════════════════════════════
-- 3. Constraints — uniqueness re-keyed to the group, fields re-keyed to kind.
-- ═══════════════════════════════════════════════════════════════════════
ALTER TABLE ORCH_CRON_JOB DROP CONSTRAINT ORCH_CRON_JOB_APP_NAME_UQ;
ALTER TABLE ORCH_CRON_JOB DROP CONSTRAINT ORCH_CRON_JOB_KIND_CHK;
ALTER TABLE ORCH_CRON_JOB DROP CONSTRAINT ORCH_CRON_JOB_KIND_FIELDS_CHK;
ALTER TABLE ORCH_CRON_JOB DROP (APPLICATION_NAME, TEMPLATE_BLOB_ID);

-- Platform reports carry a NULL group, and Oracle lets only an all-NULL key
-- repeat — so two (NULL, 'daily') rows still collide, exactly as before.
ALTER TABLE ORCH_CRON_JOB ADD CONSTRAINT ORCH_CRON_JOB_GROUP_NAME_UQ UNIQUE (GROUP_ID, NAME);

ALTER TABLE ORCH_CRON_JOB ADD CONSTRAINT ORCH_CRON_JOB_KIND_CHK CHECK (KIND IN
    ('LAUNCH_WORKFLOW', 'SCALE_OUT', 'SCALE_IN', 'INFRA_READINESS', 'DAILY_REPORT'));

ALTER TABLE ORCH_CRON_JOB ADD CONSTRAINT ORCH_CRON_JOB_KIND_FIELDS_CHK CHECK (
       (KIND = 'LAUNCH_WORKFLOW' AND GROUP_ID IS NOT NULL AND WORKFLOW_ID IS NOT NULL AND REGION IS NULL)
    OR (KIND IN ('SCALE_OUT', 'SCALE_IN') AND GROUP_ID IS NOT NULL AND REGION IS NOT NULL AND WORKFLOW_ID IS NULL)
    OR (KIND IN ('INFRA_READINESS', 'DAILY_REPORT')
        AND GROUP_ID IS NULL AND WORKFLOW_ID IS NULL AND REGION IS NULL));

-- A group holding schedules cannot be deleted — the same posture as a group
-- holding applications or workers. The index is not optional: without it, a
-- delete on the parent takes a table lock on ORCH_CRON_JOB.
CREATE INDEX ORCH_CRON_JOB_GROUP_ID_IDX ON ORCH_CRON_JOB (GROUP_ID);
ALTER TABLE ORCH_CRON_JOB ADD CONSTRAINT ORCH_CRON_JOB_GROUP_ID_FK
    FOREIGN KEY (GROUP_ID) REFERENCES ORCH_APPLICATION_GROUP (GROUP_ID);

-- No FK on WORKFLOW_ID, deliberately: deleting a workflow must not fail because
-- a schedule points at it, and it must not silently delete the schedule either.
-- The fire reports FAILED with "workflow no longer exists" and the operator sees
-- it in the Automation list — the same posture ORCH_RUN.PLUGINS takes.

COMMENT ON TABLE ORCH_CRON_JOB IS
    'Persistent schedules fired by a DB-claim sweep (ORCH_CLAIMS.CLAIM_DUE_CRON_JOBS), scoped to an application group. NEXT_FIRE_AT is advanced in the claim transaction, so a mid-fire crash errs toward not double-firing.';
COMMENT ON COLUMN ORCH_CRON_JOB.WORKFLOW_ID IS
    'LAUNCH_WORKFLOW only. No FK on purpose: a workflow delete leaves the schedule, whose next fire reports FAILED rather than vanishing unnoticed.';

GRANT SELECT ON ORCH_CRON_JOB_RETIRED TO GLOBAL_ORCHESTRATOR_WRITER;

-- ═══════════════════════════════════════════════════════════════════════
-- 4. ORCH_CLAIMS — re-created because CLAIM_DUE_CRON_JOBS names the columns
--    section 2 renamed and dropped. Flyway does NOT fail on a package that
--    compiled with errors, so leaving it would have produced a schedule sweep
--    that silently never claimed anything. V9 therefore owns the package now,
--    exactly as V5 took it from V2 — the pod and workflow claims below are
--    unchanged, and the only edit is the cron cursor's column list.
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PACKAGE ORCH_CLAIMS AUTHID DEFINER AS

    -- Locks up to p_limit IDLE workers that no non-terminal run member
    -- holds, freshest heartbeat first, and returns them as a cursor over
    -- the ORCH_POD columns. p_region / p_groupId are filters when
    -- non-NULL. Rows another claimer holds are skipped, never waited on;
    -- the locks belong to the caller's transaction and release at commit,
    -- so the caller must insert its ORCH_RUN_FLEET_MEMBER rows before committing.
    PROCEDURE CLAIM_IDLE_PODS(p_region IN VARCHAR2, p_groupId IN VARCHAR2,
                              p_limit IN NUMBER, p_pods OUT SYS_REFCURSOR);

    -- Locks up to p_limit enabled schedules due at p_now, earliest first,
    -- and returns them as a cursor over the ORCH_CRON_JOB columns. The caller
    -- advances NEXT_FIRE_AT before committing — that is what makes a fire
    -- exactly-once across replicas.
    PROCEDURE CLAIM_DUE_CRON_JOBS(p_now IN TIMESTAMP WITH TIME ZONE, p_limit IN NUMBER,
                                 p_jobs OUT SYS_REFCURSOR);


    -- Locks up to p_limit RUNNING executions due at p_now, earliest first, and
    -- returns them as a cursor over the ORCH_WORKFLOW_EXECUTION columns. The
    -- caller pushes NEXT_TICK_AT forward before committing — that lease is what
    -- lets a replica die mid-advance without stranding the execution.
    PROCEDURE CLAIM_DUE_WORKFLOWS(p_now IN TIMESTAMP WITH TIME ZONE, p_limit IN NUMBER,
                                  p_executions OUT SYS_REFCURSOR);

END ORCH_CLAIMS;
/

CREATE OR REPLACE PACKAGE BODY ORCH_CLAIMS AS

    PROCEDURE CLAIM_IDLE_PODS(p_region IN VARCHAR2, p_groupId IN VARCHAR2,
                              p_limit IN NUMBER, p_pods OUT SYS_REFCURSOR) IS
        v_ids ORCH_ID_TABLE := ORCH_ID_TABLE();
        v_id  VARCHAR2(64 CHAR);
    BEGIN
        -- Candidate pass (no locks), in claim preference order.
        FOR c IN (SELECT p.POD_ID
                  FROM   ORCH_POD p
                  WHERE  p.STATE = 'IDLE'
                    AND  (p_region IS NULL OR p.REGION = p_region)
                    AND  (p_groupId IS NULL OR p.GROUP_ID = p_groupId)
                    AND  NOT EXISTS (SELECT 1 FROM ORCH_RUN_FLEET_MEMBER m
                                     WHERE m.WORKER_ID = p.POD_ID
                                       AND m.STATE IN ('PENDING', 'REQUESTED', 'ACCEPTED', 'RUNNING', 'DRAINING'))
                  ORDER  BY p.LAST_HEARTBEAT DESC) LOOP
            EXIT WHEN v_ids.COUNT >= p_limit;
            -- One-row lock attempt, re-checking the predicates under the
            -- lock: a row another claimer holds, or that stopped being
            -- claimable since the candidate pass, simply yields nothing.
            BEGIN
                SELECT p.POD_ID INTO v_id
                FROM   ORCH_POD p
                WHERE  p.POD_ID = c.POD_ID
                  AND  p.STATE = 'IDLE'
                  AND  NOT EXISTS (SELECT 1 FROM ORCH_RUN_FLEET_MEMBER m
                                   WHERE m.WORKER_ID = p.POD_ID
                                     AND m.STATE IN ('PENDING', 'REQUESTED', 'ACCEPTED', 'RUNNING', 'DRAINING'))
                FOR UPDATE OF p.STATE SKIP LOCKED;
                v_ids.EXTEND;
                v_ids(v_ids.COUNT) := v_id;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN NULL;
            END;
        END LOOP;

        OPEN p_pods FOR
            SELECT p.POD_ID, p.REGION, p.BASE_URL, p.STATE, p.LAST_HEARTBEAT,
                   p.REGISTERED_AT, p.GROUP_ID, p.RUNS_SERVED, p.IMAGE_DIGEST,
                   p.PROVISIONED_AT, p.SOURCE
            FROM   ORCH_POD p
            WHERE  p.POD_ID IN (SELECT COLUMN_VALUE FROM TABLE(v_ids))
            ORDER  BY p.LAST_HEARTBEAT DESC;
    END CLAIM_IDLE_PODS;

    PROCEDURE CLAIM_DUE_CRON_JOBS(p_now IN TIMESTAMP WITH TIME ZONE, p_limit IN NUMBER,
                                 p_jobs OUT SYS_REFCURSOR) IS
        v_ids ORCH_ID_TABLE := ORCH_ID_TABLE();
        v_id  VARCHAR2(64 CHAR);
    BEGIN
        FOR c IN (SELECT j.CRON_JOB_ID
                  FROM   ORCH_CRON_JOB j
                  WHERE  j.ENABLED = 1 AND j.NEXT_FIRE_AT IS NOT NULL AND j.NEXT_FIRE_AT <= p_now
                  ORDER  BY j.NEXT_FIRE_AT ASC) LOOP
            EXIT WHEN v_ids.COUNT >= p_limit;
            BEGIN
                SELECT j.CRON_JOB_ID INTO v_id
                FROM   ORCH_CRON_JOB j
                WHERE  j.CRON_JOB_ID = c.CRON_JOB_ID
                  AND  j.ENABLED = 1 AND j.NEXT_FIRE_AT IS NOT NULL AND j.NEXT_FIRE_AT <= p_now
                FOR UPDATE OF j.NEXT_FIRE_AT SKIP LOCKED;
                v_ids.EXTEND;
                v_ids(v_ids.COUNT) := v_id;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN NULL;
            END;
        END LOOP;

        OPEN p_jobs FOR
            SELECT j.CRON_JOB_ID, j.NAME, j.GROUP_ID, j.WORKFLOW_ID, j.CRON_EXPRESSION,
                   j.TIME_ZONE, j.ENABLED, j.CREATED_BY, j.CREATED_AT, j.LAST_FIRED_AT,
                   j.LAST_FIRED_EXECUTION_ID, j.LAST_FIRE_STATUS, j.NEXT_FIRE_AT, j.CLAIMED_AT,
                   j.KIND, j.REGION, j.RECIPIENTS, j.CUSTOM_SUBJECT, j.CUSTOM_INTRO
            FROM   ORCH_CRON_JOB j
            WHERE  j.CRON_JOB_ID IN (SELECT COLUMN_VALUE FROM TABLE(v_ids))
            ORDER  BY j.NEXT_FIRE_AT ASC;
    END CLAIM_DUE_CRON_JOBS;


    PROCEDURE CLAIM_DUE_WORKFLOWS(p_now IN TIMESTAMP WITH TIME ZONE, p_limit IN NUMBER,
                                  p_executions OUT SYS_REFCURSOR) IS
        v_ids ORCH_ID_TABLE := ORCH_ID_TABLE();
        v_id  VARCHAR2(64 CHAR);
    BEGIN
        FOR c IN (SELECT e.EXECUTION_ID
                  FROM   ORCH_WORKFLOW_EXECUTION e
                  WHERE  e.STATE = 'RUNNING' AND e.NEXT_TICK_AT <= p_now
                  ORDER  BY e.NEXT_TICK_AT ASC) LOOP
            EXIT WHEN v_ids.COUNT >= p_limit;
            BEGIN
                SELECT e.EXECUTION_ID INTO v_id
                FROM   ORCH_WORKFLOW_EXECUTION e
                WHERE  e.EXECUTION_ID = c.EXECUTION_ID
                  AND  e.STATE = 'RUNNING' AND e.NEXT_TICK_AT <= p_now
                FOR UPDATE OF e.NEXT_TICK_AT SKIP LOCKED;
                v_ids.EXTEND;
                v_ids(v_ids.COUNT) := v_id;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN NULL;
            END;
        END LOOP;

        OPEN p_executions FOR
            SELECT e.EXECUTION_ID, e.WORKFLOW_ID, e.GROUP_ID, e.WORKFLOW_NAME, e.GRAPH,
                   e.STATE, e.STATE_REASON, e.TRIGGERED_BY, e.STARTED_AT, e.COMPLETED_AT,
                   e.NEXT_TICK_AT
            FROM   ORCH_WORKFLOW_EXECUTION e
            WHERE  e.EXECUTION_ID IN (SELECT COLUMN_VALUE FROM TABLE(v_ids))
            ORDER  BY e.NEXT_TICK_AT ASC;
    END CLAIM_DUE_WORKFLOWS;

END ORCH_CLAIMS;
/
