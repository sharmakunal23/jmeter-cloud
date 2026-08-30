# The control-plane tables on Oracle

What `oracle/migrations/V2__controlPlaneSchema.sql` adds to `CARDZATE_DB_GRAF`
beside the hosted metrics layout: the 13 `ORCH_`-prefixed control-plane tables
and the one place a plain translation would have been wrong — the two claim
queries. The prefix keeps the two families apart in one schema (`ORCH_RUN` is a
launch; `RUN` is the metrics dimension, `RUN.RUN_KEY = ORCH_RUN.RUN_ID`). For
anyone adding a table or a repository.

## Tables

| Table | Key | Notes |
|---|---|---|
| `ORCH_RUN` | `RUN_ID` | `SAVE_RESULTS NUMBER(1)`; `HIDDEN_AT` = soft delete; `APPLICATION` is the app's name; indexes on `CREATED_AT`, `(APPLICATION, CREATED_AT)`, `(METRICS_GROUP_ID, STATE)`, `(STATE, CREATED_AT)` — the Postgres partial indexes have no Oracle form and the table is small |
| `ORCH_RUN_FLEET_MEMBER` | `(RUN_ID, WORKER_ID)` | FK → `ORCH_RUN` `ON DELETE CASCADE`; `PROPERTIES CLOB IS JSON`; `(WORKER_ID, STATE, CREATED_AT)` index serves the claim's `NOT EXISTS` |
| `ORCH_APPLICATION_GROUP` | `GROUP_ID`, unique `NAME` | a team's applications **and their worker pool**; `GROUP_ID` (`[a-z][a-z0-9_]{0,29}`) = `GROUP_REGISTRY.GROUP_ID`, what workers send as `?groupId=`; `UPPER(groupId)` prefixes the group's `_METRICS` / `_METRICS_H` tables; carries the pod policy (`RECYCLE_POLICY` CHECKs + thresholds, `ALWAYS_ON NUMBER(1)`) |
| `ORCH_APPLICATION` | `APPLICATION_ID`, unique `NAME` | `METRICS_GROUP_ID` **NOT NULL** FK → `ORCH_APPLICATION_GROUP` (no ON DELETE: a group with applications cannot be deleted; indexed) + `METRICS_APPLICATION` (the group classifier's `LABEL.APPLICATION` value); `HEALTH_ENDPOINTS`/`LAST_HEALTH_DETAILS` CLOB `IS JSON`. No pool policy here — it is the group's |
| `ORCH_GROUP_CAPACITY` | `(GROUP_ID, REGION)` | FK → group cascade; `MAX_AVAILABLE BETWEEN 0 AND 1000` — every application in the group draws on it |
| `ORCH_POD` | `POD_ID` | FK → group (no action: a group with workers cannot be deleted); `SOURCE IN (DYNAMIC, STATIC)`; `(GROUP_ID, REGION, STATE, LAST_HEARTBEAT)` is the claim's candidate index **and** the FK index — without one, deleting a group takes a table lock on `ORCH_POD` |
| `ORCH_RUN_EVENT` | `EVENT_ID` | FK → run cascade; `PAYLOAD CLOB IS JSON`; append-only |
| `ORCH_CRON_JOB` | `CRON_JOB_ID`, unique `(APPLICATION_NAME, NAME)` | `KIND` + kind-fields CHECKs; `(ENABLED, NEXT_FIRE_AT)` index is the claim's candidate scan |
| `ORCH_CRON_JOB_FIRE_HISTORY`, `ORCH_APPLICATION_HEALTH_HISTORY`, `ORCH_PURGE_AUDIT` | id | deliberately FK-less — they outlive their subjects |
| `ORCH_RUN_TREND` | `RUN_ID` | `BINARY_DOUBLE` — these are stored ratios, not sums |
| `ORCH_AI_RESPONSE` | `(KIND, CACHE_KEY, PROMPT_VERSION)` | `RESPONSE CLOB IS JSON` |

Naming: every identifier UPPER_SNAKE, unquoted (the metrics layout's rule);
tables `ORCH_<NAME>`, constraints and indexes `ORCH_<TABLE>_<COLS>_{PK,FK,UQ,CHK,IDX}`.
Type rules: ids `VARCHAR2(64 CHAR)`, names 255, free text 4000, JSON `CLOB CHECK (IS JSON)`,
booleans `NUMBER(1) CHECK IN (0,1)`, instants `TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP`.

**Oracle's composite-unique rule replaces the partial unique index.** Postgres
needed `WHERE applicationName IS NULL` because it treats NULLs as distinct;
Oracle lets only an *all*-NULL key repeat, so `UNIQUE (APPLICATION_NAME, NAME)`
already rejects a second `(NULL, 'daily')`. Don't add the emulation back.

## `ORCH_CLAIMS` — `LIMIT n FOR UPDATE SKIP LOCKED` the Oracle way

`FETCH FIRST n ROWS ONLY` cannot be combined with `FOR UPDATE` (ORA-02014), and
a `FOR UPDATE` cursor with an `ORDER BY` locks its **whole** result set at open,
so a cursor-with-limit would lock every idle worker of the group for the
length of the launch transaction. `ORCH_CLAIMS` therefore does:

```
candidates := SELECT POD_ID … WHERE STATE='IDLE' AND no non-terminal member … ORDER BY LAST_HEARTBEAT DESC   (no locks)
for each candidate, until n are held:
    SELECT POD_ID INTO v FROM ORCH_POD WHERE POD_ID = :c AND <same predicates> FOR UPDATE OF STATE SKIP LOCKED
    NO_DATA_FOUND → another claimer holds it, or it stopped being claimable → skip
OPEN cursor FOR SELECT <ORCH_POD columns> WHERE POD_ID IN (TABLE(held)) ORDER BY LAST_HEARTBEAT DESC
```

| Procedure | Returns | Caller's contract |
|---|---|---|
| `CLAIM_IDLE_PODS(region, groupId, limit, OUT cursor)` — either filter may be NULL | the `ORCH_POD` columns the Java row mapper reads, freshest first | insert the `ORCH_RUN_FLEET_MEMBER` rows **before** committing — the row locks are the reservation until then |
| `CLAIM_DUE_CRON_JOBS(now, limit, OUT cursor)` | the `ORCH_CRON_JOB` columns, earliest `NEXT_FIRE_AT` first | advance `NEXT_FIRE_AT` before committing — that is what makes a fire exactly-once across replicas |

Both are called from a `BEGIN … END;` block with a `REF CURSOR` out parameter
(`Types.REF_CURSOR`); the locks belong to the caller's JDBC transaction.

| Verified (2026-08-28 on 23ai Free; re-run under the renamed schema 2026-09-01 by `GlobalRunDbTest`) | Result |
|---|---|
| 5 IDLE workers (one held by a RUNNING member, one in another region); session A claims 2 and holds; session B claims 5 while `v$locked_object` shows A's locks | A: `w3, w2` (freshest first); B: `w1` only |
| 5 schedules: two due, one not due, one disabled, one platform-level future | claim returns the two due, earliest first |
| Duplicate platform job name, `MAX_RUNS` without a threshold, pod for an unknown group, non-JSON `PROPERTIES` | ORA-00001, ORA-02290, ORA-02291, ORA-02290 |
| V1 + V2 applied from an empty schema | every object `VALID`, 13 `ORCH_*` tables, no non-UPPER object or column name but Flyway's own history |

## Roles

`GLOBAL_ORCHESTRATOR_WRITER` — the hub's run-state pool: full DML on `ORCH_RUN`,
`ORCH_RUN_FLEET_MEMBER`, `ORCH_POD`, `ORCH_APPLICATION_GROUP`, `ORCH_APPLICATION`,
`ORCH_GROUP_CAPACITY`, `ORCH_CRON_JOB`, `ORCH_AI_RESPONSE`; `SELECT, INSERT` on the
append-only tables (plus `DELETE` where a purge path exists: `ORCH_RUN_TREND`,
`ORCH_APPLICATION_HEALTH_HISTORY`); `EXECUTE` on `ORCH_CLAIMS` and `ORCH_ID_TABLE`.
V2 also carries `METRICS_READER`'s / `METRICS_PURGER`'s grants on the shared
metrics dimensions, so V1 stays the hosted file. The owner keeps DDL; every
pool sets `CURRENT_SCHEMA = CARDZATE_DB_GRAF` and names tables bare.

## Upgrading a database that has the pre-09-01 layout

`DROP USER "globalOrchestrator" CASCADE` (and the three quoted users), create
the users from `initdb/`, `flyway repair` (V1's checksum changed when its
grants moved to V2), then `flyway migrate` — V2 and the re-rendered bundles
apply on top of the existing V1. Oracle cannot rename a user, so the old
control-plane rows are not carried over.
