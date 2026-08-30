-- V3__pluginLibrary.sql — the global JMeter plugin library (UX-DYNAMICS T3).
-- Jar bytes live in document-service blobs (X-Type: plugin); this table is the
-- only registry. One version per plugin: NAME is unique (an upgrade is delete +
-- re-register), and SHA256 is unique because the same bytes under a second name
-- is the same version collision.

CREATE TABLE ORCH_PLUGIN (
    PLUGIN_ID    VARCHAR2(64 CHAR)           NOT NULL,   -- server-issued ULID
    NAME         VARCHAR2(255 CHAR)          NOT NULL,
    VERSION      VARCHAR2(64 CHAR)           NOT NULL,
    BLOB_ID      VARCHAR2(64 CHAR)           NOT NULL,   -- document-service blob (a jar, or a zip bundle of the plugin + its dependency jars)
    SHA256       VARCHAR2(64 CHAR)           NOT NULL,   -- computed server-side by document-service at upload; never caller-supplied
    SIZE_BYTES   NUMBER(19)                  NOT NULL,
    FILE_NAME    VARCHAR2(255 CHAR)          NOT NULL,   -- ends .jar or .zip; the worker stages by this name
    DESCRIPTION  VARCHAR2(4000 CHAR),
    CREATED_BY   VARCHAR2(255 CHAR)          DEFAULT 'anonymous' NOT NULL,
    CREATED_AT   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ORCH_PLUGIN_PK        PRIMARY KEY (PLUGIN_ID),
    CONSTRAINT ORCH_PLUGIN_NAME_UQ   UNIQUE (NAME),
    CONSTRAINT ORCH_PLUGIN_SHA256_UQ UNIQUE (SHA256)
);

COMMENT ON TABLE ORCH_PLUGIN IS
    'Global JMeter plugin library: one row per registered jar/bundle. Rows are immutable — an upgrade is delete + re-register (no UPDATE grant).';

-- Launch-time snapshot on the run: a JSON array of
-- {pluginId, name, version, blobId, fileName}. Deliberately NO foreign key to
-- ORCH_PLUGIN — deleting a registry entry must never break a historical run or
-- a scale-up joiner fanning out from this row (the fan-out re-reads it).
ALTER TABLE ORCH_RUN ADD (
    PLUGINS CLOB DEFAULT '[]' NOT NULL
        CONSTRAINT ORCH_RUN_PLUGINS_CHK CHECK (PLUGINS IS JSON)
);

GRANT SELECT, INSERT, DELETE ON ORCH_PLUGIN TO GLOBAL_ORCHESTRATOR_WRITER;
