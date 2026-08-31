-- V5__workflows.sql — Workflows (WORKFLOWS, 2026-08-31).
-- A workflow is a group-scoped DAG of tasks (health check, load test, email,
-- delay, approval) advanced by a tick-driven engine that claims due executions
-- the ORCH_CLAIMS way. Three new tables, plus the columns two existing tables
-- need: who owns a group (the email defaults a workflow sends to), and which
-- workflow task a run belongs to.

-- ═══════════════════════════════════════════════════════════════════════
-- Group ownership — who to tell when a workflow has something to say
-- ═══════════════════════════════════════════════════════════════════════
-- Comma-separated address lists, the same storage ORCH_CRON_JOB.RECIPIENTS
-- already uses; an EMAIL node inherits them unless it overrides.
ALTER TABLE ORCH_APPLICATION_GROUP ADD (
    TEAM_NAME   VARCHAR2(255 CHAR),
    NOTIFY_TO   VARCHAR2(2000 CHAR),
    NOTIFY_CC   VARCHAR2(2000 CHAR),
    NOTIFY_BCC  VARCHAR2(2000 CHAR)
);

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_WORKFLOW — the drawn graph
-- ═══════════════════════════════════════════════════════════════════════
CREATE TABLE ORCH_WORKFLOW (
    WORKFLOW_ID  VARCHAR2(64 CHAR)   NOT NULL,   -- ULID
    GROUP_ID     VARCHAR2(30 CHAR)   NOT NULL,   -- the pool the workflow's runs draw on
    NAME         VARCHAR2(255 CHAR)  NOT NULL,   -- unique within the group
    DESCRIPTION  VARCHAR2(4000 CHAR),
    GRAPH        CLOB                NOT NULL,   -- { v, nodes[], edges[] }; <= 64 nodes, enforced in the hub
    ENABLED      NUMBER(1)           DEFAULT 1 NOT NULL,
    -- Bumped on every save. A PUT carrying a stale value is rejected 409, so
    -- two operators editing one canvas cannot silently overwrite each other.
    REVISION     NUMBER(10)          DEFAULT 1 NOT NULL,
    CREATED_BY   VARCHAR2(255 CHAR),
    CREATED_AT   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UPDATED_BY   VARCHAR2(255 CHAR),
    UPDATED_AT   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_WORKFLOW_PK PRIMARY KEY (WORKFLOW_ID),
    CONSTRAINT ORCH_WORKFLOW_GROUP_FK FOREIGN KEY (GROUP_ID)
        REFERENCES ORCH_APPLICATION_GROUP (GROUP_ID),
    CONSTRAINT ORCH_WORKFLOW_GROUP_NAME_UQ UNIQUE (GROUP_ID, NAME),
    CONSTRAINT ORCH_WORKFLOW_GRAPH_CHK CHECK (GRAPH IS JSON),
    CONSTRAINT ORCH_WORKFLOW_ENABLED_CHK CHECK (ENABLED IN (0, 1)),
    CONSTRAINT ORCH_WORKFLOW_REVISION_CHK CHECK (REVISION >= 1)
);
COMMENT ON TABLE ORCH_WORKFLOW IS
    'Group-scoped task DAGs. GRAPH is the React Flow document the builder saves; the hub validates its shape (acyclic, <= 64 nodes, per-type config) before every write.';

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_WORKFLOW_EXECUTION — one launch of a workflow
-- ═══════════════════════════════════════════════════════════════════════
CREATE TABLE ORCH_WORKFLOW_EXECUTION (
    EXECUTION_ID   VARCHAR2(64 CHAR)   NOT NULL,
    WORKFLOW_ID    VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: history outlives the workflow it ran
    GROUP_ID       VARCHAR2(30 CHAR)   NOT NULL,
    WORKFLOW_NAME  VARCHAR2(255 CHAR)  NOT NULL,   -- snapshot: a rename must not rewrite history
    GRAPH          CLOB                NOT NULL,   -- snapshot: an edit must not rewrite what ran
    STATE          VARCHAR2(16 CHAR)   NOT NULL,
    STATE_REASON   VARCHAR2(4000 CHAR),
    TRIGGERED_BY   VARCHAR2(255 CHAR)  NOT NULL,
    STARTED_AT     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    COMPLETED_AT   TIMESTAMP(3) WITH TIME ZONE,
    -- When the engine should next advance this execution, and the claim's
    -- lease: the claim pushes it forward before committing, so a replica that
    -- dies mid-advance strands nothing.
    NEXT_TICK_AT   TIMESTAMP(3) WITH TIME ZONE,
    CONSTRAINT ORCH_WORKFLOW_EXECUTION_PK PRIMARY KEY (EXECUTION_ID),
    CONSTRAINT ORCH_WORKFLOW_EXECUTION_GRAPH_CHK CHECK (GRAPH IS JSON),
    CONSTRAINT ORCH_WORKFLOW_EXECUTION_STATE_CHK
        CHECK (STATE IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- A RUNNING execution is ALWAYS scheduled and a terminal one never is, so
    -- "running but nothing will ever touch it again" cannot be represented.
    CONSTRAINT ORCH_WORKFLOW_EXECUTION_TICK_CHK CHECK (
           (STATE =  'RUNNING' AND NEXT_TICK_AT IS NOT NULL AND COMPLETED_AT IS NULL)
        OR (STATE <> 'RUNNING' AND NEXT_TICK_AT IS NULL     AND COMPLETED_AT IS NOT NULL))
);
CREATE INDEX ORCH_WORKFLOW_EXECUTION_CLAIM_IDX
    ON ORCH_WORKFLOW_EXECUTION (STATE, NEXT_TICK_AT);
CREATE INDEX ORCH_WORKFLOW_EXECUTION_WF_IDX
    ON ORCH_WORKFLOW_EXECUTION (WORKFLOW_ID, STARTED_AT DESC);
CREATE INDEX ORCH_WORKFLOW_EXECUTION_GROUP_IDX
    ON ORCH_WORKFLOW_EXECUTION (GROUP_ID, STARTED_AT DESC);
COMMENT ON TABLE ORCH_WORKFLOW_EXECUTION IS
    'One launch of a workflow, advanced by ORCH_CLAIMS.CLAIM_DUE_WORKFLOWS. NEXT_TICK_AT is both the schedule and the claim lease; the TICK_CHK constraint makes a stranded RUNNING row unrepresentable.';

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_WORKFLOW_TASK — one node of one execution
-- ═══════════════════════════════════════════════════════════════════════
CREATE TABLE ORCH_WORKFLOW_TASK (
    TASK_ID           VARCHAR2(64 CHAR)   NOT NULL,
    EXECUTION_ID      VARCHAR2(64 CHAR)   NOT NULL,
    NODE_ID           VARCHAR2(64 CHAR)   NOT NULL,   -- the graph node this row instantiates
    TYPE              VARCHAR2(32 CHAR)   NOT NULL,
    NAME              VARCHAR2(255 CHAR)  NOT NULL,   -- the node's label at launch
    STATE             VARCHAR2(24 CHAR)   NOT NULL,
    ATTEMPT           NUMBER(4)           DEFAULT 0 NOT NULL,   -- HEALTH_CHECK retries span ticks; the engine never sleeps
    APPLICATION_NAME  VARCHAR2(255 CHAR),                       -- the app a health check probed / a load test ran; the metrics split key
    RUN_ID            VARCHAR2(64 CHAR),                        -- LOAD_TEST only; no FK, a run purge must not delete history
    STARTED_AT        TIMESTAMP(3) WITH TIME ZONE,
    COMPLETED_AT      TIMESTAMP(3) WITH TIME ZONE,
    -- Next moment this task wants attention: a DELAY's due time, an APPROVAL's
    -- deadline, a HEALTH_CHECK's next attempt, a LOAD_TEST's watchdog.
    DUE_AT            TIMESTAMP(3) WITH TIME ZONE,
    RESULT            CLOB,                                     -- per-type detail the UI renders
    ERROR_REASON      VARCHAR2(4000 CHAR),
    CONSTRAINT ORCH_WORKFLOW_TASK_PK PRIMARY KEY (TASK_ID),
    CONSTRAINT ORCH_WORKFLOW_TASK_EXEC_FK FOREIGN KEY (EXECUTION_ID)
        REFERENCES ORCH_WORKFLOW_EXECUTION (EXECUTION_ID) ON DELETE CASCADE,
    CONSTRAINT ORCH_WORKFLOW_TASK_EXEC_NODE_UQ UNIQUE (EXECUTION_ID, NODE_ID),
    CONSTRAINT ORCH_WORKFLOW_TASK_RESULT_CHK CHECK (RESULT IS JSON),
    CONSTRAINT ORCH_WORKFLOW_TASK_TYPE_CHK
        CHECK (TYPE IN ('HEALTH_CHECK', 'LOAD_TEST', 'EMAIL', 'DELAY', 'APPROVAL')),
    CONSTRAINT ORCH_WORKFLOW_TASK_STATE_CHK
        CHECK (STATE IN ('PENDING', 'RUNNING', 'AWAITING_APPROVAL',
                         'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ORCH_WORKFLOW_TASK_RUN_ID_CHK CHECK (RUN_ID IS NULL OR TYPE = 'LOAD_TEST')
);
-- Sparse by design: Oracle stores no entry for an all-NULL key, so the rows of
-- every non-LOAD_TEST task cost nothing. Serves run -> workflow on the run page.
CREATE INDEX ORCH_WORKFLOW_TASK_RUN_IDX ON ORCH_WORKFLOW_TASK (RUN_ID);
COMMENT ON TABLE ORCH_WORKFLOW_TASK IS
    'One node per execution. RUN_ID is filled after the launch commits; recovery of a crash in that window is an exact lookup on ORCH_RUN.WORKFLOW_TASK_ID, never a scan.';

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_RUN — which workflow task launched this run
-- ═══════════════════════════════════════════════════════════════════════
-- The unique index is the exactly-once fence: a load-test task launches at
-- most one run, and Oracle indexes no all-NULL key, so every run that no
-- workflow launched stays out of the index entirely.
ALTER TABLE ORCH_RUN ADD (
    WORKFLOW_EXECUTION_ID  VARCHAR2(64 CHAR),
    WORKFLOW_TASK_ID       VARCHAR2(64 CHAR)
);
CREATE UNIQUE INDEX ORCH_RUN_WORKFLOW_TASK_UQ ON ORCH_RUN (WORKFLOW_TASK_ID);

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_CLAIMS — re-created whole to add CLAIM_DUE_WORKFLOWS
-- ═══════════════════════════════════════════════════════════════════════
-- A package cannot be extended in place, so this file now OWNS ORCH_CLAIMS:
-- the two procedures below are V2's, byte-for-byte, and V2's copy is
-- historical. Editing V2 would change nothing and Flyway forbids it anyway —
-- add the next claim procedure here (or in the migration that supersedes this
-- one), never there.

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
            SELECT j.CRON_JOB_ID, j.NAME, j.APPLICATION_NAME, j.TEMPLATE_BLOB_ID, j.CRON_EXPRESSION,
                   j.TIME_ZONE, j.ENABLED, j.CREATED_BY, j.CREATED_AT, j.LAST_FIRED_AT,
                   j.LAST_FIRED_RUN_ID, j.LAST_FIRE_STATUS, j.NEXT_FIRE_AT, j.CLAIMED_AT,
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

-- ═══════════════════════════════════════════════════════════════════════
-- Grants — the hub owns workflows; nothing else reads them
-- ═══════════════════════════════════════════════════════════════════════
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_WORKFLOW            TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_WORKFLOW_EXECUTION  TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_WORKFLOW_TASK       TO GLOBAL_ORCHESTRATOR_WRITER;
