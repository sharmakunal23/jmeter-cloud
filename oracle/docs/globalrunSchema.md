# The control-plane schema on Oracle

What `oracle/migrations/globalrun/V1__globalOrchestratorSchema.sql` builds —
the 12 `"globalOrchestrator"` tables in the shape 29 Postgres migrations had
evolved them to — and the one place a plain translation would have been
wrong: the two claim queries. For anyone porting the global orchestrator's
repositories (OM-5) or adding a table.

## Tables

| Table | Key | Notes |
|---|---|---|
| `"run"` | `runId` | `saveResults NUMBER(1)`; `hiddenAt` = soft delete; indexes on `createdAt`, `(application, createdAt)`, `(state, createdAt)` — the Postgres partial indexes have no Oracle form and the table is small |
| `"runFleetMember"` | `(runId, workerId)` | FK → run `ON DELETE CASCADE`; `properties CLOB IS JSON`; `(workerId, state, createdAt)` index serves the claim's `NOT EXISTS` |
| `"application"` | `applicationId`, unique `name` | recycle policy CHECKs as last stated (V17); `healthEndpoints`/`lastHealthDetails` CLOB `IS JSON`; `alwaysOn NUMBER(1)` |
| `"applicationCapacity"` | `(applicationId, region)` | FK cascade; `maxAvailable BETWEEN 0 AND 1000` |
| `"pod"` | `podId` | FK → application (no action); `source IN (DYNAMIC, STATIC)`; `(applicationId, region, state, lastHeartbeat)` is the claim's candidate index **and** the FK index — without one, deleting an application takes a table lock on `pod` |
| `"runEvent"` | `eventId` | FK → run cascade; `payload CLOB IS JSON`; append-only |
| `"cronJob"` | `cronJobId`, unique `(applicationName, name)` | kind + kindFields CHECKs as last stated (V23); `(enabled, nextFireAt)` index is the claim's candidate scan |
| `"cronJobFireHistory"`, `"applicationHealthHistory"`, `"purgeAudit"` | id | deliberately FK-less — they outlive their subjects |
| `"runTrend"` | `runId` | `BINARY_DOUBLE` — these are stored ratios, not sums |
| `"aiResponse"` | `(kind, cacheKey, promptVersion)` | `response CLOB IS JSON` |

Type rules: ids `VARCHAR2(64 CHAR)`, names 255, free text 4000, JSON `CLOB CHECK (IS JSON)`,
booleans `NUMBER(1) CHECK IN (0,1)`, instants `TIMESTAMP(3) WITH TIME ZONE DEFAULT SYSTIMESTAMP`.

**Oracle's composite-unique rule replaces the partial unique index.** Postgres
needed `cronJob_platformName_uq WHERE applicationName IS NULL` because it treats
NULLs as distinct; Oracle lets only an *all*-NULL key repeat, so
`UNIQUE (applicationName, name)` already rejects a second `(NULL, 'daily')`.
Don't add the emulation back.

## The claims package — `LIMIT n FOR UPDATE SKIP LOCKED` the Oracle way

`FETCH FIRST n ROWS ONLY` cannot be combined with `FOR UPDATE` (ORA-02014), and
a `FOR UPDATE` cursor with an `ORDER BY` locks its **whole** result set at open,
so a cursor-with-limit would lock every idle worker of the application for the
length of the launch transaction. `"claims"` therefore does:

```
candidates := SELECT podId … WHERE state='IDLE' AND no non-terminal member … ORDER BY lastHeartbeat DESC   (no locks)
for each candidate, until n are held:
    SELECT podId INTO v FROM pod WHERE podId = :c AND <same predicates> FOR UPDATE OF state SKIP LOCKED
    NO_DATA_FOUND → another claimer holds it, or it stopped being claimable → skip
OPEN cursor FOR SELECT <pod columns> WHERE podId IN (TABLE(held)) ORDER BY lastHeartbeat DESC
```

| Procedure | Returns | Caller's contract |
|---|---|---|
| `"claimIdlePods"(region, applicationId, limit, OUT cursor)` — either filter may be NULL | the `"pod"` columns the Java row mapper reads, freshest first | insert the `"runFleetMember"` rows **before** committing — the row locks are the reservation until then |
| `"claimDueCronJobs"(now, limit, OUT cursor)` | the `"cronJob"` columns, earliest `nextFireAt` first | advance `nextFireAt` before committing — that is what makes a fire exactly-once across replicas |

Both are called from a `BEGIN … END;` block with a `REF CURSOR` out parameter
(`Types.REF_CURSOR`); the locks belong to the caller's JDBC transaction.

| Verified 2026-08-28 on 23ai Free | Result |
|---|---|
| 5 IDLE workers (one held by a RUNNING member, one in another region); session A claims 2 and holds; session B claims 5 while `v$locked_object` shows A's locks | A: `w3, w2` (freshest first); B: `w1` only |
| 5 schedules: two due, one not due, one disabled, one platform-level future | claim returns the two due, earliest first |
| Duplicate platform job name, `MAX_RUNS` without a threshold, pod for an unknown app, non-JSON `properties` | ORA-00001, ORA-02290, ORA-02291, ORA-02290 |
| V1 applied twice from an empty schema | 61 objects `VALID` both times |

## Roles

`"globalOrchestratorWriter"` — the service's only user: full DML on `run`,
`runFleetMember`, `pod`, `application`, `applicationCapacity`, `cronJob`,
`aiResponse`; `SELECT, INSERT` on the append-only tables (plus `DELETE` where a
purge path exists: `runTrend`, `applicationHealthHistory`); `EXECUTE` on
`"claims"` and the `"idTable"` type. The owner keeps DDL.
