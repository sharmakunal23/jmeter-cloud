-- V4__clusterRegistry.sql — the cluster registry (CLUSTER-CAPACITY, 2026-08-30).
-- A region row is a registered cluster: the regional-orchestrator endpoint the
-- hub validated at registration (REGIONS env parsing retired), and the cluster's
-- worker ceiling that group reservations in ORCH_GROUP_CAPACITY must fit under.
-- The column stays REGION everywhere — "cluster" is the UI's display word only.

CREATE TABLE ORCH_REGION (
    REGION             VARCHAR2(64 CHAR)   NOT NULL,   -- the placement axis; DNS-1123 label shape, enforced in the hub
    LABEL              VARCHAR2(255 CHAR)  NOT NULL,   -- display name (the UI's "Cluster"); unique — two clusters must never present as one
    REGIONAL_URL       VARCHAR2(512 CHAR)  NOT NULL,   -- the cluster's jmeter-regional-orchestrator endpoint; unique — one regional serves one cluster
    MAX_WORKERS        NUMBER(4)           DEFAULT 20 NOT NULL,  -- cluster ceiling, hard cap 20 (the 180 GB grant / 9 GB per worker); SUM of the groups' reservations must fit under it
    LAST_VALIDATED_AT  TIMESTAMP(3) WITH TIME ZONE,    -- last time the registration dry-run chain passed
    LAST_PROBE_AT      TIMESTAMP(3) WITH TIME ZONE,    -- last on-demand "test provisioning" probe (spin one pod, await ready, delete)
    LAST_PROBE_STATUS  VARCHAR2(16 CHAR),               -- RUNNING is the cross-replica claim: one probe per cluster, whatever hub answered the click
    LAST_PROBE_DETAIL  VARCHAR2(4000 CHAR),
    CREATED_AT         TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    UPDATED_AT         TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_REGION_PK PRIMARY KEY (REGION),
    CONSTRAINT ORCH_REGION_LABEL_UQ UNIQUE (LABEL),
    CONSTRAINT ORCH_REGION_REGIONAL_URL_UQ UNIQUE (REGIONAL_URL),
    CONSTRAINT ORCH_REGION_MAX_WORKERS_CHK CHECK (MAX_WORKERS BETWEEN 1 AND 20),
    CONSTRAINT ORCH_REGION_LAST_PROBE_STATUS_CHK CHECK (LAST_PROBE_STATUS IN ('PASS', 'FAIL', 'RUNNING'))
);
COMMENT ON TABLE ORCH_REGION IS
    'Registered clusters: one row per data center, added at runtime after the hub validated its regional-orchestrator endpoint. Deleting a row is service-guarded (no capacity rows or pods may reference it).';

-- Reservations must name a registered cluster, and ORCH_REGION is empty at
-- this point — so this clears EVERY existing ORCH_GROUP_CAPACITY row, not just
-- the REGIONS-env seed rows. That is unavoidable: no cluster is registered
-- yet, so every row is an orphan the FK below would reject. A reservation is
-- only a number (the pool itself is ORCH_POD, untouched), so nothing is lost
-- that re-reserving does not restore.
--
-- OPERATOR ACTION after this migration: register each cluster
-- (POST /api/v1/regions) and re-attach + re-reserve for every group, or their
-- launches answer 404 CAPACITY_REGION_NOT_FOUND until you do.
DELETE FROM ORCH_GROUP_CAPACITY;

ALTER TABLE ORCH_GROUP_CAPACITY ADD CONSTRAINT ORCH_GROUP_CAPACITY_REGION_FK
    FOREIGN KEY (REGION) REFERENCES ORCH_REGION (REGION);
-- No FK from ORCH_POD.REGION or ORCH_RUN.ORIGIN_REGION: pod rows are
-- service-guarded at cluster delete, and runs are history that outlives clusters.

GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_REGION TO GLOBAL_ORCHESTRATOR_WRITER;
