-- V2__controlPlaneSchema.sql — the control plane's tables inside the one
-- platform schema, CARDZATE_DB_GRAF (CONTROL-PLANE-NAMING, 2026-08-30): V1
-- is the hosted metrics layout verbatim, this is what the platform adds to
-- it. Every object is ORCH_-prefixed so the two families stay apart in one
-- schema (ORCH_RUN is a launch; RUN is the metrics dimension keyed by its
-- id, RUN.RUN_KEY = ORCH_RUN.RUN_ID). Applied by the owner; the service
-- connects as GLOBAL_ORCHESTRATOR_WRITER with the grants at the end, and the
-- metrics readers' grants on the shared dimensions live here too — V1
-- stays the hosted file.
-- Written to the 19c-compatible subset and validated on Oracle Free 26ai (23.26.2).
--
-- Type rules used throughout: ids VARCHAR2(64 CHAR); names 255; free text
-- 4000; JSON documents CLOB CHECK (IS JSON); booleans NUMBER(1) IN (0,1);
-- instants TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP. Every VARCHAR2
-- declares CHAR semantics because the instance default is BYTE.
--
-- The one thing that is not a plain translation: the two claim queries.
-- Oracle locks a FOR UPDATE cursor's whole result set at open when it has an
-- ORDER BY, so "LIMIT n FOR UPDATE SKIP LOCKED" is reproduced by the
-- ORCH_CLAIMS package below — an ordered candidate pass, then one SKIP
-- LOCKED lock per row until n are held.

-- ═══════════════════════════════════════════════════════════════════════
-- Runs and their fleet members
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE ORCH_RUN (
    RUN_ID              VARCHAR2(64 CHAR)   NOT NULL,
    ORIGIN_REGION       VARCHAR2(64 CHAR)   NOT NULL,
    TEST_PLAN_BLOB_ID   VARCHAR2(64 CHAR)   NOT NULL,
    DATA_FILES_BLOB_ID  VARCHAR2(64 CHAR),
    INITIATED_BY        VARCHAR2(255 CHAR)  NOT NULL,
    STATE               VARCHAR2(32 CHAR)   NOT NULL,
    STATE_REASON        VARCHAR2(4000 CHAR),
    APPLICATION         VARCHAR2(255 CHAR),                         -- application name; NULL on legacy rows
    METRICS_GROUP_ID    VARCHAR2(30 CHAR),                          -- the application's group AT LAUNCH: the run's rows live in <UPPER(id)>_METRICS forever, even if the app moves group
    SAVE_RESULTS        NUMBER(1)           DEFAULT 0 NOT NULL,     -- workers upload their JTL on COMPLETE
    CREATED_AT          TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    STARTED_AT          TIMESTAMP(3) WITH TIME ZONE,
    COMPLETED_AT        TIMESTAMP(3) WITH TIME ZONE,
    HIDDEN_AT           TIMESTAMP(3) WITH TIME ZONE,                -- soft-deleted when set; terminal runs only
    CONSTRAINT ORCH_RUN_PK PRIMARY KEY (RUN_ID),
    CONSTRAINT ORCH_RUN_SAVE_RESULTS_CHK CHECK (SAVE_RESULTS IN (0, 1))
);
CREATE INDEX ORCH_RUN_CREATED_AT_IDX
    ON ORCH_RUN (CREATED_AT DESC);
CREATE INDEX ORCH_RUN_APPLICATION_CREATED_AT_IDX
    ON ORCH_RUN (APPLICATION, CREATED_AT DESC);
-- The capacity ceiling: active members of the group's non-terminal runs in a region.
CREATE INDEX ORCH_RUN_METRICS_GROUP_ID_STATE_IDX
    ON ORCH_RUN (METRICS_GROUP_ID, STATE);
CREATE INDEX ORCH_RUN_STATE_CREATED_AT_IDX
    ON ORCH_RUN (STATE, CREATED_AT DESC);
COMMENT ON TABLE ORCH_RUN IS
    'One row per fleet run. Owned by jmeter-global-orchestrator; RUN_ID is a server-issued ULID.';

CREATE TABLE ORCH_RUN_FLEET_MEMBER (
    RUN_ID              VARCHAR2(64 CHAR)   NOT NULL,
    WORKER_ID           VARCHAR2(64 CHAR)   NOT NULL,
    REGION              VARCHAR2(64 CHAR)   NOT NULL,
    STATE               VARCHAR2(32 CHAR)   NOT NULL,
    STATE_REASON        VARCHAR2(4000 CHAR),
    FANOUT_STATUS_CODE  NUMBER(10),
    POD_BASE_URL        VARCHAR2(512 CHAR),
    PROPERTIES          CLOB                DEFAULT '{}' NOT NULL,  -- per-node JMeter -J properties at launch
    JOINED_AT_SECOND    NUMBER(19),                                 -- NULL = original fleet; >= 0 = scale-up joiner
    CREATED_AT          TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    STARTED_AT          TIMESTAMP(3) WITH TIME ZONE,
    COMPLETED_AT        TIMESTAMP(3) WITH TIME ZONE,
    CONSTRAINT ORCH_RUN_FLEET_MEMBER_PK PRIMARY KEY (RUN_ID, WORKER_ID),
    CONSTRAINT ORCH_RUN_FLEET_MEMBER_RUN_FK FOREIGN KEY (RUN_ID)
        REFERENCES ORCH_RUN (RUN_ID) ON DELETE CASCADE,
    CONSTRAINT ORCH_RUN_FLEET_MEMBER_PROPERTIES_CHK CHECK (PROPERTIES IS JSON)
);
CREATE INDEX ORCH_RUN_FLEET_MEMBER_STATE_IDX
    ON ORCH_RUN_FLEET_MEMBER (RUN_ID, STATE);
-- The claim's NOT EXISTS probe and the per-worker history both walk this.
CREATE INDEX ORCH_RUN_FLEET_MEMBER_WORKER_ID_IDX
    ON ORCH_RUN_FLEET_MEMBER (WORKER_ID, STATE, CREATED_AT DESC);
COMMENT ON TABLE ORCH_RUN_FLEET_MEMBER IS
    'Per-worker child rows of a run. Its (RUN_ID, WORKER_ID) is the metrics WORKER row''s (RUN.RUN_KEY, WORKER_KEY).';

-- ═══════════════════════════════════════════════════════════════════════
-- Application groups, applications, capacity, workers
-- ═══════════════════════════════════════════════════════════════════════

-- A team's set of applications. GROUP_ID is the value workers send as
-- ?groupId= on every metrics POST; upper-cased it prefixes the group's fact
-- tables in the metrics schema (cps -> CPS_METRICS, CPS_METRICS_H), and it
-- must name a row of GROUP_REGISTRY there.
CREATE TABLE ORCH_APPLICATION_GROUP (
    GROUP_ID             VARCHAR2(30 CHAR)   NOT NULL,   -- [a-z][a-z0-9_]{0,29}; = metrics GROUP_REGISTRY.GROUP_ID (e.g. cps)
    NAME                 VARCHAR2(255 CHAR)  NOT NULL,   -- display name (e.g. Servicing MQ)
    DESCRIPTION          VARCHAR2(4000 CHAR),
    GRAFANA_LIVE_URL     VARCHAR2(2000 CHAR),                    -- the group's live dashboard (reads <P>_METRICS); the UI's "Open in Grafana" default
    GRAFANA_HISTORY_URL  VARCHAR2(2000 CHAR),                    -- the history dashboard (reads <P>_METRICS_H); optional, falls back to live
    HOT_DAYS             NUMBER(5)           DEFAULT 7 NOT NULL,      -- days the live dashboard covers (= the group's hot retention); older runs open history
    -- The worker pool's policy — the pool is the group's (GROUP-CAPACITY, 2026-08-30), so its rules are too.
    RECYCLE_POLICY       VARCHAR2(32 CHAR) DEFAULT 'REUSE' NOT NULL,
    MAX_RUNS_PER_POD     NUMBER(10),
    POD_MAX_AGE_HOURS    NUMBER(10),
    ALWAYS_ON            NUMBER(1)         DEFAULT 0 NOT NULL,      -- DRAIN_REGION jobs skip this group's workers
    CREATED_AT           TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_APPLICATION_GROUP_PK PRIMARY KEY (GROUP_ID),
    CONSTRAINT ORCH_APPLICATION_GROUP_HOT_DAYS_CHK CHECK (HOT_DAYS > 0),
    CONSTRAINT ORCH_APPLICATION_GROUP_NAME_UQ UNIQUE (NAME),
    CONSTRAINT ORCH_APPLICATION_GROUP_ALWAYS_ON_CHK CHECK (ALWAYS_ON IN (0, 1)),
    CONSTRAINT ORCH_APPLICATION_GROUP_RECYCLE_POLICY_CHK
        CHECK (RECYCLE_POLICY IN ('REUSE', 'MAX_RUNS', 'MAX_AGE', 'BOTH', 'EVERY_RUN', 'DRAIN_AFTER_RUN')),
    -- The thresholds exist exactly when the policy reads them.
    CONSTRAINT ORCH_APPLICATION_GROUP_RECYCLE_THRESHOLDS_CHK CHECK (
           (RECYCLE_POLICY IN ('REUSE', 'EVERY_RUN', 'DRAIN_AFTER_RUN') AND MAX_RUNS_PER_POD IS NULL     AND POD_MAX_AGE_HOURS IS NULL)
        OR (RECYCLE_POLICY = 'MAX_RUNS'                                AND MAX_RUNS_PER_POD IS NOT NULL AND POD_MAX_AGE_HOURS IS NULL)
        OR (RECYCLE_POLICY = 'MAX_AGE'                                 AND MAX_RUNS_PER_POD IS NULL     AND POD_MAX_AGE_HOURS IS NOT NULL)
        OR (RECYCLE_POLICY = 'BOTH'                                    AND MAX_RUNS_PER_POD IS NOT NULL AND POD_MAX_AGE_HOURS IS NOT NULL)),
    CONSTRAINT ORCH_APPLICATION_GROUP_MAX_RUNS_PER_POD_CHK  CHECK (MAX_RUNS_PER_POD  IS NULL OR MAX_RUNS_PER_POD  BETWEEN 1 AND 10000),
    CONSTRAINT ORCH_APPLICATION_GROUP_POD_MAX_AGE_HOURS_CHK CHECK (POD_MAX_AGE_HOURS IS NULL OR POD_MAX_AGE_HOURS BETWEEN 1 AND 720)
);
COMMENT ON TABLE ORCH_APPLICATION_GROUP IS
    'A team''s applications share one group: its groupId routes their metrics to the group''s own fact tables, and the group owns the worker pool (ORCH_GROUP_CAPACITY, ORCH_POD) and its recycle policy.';

CREATE TABLE ORCH_APPLICATION (
    APPLICATION_ID          VARCHAR2(64 CHAR)   NOT NULL,
    NAME                    VARCHAR2(255 CHAR)  NOT NULL,
    SEAL_ID                 VARCHAR2(128 CHAR),
    DESCRIPTION             VARCHAR2(4000 CHAR),
    METRICS_GROUP_ID        VARCHAR2(30 CHAR)   NOT NULL,                       -- FK applicationGroup: the team whose pool the app runs on and whose tables take its metrics
    METRICS_APPLICATION     VARCHAR2(64 CHAR),                                 -- the group classifier's value for this app's labels (LABEL.APPLICATION); the dashboards are the group's
    HEALTH_ENDPOINTS        CLOB                DEFAULT '[]' NOT NULL,  -- JSON array of URLs polled by ApplicationHealthPoller
    CREATED_AT              TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    LAST_HEALTH_CHECKED_AT  TIMESTAMP(3) WITH TIME ZONE,
    LAST_HEALTH_STATUS      VARCHAR2(32 CHAR),
    LAST_HEALTH_DETAILS     CLOB,                                      -- JSON array of {url, statusCode, latencyMs, error?, ok}
    HIDDEN_AT               TIMESTAMP(3) WITH TIME ZONE,               -- soft-deleted when set; the name stays reserved
    CONSTRAINT ORCH_APPLICATION_PK PRIMARY KEY (APPLICATION_ID),
    CONSTRAINT ORCH_APPLICATION_NAME_UQ UNIQUE (NAME),
    CONSTRAINT ORCH_APPLICATION_HEALTH_ENDPOINTS_CHK CHECK (HEALTH_ENDPOINTS IS JSON),
    CONSTRAINT ORCH_APPLICATION_LAST_HEALTH_DETAILS_CHK CHECK (LAST_HEALTH_DETAILS IS JSON),
    -- No ON DELETE action: a group with applications (visible or archived) cannot be deleted.
    CONSTRAINT ORCH_APPLICATION_METRICS_GROUP_FK FOREIGN KEY (METRICS_GROUP_ID)
        REFERENCES ORCH_APPLICATION_GROUP (GROUP_ID)
);
-- FK index: without it a group delete takes a table lock on ORCH_APPLICATION.
CREATE INDEX ORCH_APPLICATION_METRICS_GROUP_ID_IDX
    ON ORCH_APPLICATION (METRICS_GROUP_ID);
COMMENT ON TABLE ORCH_APPLICATION IS
    'Registered application: operator metadata, its group (required — the pool and the metrics tables are the group''s), last health snapshot.';

CREATE TABLE ORCH_GROUP_CAPACITY (
    GROUP_ID       VARCHAR2(30 CHAR)  NOT NULL,
    REGION         VARCHAR2(64 CHAR)  NOT NULL,
    MAX_AVAILABLE  NUMBER(10)         NOT NULL,   -- workers budgeted for this group in this region; 0 allowed
    CREATED_AT     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UPDATED_AT     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_GROUP_CAPACITY_PK PRIMARY KEY (GROUP_ID, REGION),
    CONSTRAINT ORCH_GROUP_CAPACITY_GROUP_FK FOREIGN KEY (GROUP_ID)
        REFERENCES ORCH_APPLICATION_GROUP (GROUP_ID) ON DELETE CASCADE,
    CONSTRAINT ORCH_GROUP_CAPACITY_MAX_AVAILABLE_CHK CHECK (MAX_AVAILABLE BETWEEN 0 AND 1000)
);
COMMENT ON TABLE ORCH_GROUP_CAPACITY IS
    'Per-(group, region) worker budget — every application in the group draws on it. REGION is the placement axis everywhere; the UI''s "data center" is display-only.';

CREATE TABLE ORCH_POD (
    POD_ID          VARCHAR2(64 CHAR)   NOT NULL,   -- DNS-1123 label
    REGION          VARCHAR2(64 CHAR)   NOT NULL,
    BASE_URL        VARCHAR2(512 CHAR)  NOT NULL,
    STATE           VARCHAR2(32 CHAR)   NOT NULL,
    GROUP_ID        VARCHAR2(30 CHAR)   NOT NULL,                     -- the pool it belongs to; any application in the group may claim it
    SOURCE          VARCHAR2(16 CHAR)   DEFAULT 'DYNAMIC' NOT NULL,  -- DYNAMIC: control-plane owned; STATIC: operator-declared
    RUNS_SERVED     NUMBER(19)          DEFAULT 0 NOT NULL,          -- bumped inside the claim transaction
    IMAGE_DIGEST    VARCHAR2(128 CHAR),
    LAST_HEARTBEAT  TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    REGISTERED_AT   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    PROVISIONED_AT  TIMESTAMP(3) WITH TIME ZONE,                     -- container creation; survives a worker restart
    CONSTRAINT ORCH_POD_PK PRIMARY KEY (POD_ID),
    CONSTRAINT ORCH_POD_GROUP_FK FOREIGN KEY (GROUP_ID)
        REFERENCES ORCH_APPLICATION_GROUP (GROUP_ID),
    CONSTRAINT ORCH_POD_SOURCE_CHK CHECK (SOURCE IN ('DYNAMIC', 'STATIC'))
);
CREATE INDEX ORCH_POD_STATE_LAST_HEARTBEAT_IDX
    ON ORCH_POD (STATE, LAST_HEARTBEAT DESC);
-- The claim's candidate scan; also indexes the FK so a group delete never
-- takes a table lock on ORCH_POD.
CREATE INDEX ORCH_POD_GROUP_REGION_STATE_IDX
    ON ORCH_POD (GROUP_ID, REGION, STATE, LAST_HEARTBEAT DESC);
COMMENT ON TABLE ORCH_POD IS
    'Worker registry, one row per worker. Claimed IDLE → run member through ORCH_CLAIMS.CLAIM_IDLE_PODS; LOST by heartbeat age (direct regions) or the kubelet (routed regions).';

-- ═══════════════════════════════════════════════════════════════════════
-- Audit trail, automation, reports
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE ORCH_RUN_EVENT (
    EVENT_ID      VARCHAR2(64 CHAR)   NOT NULL,
    RUN_ID        VARCHAR2(64 CHAR)   NOT NULL,
    EVENT_TYPE    VARCHAR2(64 CHAR)   NOT NULL,
    ACTOR         VARCHAR2(255 CHAR)  DEFAULT 'anonymous' NOT NULL,   -- X-Actor header
    ACTOR_SOURCE  VARCHAR2(64 CHAR)   DEFAULT 'anonymous' NOT NULL,
    PAYLOAD       CLOB                DEFAULT '{}' NOT NULL,
    RESULT        VARCHAR2(32 CHAR)   NOT NULL,
    OCCURRED_AT   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_RUN_EVENT_PK PRIMARY KEY (EVENT_ID),
    CONSTRAINT ORCH_RUN_EVENT_RUN_FK FOREIGN KEY (RUN_ID)
        REFERENCES ORCH_RUN (RUN_ID) ON DELETE CASCADE,
    CONSTRAINT ORCH_RUN_EVENT_PAYLOAD_CHK CHECK (PAYLOAD IS JSON)
);
CREATE INDEX ORCH_RUN_EVENT_RUN_ID_OCCURRED_AT_IDX
    ON ORCH_RUN_EVENT (RUN_ID, OCCURRED_AT DESC);
CREATE INDEX ORCH_RUN_EVENT_EVENT_TYPE_OCCURRED_AT_IDX
    ON ORCH_RUN_EVENT (EVENT_TYPE, OCCURRED_AT DESC);
COMMENT ON TABLE ORCH_RUN_EVENT IS
    'Append-only audit log of run mutations (start / scale / drain / abort / stop), one row per operator action.';

CREATE TABLE ORCH_CRON_JOB (
    CRON_JOB_ID        VARCHAR2(64 CHAR)   NOT NULL,
    NAME               VARCHAR2(255 CHAR)  NOT NULL,
    KIND               VARCHAR2(32 CHAR)   DEFAULT 'LAUNCH_RUN' NOT NULL,
    APPLICATION_NAME   VARCHAR2(255 CHAR),                                   -- NULL for platform-level report kinds
    TEMPLATE_BLOB_ID   VARCHAR2(64 CHAR),
    REGION             VARCHAR2(64 CHAR),
    CRON_EXPRESSION    VARCHAR2(128 CHAR)  NOT NULL,
    TIME_ZONE          VARCHAR2(64 CHAR)   DEFAULT 'UTC' NOT NULL,
    ENABLED            NUMBER(1)           DEFAULT 1 NOT NULL,
    RECIPIENTS         VARCHAR2(4000 CHAR),                                  -- comma-separated emails (report kinds)
    CUSTOM_SUBJECT     VARCHAR2(512 CHAR),
    CUSTOM_INTRO       VARCHAR2(4000 CHAR),
    CREATED_BY         VARCHAR2(255 CHAR),
    CREATED_AT         TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    LAST_FIRED_AT      TIMESTAMP(3) WITH TIME ZONE,
    LAST_FIRED_RUN_ID  VARCHAR2(64 CHAR),
    LAST_FIRE_STATUS   VARCHAR2(64 CHAR),
    NEXT_FIRE_AT       TIMESTAMP(3) WITH TIME ZONE,                          -- advanced inside the claim transaction
    CLAIMED_AT         TIMESTAMP(3) WITH TIME ZONE,
    CONSTRAINT ORCH_CRON_JOB_PK PRIMARY KEY (CRON_JOB_ID),
    CONSTRAINT ORCH_CRON_JOB_APP_NAME_UQ UNIQUE (APPLICATION_NAME, NAME),
    CONSTRAINT ORCH_CRON_JOB_ENABLED_CHK CHECK (ENABLED IN (0, 1)),
    CONSTRAINT ORCH_CRON_JOB_KIND_CHK CHECK (KIND IN
        ('LAUNCH_RUN', 'DRAIN_REGION', 'PROVISION_REGION', 'INFRA_READINESS', 'DAILY_REPORT')),
    CONSTRAINT ORCH_CRON_JOB_KIND_FIELDS_CHK CHECK (
           (KIND = 'LAUNCH_RUN' AND APPLICATION_NAME IS NOT NULL AND TEMPLATE_BLOB_ID IS NOT NULL)
        OR (KIND IN ('DRAIN_REGION', 'PROVISION_REGION') AND APPLICATION_NAME IS NOT NULL AND REGION IS NOT NULL)
        OR (KIND IN ('INFRA_READINESS', 'DAILY_REPORT')))
);
-- Platform-level jobs (NULL application) also have unique names through
-- ORCH_CRON_JOB_APP_NAME_UQ: Oracle lets only an all-NULL key repeat, so two
-- (NULL, 'daily') rows collide. Postgres needed a partial index for this.
-- The claim's candidate scan.
CREATE INDEX ORCH_CRON_JOB_ENABLED_NEXT_FIRE_AT_IDX
    ON ORCH_CRON_JOB (ENABLED, NEXT_FIRE_AT);
COMMENT ON TABLE ORCH_CRON_JOB IS
    'Persistent schedules fired by a DB-claim sweep (ORCH_CLAIMS.CLAIM_DUE_CRON_JOBS). NEXT_FIRE_AT is advanced in the claim transaction, so a mid-fire crash errs toward not double-firing.';

CREATE TABLE ORCH_CRON_JOB_FIRE_HISTORY (
    FIRE_ID       VARCHAR2(64 CHAR)   NOT NULL,
    CRON_JOB_ID   VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: history survives schedule deletion
    FIRED_AT      TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    OUTCOME       VARCHAR2(32 CHAR)   NOT NULL,   -- LAUNCHED / SKIPPED / FAILED / DISABLED
    RUN_ID        VARCHAR2(64 CHAR),
    ERROR_REASON  VARCHAR2(4000 CHAR),
    CONSTRAINT ORCH_CRON_JOB_FIRE_HISTORY_PK PRIMARY KEY (FIRE_ID)
);
CREATE INDEX ORCH_CRON_JOB_FIRE_HISTORY_JOB_FIRED_AT_IDX
    ON ORCH_CRON_JOB_FIRE_HISTORY (CRON_JOB_ID, FIRED_AT DESC);

CREATE TABLE ORCH_APPLICATION_HEALTH_HISTORY (
    HISTORY_ID      VARCHAR2(64 CHAR)   NOT NULL,
    APPLICATION_ID  VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: the daily report reads it after an app is purged
    STATUS          VARCHAR2(32 CHAR)   NOT NULL,
    CHANGED_AT      TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    DETAILS         CLOB,
    CONSTRAINT ORCH_APPLICATION_HEALTH_HISTORY_PK PRIMARY KEY (HISTORY_ID),
    CONSTRAINT ORCH_APPLICATION_HEALTH_HISTORY_DETAILS_CHK CHECK (DETAILS IS JSON)
);
CREATE INDEX ORCH_APPLICATION_HEALTH_HISTORY_APP_IDX
    ON ORCH_APPLICATION_HEALTH_HISTORY (APPLICATION_ID, CHANGED_AT DESC);
COMMENT ON TABLE ORCH_APPLICATION_HEALTH_HISTORY IS
    'Append-only health transitions per application (status changes only); the infra-readiness email derives downtime windows from it.';

CREATE TABLE ORCH_RUN_TREND (
    RUN_ID            VARCHAR2(64 CHAR)   NOT NULL,
    APPLICATION_NAME  VARCHAR2(255 CHAR),
    P50_MS            BINARY_DOUBLE       NOT NULL,
    P95_MS            BINARY_DOUBLE       NOT NULL,
    P99_MS            BINARY_DOUBLE       NOT NULL,
    ERROR_RATE        BINARY_DOUBLE       NOT NULL,
    THROUGHPUT_RPS    BINARY_DOUBLE       NOT NULL,
    COMPLETED_AT      TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_RUN_TREND_PK PRIMARY KEY (RUN_ID)
);
CREATE INDEX ORCH_RUN_TREND_APP_COMPLETED_AT_IDX
    ON ORCH_RUN_TREND (APPLICATION_NAME, COMPLETED_AT DESC);
COMMENT ON TABLE ORCH_RUN_TREND IS
    'One frozen aggregate per COMPLETED run, written on the terminal transition; the daily report''s 7-day baseline without touching the metrics schema.';

CREATE TABLE ORCH_AI_RESPONSE (
    KIND            VARCHAR2(32 CHAR)   NOT NULL,
    CACHE_KEY       VARCHAR2(255 CHAR)  NOT NULL,   -- a run id, or "runA|runB" for a comparison
    PROMPT_VERSION  VARCHAR2(32 CHAR)   NOT NULL,
    RESPONSE        CLOB                NOT NULL,   -- { summary, findings[] }, kind-specific
    MODEL           VARCHAR2(128 CHAR)  NOT NULL,
    TOKENS_IN       NUMBER(10)          NOT NULL,
    TOKENS_OUT      NUMBER(10)          NOT NULL,
    CREATED_AT      TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_AI_RESPONSE_PK PRIMARY KEY (KIND, CACHE_KEY, PROMPT_VERSION),
    CONSTRAINT ORCH_AI_RESPONSE_RESPONSE_CHK CHECK (RESPONSE IS JSON)
);
CREATE INDEX ORCH_AI_RESPONSE_CREATED_AT_IDX
    ON ORCH_AI_RESPONSE (CREATED_AT);
COMMENT ON TABLE ORCH_AI_RESPONSE IS
    'Durable cache of Claude-generated insights keyed (kind, cacheKey, promptVersion); a miss costs a bill, so it lives here rather than in Redis.';

CREATE TABLE ORCH_PURGE_AUDIT (
    PURGE_ID             VARCHAR2(64 CHAR)   NOT NULL,
    TARGET_TYPE          VARCHAR2(32 CHAR)   NOT NULL,   -- RUN / APPLICATION
    TARGET_ID            VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: the tombstone outlives its target
    APPLICATION_NAME     VARCHAR2(255 CHAR),
    ACTOR                VARCHAR2(255 CHAR)  NOT NULL,
    REASON               VARCHAR2(4000 CHAR),
    METRIC_ROWS_DELETED  NUMBER(19),
    BLOBS_DELETED        NUMBER(10),
    CHILD_RUNS_PURGED    NUMBER(10),
    PURGED_AT            TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    DETAILS              CLOB,
    CONSTRAINT ORCH_PURGE_AUDIT_PK PRIMARY KEY (PURGE_ID),
    CONSTRAINT ORCH_PURGE_AUDIT_DETAILS_CHK CHECK (DETAILS IS JSON)
);
CREATE INDEX ORCH_PURGE_AUDIT_PURGED_AT_IDX
    ON ORCH_PURGE_AUDIT (PURGED_AT DESC);

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_CLAIMS — "LIMIT n FOR UPDATE SKIP LOCKED" done the Oracle way
-- ═══════════════════════════════════════════════════════════════════════

CREATE OR REPLACE TYPE ORCH_ID_TABLE AS TABLE OF VARCHAR2(64 CHAR);
/

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

END ORCH_CLAIMS;
/

-- ═══════════════════════════════════════════════════════════════════════
-- Grants — the platform's three users; the owner keeps DDL
-- ═══════════════════════════════════════════════════════════════════════

-- GLOBAL_ORCHESTRATOR_WRITER — the hub's run-state pool.
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_RUN                        TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_RUN_FLEET_MEMBER           TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_POD                        TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_APPLICATION_GROUP          TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_APPLICATION                TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_GROUP_CAPACITY             TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_CRON_JOB                   TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_AI_RESPONSE                TO GLOBAL_ORCHESTRATOR_WRITER;
-- Append-only surfaces; DELETE only where a purge path exists.
GRANT SELECT, INSERT         ON ORCH_RUN_EVENT                          TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT         ON ORCH_CRON_JOB_FIRE_HISTORY              TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, DELETE ON ORCH_APPLICATION_HEALTH_HISTORY         TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT, DELETE ON ORCH_RUN_TREND                          TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT SELECT, INSERT         ON ORCH_PURGE_AUDIT                        TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT EXECUTE                ON ORCH_CLAIMS                             TO GLOBAL_ORCHESTRATOR_WRITER;
GRANT EXECUTE                ON ORCH_ID_TABLE                           TO GLOBAL_ORCHESTRATOR_WRITER;

-- METRICS_READER — the hub's read pool on the shared dimensions; each
-- group's rendered bundle adds its own fact tables (readers/purgers).
GRANT SELECT ON LABEL           TO METRICS_READER;
GRANT SELECT ON RUN             TO METRICS_READER;
GRANT SELECT ON WORKER          TO METRICS_READER;
GRANT SELECT ON GROUP_REGISTRY  TO METRICS_READER;
GRANT SELECT ON METRICS_H_AUDIT TO METRICS_READER;
-- METRICS_PURGER — the hub's purge pool: a purge removes a run's facts
-- first, then its run-scoped dimensions; LABEL is shared and never deleted.
GRANT SELECT         ON LABEL          TO METRICS_PURGER;
GRANT SELECT         ON GROUP_REGISTRY TO METRICS_PURGER;
GRANT SELECT, DELETE ON RUN            TO METRICS_PURGER;
GRANT SELECT, DELETE ON WORKER         TO METRICS_PURGER;
