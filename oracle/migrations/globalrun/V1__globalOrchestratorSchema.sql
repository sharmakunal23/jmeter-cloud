-- V1__globalOrchestratorSchema.sql — the control plane's schema, consolidated
-- from the 29 Postgres migrations into their as-built shape (ORACLE-MIGRATION
-- OM-4, 2026-08-28). Applied by the schema owner ("globalOrchestrator"); the
-- service connects as "globalOrchestratorWriter" with the grants at the end.
-- Written to the 19c-compatible subset and validated on 23ai Free.
--
-- Type rules used throughout: ids VARCHAR2(64 CHAR); names 255; free text
-- 4000; JSON documents CLOB CHECK (IS JSON); booleans NUMBER(1) IN (0,1);
-- instants TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP. Every VARCHAR2
-- declares CHAR semantics because the instance default is BYTE.
--
-- The one thing that is not a plain translation: the two claim queries.
-- Oracle locks a FOR UPDATE cursor's whole result set at open when it has an
-- ORDER BY, so "LIMIT n FOR UPDATE SKIP LOCKED" is reproduced by the claims
-- package below — an ordered candidate pass, then one SKIP LOCKED lock per
-- row until n are held.

-- ═══════════════════════════════════════════════════════════════════════
-- Runs and their fleet members
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE "globalOrchestrator"."run" (
    "runId"            VARCHAR2(64 CHAR)   NOT NULL,
    "originRegion"     VARCHAR2(64 CHAR)   NOT NULL,
    "testPlanBlobId"   VARCHAR2(64 CHAR)   NOT NULL,
    "dataFilesBlobId"  VARCHAR2(64 CHAR),
    "initiatedBy"      VARCHAR2(255 CHAR)  NOT NULL,
    "state"            VARCHAR2(32 CHAR)   NOT NULL,
    "stateReason"      VARCHAR2(4000 CHAR),
    "application"      VARCHAR2(255 CHAR),                         -- application name; NULL on legacy rows
    "metricsGroupId"   VARCHAR2(30 CHAR),                          -- the application's group AT LAUNCH: the run's rows live in <UPPER(id)>_METRICS forever, even if the app moves group
    "saveResults"      NUMBER(1)           DEFAULT 0 NOT NULL,     -- workers upload their JTL on COMPLETE
    "createdAt"        TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "startedAt"        TIMESTAMP(3) WITH TIME ZONE,
    "completedAt"      TIMESTAMP(3) WITH TIME ZONE,
    "hiddenAt"         TIMESTAMP(3) WITH TIME ZONE,                -- soft-deleted when set; terminal runs only
    CONSTRAINT "run_pk" PRIMARY KEY ("runId"),
    CONSTRAINT "run_saveResults_chk" CHECK ("saveResults" IN (0, 1))
);
CREATE INDEX "globalOrchestrator"."run_createdAt_idx"
    ON "globalOrchestrator"."run" ("createdAt" DESC);
CREATE INDEX "globalOrchestrator"."run_application_createdAt_idx"
    ON "globalOrchestrator"."run" ("application", "createdAt" DESC);
CREATE INDEX "globalOrchestrator"."run_state_createdAt_idx"
    ON "globalOrchestrator"."run" ("state", "createdAt" DESC);
COMMENT ON TABLE "globalOrchestrator"."run" IS
    'One row per fleet run. Owned by jmeter-global-orchestrator; runId is a server-issued ULID.';

CREATE TABLE "globalOrchestrator"."runFleetMember" (
    "runId"            VARCHAR2(64 CHAR)   NOT NULL,
    "workerId"         VARCHAR2(64 CHAR)   NOT NULL,
    "region"           VARCHAR2(64 CHAR)   NOT NULL,
    "state"            VARCHAR2(32 CHAR)   NOT NULL,
    "stateReason"      VARCHAR2(4000 CHAR),
    "fanoutStatusCode" NUMBER(10),
    "podBaseUrl"       VARCHAR2(512 CHAR),
    "properties"       CLOB                DEFAULT '{}' NOT NULL,  -- per-node JMeter -J properties at launch
    "joinedAtSecond"   NUMBER(19),                                 -- NULL = original fleet; >= 0 = scale-up joiner
    "createdAt"        TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "startedAt"        TIMESTAMP(3) WITH TIME ZONE,
    "completedAt"      TIMESTAMP(3) WITH TIME ZONE,
    CONSTRAINT "runFleetMember_pk" PRIMARY KEY ("runId", "workerId"),
    CONSTRAINT "runFleetMember_run_fk" FOREIGN KEY ("runId")
        REFERENCES "globalOrchestrator"."run" ("runId") ON DELETE CASCADE,
    CONSTRAINT "runFleetMember_properties_chk" CHECK ("properties" IS JSON)
);
CREATE INDEX "globalOrchestrator"."runFleetMember_state_idx"
    ON "globalOrchestrator"."runFleetMember" ("runId", "state");
-- The claim's NOT EXISTS probe and the per-worker history both walk this.
CREATE INDEX "globalOrchestrator"."runFleetMember_workerId_idx"
    ON "globalOrchestrator"."runFleetMember" ("workerId", "state", "createdAt" DESC);
COMMENT ON TABLE "globalOrchestrator"."runFleetMember" IS
    'Per-worker child rows of a run. Joins to metrics."workerMetric" on (runId, workerId).';

-- ═══════════════════════════════════════════════════════════════════════
-- Application groups, applications, capacity, workers
-- ═══════════════════════════════════════════════════════════════════════

-- A team's set of applications. "groupId" is the value workers send as
-- ?groupId= on every metrics POST; upper-cased it prefixes the group's fact
-- tables in the metrics schema (cps -> CPS_METRICS, CPS_METRICS_H), and it
-- must name a row of GROUP_REGISTRY there.
CREATE TABLE "globalOrchestrator"."applicationGroup" (
    "groupId"      VARCHAR2(30 CHAR)   NOT NULL,   -- [a-z][a-z0-9_]{0,29}; = metrics GROUP_REGISTRY.GROUP_ID (e.g. cps)
    "name"         VARCHAR2(255 CHAR)  NOT NULL,   -- display name (e.g. Servicing MQ)
    "description"  VARCHAR2(4000 CHAR),
    "grafanaLiveUrl"    VARCHAR2(2000 CHAR),                    -- the group's live dashboard (reads <P>_METRICS); the UI's "Open in Grafana" default
    "grafanaHistoryUrl" VARCHAR2(2000 CHAR),                    -- the history dashboard (reads <P>_METRICS_H); optional, falls back to live
    "hotDays"      NUMBER(5)           DEFAULT 7 NOT NULL,      -- days the live dashboard covers (= the group's hot retention); older runs open history
    "createdAt"    TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT "applicationGroup_pk" PRIMARY KEY ("groupId"),
    CONSTRAINT "applicationGroup_hotDays_ck" CHECK ("hotDays" > 0),
    CONSTRAINT "applicationGroup_name_uq" UNIQUE ("name")
);
COMMENT ON TABLE "globalOrchestrator"."applicationGroup" IS
    'A team''s applications share one group: its groupId routes their metrics to the group''s own fact tables.';

CREATE TABLE "globalOrchestrator"."application" (
    "applicationId"       VARCHAR2(64 CHAR)   NOT NULL,
    "name"                VARCHAR2(255 CHAR)  NOT NULL,
    "sealId"              VARCHAR2(128 CHAR),
    "description"         VARCHAR2(4000 CHAR),
    "metricsGroupId"      VARCHAR2(30 CHAR),                                 -- FK applicationGroup; NULL = ungrouped, metrics not routed
    "metricsApplication"  VARCHAR2(64 CHAR),                                 -- the group classifier's value for this app's labels (LABEL.APPLICATION); the dashboards are the group's
    "healthEndpoints"     CLOB                DEFAULT '[]' NOT NULL,  -- JSON array of URLs polled by ApplicationHealthPoller
    "recyclePolicy"       VARCHAR2(32 CHAR)   DEFAULT 'REUSE' NOT NULL,
    "maxRunsPerPod"       NUMBER(10),
    "podMaxAgeHours"      NUMBER(10),
    "alwaysOn"            NUMBER(1)           DEFAULT 0 NOT NULL,   -- DRAIN_REGION jobs skip this app
    "createdAt"           TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "lastHealthCheckedAt" TIMESTAMP(3) WITH TIME ZONE,
    "lastHealthStatus"    VARCHAR2(32 CHAR),
    "lastHealthDetails"   CLOB,                                      -- JSON array of {url, statusCode, latencyMs, error?, ok}
    "hiddenAt"            TIMESTAMP(3) WITH TIME ZONE,               -- soft-deleted when set; the name stays reserved
    CONSTRAINT "application_pk" PRIMARY KEY ("applicationId"),
    CONSTRAINT "application_name_uq" UNIQUE ("name"),
    CONSTRAINT "application_healthEndpoints_chk" CHECK ("healthEndpoints" IS JSON),
    CONSTRAINT "application_lastHealthDetails_chk" CHECK ("lastHealthDetails" IS JSON),
    CONSTRAINT "application_alwaysOn_chk" CHECK ("alwaysOn" IN (0, 1)),
    CONSTRAINT "application_recyclePolicy_chk"
        CHECK ("recyclePolicy" IN ('REUSE', 'MAX_RUNS', 'MAX_AGE', 'BOTH', 'EVERY_RUN', 'DRAIN_AFTER_RUN')),
    -- The thresholds exist exactly when the policy reads them.
    CONSTRAINT "application_recycleThresholds_chk" CHECK (
           ("recyclePolicy" IN ('REUSE', 'EVERY_RUN', 'DRAIN_AFTER_RUN') AND "maxRunsPerPod" IS NULL     AND "podMaxAgeHours" IS NULL)
        OR ("recyclePolicy" = 'MAX_RUNS'                                AND "maxRunsPerPod" IS NOT NULL AND "podMaxAgeHours" IS NULL)
        OR ("recyclePolicy" = 'MAX_AGE'                                 AND "maxRunsPerPod" IS NULL     AND "podMaxAgeHours" IS NOT NULL)
        OR ("recyclePolicy" = 'BOTH'                                    AND "maxRunsPerPod" IS NOT NULL AND "podMaxAgeHours" IS NOT NULL)),
    CONSTRAINT "application_maxRunsPerPod_chk"  CHECK ("maxRunsPerPod"  IS NULL OR "maxRunsPerPod"  BETWEEN 1 AND 10000),
    CONSTRAINT "application_podMaxAgeHours_chk" CHECK ("podMaxAgeHours" IS NULL OR "podMaxAgeHours" BETWEEN 1 AND 720),
    -- No ON DELETE action: a group with applications (visible or archived) cannot be deleted.
    CONSTRAINT "application_metricsGroup_fk" FOREIGN KEY ("metricsGroupId")
        REFERENCES "globalOrchestrator"."applicationGroup" ("groupId")
);
-- FK index: without it a group delete takes a table lock on application.
CREATE INDEX "globalOrchestrator"."application_metricsGroupId_idx"
    ON "globalOrchestrator"."application" ("metricsGroupId");
COMMENT ON TABLE "globalOrchestrator"."application" IS
    'Registered application: operator metadata, metrics group, worker recycle policy, last health snapshot.';

CREATE TABLE "globalOrchestrator"."applicationCapacity" (
    "applicationId"  VARCHAR2(64 CHAR)  NOT NULL,
    "region"         VARCHAR2(64 CHAR)  NOT NULL,
    "maxAvailable"   NUMBER(10)         NOT NULL,   -- workers budgeted for this app in this region; 0 allowed
    "createdAt"      TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "updatedAt"      TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT "applicationCapacity_pk" PRIMARY KEY ("applicationId", "region"),
    CONSTRAINT "applicationCapacity_app_fk" FOREIGN KEY ("applicationId")
        REFERENCES "globalOrchestrator"."application" ("applicationId") ON DELETE CASCADE,
    CONSTRAINT "applicationCapacity_maxAvailable_chk" CHECK ("maxAvailable" BETWEEN 0 AND 1000)
);
COMMENT ON TABLE "globalOrchestrator"."applicationCapacity" IS
    'Per-(application, region) worker budget. "region" is the placement axis everywhere; the UI''s "data center" is display-only.';

CREATE TABLE "globalOrchestrator"."pod" (
    "podId"          VARCHAR2(64 CHAR)   NOT NULL,   -- DNS-1123 label
    "region"         VARCHAR2(64 CHAR)   NOT NULL,
    "baseUrl"        VARCHAR2(512 CHAR)  NOT NULL,
    "state"          VARCHAR2(32 CHAR)   NOT NULL,
    "applicationId"  VARCHAR2(64 CHAR)   NOT NULL,
    "source"         VARCHAR2(16 CHAR)   DEFAULT 'DYNAMIC' NOT NULL,  -- DYNAMIC: control-plane owned; STATIC: operator-declared
    "runsServed"     NUMBER(19)          DEFAULT 0 NOT NULL,          -- bumped inside the claim transaction
    "imageDigest"    VARCHAR2(128 CHAR),
    "lastHeartbeat"  TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    "registeredAt"   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "provisionedAt"  TIMESTAMP(3) WITH TIME ZONE,                     -- container creation; survives a worker restart
    CONSTRAINT "pod_pk" PRIMARY KEY ("podId"),
    CONSTRAINT "pod_application_fk" FOREIGN KEY ("applicationId")
        REFERENCES "globalOrchestrator"."application" ("applicationId"),
    CONSTRAINT "pod_source_chk" CHECK ("source" IN ('DYNAMIC', 'STATIC'))
);
CREATE INDEX "globalOrchestrator"."pod_state_lastHeartbeat_idx"
    ON "globalOrchestrator"."pod" ("state", "lastHeartbeat" DESC);
-- The claim's candidate scan; also indexes the FK so an application delete
-- never takes a table lock on "pod".
CREATE INDEX "globalOrchestrator"."pod_application_region_state_idx"
    ON "globalOrchestrator"."pod" ("applicationId", "region", "state", "lastHeartbeat" DESC);
COMMENT ON TABLE "globalOrchestrator"."pod" IS
    'Worker registry, one row per worker. Claimed IDLE → run member through claims.claimIdlePods; LOST by heartbeat age (direct regions) or the kubelet (routed regions).';

-- ═══════════════════════════════════════════════════════════════════════
-- Audit trail, automation, reports
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE "globalOrchestrator"."runEvent" (
    "eventId"      VARCHAR2(64 CHAR)   NOT NULL,
    "runId"        VARCHAR2(64 CHAR)   NOT NULL,
    "eventType"    VARCHAR2(64 CHAR)   NOT NULL,
    "actor"        VARCHAR2(255 CHAR)  DEFAULT 'anonymous' NOT NULL,   -- X-Actor header
    "actorSource"  VARCHAR2(64 CHAR)   DEFAULT 'anonymous' NOT NULL,
    "payload"      CLOB                DEFAULT '{}' NOT NULL,
    "result"       VARCHAR2(32 CHAR)   NOT NULL,
    "occurredAt"   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT "runEvent_pk" PRIMARY KEY ("eventId"),
    CONSTRAINT "runEvent_run_fk" FOREIGN KEY ("runId")
        REFERENCES "globalOrchestrator"."run" ("runId") ON DELETE CASCADE,
    CONSTRAINT "runEvent_payload_chk" CHECK ("payload" IS JSON)
);
CREATE INDEX "globalOrchestrator"."runEvent_runId_occurredAt_idx"
    ON "globalOrchestrator"."runEvent" ("runId", "occurredAt" DESC);
CREATE INDEX "globalOrchestrator"."runEvent_eventType_occurredAt_idx"
    ON "globalOrchestrator"."runEvent" ("eventType", "occurredAt" DESC);
COMMENT ON TABLE "globalOrchestrator"."runEvent" IS
    'Append-only audit log of run mutations (start / scale / drain / abort / stop), one row per operator action.';

CREATE TABLE "globalOrchestrator"."cronJob" (
    "cronJobId"       VARCHAR2(64 CHAR)   NOT NULL,
    "name"            VARCHAR2(255 CHAR)  NOT NULL,
    "kind"            VARCHAR2(32 CHAR)   DEFAULT 'LAUNCH_RUN' NOT NULL,
    "applicationName" VARCHAR2(255 CHAR),                                   -- NULL for platform-level report kinds
    "templateBlobId"  VARCHAR2(64 CHAR),
    "region"          VARCHAR2(64 CHAR),
    "cronExpression"  VARCHAR2(128 CHAR)  NOT NULL,
    "timeZone"        VARCHAR2(64 CHAR)   DEFAULT 'UTC' NOT NULL,
    "enabled"         NUMBER(1)           DEFAULT 1 NOT NULL,
    "recipients"      VARCHAR2(4000 CHAR),                                  -- comma-separated emails (report kinds)
    "customSubject"   VARCHAR2(512 CHAR),
    "customIntro"     VARCHAR2(4000 CHAR),
    "createdBy"       VARCHAR2(255 CHAR),
    "createdAt"       TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "lastFiredAt"     TIMESTAMP(3) WITH TIME ZONE,
    "lastFiredRunId"  VARCHAR2(64 CHAR),
    "lastFireStatus"  VARCHAR2(64 CHAR),
    "nextFireAt"      TIMESTAMP(3) WITH TIME ZONE,                          -- advanced inside the claim transaction
    "claimedAt"       TIMESTAMP(3) WITH TIME ZONE,
    CONSTRAINT "cronJob_pk" PRIMARY KEY ("cronJobId"),
    CONSTRAINT "cronJob_appName_uq" UNIQUE ("applicationName", "name"),
    CONSTRAINT "cronJob_enabled_chk" CHECK ("enabled" IN (0, 1)),
    CONSTRAINT "cronJob_kind_chk" CHECK ("kind" IN
        ('LAUNCH_RUN', 'DRAIN_REGION', 'PROVISION_REGION', 'INFRA_READINESS', 'DAILY_REPORT')),
    CONSTRAINT "cronJob_kindFields_chk" CHECK (
           ("kind" = 'LAUNCH_RUN' AND "applicationName" IS NOT NULL AND "templateBlobId" IS NOT NULL)
        OR ("kind" IN ('DRAIN_REGION', 'PROVISION_REGION') AND "applicationName" IS NOT NULL AND "region" IS NOT NULL)
        OR ("kind" IN ('INFRA_READINESS', 'DAILY_REPORT')))
);
-- Platform-level jobs (NULL application) also have unique names through
-- "cronJob_appName_uq": Oracle lets only an all-NULL key repeat, so two
-- (NULL, 'daily') rows collide. Postgres needed a partial index for this.
-- The claim's candidate scan.
CREATE INDEX "globalOrchestrator"."cronJob_enabled_nextFireAt_idx"
    ON "globalOrchestrator"."cronJob" ("enabled", "nextFireAt");
COMMENT ON TABLE "globalOrchestrator"."cronJob" IS
    'Persistent schedules fired by a DB-claim sweep (claims.claimDueCronJobs). nextFireAt is advanced in the claim transaction, so a mid-fire crash errs toward not double-firing.';

CREATE TABLE "globalOrchestrator"."cronJobFireHistory" (
    "fireId"      VARCHAR2(64 CHAR)   NOT NULL,
    "cronJobId"   VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: history survives schedule deletion
    "firedAt"     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "outcome"     VARCHAR2(32 CHAR)   NOT NULL,   -- LAUNCHED / SKIPPED / FAILED / DISABLED
    "runId"       VARCHAR2(64 CHAR),
    "errorReason" VARCHAR2(4000 CHAR),
    CONSTRAINT "cronJobFireHistory_pk" PRIMARY KEY ("fireId")
);
CREATE INDEX "globalOrchestrator"."cronJobFireHistory_job_firedAt_idx"
    ON "globalOrchestrator"."cronJobFireHistory" ("cronJobId", "firedAt" DESC);

CREATE TABLE "globalOrchestrator"."applicationHealthHistory" (
    "historyId"     VARCHAR2(64 CHAR)   NOT NULL,
    "applicationId" VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: the daily report reads it after an app is purged
    "status"        VARCHAR2(32 CHAR)   NOT NULL,
    "changedAt"     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "details"       CLOB,
    CONSTRAINT "applicationHealthHistory_pk" PRIMARY KEY ("historyId"),
    CONSTRAINT "applicationHealthHistory_details_chk" CHECK ("details" IS JSON)
);
CREATE INDEX "globalOrchestrator"."applicationHealthHistory_app_idx"
    ON "globalOrchestrator"."applicationHealthHistory" ("applicationId", "changedAt" DESC);
COMMENT ON TABLE "globalOrchestrator"."applicationHealthHistory" IS
    'Append-only health transitions per application (status changes only); the infra-readiness email derives downtime windows from it.';

CREATE TABLE "globalOrchestrator"."runTrend" (
    "runId"           VARCHAR2(64 CHAR)   NOT NULL,
    "applicationName" VARCHAR2(255 CHAR),
    "p50Ms"           BINARY_DOUBLE       NOT NULL,
    "p95Ms"           BINARY_DOUBLE       NOT NULL,
    "p99Ms"           BINARY_DOUBLE       NOT NULL,
    "errorRate"       BINARY_DOUBLE       NOT NULL,
    "throughputRps"   BINARY_DOUBLE       NOT NULL,
    "completedAt"     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT "runTrend_pk" PRIMARY KEY ("runId")
);
CREATE INDEX "globalOrchestrator"."runTrend_app_completedAt_idx"
    ON "globalOrchestrator"."runTrend" ("applicationName", "completedAt" DESC);
COMMENT ON TABLE "globalOrchestrator"."runTrend" IS
    'One frozen aggregate per COMPLETED run, written on the terminal transition; the daily report''s 7-day baseline without touching the metrics schema.';

CREATE TABLE "globalOrchestrator"."aiResponse" (
    "kind"          VARCHAR2(32 CHAR)   NOT NULL,
    "cacheKey"      VARCHAR2(255 CHAR)  NOT NULL,   -- runId, or "runA|runB" for a comparison
    "promptVersion" VARCHAR2(32 CHAR)   NOT NULL,
    "response"      CLOB                NOT NULL,   -- { summary, findings[] }, kind-specific
    "model"         VARCHAR2(128 CHAR)  NOT NULL,
    "tokensIn"      NUMBER(10)          NOT NULL,
    "tokensOut"     NUMBER(10)          NOT NULL,
    "createdAt"     TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT "aiResponse_pk" PRIMARY KEY ("kind", "cacheKey", "promptVersion"),
    CONSTRAINT "aiResponse_response_chk" CHECK ("response" IS JSON)
);
CREATE INDEX "globalOrchestrator"."aiResponse_createdAt_idx"
    ON "globalOrchestrator"."aiResponse" ("createdAt");
COMMENT ON TABLE "globalOrchestrator"."aiResponse" IS
    'Durable cache of Claude-generated insights keyed (kind, cacheKey, promptVersion); a miss costs a bill, so it lives here rather than in Redis.';

CREATE TABLE "globalOrchestrator"."purgeAudit" (
    "purgeId"           VARCHAR2(64 CHAR)   NOT NULL,
    "targetType"        VARCHAR2(32 CHAR)   NOT NULL,   -- RUN / APPLICATION
    "targetId"          VARCHAR2(64 CHAR)   NOT NULL,   -- no FK: the tombstone outlives its target
    "applicationName"   VARCHAR2(255 CHAR),
    "actor"             VARCHAR2(255 CHAR)  NOT NULL,
    "reason"            VARCHAR2(4000 CHAR),
    "metricRowsDeleted" NUMBER(19),
    "blobsDeleted"      NUMBER(10),
    "childRunsPurged"   NUMBER(10),
    "purgedAt"          TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    "details"           CLOB,
    CONSTRAINT "purgeAudit_pk" PRIMARY KEY ("purgeId"),
    CONSTRAINT "purgeAudit_details_chk" CHECK ("details" IS JSON)
);
CREATE INDEX "globalOrchestrator"."purgeAudit_purgedAt_idx"
    ON "globalOrchestrator"."purgeAudit" ("purgedAt" DESC);

-- ═══════════════════════════════════════════════════════════════════════
-- claims — "LIMIT n FOR UPDATE SKIP LOCKED" done the Oracle way
-- ═══════════════════════════════════════════════════════════════════════

CREATE OR REPLACE TYPE "globalOrchestrator"."idTable" AS TABLE OF VARCHAR2(64 CHAR);
/

CREATE OR REPLACE PACKAGE "globalOrchestrator"."claims" AUTHID DEFINER AS

    -- Locks up to p_limit IDLE workers that no non-terminal run member
    -- holds, freshest heartbeat first, and returns them as a cursor over
    -- the "pod" columns. p_region / p_applicationId are filters when
    -- non-NULL. Rows another claimer holds are skipped, never waited on;
    -- the locks belong to the caller's transaction and release at commit,
    -- so the caller must insert its "runFleetMember" rows before committing.
    PROCEDURE "claimIdlePods"(p_region IN VARCHAR2, p_applicationId IN VARCHAR2,
                              p_limit IN NUMBER, p_pods OUT SYS_REFCURSOR);

    -- Locks up to p_limit enabled schedules due at p_now, earliest first,
    -- and returns them as a cursor over the "cronJob" columns. The caller
    -- advances "nextFireAt" before committing — that is what makes a fire
    -- exactly-once across replicas.
    PROCEDURE "claimDueCronJobs"(p_now IN TIMESTAMP WITH TIME ZONE, p_limit IN NUMBER,
                                 p_jobs OUT SYS_REFCURSOR);

END "claims";
/

CREATE OR REPLACE PACKAGE BODY "globalOrchestrator"."claims" AS

    PROCEDURE "claimIdlePods"(p_region IN VARCHAR2, p_applicationId IN VARCHAR2,
                              p_limit IN NUMBER, p_pods OUT SYS_REFCURSOR) IS
        v_ids "globalOrchestrator"."idTable" := "globalOrchestrator"."idTable"();
        v_id  VARCHAR2(64 CHAR);
    BEGIN
        -- Candidate pass (no locks), in claim preference order.
        FOR c IN (SELECT p."podId"
                  FROM   "globalOrchestrator"."pod" p
                  WHERE  p."state" = 'IDLE'
                    AND  (p_region IS NULL OR p."region" = p_region)
                    AND  (p_applicationId IS NULL OR p."applicationId" = p_applicationId)
                    AND  NOT EXISTS (SELECT 1 FROM "globalOrchestrator"."runFleetMember" m
                                     WHERE m."workerId" = p."podId"
                                       AND m."state" IN ('PENDING', 'REQUESTED', 'ACCEPTED', 'RUNNING', 'DRAINING'))
                  ORDER  BY p."lastHeartbeat" DESC) LOOP
            EXIT WHEN v_ids.COUNT >= p_limit;
            -- One-row lock attempt, re-checking the predicates under the
            -- lock: a row another claimer holds, or that stopped being
            -- claimable since the candidate pass, simply yields nothing.
            BEGIN
                SELECT p."podId" INTO v_id
                FROM   "globalOrchestrator"."pod" p
                WHERE  p."podId" = c."podId"
                  AND  p."state" = 'IDLE'
                  AND  NOT EXISTS (SELECT 1 FROM "globalOrchestrator"."runFleetMember" m
                                   WHERE m."workerId" = p."podId"
                                     AND m."state" IN ('PENDING', 'REQUESTED', 'ACCEPTED', 'RUNNING', 'DRAINING'))
                FOR UPDATE OF p."state" SKIP LOCKED;
                v_ids.EXTEND;
                v_ids(v_ids.COUNT) := v_id;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN NULL;
            END;
        END LOOP;

        OPEN p_pods FOR
            SELECT p."podId", p."region", p."baseUrl", p."state", p."lastHeartbeat",
                   p."registeredAt", p."applicationId", p."runsServed", p."imageDigest",
                   p."provisionedAt", p."source"
            FROM   "globalOrchestrator"."pod" p
            WHERE  p."podId" IN (SELECT COLUMN_VALUE FROM TABLE(v_ids))
            ORDER  BY p."lastHeartbeat" DESC;
    END "claimIdlePods";

    PROCEDURE "claimDueCronJobs"(p_now IN TIMESTAMP WITH TIME ZONE, p_limit IN NUMBER,
                                 p_jobs OUT SYS_REFCURSOR) IS
        v_ids "globalOrchestrator"."idTable" := "globalOrchestrator"."idTable"();
        v_id  VARCHAR2(64 CHAR);
    BEGIN
        FOR c IN (SELECT j."cronJobId"
                  FROM   "globalOrchestrator"."cronJob" j
                  WHERE  j."enabled" = 1 AND j."nextFireAt" IS NOT NULL AND j."nextFireAt" <= p_now
                  ORDER  BY j."nextFireAt" ASC) LOOP
            EXIT WHEN v_ids.COUNT >= p_limit;
            BEGIN
                SELECT j."cronJobId" INTO v_id
                FROM   "globalOrchestrator"."cronJob" j
                WHERE  j."cronJobId" = c."cronJobId"
                  AND  j."enabled" = 1 AND j."nextFireAt" IS NOT NULL AND j."nextFireAt" <= p_now
                FOR UPDATE OF j."nextFireAt" SKIP LOCKED;
                v_ids.EXTEND;
                v_ids(v_ids.COUNT) := v_id;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN NULL;
            END;
        END LOOP;

        OPEN p_jobs FOR
            SELECT j."cronJobId", j."name", j."applicationName", j."templateBlobId", j."cronExpression",
                   j."timeZone", j."enabled", j."createdBy", j."createdAt", j."lastFiredAt",
                   j."lastFiredRunId", j."lastFireStatus", j."nextFireAt", j."claimedAt",
                   j."kind", j."region", j."recipients", j."customSubject", j."customIntro"
            FROM   "globalOrchestrator"."cronJob" j
            WHERE  j."cronJobId" IN (SELECT COLUMN_VALUE FROM TABLE(v_ids))
            ORDER  BY j."nextFireAt" ASC;
    END "claimDueCronJobs";

END "claims";
/

-- ═══════════════════════════════════════════════════════════════════════
-- Grants — the service's single user; the owner keeps DDL
-- ═══════════════════════════════════════════════════════════════════════

GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."run"                 TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."runFleetMember"      TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."pod"                 TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."applicationGroup"    TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."application"         TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."applicationCapacity" TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."cronJob"             TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, UPDATE, DELETE ON "globalOrchestrator"."aiResponse"          TO "globalOrchestratorWriter";
-- Append-only surfaces; DELETE only where a purge path exists.
GRANT SELECT, INSERT         ON "globalOrchestrator"."runEvent"                 TO "globalOrchestratorWriter";
GRANT SELECT, INSERT         ON "globalOrchestrator"."cronJobFireHistory"       TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, DELETE ON "globalOrchestrator"."applicationHealthHistory" TO "globalOrchestratorWriter";
GRANT SELECT, INSERT, DELETE ON "globalOrchestrator"."runTrend"                 TO "globalOrchestratorWriter";
GRANT SELECT, INSERT         ON "globalOrchestrator"."purgeAudit"               TO "globalOrchestratorWriter";
GRANT EXECUTE                ON "globalOrchestrator"."claims"                   TO "globalOrchestratorWriter";
GRANT EXECUTE                ON "globalOrchestrator"."idTable"                  TO "globalOrchestratorWriter";
