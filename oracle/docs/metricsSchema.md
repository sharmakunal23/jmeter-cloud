# The metrics schema on Oracle

What `oracle/migrations/metrics/V1__metricsSchema.sql` builds and the three
rules that keep it correct, for anyone changing the consumer's write path, the
orchestrator's reads, or retention. It replaces the Postgres-era schema design
(in git history before the 2026-08-28 cutover); the rollup idea carried over,
the mechanics did not.

## Tables

| Table | Key | Partitioned | Written by | Read by |
|---|---|---|---|---|
| `"workerMetric"` | `(runId, workerId, label, windowSecond)` | weekly `INTERVAL (604800)` on `windowSecond` | `metricsIngest` only | rebuild, purge |
| `"workerMetricStatus"` | `(…, code)` — one row per response code | same | `metricsIngest` only | rebuild, purge |
| `"runSecond"` | `(runId, windowSecond, region)` | same | `metricsIngest` only | every orchestrator timeseries read |
| `"runSecondStatus"` | `(…, code)` | same | `metricsIngest` only | status-code series |
| `"runLabel"` | `(runId, label)` | no (index on `lastSecond`) | `metricsIngest` only | aggregate report, per-label rollup, purge bounds |
| `"workerMetricStage"`, `"workerMetricStatusStage"` | — | global temporary, `ON COMMIT DELETE ROWS` | the consumer's JDBC batch | `metricsIngest` |
| `"maintenanceLock"` | one row `'retention'` | — | — | `FOR UPDATE SKIP LOCKED` by the retention job |

Every primary key on a partitioned table is a `LOCAL` index (it contains
`windowSecond`), so a partition drop is metadata-only and no global index ever
goes `UNUSABLE`. The `"p0" VALUES LESS THAN (0)` anchor is never dropped —
Oracle refuses to drop the last range partition of an interval table.

Column sizes are the wire contract's bounds, enforced by the consumer at the
edge (400) before the database ever sees them: ids and regions 64 chars,
labels 255, response codes 128 (`Non HTTP response code: …` strings), counts
non-negative, `errorCount ≤ throughput`, `windowSecond > 0`. Every `VARCHAR2`
declares `CHAR` semantics because the instance default is `BYTE`.

## Rule 1 — rollups are exact deltas of rows that actually landed

```
consumer, one transaction per chunk (≤ 5,000 rows):
  JDBC addBatch  → "workerMetricStage" + "workerMetricStatusStage"      (1 round-trip)
  CALL metrics."metricsIngest"."ingestStaged"(:landed)                   (1 round-trip)
     1. drop replays: DELETE stage WHERE EXISTS (same key in "workerMetric")
     2. INSERT "workerMetric"/"workerMetricStatus" FROM stage     ← ORA-00001 = a concurrent
     3. mergeStaged: MERGE the three rollups FROM stage               replica won the key →
        :landed = rows inserted in step 2                             whole tx rolls back →
  (the writer de-duplicates by key before staging)                    503 → worker replays
```

After step 1 the stage holds exactly the rows this transaction inserts, so
the MERGE in step 3 can never add a delta for a row that did not land. The
ORA-00001 abort in step 3 is what makes that true under concurrency —
**never hint `IGNORE_ROW_ON_DUPKEY_INDEX` on that insert**; it would turn the
abort into a silent double-count.

**The rollup MERGEs retry on `DUP_VAL_ON_INDEX`.** MERGE is not atomic
against a concurrent inserter: two workers in one region posting the same
second both find no `runSecond` row, both take the INSERT branch, and the
loser raises ORA-00001 once the winner commits — 27 of 2,427 ingest POSTs in
the 8-worker validation run before the retry existed. `mergeStaged` takes a
savepoint, runs the three MERGEs, and on a duplicate rolls back to the
savepoint and merges again (up to 8 times) — one policy, no partial delta,
and the retry matches the now-committed row. The raw insert before it is
untouched, so exactly-once holds. Two replicas merging the same keys in
opposite orders can still deadlock (ORA-00060): that is a 503 and a replay.

## Rule 2 — one aggregation, shared by ingest and rebuild

`"rebuildRunRollups"(runId)` deletes a run's rollups, stages the run's raw rows
into the same temporary tables, and calls the same `mergeStaged`. There is no
second copy of the arithmetic to drift; the `-PdbTests` suite still asserts
`delta == rebuild` as a tripwire. **It refuses (ORA-20002) when the raw rows no
longer cover the run's `runLabel` window** — raw retention is 30 days, rollups
keep 52 weeks, and a rebuild from partial raw would silently shrink a run the
rollups still describe in full. Rollup columns are component sums, never
ratios — readers fold across regions, labels and seconds, then divide.
`NUMBER` arithmetic is exact, so no cast is needed anywhere.

## Rule 3 — retention is a partition drop, run by one replica

`"metricsRetention"."dropOldRaw"(keepDays)` and `"dropOldRollups"(keepWeeks)`
drop every interval partition whose upper bound is at or below the cutoff,
found by `HIGH_VALUE` (interval partitions carry system names). `"runLabel"`
is swept by `DELETE … WHERE "lastSecond" < cutoff`. The consumer's job takes
`"maintenanceLock"` `FOR UPDATE SKIP LOCKED` first; the DDL runs in an
autonomous transaction so its implicit commit cannot release that lock
mid-pass. Partition granularity means a week's rows can outlive the cutoff by
up to seven days — by design.

| Verified 2026-08-28 on 23ai Free | Result |
|---|---|
| Chunk with an in-batch duplicate, then full replay, then partial replay + 4 new keys | landed 9 → 0 → 4 |
| Delta-maintained rollups vs `rebuildRunRollups`, all three tables | 0 differing rows |
| First insert into a new week | partition appeared (`SYS_P…`) |
| `dropOldRaw(30)` with a 2024 row present / with only current-week rows | dropped that week from both raw tables / dropped none |
| Lock held across the autonomous DDL | still held after the call |
| 8 workers × 300 s across 2 regions (OM-7), before the MERGE retry | 2,397 raw rows, every worker's seconds contiguous, raw == rollups == rebuild; the MERGE insert race surfaced as 27 retried 503s |
| Same run with the retry | 2,400/2,400 rows, 0 × 503, ingest p50 3 / p99 7 / max 17 ms, `/timeseries` p99 17 ms |
| Worker killed mid-run · database paused 30 s mid-run | run COMPLETED both times; raw == rollups == rebuild; the outage's replays collided only with their own stalled originals (`workerMetric_pk`, 2 × 503, pruned on replay) |

## Roles

| User | Can |
|---|---|
| `"metricsWriter"` | `INSERT` the two stage tables, `EXECUTE` both packages, `SELECT/UPDATE` `"maintenanceLock"`. Cannot touch the raw or rollup tables directly. |
| `"metricsReader"` | `SELECT` on all five tables |
| `"metricsPurger"` | `SELECT, DELETE` on all five tables |
