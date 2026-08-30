# The metrics schema — `CARDZATE_DB_GRAF`

The hosted environment's metrics schema reproduced verbatim, so one SQL bundle
runs locally and on the hosted database. Shared dimensions and the group
registry are Flyway `V1__metricsSchema.sql`; every application group's objects
are rendered from `oracle/groups/<groupId>.json` into
`R__group_<groupId>.sql` (a Flyway repeatable) by `oracle/groups/renderGroup.mjs`.

## Tables

| Table | Key | Partitioned | Written by | Read by |
|---|---|---|---|---|
| `LABEL` | `LABEL_ID`; unique `(GROUP_ID, LABEL_KEY)` | no | consumer get-or-create; `APPLICATION` set by `<P>_CLASSIFY_LABEL` on insert | every read (joins) |
| `RUN` | `RUN_ID`; unique `(GROUP_ID, RUN_KEY)` | no | consumer get-or-create; `RUN_BIU` derives `BASE_RUN_KEY` | reads resolve `runId` → `RUN_ID` here |
| `WORKER` | `WORKER_ID`; unique `(RUN_ID, WORKER_KEY)` | no | consumer get-or-create (`REGION`, `JOINED_AT_SECOND` create-only) | per-region reads |
| `GROUP_REGISTRY` | `GROUP_ID` (= `?groupId=`) | no | the group bundle (`MERGE`) | consumer routing, global reader (cached) |
| `<P>_METRICS` | `(RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND)` — the **only** unique index | daily `INTERVAL (86400)` on `WINDOW_SECOND` | consumer, `IGNORE_ROW_ON_DUPKEY_INDEX` | every live/recent read, the archive |
| `<P>_METRICS_H` | `(RUN_ID, LABEL_ID, WINDOW_SECOND)` | daily, one partition per archived day | `<P>_ARCHIVE_TO_H` via `EXCHANGE PARTITION` from `<P>_METRICS_H_STAGE` | reads older than `hotDays` |
| `METRICS_H_AUDIT` | `(SOURCE_TABLE, PARTITION_HIGH_VALUE)` | no | the archive procedure | operators |

`P = UPPER(groupId)`; the dimensions' `GROUP_ID` column holds the group's
`TABLE_PREFIX` (`CPS`), not the registry key (`cps`) — a hosted quirk the
consumer and every reader must keep. The dimension PKs are `RELY` because the
fact tables' FKs are `RELY DISABLE NOVALIDATE` (declared for the optimizer,
never enforced on insert — ORA-25158 otherwise).

## Rule 1 — facts are first-write-wins, one round trip per chunk

```
consumer, per envelope: RUN → WORKER → LABEL get-or-create (SELECT → INSERT → catch dup → re-SELECT, autocommit)
consumer, per chunk (≤ 5,000 rows):
  INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(<P>_METRICS(RUN_ID,WORKER_ID,LABEL_ID,WINDOW_SECOND)) */ … 21 columns
  → a replayed row is silently skipped (update count 0); rowsInserted counts only new rows; 0 is success
```

Oracle validates the hint against a unique index with exactly those four
columns, so the PK must stay the fact table's only unique index — a second one
would fail whole chunks on collision. Corrections are an explicit `UPDATE`;
re-posting never overwrites.

**Local Oracle caveat.** On Oracle Free 26ai (23.26.2) with ojdbc 23.7 a JDBC
array insert of two or more rows in which the hint suppresses a row raises
`ORA-00600 [qerltcUserIterGet_0, <row>]` and ends the session; a one-row batch,
a single insert and PL/SQL `FORALL` are correct, and the hosted consumer's batch
path works on its Oracle version. The consumer therefore reads the labels already
landed for the envelope's `(RUN_ID, WORKER_ID, WINDOW_SECOND)` — one PK-prefix,
partition-pruned probe — and batches only the missing rows; a duplicate that
still reaches a batch (a concurrent replica) is a 503 and a replay, which the
probe then filters. `MetricsSchemaDbTest` carries a tripwire for the bug.

## Rule 2 — no rollups: readers aggregate at query time

Every read carries `RUN_ID` (resolved through `RUN`) **and** a `WINDOW_SECOND`
range so the daily partitions prune and `<P>_METRICS_RUN_LBL_IDX`
`(RUN_ID, LABEL_ID, WINDOW_SECOND)` serves it; buckets are
`FLOOR(WINDOW_SECOND / g) * g` for `g ∈ {15, 30, 60}`, throughput is
`SUM(THROUGHPUT) / g`, latencies are throughput-weighted
(`SUM(AVG_MS * THROUGHPUT) / NULLIF(SUM(THROUGHPUT), 0)`), the application facet
is `LABEL.APPLICATION`, the region facet `WORKER.REGION`. The Grafana panel
queries are the reference shape; the `oracle-sql` skill holds the rules.

The global orchestrator's reader (`MetricsTimeseriesRepository`, Track 4)
computes the bucket once in an inline view and groups by its alias — two
`?` placeholders are two expressions to Oracle (ORA-00979) — and unions the
`_H` archive under the same predicate. Its plan on Oracle Free 26ai (23.26.2):

```
SORT GROUP BY
  VIEW / UNION-ALL
    PARTITION RANGE ITERATOR                      KEY..KEY
      TABLE ACCESS BY LOCAL INDEX ROWID BATCHED   CPS_METRICS
        INDEX RANGE SCAN                          CPS_METRICS_RUN_LBL_IDX   RUN_ID = :r AND WINDOW_SECOND BETWEEN :lo AND :hi
    PARTITION RANGE SINGLE                        KEY..KEY
      TABLE ACCESS BY LOCAL INDEX ROWID BATCHED   CPS_METRICS_H
        INDEX RANGE SCAN                          CPS_METRICS_H_PK
```

## Rule 3 — retention is the group's nightly job

`<P>_NIGHTLY_MAINT` (`DBMS_SCHEDULER`, a random minute in the descriptor's
window) runs `<P>_ARCHIVE_TO_H(hotDays)` → `<P>_PRUNE_H(historyDays)` →
`<P>_MAINTAIN`. The archive collapses each aged daily partition — worker rows
summed per `(RUN_ID, LABEL_ID, WINDOW_SECOND)`, latencies throughput-weighted —
into the private `_STAGE` table in 15-minute chunks, asserts every total is
conserved, publishes the day with one `EXCHANGE PARTITION`, then drops the
source partition; `METRICS_H_AUDIT` records each step so a blocked
(`ORA-00054`, `DDL_LOCK_TIMEOUT = 0`) or failed pass resumes the next night.
Prune drops whole history partitions by `HIGH_VALUE`. Nothing an application
runs ever `DELETE`s from a fact table; the run purge is the one bounded
exception.

## Onboarding a group

```bash
$EDITOR oracle/groups/<groupId>.json            # id, name, applications + label prefixes, hotDays, historyDays, readers
node oracle/groups/renderGroup.mjs --all         # → oracle/migrations/R__group_<groupId>.sql
docker compose up flyway-migrate                 # locally; the DBA runs the same file on the hosted database
```

Then create the application group with the same `groupId` in the UI and put
the applications in it; their workers send `?groupId=<groupId>`.

## Verified 2026-08-29 on Oracle Free 26ai (23.26.2) (`jmeter-metrics-consumer -PdbTests`, `MetricsSchemaDbTest`)

| Gate | Result |
|---|---|
| Shared V1 + `cps` + `demo` bundles applied to an empty schema, then re-applied | every object `VALID`; both `GROUP_REGISTRY` rows; the re-run changes nothing |
| `CPS_METRICS` unique indexes | exactly `CPS_METRICS_PK (RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND)` |
| `CPS_CLASSIFY_LABEL('TG5 …')` / `('TG2 …')` / `('unknown')` | `CPS-PCI` / `CPS` / `OTHER`; `RUN_BIU` turns `MA_cps-2026-08-29_3_S1P2` into `cps-2026-08-29` |
| The hosted worked example (2 rows), a whole-envelope replay through the writer's probe, a single-row race duplicate, a second worker | `[1,1]`; probe returns both labels so nothing is inserted; the race row counts 0 and the first write stays; second worker `[1]`; the by-application query folds 195 |
| Tripwire: a 2-row JDBC batch with one suppressed duplicate | `ORA-00600 [qerltcUserIterGet_0]` on this build (the test fails when the bug is gone); the same duplicate as a single row counts 0 |
| `EXPLAIN PLAN` of the reader shape | `PARTITION RANGE` (not `ALL`), `CPS_METRICS_RUN_LBL_IDX`, no full scan |
| `CPS_ARCHIVE_TO_H(7)` on a 2024 day with 2 workers | 2 history rows with `WORKER_COUNT 2`, throughput 40, weighted `AVG_MS 175`; source partition dropped; audit `COMPLETE`; the current day untouched |
| `CPS_PRUNE_H(30)`, `CPS_MAINTAIN` | history partition dropped; stats gathered |
| Grants on `CPS_METRICS` | `METRICS_READER:SELECT`, `METRICS_PURGER:SELECT, DELETE` — nobody else |
