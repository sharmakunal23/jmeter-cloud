-- R__group_cps.sql — the "Servicing MQ" application group (groupId cps, prefix CPS).
-- Rendered by oracle/groups/renderGroup.mjs from oracle/groups/cps.json
-- — do not hand-edit; change the descriptor and re-run the renderer.
--
-- Every statement is idempotent, so this file can be re-run (Flyway
-- repeatable locally; the DBA's bundle on the hosted database). Objects:
-- CPS_METRICS (hot, daily partitions, 7 days), CPS_METRICS_H (+ _STAGE,
-- 30 days), CPS_CLASSIFY_LABEL, CPS_ARCHIVE_TO_H, CPS_PRUNE_H,
-- CPS_MAINTAIN, job CPS_NIGHTLY_MAINT, the GROUP_REGISTRY row and the grants.

-- ═══════════════════════════════════════════════════════════════════════
-- 1. CPS_METRICS — hot 15 s fact: daily partitions, ~7-day retention.
--    FKs are RELY DISABLE NOVALIDATE: declared for the optimizer but NOT
--    enforced on insert (the app resolves dims first) — ingest stays as
--    fast as an FK-less fact. The PK is the ONLY unique index: the
--    consumer's IGNORE_ROW_ON_DUPKEY_INDEX hint names exactly its columns.
-- ═══════════════════════════════════════════════════════════════════════
BEGIN
EXECUTE IMMEDIATE q'[CREATE TABLE CPS_METRICS (
        RUN_ID         NUMBER NOT NULL,                 -- FK RUN
        WORKER_ID      NUMBER NOT NULL,                 -- FK WORKER
        LABEL_ID       NUMBER NOT NULL,                 -- FK LABEL
        WINDOW_SECOND  NUMBER(19)  NOT NULL,            -- 15s-aligned epoch second (partition key)

        THROUGHPUT     NUMBER(10)  DEFAULT 0 NOT NULL,
        ERROR_COUNT    NUMBER(10)  DEFAULT 0 NOT NULL,

        AVG_MS         NUMBER(9,1) DEFAULT 0 NOT NULL,
        P50_MS         NUMBER(9,1),
        P90_MS         NUMBER(9,1),
        P95_MS         NUMBER(9,1),
        P99_MS         NUMBER(9,1),
        MIN_MS         NUMBER(9,1),
        MAX_MS         NUMBER(9,1),

        BYTES_RECV     NUMBER(19)  DEFAULT 0 NOT NULL,
        BYTES_SENT     NUMBER(19)  DEFAULT 0 NOT NULL,

        HTTP_2XX       NUMBER(10)  DEFAULT 0 NOT NULL,
        HTTP_3XX       NUMBER(10)  DEFAULT 0 NOT NULL,
        HTTP_4XX       NUMBER(10)  DEFAULT 0 NOT NULL,
        HTTP_5XX       NUMBER(10)  DEFAULT 0 NOT NULL,
        HTTP_OTHER     NUMBER(10)  DEFAULT 0 NOT NULL,

        ACTIVE_THREADS NUMBER(8)   DEFAULT 0 NOT NULL,

        CONSTRAINT CPS_METRICS_PK
            PRIMARY KEY (RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND) USING INDEX LOCAL,
        CONSTRAINT CPS_METRICS_RUN_FK FOREIGN KEY (RUN_ID)    REFERENCES RUN (RUN_ID)       RELY DISABLE NOVALIDATE,
        CONSTRAINT CPS_METRICS_WRK_FK FOREIGN KEY (WORKER_ID) REFERENCES WORKER (WORKER_ID) RELY DISABLE NOVALIDATE,
        CONSTRAINT CPS_METRICS_LBL_FK FOREIGN KEY (LABEL_ID)  REFERENCES LABEL (LABEL_ID)   RELY DISABLE NOVALIDATE
    ) PCTFREE 0
      PARTITION BY RANGE (WINDOW_SECOND) INTERVAL (86400)
      ( PARTITION CPS_METRICS_P_INIT VALUES LESS THAN (1578268800) )]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE NOT IN (-955, -1408) THEN RAISE; END IF;
END;
/
BEGIN
EXECUTE IMMEDIATE 'CREATE INDEX CPS_METRICS_RUN_LBL_IDX ON CPS_METRICS (RUN_ID, LABEL_ID, WINDOW_SECOND) LOCAL';
EXCEPTION WHEN OTHERS THEN IF SQLCODE NOT IN (-955, -1408) THEN RAISE; END IF;
END;
/

-- ═══════════════════════════════════════════════════════════════════════
-- 2. CPS_METRICS_H — worker-aggregated 15 s history, one row per
--    (RUN_ID, LABEL_ID, WINDOW_SECOND); daily partitions published atomically
--    by EXCHANGE PARTITION from the private, non-partitioned _STAGE.
-- ═══════════════════════════════════════════════════════════════════════
BEGIN
EXECUTE IMMEDIATE q'[CREATE TABLE CPS_METRICS_H (
        RUN_ID          NUMBER NOT NULL,
        LABEL_ID        NUMBER NOT NULL,
        WINDOW_SECOND   NUMBER(19) NOT NULL,
        WORKER_COUNT    NUMBER(5) DEFAULT 0 NOT NULL,

        THROUGHPUT      NUMBER(19) DEFAULT 0 NOT NULL,
        ERROR_COUNT     NUMBER(19) DEFAULT 0 NOT NULL,

        AVG_MS          NUMBER,
        P50_MS          NUMBER,
        P90_MS          NUMBER,
        P95_MS          NUMBER,
        P99_MS          NUMBER,
        MIN_MS          NUMBER(9,1),
        MAX_MS          NUMBER(9,1),

        BYTES_RECV      NUMBER(19) DEFAULT 0 NOT NULL,
        BYTES_SENT      NUMBER(19) DEFAULT 0 NOT NULL,

        HTTP_2XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_3XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_4XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_5XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_OTHER      NUMBER(19) DEFAULT 0 NOT NULL,

        ACTIVE_THREADS  NUMBER(12) DEFAULT 0 NOT NULL,

        CONSTRAINT CPS_METRICS_H_PK
            PRIMARY KEY (RUN_ID, LABEL_ID, WINDOW_SECOND) USING INDEX LOCAL
    ) PCTFREE 0
      ROW STORE COMPRESS ADVANCED
      PARTITION BY RANGE (WINDOW_SECOND)
      ( PARTITION CPS_METRICS_H_P_INIT VALUES LESS THAN (1578268800) )]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE NOT IN (-955, -1408) THEN RAISE; END IF;
END;
/

BEGIN
EXECUTE IMMEDIATE q'[CREATE TABLE CPS_METRICS_H_STAGE (
        RUN_ID          NUMBER NOT NULL,
        LABEL_ID        NUMBER NOT NULL,
        WINDOW_SECOND   NUMBER(19) NOT NULL,
        WORKER_COUNT    NUMBER(5) DEFAULT 0 NOT NULL,

        THROUGHPUT      NUMBER(19) DEFAULT 0 NOT NULL,
        ERROR_COUNT     NUMBER(19) DEFAULT 0 NOT NULL,

        AVG_MS          NUMBER,
        P50_MS          NUMBER,
        P90_MS          NUMBER,
        P95_MS          NUMBER,
        P99_MS          NUMBER,
        MIN_MS          NUMBER(9,1),
        MAX_MS          NUMBER(9,1),

        BYTES_RECV      NUMBER(19) DEFAULT 0 NOT NULL,
        BYTES_SENT      NUMBER(19) DEFAULT 0 NOT NULL,

        HTTP_2XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_3XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_4XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_5XX        NUMBER(19) DEFAULT 0 NOT NULL,
        HTTP_OTHER      NUMBER(19) DEFAULT 0 NOT NULL,

        ACTIVE_THREADS  NUMBER(12) DEFAULT 0 NOT NULL,

        CONSTRAINT CPS_METRICS_H_STAGE_PK
            PRIMARY KEY (RUN_ID, LABEL_ID, WINDOW_SECOND)
    ) PCTFREE 0
      ROW STORE COMPRESS ADVANCED]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE NOT IN (-955, -1408) THEN RAISE; END IF;
END;
/

-- ═══════════════════════════════════════════════════════════════════════
-- 3. CPS_CLASSIFY_LABEL — label → application by prefix; first match wins.
--    Called once per NEW label by the consumer's LABEL insert, never in a
--    WHERE clause.
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION CPS_CLASSIFY_LABEL (p_label IN VARCHAR2)
    RETURN VARCHAR2 DETERMINISTIC
AS
BEGIN
    -- CPP-PCI
    IF SUBSTR(p_label, 1, 3) = 'TG6' THEN RETURN 'CPP-PCI'; END IF;
    -- CPS-PCI
    IF SUBSTR(p_label, 1, 3) = 'TG5' THEN RETURN 'CPS-PCI'; END IF;
    -- CPP
    IF SUBSTR(p_label, 1, 3) = 'TG4' THEN RETURN 'CPP'; END IF;
    -- CPS
    IF SUBSTR(p_label, 1, 3) = 'TG1' THEN RETURN 'CPS'; END IF;
    IF SUBSTR(p_label, 1, 3) = 'TG2' THEN RETURN 'CPS'; END IF;
    IF SUBSTR(p_label, 1, 3) = 'TG3' THEN RETURN 'CPS'; END IF;
    RETURN 'OTHER';
END CPS_CLASSIFY_LABEL;
/

-- 4. Re-classify this group's existing labels with the current rules.
UPDATE LABEL
SET APPLICATION = CPS_CLASSIFY_LABEL(LABEL_KEY)
WHERE GROUP_ID = 'CPS';
COMMIT;

-- 5. Register cps → CPS_METRICS so ?groupId=cps routes here.
MERGE INTO GROUP_REGISTRY t
    USING (SELECT 'cps' AS GROUP_ID FROM dual) s
    ON (t.GROUP_ID = s.GROUP_ID)
    WHEN MATCHED THEN UPDATE SET
        GROUP_NAME         = 'Servicing MQ',
        TABLE_PREFIX       = 'CPS',
        METRICS_TABLE      = 'CPS_METRICS',
        METRICS_HIST_TABLE = 'CPS_METRICS_H',
        CLASSIFY_FN        = 'CPS_CLASSIFY_LABEL',
        ENABLED            = 1
    WHEN NOT MATCHED THEN INSERT
        (GROUP_ID, GROUP_NAME, TABLE_PREFIX, METRICS_TABLE, METRICS_HIST_TABLE, CLASSIFY_FN, ENABLED)
        VALUES ('cps', 'Servicing MQ', 'CPS',
                'CPS_METRICS', 'CPS_METRICS_H', 'CPS_CLASSIFY_LABEL', 1);
COMMIT;

-- ═══════════════════════════════════════════════════════════════════════
-- 6. CPS_ARCHIVE_TO_H — collapse worker rows of every aged daily partition
--    into private staging, validate conservation, publish the day
--    atomically (EXCHANGE PARTITION), then drop the source partition.
--    Every step is recorded in METRICS_H_AUDIT so a blocked or failed pass
--    resumes where it stopped.
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PROCEDURE CPS_ARCHIVE_TO_H
(
    p_hot_days         NUMBER DEFAULT 7,
    p_pause_secs       NUMBER DEFAULT 2,
    p_chunk_minutes    NUMBER DEFAULT 15,
    p_chunk_pause_secs NUMBER DEFAULT 1
) AS
    v_now_sec NUMBER :=
        (CAST(SYSTIMESTAMP AT TIME ZONE 'UTC' AS DATE) - DATE '1970-01-01') * 86400;
    v_cut_sec NUMBER := v_now_sec - (p_hot_days * 86400);
    v_hi      NUMBER;
    v_hi_text VARCHAR2(40);
    v_target_partition user_tab_partitions.partition_name%TYPE;
    v_current_source   user_tab_partitions.partition_name%TYPE;
    v_partition_exists NUMBER;
    v_target_has_rows  NUMBER;
    v_stage_has_rows   NUMBER;
    v_chunk_seconds    NUMBER;
    v_chunk_low        NUMBER;
    v_chunk_high       NUMBER;
    v_chunk_rows       NUMBER;
    v_error_code       NUMBER;
    v_error_message    VARCHAR2(2000);

    e_resource_busy EXCEPTION;
    PRAGMA EXCEPTION_INIT(e_resource_busy, -54);

    TYPE t_part IS RECORD
    (
        pname user_tab_partitions.partition_name%TYPE,
        hi    NUMBER
    );
    TYPE t_parts IS TABLE OF t_part INDEX BY PLS_INTEGER;
    v_parts t_parts;
    v_part_count PLS_INTEGER := 0;

    TYPE t_totals IS RECORD
    (
        stored_rows    NUMBER,
        worker_rows    NUMBER,
        min_window     NUMBER,
        max_window     NUMBER,
        throughput     NUMBER,
        error_count    NUMBER,
        bytes_recv     NUMBER,
        bytes_sent     NUMBER,
        http_2xx       NUMBER,
        http_3xx       NUMBER,
        http_4xx       NUMBER,
        http_5xx       NUMBER,
        http_other     NUMBER,
        active_threads NUMBER,
        min_ms         NUMBER,
        max_ms         NUMBER
    );
    v_source_totals t_totals;
    v_chunk_totals  t_totals;
    v_stage_totals  t_totals;
    v_target_totals t_totals;

    PROCEDURE set_status
    (
        p_status        VARCHAR2,
        p_source_rows   NUMBER DEFAULT NULL,
        p_history_rows  NUMBER DEFAULT NULL,
        p_error_code    NUMBER DEFAULT NULL,
        p_error_message VARCHAR2 DEFAULT NULL
    ) AS
    BEGIN
        UPDATE METRICS_H_AUDIT
        SET    STATUS        = p_status,
               SOURCE_ROWS   = COALESCE(p_source_rows, SOURCE_ROWS),
               HISTORY_ROWS  = COALESCE(p_history_rows, HISTORY_ROWS),
               UPDATED_AT    = SYSTIMESTAMP,
               COMPLETED_AT  = CASE WHEN p_status = 'COMPLETE'
                                    THEN SYSTIMESTAMP ELSE COMPLETED_AT END,
               ERROR_CODE    = p_error_code,
               ERROR_MESSAGE = SUBSTR(p_error_message, 1, 2000)
        WHERE  SOURCE_TABLE = 'CPS_METRICS'
        AND    PARTITION_HIGH_VALUE = v_hi;
        COMMIT;
    END set_status;

    PROCEDURE assert_partition_name(p_name VARCHAR2) AS
    BEGIN
        IF p_name IS NULL
           OR LENGTHB(p_name) > 128
           OR NOT REGEXP_LIKE(p_name, '^[A-Z][A-Z0-9_$#]*$')
        THEN
            RAISE_APPLICATION_ERROR(-20004, 'Unsafe partition name');
        END IF;
    END assert_partition_name;

    PROCEDURE reset_totals(p_totals OUT t_totals) AS
    BEGIN
        p_totals.stored_rows    := 0;
        p_totals.worker_rows    := 0;
        p_totals.min_window     := NULL;
        p_totals.max_window     := NULL;
        p_totals.throughput     := 0;
        p_totals.error_count    := 0;
        p_totals.bytes_recv     := 0;
        p_totals.bytes_sent     := 0;
        p_totals.http_2xx       := 0;
        p_totals.http_3xx       := 0;
        p_totals.http_4xx       := 0;
        p_totals.http_5xx       := 0;
        p_totals.http_other     := 0;
        p_totals.active_threads := 0;
        p_totals.min_ms         := NULL;
        p_totals.max_ms         := NULL;
    END reset_totals;

    PROCEDURE add_totals
    (
        p_totals IN OUT t_totals,
        p_chunk  t_totals
    ) AS
    BEGIN
        p_totals.stored_rows    := p_totals.stored_rows + p_chunk.stored_rows;
        p_totals.worker_rows    := p_totals.worker_rows + p_chunk.worker_rows;
        p_totals.throughput     := p_totals.throughput + p_chunk.throughput;
        p_totals.error_count    := p_totals.error_count + p_chunk.error_count;
        p_totals.bytes_recv     := p_totals.bytes_recv + p_chunk.bytes_recv;
        p_totals.bytes_sent     := p_totals.bytes_sent + p_chunk.bytes_sent;
        p_totals.http_2xx       := p_totals.http_2xx + p_chunk.http_2xx;
        p_totals.http_3xx       := p_totals.http_3xx + p_chunk.http_3xx;
        p_totals.http_4xx       := p_totals.http_4xx + p_chunk.http_4xx;
        p_totals.http_5xx       := p_totals.http_5xx + p_chunk.http_5xx;
        p_totals.http_other     := p_totals.http_other + p_chunk.http_other;
        p_totals.active_threads := p_totals.active_threads + p_chunk.active_threads;

        IF p_chunk.min_window IS NOT NULL
           AND (p_totals.min_window IS NULL
                OR p_chunk.min_window < p_totals.min_window)
        THEN
            p_totals.min_window := p_chunk.min_window;
        END IF;
        IF p_chunk.max_window IS NOT NULL
           AND (p_totals.max_window IS NULL
                OR p_chunk.max_window > p_totals.max_window)
        THEN
            p_totals.max_window := p_chunk.max_window;
        END IF;
        IF p_chunk.min_ms IS NOT NULL
           AND (p_totals.min_ms IS NULL OR p_chunk.min_ms < p_totals.min_ms)
        THEN
            p_totals.min_ms := p_chunk.min_ms;
        END IF;
        IF p_chunk.max_ms IS NOT NULL
           AND (p_totals.max_ms IS NULL OR p_chunk.max_ms > p_totals.max_ms)
        THEN
            p_totals.max_ms := p_chunk.max_ms;
        END IF;
    END add_totals;

    PROCEDURE read_source_chunk
    (
        p_partition VARCHAR2,
        p_low       NUMBER,
        p_high      NUMBER,
        p_totals OUT t_totals
    ) AS
    BEGIN
        EXECUTE IMMEDIATE
            'SELECT /*+ NO_PARALLEL(f) */ '
                || 'COUNT(*), COUNT(*), MIN(WINDOW_SECOND), MAX(WINDOW_SECOND), '
                || 'NVL(SUM(THROUGHPUT),0), NVL(SUM(ERROR_COUNT),0), '
                || 'NVL(SUM(BYTES_RECV),0), NVL(SUM(BYTES_SENT),0), '
                || 'NVL(SUM(HTTP_2XX),0), NVL(SUM(HTTP_3XX),0), '
                || 'NVL(SUM(HTTP_4XX),0), NVL(SUM(HTTP_5XX),0), '
                || 'NVL(SUM(HTTP_OTHER),0), NVL(SUM(ACTIVE_THREADS),0), '
                || 'MIN(MIN_MS), MAX(MAX_MS) '
                || 'FROM CPS_METRICS PARTITION ("' || p_partition || '") f '
                || 'WHERE f.WINDOW_SECOND >= :range_low '
                || 'AND f.WINDOW_SECOND < :range_high'
            INTO p_totals.stored_rows,
                 p_totals.worker_rows,
                 p_totals.min_window,
                 p_totals.max_window,
                 p_totals.throughput,
                 p_totals.error_count,
                 p_totals.bytes_recv,
                 p_totals.bytes_sent,
                 p_totals.http_2xx,
                 p_totals.http_3xx,
                 p_totals.http_4xx,
                 p_totals.http_5xx,
                 p_totals.http_other,
                 p_totals.active_threads,
                 p_totals.min_ms,
                 p_totals.max_ms
            USING p_low, p_high;
    END read_source_chunk;

    PROCEDURE read_source_totals
    (
        p_partition VARCHAR2,
        p_high      NUMBER,
        p_totals OUT t_totals
    ) AS
        v_range_low  NUMBER := p_high - 86400;
        v_range_high NUMBER;
    BEGIN
        reset_totals(p_totals);
        WHILE v_range_low < p_high LOOP
            v_range_high := LEAST(v_range_low + v_chunk_seconds, p_high);
            read_source_chunk(
                p_partition,
                v_range_low,
                v_range_high,
                v_chunk_totals);
            add_totals(p_totals, v_chunk_totals);

            v_range_low := v_range_high;
            IF v_range_low < p_high
               AND v_chunk_totals.stored_rows > 0
               AND p_chunk_pause_secs > 0
            THEN
                DBMS_SESSION.SLEEP(p_chunk_pause_secs);
            END IF;
        END LOOP;
    END read_source_totals;

    PROCEDURE read_stage_totals(p_totals OUT t_totals) AS
    BEGIN
        SELECT COUNT(*), NVL(SUM(WORKER_COUNT),0),
               MIN(WINDOW_SECOND), MAX(WINDOW_SECOND),
               NVL(SUM(THROUGHPUT),0), NVL(SUM(ERROR_COUNT),0),
               NVL(SUM(BYTES_RECV),0), NVL(SUM(BYTES_SENT),0),
               NVL(SUM(HTTP_2XX),0), NVL(SUM(HTTP_3XX),0),
               NVL(SUM(HTTP_4XX),0), NVL(SUM(HTTP_5XX),0),
               NVL(SUM(HTTP_OTHER),0), NVL(SUM(ACTIVE_THREADS),0),
               MIN(MIN_MS), MAX(MAX_MS)
        INTO   p_totals.stored_rows,
               p_totals.worker_rows,
               p_totals.min_window,
               p_totals.max_window,
               p_totals.throughput,
               p_totals.error_count,
               p_totals.bytes_recv,
               p_totals.bytes_sent,
               p_totals.http_2xx,
               p_totals.http_3xx,
               p_totals.http_4xx,
               p_totals.http_5xx,
               p_totals.http_other,
               p_totals.active_threads,
               p_totals.min_ms,
               p_totals.max_ms
        FROM CPS_METRICS_H_STAGE;
    END read_stage_totals;

    PROCEDURE read_target_totals
    (
        p_partition VARCHAR2,
        p_totals OUT t_totals
    ) AS
    BEGIN
        EXECUTE IMMEDIATE
            'SELECT COUNT(*), NVL(SUM(WORKER_COUNT),0), '
                || 'MIN(WINDOW_SECOND), MAX(WINDOW_SECOND), '
                || 'NVL(SUM(THROUGHPUT),0), NVL(SUM(ERROR_COUNT),0), '
                || 'NVL(SUM(BYTES_RECV),0), NVL(SUM(BYTES_SENT),0), '
                || 'NVL(SUM(HTTP_2XX),0), NVL(SUM(HTTP_3XX),0), '
                || 'NVL(SUM(HTTP_4XX),0), NVL(SUM(HTTP_5XX),0), '
                || 'NVL(SUM(HTTP_OTHER),0), NVL(SUM(ACTIVE_THREADS),0), '
                || 'MIN(MIN_MS), MAX(MAX_MS) '
                || 'FROM CPS_METRICS_H PARTITION ("' || p_partition || '")'
            INTO p_totals.stored_rows,
                 p_totals.worker_rows,
                 p_totals.min_window,
                 p_totals.max_window,
                 p_totals.throughput,
                 p_totals.error_count,
                 p_totals.bytes_recv,
                 p_totals.bytes_sent,
                 p_totals.http_2xx,
                 p_totals.http_3xx,
                 p_totals.http_4xx,
                 p_totals.http_5xx,
                 p_totals.http_other,
                 p_totals.active_threads,
                 p_totals.min_ms,
                 p_totals.max_ms;
    END read_target_totals;

    PROCEDURE assert_conserved
    (
        p_source  t_totals,
        p_history t_totals,
        p_low     NUMBER,
        p_high    NUMBER
    ) AS
    BEGIN
        IF p_source.stored_rows = 0 THEN
            RAISE_APPLICATION_ERROR(-20001, 'Aged source partition is empty');
        END IF;

        IF p_history.stored_rows = 0
           OR p_history.stored_rows > p_source.stored_rows
           OR p_history.worker_rows <> p_source.worker_rows
           OR p_history.min_window < p_low
           OR p_history.max_window >= p_high
           OR p_history.min_window <> p_source.min_window
           OR p_history.max_window <> p_source.max_window
           OR p_history.throughput <> p_source.throughput
           OR p_history.error_count <> p_source.error_count
           OR p_history.bytes_recv <> p_source.bytes_recv
           OR p_history.bytes_sent <> p_source.bytes_sent
           OR p_history.http_2xx <> p_source.http_2xx
           OR p_history.http_3xx <> p_source.http_3xx
           OR p_history.http_4xx <> p_source.http_4xx
           OR p_history.http_5xx <> p_source.http_5xx
           OR p_history.http_other <> p_source.http_other
           OR p_history.active_threads <> p_source.active_threads
           OR (p_history.min_ms IS NULL AND p_source.min_ms IS NOT NULL)
           OR (p_history.min_ms IS NOT NULL AND p_source.min_ms IS NULL)
           OR (p_history.min_ms IS NOT NULL AND p_source.min_ms IS NOT NULL
               AND p_history.min_ms <> p_source.min_ms)
           OR (p_history.max_ms IS NULL AND p_source.max_ms IS NOT NULL)
           OR (p_history.max_ms IS NOT NULL AND p_source.max_ms IS NULL)
           OR (p_history.max_ms IS NOT NULL AND p_source.max_ms IS NOT NULL
               AND p_history.max_ms <> p_source.max_ms)
        THEN
            RAISE_APPLICATION_ERROR(
                -20002,
                'Aggregate validation failed for source partition ' || v_current_source);
        END IF;
    END assert_conserved;

BEGIN
    IF p_hot_days IS NULL OR p_hot_days < 1
       OR p_pause_secs IS NULL OR p_pause_secs < 0
       OR p_chunk_minutes IS NULL OR p_chunk_minutes < 1
       OR p_chunk_minutes <> TRUNC(p_chunk_minutes)
       OR MOD(1440, p_chunk_minutes) <> 0
       OR p_chunk_pause_secs IS NULL OR p_chunk_pause_secs < 0
    THEN
        RAISE_APPLICATION_ERROR(
            -20003,
            'Invalid archive pacing: chunk minutes must divide 1440 exactly');
    END IF;
    v_chunk_seconds := p_chunk_minutes * 60;

    EXECUTE IMMEDIATE 'ALTER SESSION SET DDL_LOCK_TIMEOUT = 0';

    FOR p IN
        (
            SELECT partition_name, high_value
            FROM   user_tab_partitions
            WHERE  table_name = 'CPS_METRICS'
            AND    partition_name <> 'CPS_METRICS_P_INIT'
            ORDER  BY partition_position
        ) LOOP
        v_hi := TO_NUMBER(p.high_value);
        IF v_hi <= v_cut_sec THEN
            v_part_count := v_part_count + 1;
            v_parts(v_part_count).pname := p.partition_name;
            v_parts(v_part_count).hi    := v_hi;
        END IF;
    END LOOP;

    FOR i IN 1 .. v_part_count LOOP
        v_current_source := v_parts(i).pname;
        v_hi := v_parts(i).hi;
        v_hi_text := TO_CHAR(
            TRUNC(v_hi),
            'FM99999999999999999990',
            'NLS_NUMERIC_CHARACTERS=''.,''');
        v_target_partition := 'CPS_MH_P_' || TO_CHAR(
            DATE '1970-01-01' + (v_hi / 86400),
            'YYYYMMDD',
            'NLS_DATE_LANGUAGE=English');
        assert_partition_name(v_current_source);
        assert_partition_name(v_target_partition);

        MERGE INTO METRICS_H_AUDIT a
            USING
                (
                    SELECT 'CPS' AS table_prefix,
                           'CPS_METRICS' AS source_table,
                           'CPS_METRICS_H' AS history_table,
                           v_current_source AS source_partition,
                           v_hi AS partition_high_value,
                           v_target_partition AS target_partition
                    FROM dual
                ) s
            ON (a.SOURCE_TABLE = s.source_table
                AND a.PARTITION_HIGH_VALUE = s.partition_high_value)
            WHEN MATCHED THEN UPDATE SET
                a.TABLE_PREFIX         = s.table_prefix,
                a.HISTORY_TABLE        = s.history_table,
                a.SOURCE_PARTITION     = s.source_partition,
                a.TARGET_PARTITION     = s.target_partition,
                a.UPDATED_AT           = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT
                (TABLE_PREFIX, SOURCE_TABLE, HISTORY_TABLE, SOURCE_PARTITION,
                 PARTITION_HIGH_VALUE, TARGET_PARTITION, STATUS, STARTED_AT,
                 UPDATED_AT)
                VALUES
                    (s.table_prefix, s.source_table, s.history_table,
                     s.source_partition, s.partition_high_value, s.target_partition,
                     'STAGING', SYSTIMESTAMP, SYSTIMESTAMP);
        COMMIT;

        SELECT COUNT(*)
        INTO   v_partition_exists
        FROM   user_tab_partitions
        WHERE  table_name = 'CPS_METRICS_H'
        AND    partition_name = v_target_partition;

        IF v_partition_exists = 1 THEN
            EXECUTE IMMEDIATE
                'SELECT COUNT(*) FROM CPS_METRICS_H PARTITION ("'
                || v_target_partition || '") WHERE ROWNUM = 1'
            INTO v_target_has_rows;

            IF v_target_has_rows > 0 THEN
                read_source_totals(v_current_source, v_hi, v_source_totals);
                read_target_totals(v_target_partition, v_target_totals);
                assert_conserved(
                    v_source_totals,
                    v_target_totals,
                    v_hi - 86400,
                    v_hi);
                set_status(
                    'PUBLISHED',
                    v_source_totals.stored_rows,
                    v_target_totals.stored_rows);

                BEGIN
                    EXECUTE IMMEDIATE
                        'ALTER TABLE CPS_METRICS DROP PARTITION "'
                        || v_current_source || '"';
                EXCEPTION
                    WHEN e_resource_busy THEN
                        set_status(
                            'BLOCKED_DROP',
                            v_source_totals.stored_rows,
                            v_target_totals.stored_rows,
                            SQLCODE,
                            SQLERRM);
                        RETURN;
                END;

                set_status(
                    'COMPLETE',
                    v_source_totals.stored_rows,
                    v_target_totals.stored_rows);

                IF p_pause_secs > 0 THEN
                    DBMS_SESSION.SLEEP(p_pause_secs);
                END IF;
                CONTINUE;
            END IF;
        END IF;

        set_status('STAGING');

        BEGIN
            EXECUTE IMMEDIATE 'TRUNCATE TABLE CPS_METRICS_H_STAGE';
        EXCEPTION
            WHEN e_resource_busy THEN
                set_status('BLOCKED_PUBLISH', NULL, NULL, SQLCODE, SQLERRM);
                RETURN;
        END;

        SELECT COUNT(*)
        INTO   v_stage_has_rows
        FROM   CPS_METRICS_H_STAGE
        WHERE  ROWNUM = 1;

        IF v_stage_has_rows <> 0 THEN
            RAISE_APPLICATION_ERROR(-20005, 'Aggregate stage is not empty');
        END IF;

        v_chunk_low := v_hi - 86400;
        WHILE v_chunk_low < v_hi LOOP
            v_chunk_high := LEAST(v_chunk_low + v_chunk_seconds, v_hi);

            EXECUTE IMMEDIATE
                'INSERT /*+ APPEND NO_PARALLEL */ INTO CPS_METRICS_H_STAGE ('
                    || 'RUN_ID, LABEL_ID, WINDOW_SECOND, WORKER_COUNT, '
                    || 'THROUGHPUT, ERROR_COUNT, AVG_MS, P50_MS, P90_MS, P95_MS, P99_MS, '
                    || 'MIN_MS, MAX_MS, BYTES_RECV, BYTES_SENT, '
                    || 'HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX, HTTP_OTHER, ACTIVE_THREADS) '
                    || 'SELECT /*+ NO_PARALLEL(f) */ RUN_ID, LABEL_ID, WINDOW_SECOND, COUNT(*), '
                    || 'SUM(THROUGHPUT), SUM(ERROR_COUNT), '
                    || 'SUM(AVG_MS * THROUGHPUT) / NULLIF(SUM(THROUGHPUT), 0), '
                    || 'SUM(P50_MS * THROUGHPUT) / NULLIF(SUM(THROUGHPUT), 0), '
                    || 'SUM(P90_MS * THROUGHPUT) / NULLIF(SUM(THROUGHPUT), 0), '
                    || 'SUM(P95_MS * THROUGHPUT) / NULLIF(SUM(THROUGHPUT), 0), '
                    || 'SUM(P99_MS * THROUGHPUT) / NULLIF(SUM(THROUGHPUT), 0), '
                    || 'MIN(MIN_MS), MAX(MAX_MS), SUM(BYTES_RECV), SUM(BYTES_SENT), '
                    || 'SUM(HTTP_2XX), SUM(HTTP_3XX), SUM(HTTP_4XX), SUM(HTTP_5XX), '
                    || 'SUM(HTTP_OTHER), SUM(ACTIVE_THREADS) '
                    || 'FROM CPS_METRICS PARTITION ("' || v_current_source || '") f '
                    || 'WHERE f.WINDOW_SECOND >= :chunk_low '
                    || 'AND f.WINDOW_SECOND < :chunk_high '
                    || 'GROUP BY RUN_ID, LABEL_ID, WINDOW_SECOND'
                USING v_chunk_low, v_chunk_high;
            v_chunk_rows := SQL%ROWCOUNT;
            COMMIT;

            v_chunk_low := v_chunk_high;
            IF v_chunk_low < v_hi
               AND v_chunk_rows > 0
               AND p_chunk_pause_secs > 0
            THEN
                DBMS_SESSION.SLEEP(p_chunk_pause_secs);
            END IF;
        END LOOP;

        read_source_totals(v_current_source, v_hi, v_source_totals);
        read_stage_totals(v_stage_totals);
        assert_conserved(
            v_source_totals,
            v_stage_totals,
            v_hi - 86400,
            v_hi);
        set_status(
            'STAGED',
            v_source_totals.stored_rows,
            v_stage_totals.stored_rows);

        IF v_partition_exists = 0 THEN
            BEGIN
                EXECUTE IMMEDIATE
                    'ALTER TABLE CPS_METRICS_H ADD PARTITION "'
                    || v_target_partition || '" VALUES LESS THAN ('
                    || v_hi_text || ')';
            EXCEPTION
                WHEN e_resource_busy THEN
                    set_status(
                        'BLOCKED_PUBLISH',
                        v_source_totals.stored_rows,
                        v_stage_totals.stored_rows,
                        SQLCODE,
                        SQLERRM);
                    RETURN;
            END;
        END IF;

        BEGIN
            EXECUTE IMMEDIATE
                'ALTER TABLE CPS_METRICS_H EXCHANGE PARTITION "'
                || v_target_partition
                || '" WITH TABLE CPS_METRICS_H_STAGE '
                || 'INCLUDING INDEXES WITHOUT VALIDATION';
        EXCEPTION
            WHEN e_resource_busy THEN
                set_status(
                    'BLOCKED_PUBLISH',
                    v_source_totals.stored_rows,
                    v_stage_totals.stored_rows,
                    SQLCODE,
                    SQLERRM);
                RETURN;
        END;

        set_status(
            'PUBLISHED',
            v_source_totals.stored_rows,
            v_stage_totals.stored_rows);

        BEGIN
            EXECUTE IMMEDIATE
                'ALTER TABLE CPS_METRICS DROP PARTITION "'
                || v_current_source || '"';
        EXCEPTION
            WHEN e_resource_busy THEN
                set_status(
                    'BLOCKED_DROP',
                    v_source_totals.stored_rows,
                    v_stage_totals.stored_rows,
                    SQLCODE,
                    SQLERRM);
                RETURN;
        END;

        set_status(
            'COMPLETE',
            v_source_totals.stored_rows,
            v_stage_totals.stored_rows);

        IF p_pause_secs > 0 THEN
            DBMS_SESSION.SLEEP(p_pause_secs);
        END IF;
    END LOOP;
EXCEPTION
    WHEN OTHERS THEN
        v_error_code := SQLCODE;
        v_error_message := SQLERRM;
        ROLLBACK;

        IF v_current_source IS NOT NULL THEN
            BEGIN
                UPDATE METRICS_H_AUDIT
                SET    STATUS        = 'FAILED',
                       UPDATED_AT    = SYSTIMESTAMP,
                       ERROR_CODE    = v_error_code,
                       ERROR_MESSAGE = SUBSTR(v_error_message, 1, 2000)
                WHERE  SOURCE_TABLE = 'CPS_METRICS'
                AND    PARTITION_HIGH_VALUE = v_hi;
                COMMIT;
            EXCEPTION
                WHEN OTHERS THEN
                    DBMS_OUTPUT.PUT_LINE(
                        'CPS_ARCHIVE_TO_H audit update failed: ' || SQLERRM);
            END;
        END IF;
        RAISE;
END CPS_ARCHIVE_TO_H;
/

-- ═══════════════════════════════════════════════════════════════════════
-- 7. CPS_PRUNE_H — drop whole daily history partitions past retention.
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE PROCEDURE CPS_PRUNE_H
(
    p_hist_days  NUMBER DEFAULT 30,
    p_pause_secs NUMBER DEFAULT 1
) AS
    v_now_sec NUMBER :=
        (CAST(SYSTIMESTAMP AT TIME ZONE 'UTC' AS DATE) - DATE '1970-01-01') * 86400;
    v_cut_sec NUMBER := v_now_sec - (p_hist_days * 86400);
    v_hi      NUMBER;

    e_resource_busy EXCEPTION;
    PRAGMA EXCEPTION_INIT(e_resource_busy, -54);

    TYPE t_names IS TABLE OF user_tab_partitions.partition_name%TYPE
        INDEX BY PLS_INTEGER;
    v_drop t_names;
    v_drop_count PLS_INTEGER := 0;
BEGIN
    IF p_hist_days IS NULL OR p_hist_days < 1
       OR p_pause_secs IS NULL OR p_pause_secs < 0
    THEN
        RAISE_APPLICATION_ERROR(
            -20011,
            'p_hist_days must be >= 1 and p_pause_secs >= 0');
    END IF;

    EXECUTE IMMEDIATE 'ALTER SESSION SET DDL_LOCK_TIMEOUT = 0';

    FOR p IN
        (
            SELECT partition_name, high_value
            FROM   user_tab_partitions
            WHERE  table_name = 'CPS_METRICS_H'
            AND    partition_name <> 'CPS_METRICS_H_P_INIT'
            ORDER  BY partition_position
        ) LOOP
        v_hi := TO_NUMBER(p.high_value);
        IF v_hi <= v_cut_sec THEN
            v_drop_count := v_drop_count + 1;
            v_drop(v_drop_count) := p.partition_name;
        END IF;
    END LOOP;

    FOR i IN 1 .. v_drop_count LOOP
        BEGIN
            EXECUTE IMMEDIATE
                'ALTER TABLE CPS_METRICS_H DROP PARTITION "'
                || v_drop(i) || '"';
        EXCEPTION
            WHEN e_resource_busy THEN
                RETURN;
        END;

        IF p_pause_secs > 0 THEN
            DBMS_SESSION.SLEEP(p_pause_secs);
        END IF;
    END LOOP;
END CPS_PRUNE_H;
/

-- ═══════════════════════════════════════════════════════════════════════
-- 8. Incremental-stats prefs + CPS_MAINTAIN (stale-only gather, local-index health)
-- ═══════════════════════════════════════════════════════════════════════
BEGIN
    DBMS_STATS.SET_TABLE_PREFS(USER, 'CPS_METRICS',   'INCREMENTAL',       'TRUE');
    DBMS_STATS.SET_TABLE_PREFS(USER, 'CPS_METRICS',   'INCREMENTAL_LEVEL', 'PARTITION');
    DBMS_STATS.SET_TABLE_PREFS(USER, 'CPS_METRICS',   'GRANULARITY',       'AUTO');
    DBMS_STATS.SET_TABLE_PREFS(USER, 'CPS_METRICS_H', 'INCREMENTAL',       'TRUE');
    DBMS_STATS.SET_TABLE_PREFS(USER, 'CPS_METRICS_H', 'INCREMENTAL_LEVEL', 'PARTITION');
    DBMS_STATS.SET_TABLE_PREFS(USER, 'CPS_METRICS_H', 'GRANULARITY',       'AUTO');
END;
/

CREATE OR REPLACE PROCEDURE CPS_MAINTAIN AS
BEGIN
    -- Stale-only gathers; DEGREE pinned to 2 (never AUTO_DEGREE) to cap parallel IO.
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'CPS_METRICS',
        options => 'GATHER AUTO', degree => 2, cascade => TRUE);
    DBMS_STATS.GATHER_TABLE_STATS(USER, 'CPS_METRICS_H',
        options => 'GATHER AUTO', degree => 2, cascade => TRUE);

    -- Defensive: rebuild any local index partition that went UNUSABLE.
    FOR ix IN (
        SELECT index_name, partition_name
        FROM   user_ind_partitions
        WHERE  status = 'UNUSABLE'
        AND    index_name IN ('CPS_METRICS_PK','CPS_METRICS_RUN_LBL_IDX',
                              'CPS_METRICS_H_PK')
    ) LOOP
        EXECUTE IMMEDIATE 'ALTER INDEX "' || ix.index_name
            || '" REBUILD PARTITION "' || ix.partition_name || '" ONLINE';
    END LOOP;
END CPS_MAINTAIN;
/

-- ═══════════════════════════════════════════════════════════════════════
-- 9. Nightly job: archive → prune → stats, serialized so nothing contends.
--    Fires at a minute chosen at deploy time within
--    04:00–06:59 America/New_York so groups don't all start at once.
-- ═══════════════════════════════════════════════════════════════════════
DECLARE
    v_hour   PLS_INTEGER := TRUNC(DBMS_RANDOM.VALUE(4, 7));
    v_minute PLS_INTEGER := TRUNC(DBMS_RANDOM.VALUE(0, 60));
BEGIN
    BEGIN DBMS_SCHEDULER.DROP_JOB('CPS_NIGHTLY_MAINT', force => TRUE);
    EXCEPTION WHEN OTHERS THEN NULL; END;

    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'CPS_NIGHTLY_MAINT',
        job_type        => 'PLSQL_BLOCK',
        job_action      => 'BEGIN CPS_ARCHIVE_TO_H(7); CPS_PRUNE_H(30); CPS_MAINTAIN; END;',
        start_date      => TIMESTAMP '2026-01-01 04:00:00 America/New_York',
        repeat_interval => 'FREQ=DAILY;BYHOUR=' || v_hour || ';BYMINUTE=' || v_minute || ';BYSECOND=0',
        comments        => 'Worker-aggregated history: staged archive -> 30d prune -> stats for CPS',
        enabled         => TRUE);
END;
/

-- ═══════════════════════════════════════════════════════════════════════
-- 10. Grants — readers see the shared dims + this group's facts; purgers
--     may delete this group's facts.
-- ═══════════════════════════════════════════════════════════════════════
GRANT SELECT ON LABEL          TO METRICS_READER;
GRANT SELECT ON RUN            TO METRICS_READER;
GRANT SELECT ON WORKER         TO METRICS_READER;
GRANT SELECT ON GROUP_REGISTRY TO METRICS_READER;
GRANT SELECT ON CPS_METRICS   TO METRICS_READER;
GRANT SELECT ON CPS_METRICS_H TO METRICS_READER;
GRANT SELECT, DELETE ON CPS_METRICS   TO METRICS_PURGER;
GRANT SELECT, DELETE ON CPS_METRICS_H TO METRICS_PURGER;
