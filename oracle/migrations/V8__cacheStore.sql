-- V8__cacheStore.sql — the hub's cache store (CACHE-ORACLE, 2026-08-31).
-- Redis is retired: the hosted environment offers one database and no cache
-- server, so the Spring Cache provider behind the hub's @Cacheable /
-- @CacheEvict layer now writes here. The seam above it does not move — same
-- six cache names, same TTLs, same terminal-only gating.
--
-- Reclaim is a bounded DELETE, NOT a partition drop. A cache needs a global
-- unique index on its key to answer a get in one probe, and DROP PARTITION
-- either invalidates that index or forces per-row maintenance through
-- UPDATE INDEXES. The chunked delete below is pure DML: no DDL, so no cursor
-- invalidation and no library-cache lock, and one commit per 5,000 rows keeps
-- undo flat. TRUNCATE TABLE ORCH_CACHE stays available as an instant full
-- flush — a cache is rebuildable from its source by definition.

CREATE TABLE ORCH_CACHE (
    CACHE_KEY    VARCHAR2(512 CHAR)  NOT NULL,   -- '<cacheName>::<key>'; a key over the column width is stored as '<cacheName>::sha256:<hex>'
    CACHE_NAME   VARCHAR2(64  CHAR)  NOT NULL,   -- the Spring cache name alone, so an allEntries evict is one indexed DELETE
    CACHE_VALUE  BLOB                NOT NULL,   -- gzipped UTF-8 JSON; the hub compresses, so this stays a small inline LOB
    VALUE_BYTES  NUMBER(10)          NOT NULL,   -- compressed size, for the operator's "what is this table holding" query
    CREATED_AT   TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    EXPIRES_AT   TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    CONSTRAINT ORCH_CACHE_PK PRIMARY KEY (CACHE_KEY),
    CONSTRAINT ORCH_CACHE_VALUE_BYTES_CHK CHECK (VALUE_BYTES >= 0)
)
-- Small values live in the row, so a hit is one buffered block and no separate
-- LOB read; CACHE keeps those blocks in the buffer pool, which is the whole
-- point of a cache table. No LOB COMPRESS: the hub already gzips, and SecureFile
-- compression is an Advanced Compression feature.
LOB (CACHE_VALUE) STORE AS SECUREFILE ORCH_CACHE_VALUE_LOB (ENABLE STORAGE IN ROW CACHE);

-- The reaper's driving scan.
CREATE INDEX ORCH_CACHE_EXPIRES_AT_IDX ON ORCH_CACHE (EXPIRES_AT);
-- clear(cacheName) — the write-through evict on groupCapacity.
CREATE INDEX ORCH_CACHE_CACHE_NAME_IDX ON ORCH_CACHE (CACHE_NAME);

COMMENT ON TABLE ORCH_CACHE IS
    'The hub''s Spring Cache store (replaces Redis). Rows are disposable: a get filters on EXPIRES_AT so an expired entry is never served, and ORCH_CACHE_REAP_JOB reclaims the space in bounded chunks. TRUNCATE is a safe full flush.';
COMMENT ON COLUMN ORCH_CACHE.CACHE_KEY IS
    'cacheName + ''::'' + the Spring key. Keys wider than the column are hashed by the hub (''<cacheName>::sha256:<hex>''), so a long label prefix can never overflow it.';

GRANT SELECT, INSERT, UPDATE, DELETE ON ORCH_CACHE TO GLOBAL_ORCHESTRATOR_WRITER;

-- ═══════════════════════════════════════════════════════════════════════
-- ORCH_CACHE_REAP — delete expired entries in bounded chunks.
--
-- ROWNUM caps each statement, and the commit after each chunk releases the
-- undo and any row locks before the next one starts, so a large backlog is
-- reclaimed as a series of short transactions rather than one long one.
-- p_maxChunks stops a pathological backlog from running past its window; what
-- it leaves behind is picked up 10 minutes later, and is unservable meanwhile
-- because every get carries the EXPIRES_AT predicate.
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PROCEDURE ORCH_CACHE_REAP(
    p_chunkRows IN NUMBER DEFAULT 5000,
    p_maxChunks IN NUMBER DEFAULT 200
) AS
    v_deleted NUMBER;
BEGIN
    FOR i IN 1 .. p_maxChunks LOOP
        DELETE FROM ORCH_CACHE
         WHERE EXPIRES_AT < SYSTIMESTAMP
           AND ROWNUM <= p_chunkRows;
        v_deleted := SQL%ROWCOUNT;
        COMMIT;
        EXIT WHEN v_deleted = 0;
    END LOOP;
END ORCH_CACHE_REAP;
/

-- Every 10 minutes. A cache entry is unservable the instant it expires, so
-- this job reclaims space only — running late costs storage, never freshness.
BEGIN
    BEGIN DBMS_SCHEDULER.DROP_JOB('ORCH_CACHE_REAP_JOB', force => TRUE);
    EXCEPTION WHEN OTHERS THEN NULL; END;

    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'ORCH_CACHE_REAP_JOB',
        job_type        => 'PLSQL_BLOCK',
        job_action      => 'BEGIN ORCH_CACHE_REAP; END;',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=MINUTELY;INTERVAL=10',
        comments        => 'Reclaims expired ORCH_CACHE rows in bounded chunks (CACHE-ORACLE)',
        enabled         => TRUE);
END;
/
