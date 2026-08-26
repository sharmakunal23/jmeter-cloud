-- V15__dropUnreadColumns.sql — applied to the jmetercloud_metrics database.
--
-- SCHEMA-OPT Phase 0 (2026-07-29). Removes five columns from
-- metrics."workerMetric" that are written on every row and read by nothing.
-- See postgres/docs/schemaOptimization.md for the full costing; the summary:
-- at 20 workers × 200 labels × 15 h these five cost ~48 bytes on every one of
-- ~154,000,000 rows per run.
--
--   "ingestedAt"      — added for "Kafka produce time → row visible in Postgres"
--                       latency telemetry. Kafka left the platform 2026-07-20 and
--                       SLIMDOWN (07-22) removed the metrics registry that would
--                       have consumed the measurement. The consumer never even
--                       bound it — it relied on DEFAULT now(), so every INSERT was
--                       paying a now() call for a value nothing read.
--   "windowTimestamp" — the raw JTL timestamp string (e.g. "2025/04/13 14:32:07"),
--                       a formatted duplicate of "windowSecond". It is an
--                       ENVELOPE-level wire field (one per POST) that the writer
--                       fanned onto all ~200 rows of that envelope. It STAYS on
--                       the wire and stays validated at the edge by
--                       IngestController (the 400 contract is unchanged) — it is
--                       genuinely useful for correlating an envelope back to a
--                       JTL line. It just stops being stored 200×.
--   "errorRate"       — derived: "errorCount" / "throughput". Every reader
--                       recomputes it from the component sums instead, because a
--                       mean of per-row rates is not the rate of the whole. The
--                       "errorRate" that IS read comes from a computed SELECT
--                       alias in MetricsRollupRepository and from
--                       globalOrchestrator."runTrend" — never from this column.
--   "joinedAtSecond"  — V12 added it so "future per-second fleet rollups" could
--                       answer "which members were live at second X" without
--                       joining runFleetMember. Those rollups arrive in V16 and
--                       do not need it; the readers that do use joinedAtSecond
--                       read globalOrchestrator."runFleetMember".
--   "rawMaxMs"        — see the maxMs note below. This one is a CORRECTNESS fix,
--                       not just space.
--
-- ── The maxMs / rawMaxMs swap (correctness) ─────────────────────────
-- The worker records into an HDRHistogram with 2 significant digits and a
-- 3,600,000 ms ceiling, clamping on the way in. So it emitted two maxima:
--   maxMs    — histogram-derived: the *bucket* upper bound containing the true
--              max, quantized, and clamped at 1 hour for timeout rows.
--   rawMaxMs — the exact unclamped maximum, kept deliberately for that reason.
-- and the platform read the WRONG one: MetricsRollupRepository reports
-- max("maxMs"), and AiInsightsService forwards that same value to Claude as the
-- run's maximum. The exact column was the unread one.
--
-- From this migration on, the consumer binds the exact value into "maxMs" and
-- "rawMaxMs" is dropped. Both fields remain on the wire, so no producer change
-- and no coordinated worker deploy is needed. Consequence to expect: reported
-- maxima move slightly DOWN for ordinary rows (the quantization overshoot is
-- gone, up to ~1% at 2 significant digits) and can move sharply UP for rows that
-- were being clamped at 3,600,000 ms — which is the bug being fixed.
--
-- ── Deploy ordering ─────────────────────────────────────────────────
-- Flyway runs before the consumer starts (compose `flyway-migrate`, K8s Job), so
-- the matching consumer lands with this migration. If an OLD consumer image is
-- still running when this applies, its INSERT names dropped columns and fails →
-- IngestController maps that to 503 → the worker's disk buffer retries. Noisy,
-- but NOT lossy: the data flows as soon as the new consumer is up.
--
-- ── What DROP COLUMN does and does not reclaim ──────────────────────
-- This is a metadata-only operation (no table rewrite, cheap on the partitioned
-- parent — it cascades to existing partitions and applies to future ones). Two
-- consequences worth knowing:
--   1. Rows already written keep their bytes until they are rewritten or their
--      partition is dropped. Only NEW rows get smaller.
--   2. Dropped attributes stay in the tuple descriptor as placeholders, so new
--      tuples acquire a null bitmap and the tuple header grows 24 → 32 bytes.
--      Net for these five: −48 B of data +8 B of header = −40 B/row, ≈5.7 GB
--      per 15 h run. The 8 B does not come back without a table rewrite, which
--      is why the type/ordering work in Phase 2 is planned as a fresh table
--      rather than a sequence of ALTERs.
--
-- Deliberately NOT dropped here, though they also have no reader today:
--   "minMs", "bytesReceived", "bytesSent" — together ~1.6% of the footprint.
--   Phase 2 rebuilds this table anyway, so deciding them then is free, whereas
--   dropping them now permanently discards history for a rounding-error win.
--   Bandwidth in particular is a real load-testing signal that only this schema
--   could supply.

ALTER TABLE metrics."workerMetric"
    DROP COLUMN IF EXISTS "ingestedAt",
    DROP COLUMN IF EXISTS "windowTimestamp",
    DROP COLUMN IF EXISTS "errorRate",
    DROP COLUMN IF EXISTS "joinedAtSecond",
    DROP COLUMN IF EXISTS "rawMaxMs";

COMMENT ON COLUMN metrics."workerMetric"."maxMs" IS
    'Exact unclamped maximum elapsed time (ms) for the (worker, label, second) window. Fed from the wire''s rawMaxMs since V15 — NOT the HDRHistogram-derived value, which was quantized to 2 significant digits and clamped at 3,600,000 ms.';

COMMENT ON TABLE metrics."workerMetric" IS
    'Per-second per-(runId, workerId, label) metric rows. Partitioned weekly on "windowSecond" (Unix epoch). Primary key matches the producer→consumer idempotency contract; duplicate deliveries land as ON CONFLICT DO NOTHING. Since V16 this table is NOT on any hot read path — the orchestrator reads the metrics."runSecond" / "runSecondStatus" / "runLabel" rollups instead.';
