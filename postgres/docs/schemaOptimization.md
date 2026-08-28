# Schema optimization — space and time

**Status: Phases 0, 1 and 2 landed 2026-07-30 (V15 + V16 + V17). Phase 3 was
written, applied on 2026-08-20 without its application code, and reverted on
2026-08-26 — see the callout below before restarting it. Phase 4 is still a
proposal.** §5.0 records Phases 0–1 as built; §5.3a records Phase 2, and
carries the **first real measurements of row width** this document has ever had
— §2.2's arithmetic was never verified before that. The rest is kept as written
on 2026-07-29, because the argument for the remaining phases depends on it.

Written against a concrete target workload, because "is the schema good?" has no
answer without one:

> **20 workers × 200 endpoints (labels) × 15 hours × 5000 TPS aggregate.**

Everything below is derived from that. A 5-minute smoke test with 3 labels does
not stress any of this, which is why the current schema has been fine so far.

---

## 0. The short answer

`ingestedAt` should go — it is written on every row and read by nothing. It is
also not alone: **four columns are provably dead**, and they are not where the
real money is. At the target workload the raw table is ~71 GB and ~154M rows
**per test run**, and the live chart re-aggregates ~5.1M of those rows every 5
seconds. The dead columns are ~8% of that. The structural problems are:

1. There is **no pre-aggregation**. Every read goes to the per-second,
   per-worker, per-label fact table, and every reader immediately aggregates the
   worker and label dimensions away.
2. The fact grain is **finer than anything that can be displayed**. A 15-hour run
   renders 900 points per series (§2.3); we store 154M rows to draw them.
3. **Identity is stored as text on every row** — `runId`/`workerId`/`label` are
   ~35% of the heap and ~100% of both index keys.

Ordered by leverage, the fixes are: rollup tables (time), then column/type
surgery (space), then dictionary-encoded identity (space), then retention split
(space at rest). §5 has the phasing.

*(2026-07-30: the dead columns, the rollup tables and the type/padding surgery
are all done — §5.0 and §5.3a. What is left is **identity** (Phase 3, measured
at 47% of footprint) and **retention** (Phase 4). Everything from §1 to §4 is
preserved as the argument that justified it.)*

> **⚠ Phase 3 was attempted and reverted. Read this before starting it again.**
>
> `V18__dictionaryEncodedIdentity.sql` was written on 2026-07-30 and applied to
> the local database on 2026-08-20 — **without the application changes it
> requires**. The consumer's `WorkerMetricWriter` and the Grafana dashboard both
> still addressed the fact table by `runId`/`workerId`/`label`, so every ingest
> failed with *column "runId" of relation "workerMetric" does not exist*. The
> migration was in the tracked set, so fresh databases came up broken too, and
> the consumer's integration suite had been red for four weeks before anyone
> noticed — the failure is invisible while nothing is ingesting, because an idle
> consumer already reports DOWN on `ingestProgress`.
>
> Reverted to the V17 shape on 2026-08-26 with all 6,202,437 rows preserved, by
> joining the dictionaries back. The migration is archived at
> `postgres/docs/deferred/V18__dictionaryEncodedIdentity.sql`.
>
> **The schema half of Phase 3 is written and works.** What it needs, all in the
> same change: (1) a consumer-side dictionary cache with insert-on-miss binding
> the surrogate keys; (2) Grafana's panels joined through the three dimensions;
> (3) the soak below, which has still never been run.

---

## 1. What is there today

### 1.1 Databases

| Database | Roles | Contents |
|---|---|---|
| `jmetercloud_metrics` | `metricsWriter` (INSERT+SELECT), `metricsReader` (SELECT), `metricsPurger` (SELECT+DELETE) | one table: `metrics."workerMetric"` |
| `jmetercloud_globalrun` | `globalOrchestratorWriter` | run/fleet/pod/application/capacity/cron/trend/audit — 12 tables |

The metrics DB is where the volume is. The run DB holds O(runs) and O(pods) rows
and is addressed briefly in §7.

### 1.2 `metrics."workerMetric"` as it stands

23 columns; `PARTITION BY RANGE ("windowSecond")`, one partition per ISO week, 8
pre-created; PK `("runId","workerId","label","windowSecond")` (also the ingest
idempotency contract); one secondary index `("runId","label","windowSecond")`.

> **Since V17 (2026-07-30): 17 columns, and a different physical order.** V15
> dropped `ingestedAt`, `windowTimestamp`, `errorRate`, `joinedAtSecond` and
> `rawMaxMs`; V17 dropped `minMs`, replaced `avgRespTimeMs` with an exact
> `sumElapsedMs`, narrowed eight columns to INTEGER, and reordered to
> fixed-width-first (§5.3a). Partitioning, PK and the secondary index are
> unchanged. Three rollup tables sit alongside it — §5.0.

### 1.3 Who actually reads each column

Established by grepping every reader in the repo — the orchestrator's
repositories, the Grafana dashboard, the AI insights service. **As written on
2026-07-29** — the "read by nothing" rows in bold were dropped by V15, and every
non-Grafana reader below now goes through a rollup table instead.

| Column | Type | Read by |
|---|---|---|
| `runId`, `label`, `windowSecond` | TEXT, TEXT, BIGINT | everything |
| `workerId` | TEXT | PK identity; Grafana active-threads panel |
| `region` | TEXT | `fetchNumericByRegion` / `fetchStatusByRegion` (split-by-region) |
| `throughput`, `errorCount` | BIGINT | every aggregate |
| `avgRespTimeMs` | DOUBLE | timeseries weighted mean, Grafana |
| `p50Ms` | DOUBLE | `rollupByLabel`, `runAggregate` |
| `p90Ms` | DOUBLE | Grafana only |
| `p95Ms`, `p99Ms` | DOUBLE | `rollupByLabel`, `runAggregate`, Grafana |
| `maxMs` | DOUBLE | `rollupByLabel`, AI insights — **but see F9, both read the quantized one** |
| `activeThreads` | BIGINT | `rollupByLabel` (`max(…) AS "maxActiveThreads"`), Grafana |
| `statusCodes` | JSONB | timeseries status series, Grafana |
| **`ingestedAt`** | TIMESTAMPTZ | **nothing** |
| **`windowTimestamp`** | TEXT | **nothing** |
| **`errorRate`** | DOUBLE | **nothing** |
| **`joinedAtSecond`** | BIGINT | **nothing** |
| `minMs` | DOUBLE | nothing (see F5) |
| `rawMaxMs` | BIGINT | nothing (see F9) |
| `bytesReceived`, `bytesSent` | BIGINT | nothing (see F5) |

Two of those need care, because a grep for the *name* is misleading:

- `errorRate` — `AiInsightsService` reads a map key `errorRate`, but that is the
  computed alias `sum(errorCount)/sum(throughput) AS "errorRate"` from
  `MetricsRollupRepository`. `RunTrendRepository` reads `errorRate` from
  `globalOrchestrator."runTrend"`. **The column is never selected.**
- `joinedAtSecond` — read in `RunService`/`RunRepository`, but from
  `globalOrchestrator."runFleetMember"`. The metrics copy (V12, added so "future
  per-second fleet rollups" could avoid a join) has no reader. The rollup it was
  added for is the one proposed in §5.2, and that rollup does not need it either.

### 1.4 Read paths and their cadence

| Path | Shape | Cadence |
|---|---|---|
| `GET /runs/{id}/timeseries` | `GROUP BY windowSecond` over the run (+30 min window when live) | **every 5 s per open Metrics tab**; cached only for terminal runs |
| ↳ same request | `jsonb_each_text` LATERAL over the same rows | same |
| ↳ same request | `max("windowSecond") WHERE "runId"=?` | same, whenever a window or settle margin is set — i.e. always, live |
| `GET /runs/{id}/metrics` | whole-test `GROUP BY label` | on demand, uncached while live |
| `runAggregate` | whole-test single row | once at run completion (runTrend snapshot) |
| AI insights | whole-test timeseries + rollup | on demand |
| Grafana `perTestLiveMetrics` | ~14 panels, per-second `GROUP BY`, no downsampling | per dashboard refresh |
| purge | `DELETE … WHERE "runId"=?` | operator-driven |

Caching is `condition = "#state.terminal"` — **a live run bypasses the cache on
every poll by design.** That is correct (the data is changing) and it is why the
live cost matters.

---

## 2. The arithmetic

### 2.1 Row count

Rows = workers × labels × seconds — but buckets are created lazily
(`TumblingWindowAggregator` only materialises a `(label, second)` bucket when a
sample lands in it), so silent windows cost nothing:

- dense upper bound: 20 × 200 × 54,000 = **216,000,000 rows**
- 5000 TPS over 20 workers × 200 labels = **1.25 samples per (worker, label,
  second)**. At Poisson(1.25), 28.7% of windows are empty →
  **≈154,000,000 rows** actual.

That second number is the one to hold onto, and it carries an uncomfortable
implication: **each ~500-byte row describes 1.25 samples.** The aggregation layer
is not aggregating at this fan-out — it is paying full per-row metadata cost to
describe roughly one sample. §3 F2 follows from this.

### 2.2 Footprint

Computed from Postgres' documented heap layout (23-byte tuple header, MAXALIGN 8,
per-attribute `typalign` padding, short 1-byte varlena headers, 4-byte line
pointer, btree entries at fillfactor 90). Assumes ULID `runId` = 26 chars,
`workerId` ≈ 22, `label` ≈ 28, `region` ≈ 7, `windowTimestamp` = 19–20,
`statusCodes` ≈ 27 B for one or two codes.

| | heap/row | PK entry | 2nd idx | total/row | **per 15 h run** |
|---|---|---|---|---|---|
| today | 304 B (8 B wasted on padding) | 107 B | 80 B | **495 B** | **71.0 GB** (heap 44.2 + idx 26.8) |
| after §5.3 | 216 B | 107 B | 80 B | 407 B | 58.3 GB |
| after §5.4 | 128 B | 27 B | 27 B | **185 B** | **26.6 GB** |

Note what the middle row says: doing *all* the cheap column and type work is an
18% win, because **38% of the footprint is two index keys made of text.** That is
the argument for §5.4, and the reason not to stop at §5.3.

WAL, from the same widths: ~1.4 MB/s, **≈72 GB of WAL per run** today; ~26 GB
after §5.4. That is replica/archive bandwidth, not just disk.

At rest with 52-week retention and one such test per weekday: **18 TB** today,
6.4 TB after §5.4, **0.3 TB if only rollups are retained** (§5.5).

### 2.3 The resolution ceiling — why the grain is wrong

`MetricsTimeseriesRepository` buckets server-side: `BUCKET_TARGET = 1500` points,
`MAX_BUCKET_SECONDS = 60`. For a 15-hour run, `chooseBucketSize(54000)` picks
**60 s**, so the whole-test view renders **900 points per series**.

154,000,000 rows → 900 rendered points. **~171,000 rows per point.** Per-second
raw resolution survives only for runs under ~25 minutes (1500 s at width 1);
beyond that it is averaged away before it reaches the browser. We are storing —
and re-reading every 5 seconds — a resolution that the product cannot display and
does not claim to.

(Grafana is worse: its panels `GROUP BY "windowSecond"` with no bucketing, so a
whole-test panel asks for 54,000 points per series.)

### 2.4 Live poll cost

One open Metrics tab on a live 15-hour run, 30-minute window:

- rows aggregated per poll: 20 × 200 × 1800 × 0.713 = **5.13M**
- heap touched: **~1.5 GB per query**, two queries per poll
- `jsonb_each_text` invocations: **10.3M per poll**
- at one poll per 5 s: **~600 MB/s of buffer traffic for a single viewer**

Two viewers, or one viewer plus the Grafana dashboard, doubles it. There is no
per-run work-sharing — each poll is a cold aggregate.

### 2.5 The 30-second wall

The metrics read pool sets `statement_timeout = 30000` (S-3, deliberate). At
154M rows the whole-test queries do not merely get slow, they **cross that
timeout and the features fail**:

- Metrics tab → "Whole test" on a finished 15 h run: error, not a slow chart.
- `runAggregate` at completion → no `runTrend` row for the run, silently.
- AI insights (whole-test timeseries + per-label rollup): fails.
- Purge of one run: `DELETE … WHERE "runId"=?` is unpruned (§3 F7) against a
  120 s timeout.

So at the target workload this is a **correctness/availability** issue with a
performance cause, not a tuning nicety. Raising the timeout is the wrong fix; it
converts a fast failure into a pinned connection out of a pool of 10.

---

## 3. Findings

Severity is against the target workload.

Status as of 2026-07-30 — see §5.0.

| # | Finding | Sev | Status |
|---|---|---|---|
| F1 | No pre-aggregation: every read hits the raw fact table and aggregates away the worker and label dimensions | **high** | **fixed** for orchestrator reads (V16); Grafana still raw |
| F2 | Fact grain (worker × label × second) is finer than anything renderable, and at 1.25 samples/row is barely an aggregate | **high** | open — raw grain unchanged; the rollups make it no longer *read* at that grain |
| F3 | `max("windowSecond") WHERE "runId"=?` cannot use the MIN/MAX index shortcut, and has no partition predicate | **high** | **fixed** — 5,338 buffers → 4 |
| F4 | Four columns written on every row, read by nothing: `ingestedAt`, `windowTimestamp`, `errorRate`, `joinedAtSecond` | med | **fixed** (V15) |
| F5 | Four more with no reader but arguable value: `minMs`, `rawMaxMs`, `bytesReceived`, `bytesSent` | low | **settled** — `rawMaxMs` (V15) and `minMs` (V17) dropped; bytes kept, and carried into the rollups |
| F6 | Types oversized: BIGINT/DOUBLE where INT/SMALLINT is exact; 8 B lost to padding | med | **fixed** (V17) — 304 → 244 B/row measured |
| F7 | Purge `DELETE` has no `windowSecond` predicate → scans every partition | med | **fixed** — bounds come from `runLabel.firstSecond/lastSecond` |
| F8 | Weekly partitions are too coarse at ~71 GB/run | med | open — Phase 4 |
| F9 | `rollupByLabel` reads the histogram-quantized `maxMs`; the exact `rawMaxMs` is the unread one | **correctness** | **fixed** — `maxMs` is now fed the exact value at ingest |
| F10 | Percentiles are aggregated as throughput-weighted means of percentiles | **correctness** | open — the rollups preserve the existing behaviour exactly; §6's sketch is the real fix |
| F11 | `avgRespTimeMs` is a lossy round-trip of a value the worker already has exactly | med | **fixed** (V17) — the wire carries `sumElapsedMs`; the mean is derived at read time |
| F12 | Insert-only table, so the visibility map is never set → no index-only scans | med | **moot** — the query that needed it is gone (§5.0, item 1) |
| F13 | Identity stored as text on every row: ~35% of heap, ~100% of index keys | **high** | open — Phase 3, and now **measured at 47% of total footprint** (§5.3a) |
| F14 | Run-DB history tables grow without retention | low | open |

### F3 — the worst one line of SQL

```java
"SELECT max(\"windowSecond\") FROM metrics.\"workerMetric\" WHERE \"runId\" = ?"
```

Two independent problems:

1. **No index shortcut.** The available index is
   `("runId","label","windowSecond")`. With `runId` fixed by equality, the index
   produces rows ordered by `(label, windowSecond)` — there is no pathkey on
   `windowSecond` alone, so the planner cannot turn this into
   `Index Scan Backward … Limit 1`. It degenerates to reading **every index entry
   for the run** and aggregating: 154M entries by end of test.
2. **No partition pruning.** There is no `windowSecond` predicate, so it visits
   *every* partition, including the 7 future empty ones and every past week still
   inside retention.

And it runs on **every live poll**, because `resolveBounds` calls it whenever a
window or a settle margin is set, and a live run always has `settleSeconds = 5`.

The fix is not another index (that is ~8 GB and slows ingest at this volume) —
it is a one-row-per-run high-water mark (§5.2).

### F9 / F10 / F11 — three correctness items that get cheaper, not dearer

The worker's `SecondBucket` records into an HDRHistogram with
`SIGNIFICANT_DIGITS = 2` and a 3,600,000 ms ceiling, clamping on the way in. So:

- `maxMs` is the histogram's *bucket* upper bound, quantized and clamped at 1 h.
- `rawMaxMs` is the exact unclamped maximum — deliberately kept for that reason,
  per its own comment — **and it is the column nobody reads.** `rollupByLabel`
  reports `max("maxMs")`, and `AiInsightsService` forwards that same value to
  Claude as the run's max. So the platform reports (and reasons about) the
  quantized max while storing the exact one unused. Fix: feed the exact value
  into `maxMs`, drop `rawMaxMs`. One column fewer, one correctness bug fewer, no
  reader change.
- `avgRespTimeMs` is computed by the worker as `(double) sumElapsedMs /
  requestCount` and then re-multiplied by throughput on read
  (`sum("avgRespTimeMs" * "throughput") / sum("throughput")`). Storing
  **`sumElapsedMs` (BIGINT)** instead is the same 8 bytes, exact, needs no new
  worker computation (the field already exists), and turns the read into
  `sum("sumElapsedMs") / sum("throughput")` — integer addition, no per-row float
  multiply across 5.1M rows per poll.
- Percentiles cannot be averaged. `sum("p99Ms" * "throughput") / sum("throughput")`
  is a weighted mean of per-(worker, label, second) p99s, which is not the p99 of
  anything. Both repositories document it as an approximation, which is honest,
  but for a load-testing product whose headline number is p99 it is worth fixing
  properly — see §6 (mergeable sketches). At 1.25 samples per bucket the current
  per-row percentiles are close to meaningless anyway: p99 of one sample is that
  sample.

*(Minor: `clampedMaxMs`'s javadoc says "At 3 significant digits" while the
constant is 2. Worth correcting when that file is next touched.)*

### F12 — index-only scans never engage

The table is insert-only, so the only thing that would set the visibility map is
autovacuum, and pre-PG13 defaults only trigger it on dead tuples. PG 16 has
`autovacuum_vacuum_insert_threshold` (default 1000) — it will fire, but at these
insert rates the VM will chronically lag. Without an all-visible VM an index-only
scan degrades to heap fetches, which is exactly the 1.5 GB per poll in §2.4. An
explicit `VACUUM (ANALYZE)` of the run's partition at run completion is cheap
(the orchestrator already knows when a run ends) and makes the terminal-run
queries dramatically faster — and the terminal query is the cacheable one.

---

## 4. `ingestedAt` specifically

Drop it. It was added "for end-to-end latency telemetry (Kafka produce time → row
visible in Postgres)". Kafka left the platform on 2026-07-20 and SLIMDOWN
(2026-07-22) removed the metrics registry that would have consumed such a
measurement. Nothing selects it; the consumer does not even bind it (it relies on
`DEFAULT now()`).

Cost of keeping it: 8 bytes × 154M rows = **1.15 GB per run**, plus it forces
`now()` per row.

Two caveats worth knowing before the DROP:

1. `ALTER TABLE … DROP COLUMN` is metadata-only. Existing rows keep their bytes
   until rewritten; new rows stop storing the value immediately.
2. Dropped attributes stay in the tuple descriptor as placeholders, so new tuples
   acquire a **null bitmap** — the header grows from 24 to 32 bytes. Dropping all
   four dead columns is therefore −48 B of data +8 B of header = **−40 B/row net
   (304 → 264), ≈5.7 GB per run.** Dropping all eight unread columns:
   **304 → 232 B, ≈10.3 GB per run.** The 8 B never comes back without a table
   rewrite, which is why §5.3 does the type work as a fresh table rather than a
   sequence of `ALTER`s.

Nothing on the wire needs to change: `windowTimestamp` is an *envelope*-level
field (one per POST, not per entry — the writer fans it onto all 200 rows), it
costs nothing to send, and it is genuinely useful for correlating an envelope
back to a JTL line. Keep sending it; stop storing it 200×.

---

## 5. Proposal

Sequenced so that **each phase is independently shippable and the risky phases
come after the read paths have been moved off the raw table.** That ordering is
the whole trick: once readers go through rollups (§5.2), the raw table has no
reader left except the rollup builder and the purge, and reshaping it (§5.3/§5.4)
stops being a coordinated multi-service change.

### 5.0 What landed (2026-07-30)

Phases 0 and 1 shipped together, in that order, as `V15__dropUnreadColumns.sql`
and `V16__rollupTables.sql` plus the consumer write path and the orchestrator's
read paths. Sections 5.1 and 5.2 below are the *plan*; this section is the
*as-built*, and where they differ, this section wins.

**Migrations.** `V15` drops five columns — the four named in §5.1 plus
`rawMaxMs`, after the F9 swap made it redundant. `V16` creates three rollup
tables, a `rebuildRunRollups(runId)` function, a `dropOldRollups(keepWeeks)`
retention function, and backfills every existing run unconditionally.

| As built | Grain | Why it differs from §5.2 |
|---|---|---|
| `metrics."runSecond"` | `(runId, windowSecond, region)` | `region` is in the **key**, not aggregated away: `/timeseries?byRegion` renders one series per region, so folding regions in the rollup would have deleted a dimension the API returns. |
| `metrics."runSecondStatus"` | `(runId, windowSecond, region, code)` | Not `c2xx…c5xx` columns. The status-code series returns **exact codes**, not classes, so class buckets would have changed what the chart shows. One narrow row per code seen per second beats a `jsonb_each_text` over 10M rows. |
| `metrics."runLabel"` | `(runId, label)` | As proposed, plus `firstSecond`/`lastSecond` — which is what gives the purge its `BETWEEN` bounds (F7) without a probe of the raw table. |

`labelMinute` was **not built.** No orchestrator reader wants per-label-per-time;
`rollupByLabel` is whole-test, and the per-label-per-time grain belongs to
Grafana, which did not move (see below). Building it would have been ~21 MB/run
of table that nothing queried.

`runLabel` carries percentile sums **twice** — `sumP50` (unweighted) and
`sumP50Weighted` (× throughput), and the same for p90/p95/p99. `rollupByLabel`
historically reported an unweighted mean per label and `runAggregate` a weighted
one; storing both keeps every currently displayed number byte-identical instead
of silently changing a metric under the operator while claiming to be a
performance change. F10 (percentiles are not mergeable at all) is untouched by
this and still stands.

**Maintenance is the delta path from §5.2, not a background job.** The consumer's
insert is now `WITH "ins" AS (INSERT … ON CONFLICT DO NOTHING RETURNING …)`
feeding three upserts. Verified end-to-end: three POSTs of the same envelope (one
original, two disk-buffer replays) leave `samples = 100`, not 300. The rollup
sweep also joined `PartitionMaintenanceJob`, inside the same advisory-locked
transaction as the partition work.

**Measured, on the real local database** (6.2M raw rows, 89 historical runs
backfilled — not the target workload, but real data and real plans):

| | Before | After |
|---|---|---|
| timeseries aggregate, 1.06M-row run | 186.98 ms | **1.345 ms** (139×) |
| `max("windowSecond")` (F3) | `Parallel Append`, 1,056,642 index entries, 5,338 buffers | `Index Only Scan Backward … Limit 1`, **4 buffers, 0.033 ms** |
| `GET /timeseries` | — | 45 ms |
| `GET /metrics` (rollup) | — | 24 ms |
| whole-test read, 970k-row run | crossed the timeout at scale | 27 ms |
| 6.2M raw rows fold to | — | 34,495 `runSecond` + 97,472 `runSecondStatus` + 4,416 `runLabel` (**36 MB** of rollup) |

F3 behaved exactly as §3 predicted — the plan flipped from a full per-run index
scan across every partition to a four-buffer probe, because `windowSecond` is now
the ordering column immediately after the equality prefix.

The F9 correctness fix is live: an ingest carrying a quantized `maxMs` of 490.0
and an exact `rawMaxMs` of 507 now reports **507**. The platform (and the AI
insight prompt) had been forwarding the histogram bucket edge.

**Three things deliberately not done, and why:**

1. **`VACUUM (ANALYZE)` at run completion — dropped from Phase 0.** It was there
   to make index-only scans possible (F12) for the live-poll query. Phase 1
   removed that query, so the visibility map is no longer on any hot path, and
   repeatedly vacuuming a growing 70 GB partition costs more than the reader it
   would have helped. Autovacuum's own schedule is sufficient now.
2. **`minMs`, `bytesReceived`, `bytesSent` kept — deferred to Phase 2**, which
   reverses §5.6's "drop `minMs`" recommendation. Together they are ~1.6% of the
   footprint; dropping a column destroys history irreversibly, and Phase 2
   rebuilds the table anyway, which is the cheap moment to decide. The bytes are
   carried into the rollups so a future bandwidth panel has them.
3. **Grafana was not migrated, and still reads the raw table.** Its 14 panels
   need per-label-per-time and per-worker-per-time grains that the rollups
   deliberately do not carry. Moving it is a chart-resolution trade-off for the
   operator to make, not a silent one. Tables 1–3 cover **100% of orchestrator
   reads**, which is the clean seam; Grafana is now the last reader of the raw
   table, exactly as §5.2 warned. Phase 2 does not depend on it — but a `SELECT *`
   habit in a dashboard panel would break on the reshape, so audit the panels
   before then.

**Verification.** The (since-retired) parity check was clean (main 112/0/0, test 45/0/0,
literals 4 ok, 27 migrations). Consumer 8 unit + 18 IT; global-orchestrator 208
unit + 166 IT; k8s-orchestrator 208 unit + 167 IT — all `mvn clean verify` green.
Three new consumer ITs pin the delta path: rollups maintained on ingest, deltas
exactly-once under replay, and the delta result agreeing row-for-row with
`rebuildRunRollups` from raw.

### 5.1 Phase 0 — dead weight and two one-liners (hours, no risk)

**Landed 2026-07-30 as `V15__dropUnreadColumns.sql` — see §5.0 for the
as-built.** `rawMaxMs` was dropped alongside the four listed here; the
`VACUUM (ANALYZE)` row was dropped from scope; `minMs`/bytes were kept.

| Change | Win |
|---|---|
| `DROP COLUMN ingestedAt, windowTimestamp, errorRate, joinedAtSecond` | −40 B/row, **−5.7 GB/run** |
| Feed the exact max into `maxMs`; drop `rawMaxMs` | F9 fixed, −8 B/row |
| Purge `DELETE` gains `AND "windowSecond" BETWEEN ? AND ?` from the run's known bounds | F7: 8+ partitions → 1–2 |
| `VACUUM (ANALYZE)` the run's partition at completion | F12: index-only scans become possible |
| Sort each insert chunk by PK before binding | tighter index leaf locality on ingest |
| Decide `minMs`/`bytesReceived`/`bytesSent` (§5.6) | up to a further −4.6 GB/run |

No wire change, no reader change except the purge SQL and (if bytes are dropped)
none at all.

### 5.2 Phase 1 — rollups (the time fix; biggest single win)

**Landed 2026-07-30 as `V16__rollupTables.sql`. §5.0 has the as-built grains,
which differ from the table below** — `region` joined the `runSecond` key,
`labelMinute` was not built, and status codes became rows rather than class
columns.

Three derived tables in the `metrics` schema. All are per-run and additive; the
raw table keeps its current shape at this phase.

| Table | Grain | Rows / 15 h run | Size |
|---|---|---|---|
| `runSecond` | `(runId, windowSecond)` | 54,000 | ~6 MB |
| `labelMinute` | `(runId, label, minute)` | 180,000 | ~21 MB |
| `runLabel` | `(runId, label)` whole-test | 200 | ~24 KB |

Columns are **component sums, never ratios**: `samples`, `errors`,
`sumElapsedMs`, `bytesIn`, `bytesOut`, `c2xx…c5xx`, `maxMs`, `activeThreads`, and
`maxWindowSecond` on `runSecond`. Ratios are derived at read time from sums, which
is what both repositories already do internally to avoid weighting drift — this
just moves the sums to where they can be reused.

Effect on the hot paths:

| Path | Now | After |
|---|---|---|
| live timeseries, 30 m window | 5.13M rows, ~1.5 GB, ×2 queries | **1,800 rows, ~211 KB** (2,850× fewer) |
| whole-test timeseries (15 h) | 154M rows → 30 s timeout | 54,000 rows, single-digit ms |
| status-code series | 10.3M `jsonb_each_text` calls/poll | 4 integer columns |
| `rollupByLabel` | 154M rows → timeout | 200 rows |
| `runAggregate` | 154M rows → timeout | 200 rows |
| `max(windowSecond)` (F3) | full per-run index scan, all partitions | 1 row |

**Maintenance, and the one trap.** The raw insert is idempotent via
`ON CONFLICT DO NOTHING`; a rollup `+= delta` is **not** — a retried envelope
would double-count. The consumer already has the rows in hand, so:

```sql
WITH inserted AS (
  INSERT INTO metrics."workerMetric" (…) VALUES (…), (…)
  ON CONFLICT (…) DO NOTHING
  RETURNING "runId","windowSecond","label","throughput","errorCount", …
)
INSERT INTO metrics."runSecond" (…) SELECT … FROM inserted GROUP BY 1,2
ON CONFLICT ("runId","windowSecond") DO UPDATE SET samples = "runSecond".samples + EXCLUDED.samples, …
```

`RETURNING` on a `DO NOTHING` insert yields **only the rows that actually
landed**, so the delta is exactly-once for free, in one round-trip, in one
transaction. This is the reliable version; a periodic "read raw, rebuild rollup"
job is the simpler fallback but lags the live chart and re-reads the raw table,
which is what we are trying to stop doing.

Also add `metrics."runProgress"(runId, maxWindowSecond, updatedAt)` or fold the
high-water mark into `runSecond` — either kills F3 outright. *(As built: no
separate table. `max("windowSecond")` simply reads `runSecond`, where the PK
ordering makes it a `Limit 1` backward probe — 4 buffers, measured.)*

**Grafana must move to the rollups too**, or it will remain the largest single
reader of the raw table. *(As built: it did not move, deliberately — §5.0,
item 3. It is now the only reader of the raw table.)*

### 5.3 Phase 2 — reshape the fact table (space, ~18%)

With readers on rollups, the raw table can be rebuilt freely. Target shape:
8-byte columns first, then 4-byte, then 2-byte, then varlena — zero padding.

| Was | Becomes | Why |
|---|---|---|
| `windowSecond BIGINT` | unchanged | epoch seconds; INT hits 2038 |
| `avgRespTimeMs DOUBLE` | `sumElapsedMs BIGINT` | exact, no read-side multiply (F11) |
| `throughput`, `errorCount` BIGINT | INTEGER | 2.1e9 samples/label/worker/second |
| `p50/p90/p95/p99/minMs/maxMs` DOUBLE | INTEGER (ms) | JMeter elapsed is integer ms; **exact**, half the width |
| `bytesReceived`, `bytesSent` BIGINT | BIGINT (kept) | a single worker-second can be large; not worth the risk |
| `activeThreads BIGINT` | SMALLINT | caps at 32,767 threads/worker — document it; INTEGER costs nothing extra due to packing if that cap is unwelcome |
| `statusCodes JSONB` | unchanged | exact per-code detail stays; the rollup carries the fast path |

Result: heap 304 → **216 B**. Must be done as a **new table plus swap**, not a
sequence of `ALTER TYPE` (each would rewrite every partition), and it needs the
wire to carry `sumElapsedMs` — an *added* field, which is producer-first-safe per
cross-component contract #1. Update `goldenWorkerMetricBatch.json` in both test
trees together.

### 5.3a Phase 2 as built (2026-07-30, `V17__reshapeFactTable.sql`)

Shipped as planned with three deviations, and — for the first time in this
document — with **measured** rather than computed numbers.

**Measured, on two databases seeded with the identical 400,000-row dataset**
(realistic identity widths: 26-char ULID `runId`, real worker names, real
labels), one at the V16 shape and one at V17:

| | heap B/row | index B/row | `pg_column_size` |
|---|---|---|---|
| V16 (before) | 315.1 | 228.8 | **304.0 B** |
| V17 (after) | 257.2 | 230.4 | **244.4 B** |

So §2.2's *starting* width of 304 B was exactly right, and its 216 B target was
optimistic — the true landing is **244 B, −19.6%**. Where the 60 B came from:
−8 B (throughput/errorCount), −16 B (four percentiles), −8 B (`minMs` dropped),
−4 B (`maxMs`), −4 B (`activeThreads`), −8 B (the V15 null-bitmap tax, repaid
because a rebuilt table has no dropped attributes), and ~−12 B of alignment
padding. `avgRespTimeMs` → `sumElapsedMs` is width-neutral; it buys exactness.

**Indexes did not move** (228.8 → 230.4 B/row), which is the most useful thing
this measurement says: index keys are 100% identity text, so Phase 2 could not
touch them, and at the new shape they are **47%** of total footprint. That is
Phase 3's case, made with data instead of arithmetic.

On the real local database (6.2M rows) the migration took **49 s** and total
size fell 4614 → 2631 MB — but that figure is inflated by reclaimed bloat and
pre-V15 column bytes, so the controlled 400k measurement above is the honest
per-row number.

**Three deviations from the plan above:**

1. **`activeThreads` is INTEGER, not SMALLINT.** An out-of-range bind aborts the
   chunk → 503 → the worker retries from its disk buffer forever. Trading a
   permanent fleet-wide ingest stall for two bytes is a bad trade — and it isn't
   even two bytes: 7×INTEGER + 1×SMALLINT pads back to the same 32 B, exactly as
   this section's own table parenthetically predicted.
2. **`minMs` dropped, bytes kept** (operator decision) — §5.6 item 1's own
   recommendation, made actionable by the rewrite.
3. **`avgRespTimeMs` stays on the wire**, though nothing stores it. Removing it
   would make a rolling upgrade either lose data (old worker → 400) or silently
   zero it. The consumer falls back to `round(avgRespTimeMs × throughput)` when
   `sumElapsedMs` is absent, which recovers precisely the number that worker's
   rows already carried. The record component is `Long`, not `long`, because a
   legitimate zero sum must stay distinguishable from an absent field.

**Two traps the INTEGER narrowing set, both found by executing SQL rather than
reading it** — and both live in *three* places at once (the rebuild function,
the consumer's delta CTE, and Grafana):

- `integer * integer` is `integer` in Postgres, so `"p99Ms" * "throughput"`
  overflows on a slow sample at moderate throughput: `3600000 * 700` is a hard
  `integer out of range`. In the consumer's CTE that would have aborted ingest,
  not a panel. Fixed by `"p99Ms"::BIGINT * "throughput"`.
- `bigint / bigint` is **integer division**. `SUM(p90 × tp) / SUM(tp)` over
  (100 ms × 5, 105 ms × 2) returns `101` where the answer is `101.43` — a
  silent truncation of every percentile to whole milliseconds, with no error.
  Fixed by `::FLOAT` on the numerator, and by deliberately leaving the rollup
  columns `DOUBLE PRECISION` (see below).

**Why the rollup `sumElapsedMs` column stayed DOUBLE PRECISION** even though it
now holds a pure integer sum: making it BIGINT would have converted every
reader's `sum("sumElapsedMs") / sum("samples")` into integer division across
the orchestrator's repositories. A double is exact well past 2^53; the
truncation risk was not worth zero bytes on a 36 MB table.

`metrics."runSecond"."sumRtWeighted"` and `runLabel."sumRtWeighted"` were
renamed to `sumElapsedMs` in the same migration — the old name described a
construction (Σ mean × count) that no longer happens. Pre-Phase-2 rows keep
their reconstructed values; no backfill, because re-deriving them adds no
information. One consequence worth knowing before it looks like a bug:
rebuilding an *old* run's rollups now sums per-row rounded totals where the
stored value summed unrounded ones, so the two can differ by up to half a
millisecond per raw row. Runs ingested since agree exactly — pinned by
`MetricsConsumerWriteIT.delta_path_agrees_with_rebuild_from_raw` and re-checked
live on the real database.

**Grafana still reads the raw table** and its panels moved with the schema: 4
used `avgRespTimeMs`, and 9 percentile expressions needed the casts above. It
remains the only raw reader.

### 5.4 Phase 3 — dictionary-encoded identity (space, 63% total)

Replace the three text identity columns with surrogate keys and small dimension
tables in the `metrics` schema:

```
metrics."run"      (runKey   int  GENERATED ALWAYS AS IDENTITY, runId  text UNIQUE)
metrics."worker"   (workerKey int GENERATED ALWAYS AS IDENTITY, workerId text UNIQUE)
metrics."labelDict"(labelId  int  GENERATED ALWAYS AS IDENTITY, label  text UNIQUE)
```

Fact row carries `runKey INT, workerKey SMALLINT, labelId SMALLINT`. PK becomes
`(runKey, workerKey, labelId, windowSecond)` = a 16-byte key.

| | heap/row | PK | 2nd idx | total | per run |
|---|---|---|---|---|---|
| after §5.3 | 216 B | 107 B | 80 B | 407 B | 58.3 GB |
| after §5.4 | **128 B** | **27 B** | **27 B** | **185 B** | **26.6 GB** |

Index footprint drops 26.8 → 7.6 GB — a **3.5×** shrink, because the keys stop
being strings. WAL drops to ~26 GB/run.

Costs, stated plainly: the consumer needs an in-memory dictionary cache with an
insert-on-miss path (a new failure mode: dictionary write contention on first
sight of a label); every ad-hoc query and the Grafana dashboard need joins; the
purge path gains an indirection. This is the phase to defer if the platform's
real ceiling turns out to be well below 20 × 200.

### 5.5 Phase 4 — retention split (space at rest)

Raw and rollups age out on different clocks:

| Tier | Retention | Per year, 1 test/weekday |
|---|---|---|
| raw fact rows | **7–30 days** | 0.12–0.5 TB |
| `labelMinute` + `runLabel` + `runSecond` | 52 weeks | **~0.3 TB** |

vs **18 TB** today. Also switch raw partitions from weekly to **daily**: a weekly
partition at this volume holds one to several 71 GB runs, which makes every
`REINDEX`, freeze and drop a long job, and makes the pre-created 8-week runway
enormous. Daily gives 1-day drop granularity and keeps each partition's indexes
in cache. Rollups can stay weekly, or be unpartitioned.

Two operational notes for many partitions: a whole-table `DELETE` takes a lock
per partition, so watch `max_locks_per_transaction`; and the existing
`PartitionMaintenanceJob` runway (currently 8 weeks) must be re-expressed in days
with matching lead time.

### 5.6 Open decisions

1. **`minMs`, `bytesReceived`, `bytesSent`** — no reader today, but bandwidth is a
   real load-testing signal and the schema is the only place it could come from.
   Keep (cost ~4.6 GB/run at today's widths, ~2.5 GB after §5.3) or drop? My
   recommendation: **keep the bytes, drop `minMs`** — bytes answer "was the SUT
   bandwidth-bound", and add a bandwidth panel to earn them; a quantized minimum
   answers nothing that p50 does not. *(**Settled 2026-07-30 in V17, exactly as
   recommended**: `minMs` dropped, both byte counters kept and carried into
   `runSecond`/`runLabel` so a bandwidth panel has a source.)*
2. **`activeThreads` as SMALLINT** — accepts a 32,767 thread/worker cap.
   *(**Rejected 2026-07-30**: it is INTEGER. An out-of-range bind aborts the
   chunk → 503 → the worker retries that envelope forever, so the cap buys a
   permanent ingest stall; and packing makes SMALLINT save nothing here anyway
   — §5.3a.)*
3. **Do §5.4 at all**, given its complexity cost.
4. **Mergeable percentile sketch** (§6) — the one item that costs space to buy
   correctness.

---

## 6. Considered and rejected (or deferred)

**Coarsen the worker's emit window for long runs** (e.g. 5 s or 10 s windows
beyond some duration) — 5–10× fewer raw rows, and §2.3 says nothing renderable is
lost. Rejected as a *default* because it changes the meaning of a stored row
mid-flight, complicates the idempotency key, and would make two runs of different
lengths non-comparable at the raw grain. Worth revisiting as an explicit
per-run "high cardinality" mode after §5.2 lands and we can see whether raw is
ever read at all.

**Partition by `runId`** (hash, or list-per-run) — makes purge a `DETACH`+`DROP`
instead of a 154M-row `DELETE`, and confines a run to one partition. Rejected for
now: it breaks the time-based retention model that `dropOldPartitions` implements,
list-per-run means DDL on the run path (a new failure mode at run start), and hash
does not actually help purge. Revisit if purge becomes an operational pain point;
§5.1's pruning fix should be enough.

**Covering index with `INCLUDE (throughput, sumElapsedMs, errorCount)`** — would
make the live window query index-only and skip the 1.5 GB heap read. Rejected
because §5.2 removes the query entirely, and the index would add ~15 GB/run and
slow ingest. Already noted in the metrics-scale benchmark as "held in reserve";
keep it there.

**TimescaleDB / columnar compression** — this is the honest 10× answer for a
per-second metrics table (native compression on immutable chunks, continuous
aggregates instead of hand-rolled rollups). Rejected here because it is an
extension dependency and a substrate decision, not a schema change: it must work
on plain `postgres:16` in compose, in the K8s StatefulSet, and on managed RDS.
Worth a separate conversation if the volume target grows past what §5.2–§5.5
deliver.

**Mergeable percentile sketch instead of scalar percentiles** — a t-digest or
DDSketch (~48 B) in place of the six scalar percentile columns would make
percentiles *exactly mergeable* across workers, labels and time, fixing F10
properly and letting the rollups carry a true whole-test p99. It costs ~24 B/row
net at the raw grain. Deferred, not rejected: at 1.25 samples/bucket the sketch
would mostly encode single samples, so the right place for it is the **rollup**
(the worker's histogram already exists and is thrown away at flush — it could be
merged into a per-minute sketch instead). This is the one place I would trade
space for correctness, and it should be its own decision.

**Raising `statement_timeout`** — rejected. It converts a 30 s failure into a
several-minute pinned connection out of a pool of 10, on a service that also
serves run control. The timeout is doing its job; the query is the problem.

---

## 7. The run databases

Small and well-indexed. `run`, `pod`, `runFleetMember`, `applicationCapacity` all
have indexes matching their hot paths (`FOR UPDATE SKIP LOCKED` pod claiming,
`createdAt DESC` listings, the partial `run_active_idx`). Two notes only:

1. **F14 — unbounded history.** `runEvent`, `cronJobFireHistory`,
   `applicationHealthHistory` and `purgeAudit` are append-only with no retention
   sweeper; they are deleted only when the parent run/application is purged.
   Growth is slow (O(events), not O(seconds)) so this is hygiene, not a fire —
   but a bounded-retention sweeper alongside the existing `dropOldPartitions`
   cadence is cheap insurance. `aiResponse` already has a `createdAt`-based
   delete path.
2. `runFleetMember_state_idx` is `(runId, state)`; `run_active_idx` is a partial
   on `createdAt DESC`. Both fine. No changes proposed.

Any change here lands in `postgres/migrations/globalrun/` — see §8.

---

## 8. Mechanics

- **Flyway, forward-only.** New migrations as `V<n>__camelCaseDescription.sql`;
  never edit a landed one. ~~The metrics tree is at V14, so this work starts at
  V15.~~ **The metrics tree is at V17** (V15 dropped the columns, V16 built the
  rollups, V17 reshaped the fact table); Phase 3 starts at V18. Note the metrics
  tree deliberately skips V3–V11 to stay numerically in step with `globalrun`.
- **One reader.** `MetricsTimeseriesRepository`, `MetricsRollupRepository`,
  `MetricsPurgeRepository`, `CachingMetricsService` and `DataSourceConfig` live
  in `jmeter-global-orchestrator` only (the `jmeter-orchestrator-k8s` twin was
  decommissioned on 2026-08-28).
- **Grants do not propagate to partitions.** Any new table needs `metricsWriter`
  (INSERT+SELECT — `ON CONFLICT` probes the index), `metricsReader` (SELECT),
  `metricsPurger` (SELECT+DELETE), and if partitioned, the same grants re-issued
  inside `createWeeklyPartition`.
- **Wire contract.** The consumer's `api/openapi.yaml` is canonical. Adding
  `sumElapsedMs` is producer-first-safe; removing a field rebuilds both sides
  together. `goldenWorkerMetricBatch.json` is duplicated verbatim in both test
  trees.
- **camelCase everywhere**, quoted identifiers, per the standing convention.

---

## 9. Verify, don't trust this document

Every number in §2 is **computed** from Postgres' layout rules, not measured —
Docker was down when this was written. Before implementing, confirm against real
data:

> **2026-07-30 — now largely confirmed.** The plan-shape predictions (F3's
> full-index-scan `max()`, and the live-poll aggregate's row and buffer counts)
> were checked on the real local database before V15/V16 — §5.0 has the
> before/after. The **footprint** arithmetic was measured during Phase 2 by
> seeding two databases with an identical 400,000-row realistic dataset, one at
> each shape: §2.2's 304 B starting width was **exactly right**, its Phase 2
> target was optimistic (244 B, not 216 B), and its claim that identity
> dominates the indexes was confirmed hard — the index bytes did not move at all
> (§5.3a).
>
> What is still unmeasured is **behaviour at the target volume**: 400k rows is
> six orders of magnitude short of a 154M-row run, so per-row widths are
> trustworthy but vacuum/bloat/cache behaviour is not. Phase 3's dictionary cache
> in particular introduces a new failure mode (write contention on first sight of
> a label) that only a real soak will show. Run the soak below before Phase 3.

```sql
-- Actual average row width and the real identity-string lengths.
SELECT avg(pg_column_size(w.*))                        AS "avgRowBytes",
       avg(length("runId")), avg(length("workerId")),
       avg(length("label")), avg(pg_column_size("statusCodes"))
FROM   metrics."workerMetric" w TABLESAMPLE SYSTEM (1);

-- Where the space actually is, per partition.
SELECT relname,
       pg_size_pretty(pg_relation_size(oid))        AS heap,
       pg_size_pretty(pg_indexes_size(oid))         AS idx,
       (SELECT reltuples::bigint FROM pg_class c2 WHERE c2.oid = c.oid) AS rows
FROM   pg_class c WHERE relname LIKE 'workerMetric%' ORDER BY pg_relation_size(oid) DESC;

-- F3: confirm the max() probe is a full scan and touches every partition.
EXPLAIN (ANALYZE, BUFFERS)
SELECT max("windowSecond") FROM metrics."workerMetric" WHERE "runId" = '<runId>';

-- F1/F12: the live-poll query — check rows, buffers, and whether it is index-only.
EXPLAIN (ANALYZE, BUFFERS)
SELECT "windowSecond", sum("throughput"),
       sum("avgRespTimeMs" * "throughput") / sum("throughput")
FROM   metrics."workerMetric"
WHERE  "runId" = '<runId>' AND "windowSecond" >= <max-1800>
GROUP  BY "windowSecond" ORDER BY "windowSecond";

-- F2: is the aggregation actually aggregating? (expect ~1.25 at 5000 TPS/20/200)
SELECT avg("throughput") AS "samplesPerRow", count(*) AS rows,
       count(DISTINCT "label") AS labels, count(DISTINCT "workerId") AS workers
FROM   metrics."workerMetric" WHERE "runId" = '<runId>';
```

A load-generating soak at even a fraction of 20 × 200 × 15 h — say 20 × 200 for
30 minutes — validates the row-width and per-poll numbers well enough to commit
to the phasing. Phases 0 and 1 did not need it (their wins are plan-shape wins,
and both were confirmed directly); **Phase 2 onward does**, since those phases
are justified purely by bytes-per-row.
