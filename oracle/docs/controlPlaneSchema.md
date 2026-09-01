# The control-plane tables on Oracle

What `oracle/migrations/V2__controlPlaneSchema.sql` adds to `CARDZATE_DB_GRAF`
beside the hosted metrics layout: the 13 `ORCH_`-prefixed control-plane tables
and the one place a plain translation would have been wrong — the two claim
queries. `V3__pluginLibrary.sql` (UX-DYNAMICS T3, 2026-08-30) adds the 14th,
`ORCH_PLUGIN`, plus the run's `PLUGINS` snapshot column;
`V4__clusterRegistry.sql` (CLUSTER-CAPACITY, 2026-08-31) the 15th,
`ORCH_REGION`, plus the FK that makes every reservation name a registered cluster;
`V5__workflows.sql` (WORKFLOWS, 2026-08-31) the 16th to 18th — the workflow, its
executions and their tasks — with `V6__workflowExecutionArchive.sql` adding the
execution's `HIDDEN_AT` and `V7__workflowVerdictBackfill.sql` settling the
executions stored before an execution stopped forgiving a handled failure. The prefix keeps the two families apart in one schema (`ORCH_RUN` is a
launch; `RUN` is the metrics dimension, `RUN.RUN_KEY = ORCH_RUN.RUN_ID`). For
anyone adding a table or a repository.

## Tables

| Table | Key | Notes |
|---|---|---|
| `ORCH_RUN` | `RUN_ID` | `SAVE_RESULTS NUMBER(1)`; `HIDDEN_AT` = soft delete; `APPLICATION` is the app's name; `PLUGINS CLOB IS JSON` (V3) — the launch-time plugin snapshot `[{pluginId,name,version,blobId,fileName}]`, deliberately no FK so registry deletes never break a run; indexes on `CREATED_AT`, `(APPLICATION, CREATED_AT)`, `(METRICS_GROUP_ID, STATE)`, `(STATE, CREATED_AT)` — the Postgres partial indexes have no Oracle form and the table is small; `WORKFLOW_EXECUTION_ID` + `WORKFLOW_TASK_ID` (V5) with a **unique index on `WORKFLOW_TASK_ID`** — the fence that lets a load-test task launch at most one run, free because Oracle indexes no all-NULL key |
| `ORCH_RUN_FLEET_MEMBER` | `(RUN_ID, WORKER_ID)` | FK → `ORCH_RUN` `ON DELETE CASCADE`; `PROPERTIES CLOB IS JSON`; `(WORKER_ID, STATE, CREATED_AT)` index serves the claim's `NOT EXISTS` |
| `ORCH_APPLICATION_GROUP` | `GROUP_ID`, unique `NAME` | a team's applications **and their worker pool**; `GROUP_ID` (`[a-z][a-z0-9_]{0,29}`) = `GROUP_REGISTRY.GROUP_ID`, what workers send as `?groupId=`; `UPPER(groupId)` prefixes the group's `_METRICS` / `_METRICS_H` tables; carries the pod policy (`RECYCLE_POLICY` CHECKs + thresholds, `ALWAYS_ON NUMBER(1)`) and, since V5, who owns it — `TEAM_NAME` + the comma-separated `NOTIFY_TO`/`NOTIFY_CC`/`NOTIFY_BCC` a workflow's email nodes inherit |
| `ORCH_APPLICATION` | `APPLICATION_ID`, unique `NAME` | `METRICS_GROUP_ID` **NOT NULL** FK → `ORCH_APPLICATION_GROUP` (no ON DELETE: a group with applications cannot be deleted; indexed) + `METRICS_APPLICATION` (the group classifier's `LABEL.APPLICATION` value); `HEALTH_ENDPOINTS`/`LAST_HEALTH_DETAILS` CLOB `IS JSON`. No pool policy here — it is the group's |
| `ORCH_GROUP_CAPACITY` | `(GROUP_ID, REGION)` | FK → group cascade + FK → `ORCH_REGION` (V4, no action); `MAX_AVAILABLE BETWEEN 0 AND 1000` is the group's **reservation** on that cluster — every application in the group draws on it, and the SUM of reservations per region must fit the cluster's `MAX_WORKERS` (service-enforced under a `FOR UPDATE` of the `ORCH_REGION` row) |
| `ORCH_POD` | `POD_ID` | FK → group (no action: a group with workers cannot be deleted); `SOURCE IN (DYNAMIC, STATIC)`; `(GROUP_ID, REGION, STATE, LAST_HEARTBEAT)` is the claim's candidate index **and** the FK index — without one, deleting a group takes a table lock on `ORCH_POD` |
| `ORCH_RUN_EVENT` | `EVENT_ID` | FK → run cascade; `PAYLOAD CLOB IS JSON`; append-only |
| `ORCH_CRON_JOB` | `CRON_JOB_ID`, unique `(GROUP_ID, NAME)` | **Group-scoped since V9** — `APPLICATION_NAME`/`TEMPLATE_BLOB_ID` dropped for `GROUP_ID` (FK → group, indexed) + `WORKFLOW_ID` (**no FK**: deleting a workflow must leave the schedule, whose next fire reports FAILED, rather than fail the delete or silently remove it). `KIND` + kind-fields CHECKs decide which of `WORKFLOW_ID`/`REGION` may be set; `(ENABLED, NEXT_FIRE_AT)` is the claim's candidate scan. A report's NULL group still collides on name — Oracle repeats only an all-NULL key |
| `ORCH_CRON_JOB_FIRE_HISTORY`, `ORCH_APPLICATION_HEALTH_HISTORY`, `ORCH_PURGE_AUDIT` | id | deliberately FK-less — they outlive their subjects. V9 renamed the fire history's `RUN_ID` to `EXECUTION_ID`: a fire starts a workflow execution, never a run directly |
| `ORCH_CRON_JOB_RETIRED` (V9) | `CRON_JOB_ID` | schedules V9 could not carry into the group-scoped shape, kept verbatim with the reason so an operator can re-create them. Never read by the application |
| `ORCH_RUN_TREND` | `RUN_ID` | `BINARY_DOUBLE` — these are stored ratios, not sums |
| `ORCH_AI_RESPONSE` | `(KIND, CACHE_KEY, PROMPT_VERSION)` | `RESPONSE CLOB IS JSON`. Its V2 table comment says "rather than in Redis" — a reference to a store retired by V8; an applied migration is immutable, so the correction lives here |
| `ORCH_PLUGIN` (V3) | `PLUGIN_ID`, unique `NAME`, unique `SHA256` | the global JMeter plugin library — one version per plugin (upgrade = delete + re-register; rows immutable, no UPDATE grant); jar bytes live in a document-service blob (`BLOB_ID`); a delete is blocked `409` while a non-terminal run's snapshot references it |
| `ORCH_REGION` (V4) | `REGION`, unique `LABEL`, unique `REGIONAL_URL` | a registered cluster, identified three ways and unique in all of them (id/PK, display name, endpoint — one regional serves one cluster): `MAX_WORKERS 1..20` (default 20, the hard cap — the 180 GB grant at 9 GB per worker), `LAST_VALIDATED_AT`, `LAST_PROBE_*` (the on-demand test-provisioning result). Delete is service-guarded — no capacity rows or pods may reference the region; `ORCH_POD.REGION` and `ORCH_RUN.ORIGIN_REGION` deliberately carry no FK (pods are service-guarded, runs are history) |
| `ORCH_WORKFLOW` (V5) | `WORKFLOW_ID`, unique `(GROUP_ID, NAME)` | a group-scoped task DAG; `GRAPH CLOB IS JSON` is the React Flow document (≤ 64 nodes, hub-validated), `REVISION` is the optimistic lock a stale `PUT` loses on. FK → group, so a group holding workflows cannot be deleted |
| `ORCH_WORKFLOW_EXECUTION` (V5) | `EXECUTION_ID` | one launch; `WORKFLOW_NAME` + `GRAPH` are **snapshots** so a rename or an edit never rewrites history (hence no FK to `ORCH_WORKFLOW`). `NEXT_TICK_AT` is both the schedule and the claim lease, and `ORCH_WORKFLOW_EXECUTION_TICK_CHK` makes a stranded `RUNNING` row — running, but nothing will ever touch it again — unrepresentable |
| `ORCH_WORKFLOW_TASK` (V5) | `TASK_ID`, unique `(EXECUTION_ID, NODE_ID)` | one node per execution; FK → execution cascade. `RUN_ID` is `LOAD_TEST`-only (CHECK) and carries **no** FK — a run purge must not delete workflow history; its index is sparse and serves run → workflow on the run page |
| `ORCH_CACHE` (V8) | `CACHE_KEY` | the hub's Spring Cache store, replacing Redis. **V8's header says "same six cache names"; there are five** — `groupCapacity` was dropped in the same wave (a transactional evict-before-commit could re-cache pre-commit rows), and an applied migration's comment cannot be edited. `CacheConfig.cacheTtls()` is the list. `CACHE_KEY` is `<cacheName>::<key>` (hashed past 512 chars) so a get is one `INDEX UNIQUE SCAN` — **the hint names the PK**, because the optimizer otherwise range-scans `ORCH_CACHE_EXPIRES_AT_IDX` and filters the key. `CACHE_VALUE BLOB` is gzipped JSON, stored in-row; a get filters `EXPIRES_AT > SYSTIMESTAMP`, so `ORCH_CACHE_REAP_JOB` reclaims space and never gates freshness. Not partitioned on purpose: a global unique key and `DROP PARTITION` cannot coexist cheaply |

Naming: every identifier UPPER_SNAKE, unquoted (the metrics layout's rule);
tables `ORCH_<NAME>`, constraints and indexes `ORCH_<TABLE>_<COLS>_{PK,FK,UQ,CHK,IDX}` —
`<COLS>` may be abbreviated (`ORCH_POD_GROUP_REGION_STATE_IDX` covers `(GROUP_ID, REGION,
STATE, LAST_HEARTBEAT)`); the suffix is the rule.
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
| `CLAIM_DUE_WORKFLOWS(now, limit, OUT cursor)` (V5) | the `ORCH_WORKFLOW_EXECUTION` columns, earliest `NEXT_TICK_AT` first | push `NEXT_TICK_AT` forward by the lease before committing, then advance the execution **outside** the transaction — a replica that dies mid-advance strands nothing |

**`V5` owns the package.** A package cannot be extended in place, so
`V5__workflows.sql` re-creates `ORCH_CLAIMS` whole and V2's copy is historical;
add the next claim procedure to the newest migration that declares it, never to
V2 (which Flyway checksums and forbids editing anyway).

Both are called from a `BEGIN … END;` block with a `REF CURSOR` out parameter
(`Types.REF_CURSOR`); the locks belong to the caller's JDBC transaction.

| Verified on Oracle Free 26ai (23.26.2) — hand-run 2026-08-28, then `GlobalRunDbTest` under the one schema 2026-08-30 | Result |
|---|---|
| Hand-run: 5 IDLE workers (one held by a RUNNING member, one in another region); session A claims 2 and holds; session B claims 5 while `v$locked_object` shows A's locks | A: `w3, w2` (freshest first); B: `w1` only |
| `GlobalRunDbTest`: 5 IDLE workers in one region; transaction A claims 2 and holds; B claims 5 before A commits | B gets exactly the other 3; a region or group filter that matches none returns none |
| `ORCH_AI_RESPONSE`: upsert → find → upsert again → find past the TTL → purge by run id | the CLOB round-trips by its bare label; the MERGE replaces; an expired row is a miss; `deleteForRun` removes the single-run row and the comparison it sits in |
| 5 schedules: two due, one not due, one disabled, one platform-level future | claim returns the two due, earliest first |
| Duplicate platform job name, `MAX_RUNS` without a threshold, pod for an unknown group, non-JSON `PROPERTIES` | ORA-00001, ORA-02290, ORA-02291, ORA-02290 |
| V5 hand-run 2026-08-31: stranded `RUNNING` execution (no `NEXT_TICK_AT`); terminal execution still carrying one; `EMAIL` task carrying a `RUN_ID`; a second run for one workflow task | ORA-02290 ×3 (`…_TICK_CHK` twice, `…_RUN_ID_CHK`), ORA-00001 on `ORCH_RUN_WORKFLOW_TASK_UQ` — while two runs with a NULL task id coexist |
| V5: `CLAIM_DUE_WORKFLOWS` against one due execution; `EXPLAIN PLAN` for the crash-recovery lookup and the claim's candidate scan | claims it; `INDEX UNIQUE SCAN ORCH_RUN_WORKFLOW_TASK_UQ` and `INDEX RANGE SCAN ORCH_WORKFLOW_EXECUTION_CLAIM_IDX` — no scan on either path |
| Every migration applied from an empty schema | every object `VALID`, 20 `ORCH_*` tables, no non-UPPER object or column name but Flyway's three (`flyway_schema_history`, its `_pk` and `_s_idx` — tool-imposed, like `docker-compose.yml`; don't "fix" them with `-table=`) |
| V8 (`CacheStoreDbTest`, 2026-08-31): round trip through the BLOB; a row aged past `EXPIRES_AT`; `clear` on one cache name; 8 threads writing one key; `ORCH_CACHE_REAP(10, 200)` over a 26-row backlog | value + `VALUE_BYTES` survive; the expired row is **not served while still present**; only that cache's rows go; one row, no duplicate-key error; 25 deleted in chunks, the live row kept |
| V8: `EXPLAIN PLAN` for the get, the reaper's delete and the clear | `INDEX UNIQUE SCAN ORCH_CACHE_PK` **only with the `INDEX` hint** — unhinted it picks `ORCH_CACHE_EXPIRES_AT_IDX` and range-scans every live entry; `COUNT STOPKEY` over `ORCH_CACHE_EXPIRES_AT_IDX`; `INDEX RANGE SCAN ORCH_CACHE_CACHE_NAME_IDX` |

| V9 (`GlobalRunDbTest`, 2026-08-31): the kind/field matrix — a workflow schedule with no workflow, a scale schedule with no cluster, a report scoped to a group, a group that does not exist; then duplicate `(group, name)` and two platform reports sharing a name | ORA-02290 ×3 then ORA-02291; ORA-00001 ×2. `CLAIM_DUE_CRON_JOBS` still returns the renamed columns — **V9 re-creates `ORCH_CLAIMS` and owns it**, because the package names the columns V9 drops and renames and Flyway does not fail on a package that compiled with errors: a stale one would have claimed nothing, silently |
| V9 rehearsed on a throwaway Oracle before the shared one: V1→V9 from empty, then the same matrix | every object `VALID`, `user_errors` empty, 13/13 cases as designed |

## Roles

`GLOBAL_ORCHESTRATOR_WRITER` — the hub's run-state pool: full DML on `ORCH_RUN`,
`ORCH_RUN_FLEET_MEMBER`, `ORCH_POD`, `ORCH_APPLICATION_GROUP`, `ORCH_APPLICATION`,
`ORCH_GROUP_CAPACITY`, `ORCH_CRON_JOB`, `ORCH_AI_RESPONSE`, `ORCH_REGION` (V4),
`ORCH_WORKFLOW` / `ORCH_WORKFLOW_EXECUTION` / `ORCH_WORKFLOW_TASK` (V5), `ORCH_CACHE` (V8); `SELECT, INSERT, DELETE`
on `ORCH_PLUGIN` (V3 — no UPDATE, rows are immutable); `SELECT, INSERT` on the
append-only tables (plus `DELETE` where a purge path exists: `ORCH_RUN_TREND`,
`ORCH_APPLICATION_HEALTH_HISTORY`); `EXECUTE` on `ORCH_CLAIMS` and `ORCH_ID_TABLE`.
V2 also carries `METRICS_READER`'s / `METRICS_PURGER`'s grants on the shared
metrics dimensions, so V1 stays the hosted file. The owner keeps DDL; every
pool sets `CURRENT_SCHEMA = CARDZATE_DB_GRAF` and names tables bare.

## Upgrading a database to V4 (the cluster registry)

`V4` adds `ORCH_REGION` and an FK from `ORCH_GROUP_CAPACITY.REGION`. Because no
cluster is registered when it runs, it **clears every existing reservation row**
(the pools in `ORCH_POD` are untouched). After migrating, register each cluster
(`POST /api/v1/regions`) and re-attach + re-reserve for every group — until then
their launches answer `404 CAPACITY_REGION_NOT_FOUND`.

## Upgrading a database that has the two-schema layout

Oracle cannot rename a user, so the old control-plane rows are not carried
over: as SYS, `DROP USER "globalOrchestrator" CASCADE` and the three quoted
users, re-run `initdb/01_createSchemasAndUsers.sql` (re-runnable — the
existing owner is kept), then repair V1's checksum (its grants block moved to
V2) and migrate; V2 and the re-rendered bundles apply on top of the existing V1.

```bash
docker compose run --rm --entrypoint flyway flyway-migrate \
  -url=jdbc:oracle:thin:@//oracle:1521/FREEPDB1 -user=CARDZATE_DB_GRAF \
  -password="${ORACLE_METRICS_OWNER_PASSWORD:-localdev}" -locations=filesystem:/flyway/sql repair
docker compose up flyway-migrate
```

On Kubernetes the Job is immutable, so it is delete → apply with the
`FLYWAY_COMMAND=repair` patch (stub in `kube/overlays/privateCloud`) → delete →
apply with `migrate`.
