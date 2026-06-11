package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.WorkerMetricBatch;
import com.perf.orchestrator.WorkerMetricEntry;
import com.perf.orchestrator.model.JtlRow;
import com.perf.orchestrator.observability.WarningThrottle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Routes incoming {@link JtlRow} records into one-second tumbling windows,
 * closes windows after a configurable grace period, and produces
 * {@link WorkerMetricBatch} envelopes ready for Kafka publication.
 *
 * <h2>Envelope grouping (K-1)</h2>
 * Each drain returns one envelope per {@code (workerId, windowSecond)} pair —
 * not one record per {@code (workerId, label, windowSecond)} as the legacy
 * per-row path did. The 5 envelope-level fields ({@code windowSecond},
 * {@code windowTimestamp}, {@code region}, {@code workerId}, {@code runId})
 * appear once per envelope; per-label aggregates ride in the {@code entries[]}
 * array. Pathological test plans with more than {@link #MAX_ENTRIES_PER_ENVELOPE}
 * labels in a single window split into multiple envelopes carrying the same
 * envelope-level metadata.
 *
 * <h2>Window lifecycle</h2>
 * <ol>
 *   <li>A row arrives with {@code epochSecond = T} and {@code label = L}.</li>
 *   <li>The aggregator finds or creates a {@link SecondBucket} for {@code (T, L)}.</li>
 *   <li>After each {@link #record} call, any window at epoch second {@code W}
 *       where {@code latestEpochSecond >= W + graceSeconds} AND
 *       {@code W < latestEpochSecond} is considered closed and returned by
 *       {@link #drainCloseable()}.</li>
 *   <li>Closed windows are evicted from the internal map — they will never
 *       receive more rows.</li>
 * </ol>
 *
 * <h2>Close rule: never evict the leading edge</h2>
 * The leading-edge window (the one at {@code latestEpochSecond}) is never
 * closed, even when {@code graceSeconds == 0}. Rationale: the poll loop
 * processes rows in arrival order and the leading edge is, by definition,
 * the most recent second observed. More rows for that second may still
 * arrive in the next poll batch. Closing it prematurely would cause those
 * rows to be dropped as late arrivals. Grace period still applies to
 * non-leading-edge windows.
 *
 * <h2>Grace period</h2>
 * JMeter workers flush in batches; a row timestamped at second {@code T} may
 * arrive after rows from {@code T+1} due to OS scheduling. The grace period
 * absorbs this jitter. A row that arrives after its window is already evicted
 * is silently dropped with a WARNING log. Increase {@code GRACE_PERIOD_SECONDS}
 * if dropped rows appear in the logs during high-load tests.
 *
 * <h2>TreeMap structure</h2>
 * {@code windows: TreeMap<epochSecond, Map<label, SecondBucket>>}
 * <ul>
 *   <li>Outer key ({@code Long}): enables {@code headMap()} to find all
 *       closeable windows in O(log n) time.</li>
 *   <li>Inner key ({@code String}): label — multiple endpoints can be active
 *       in the same second.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Must be called exclusively from the single poll-loop thread.
 */
public final class TumblingWindowAggregator {

    private static final Logger LOG = Logger.getLogger(TumblingWindowAggregator.class.getName());

    /**
     * Hard cap on entries per {@link WorkerMetricBatch} envelope. A pod-window
     * with more than this many distinct labels splits into multiple envelopes
     * carrying the same envelope-level metadata. Guards against pathological
     * test plans (e.g. 10k-endpoint stress tests) producing oversized Kafka
     * records — at typical entry size (~100 B Avro binary), 500 entries land
     * around 50 KB, well under the 1 MB Kafka default.
     */
    static final int MAX_ENTRIES_PER_ENVELOPE = 500;

    /** Worker identity — constant for the orchestrator lifetime, injected into every metric. */
    private final String workerId;
    private final String region;
    private final String runId;
    /**
     * MID-TEST-SCALING Phase C — stamped on every emitted
     * {@link WorkerMetricBatch}. {@code 0} for original-fleet members;
     * {@code > 0} for mid-test scale-up joiners (seconds since
     * {@code run.startedAt}). Source: {@code OrchestratorConfig.getJoinedAtSecond()}.
     */
    private final long joinedAtSecond;

    /**
     * Seconds to wait after seeing rows from second {@code T+graceSeconds} before
     * closing the window at second {@code T}.
     */
    private final int graceSeconds;

    /**
     * When {@code true}, the per-row worker ID is extracted from {@link JtlRow#threadName()}
     * rather than using the fixed {@link #workerId}. Used in master-slave deployments where
     * the master JTL contains rows from all slaves, each stamped with the slave's FQDN in
     * the {@code threadName} column.
     */
    private final boolean useThreadName;

    /**
     * Live windows keyed by epoch second (outer) and composite {@code "workerId|label"} (inner).
     * The composite key separates per-slave buckets in THREAD_NAME mode and is uniform across
     * both modes so {@link #flushAll} never needs to branch.
     * TreeMap order allows O(log n) range queries for closeable windows.
     */
    private final TreeMap<Long, Map<String, SecondBucket>> windows;

    /**
     * Highest epoch second seen across all recorded rows.
     * Drives the grace-period calculation in {@link #drainCloseable()}.
     */
    private long latestEpochSecond;

    /**
     * Rate-limiter for the per-row "dropping late-arriving row" WARNING. If
     * GRACE_PERIOD_SECONDS is too small for the workload (e.g. slow samples
     * written to the JTL well after their start second) this would otherwise
     * fire once per dropped row. Per-instance (per-run), single-threaded.
     */
    private final WarningThrottle lateRowWarnings = new WarningThrottle();

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * @param workerId     Kubernetes pod name, e.g. {@code jmeter-worker-4}
     * @param region       AWS region, e.g. {@code us-east-1}
     * @param runId        test run identifier, e.g. {@code 20250413-east}
     * @param graceSeconds seconds to hold a non-leading-edge window open after
     *                     the leading edge has advanced past it; must be >= 0
     */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds) {
        this(workerId, region, runId, graceSeconds, false, 0L);
    }

    /** Pre-MID-TEST-SCALING-Phase-C convenience constructor (joinedAtSecond defaults to 0). */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds, boolean useThreadName) {
        this(workerId, region, runId, graceSeconds, useThreadName, 0L);
    }

    /**
     * @param workerId       Kubernetes pod name used when {@code useThreadName=false}
     * @param region         AWS region, e.g. {@code us-east-1}
     * @param runId          test run identifier, e.g. {@code 20250413-east}
     * @param graceSeconds   seconds to hold a non-leading-edge window open; must be >= 0
     * @param useThreadName  when {@code true}, extract workerId per-row from threadName
     *                       (set via {@code WORKER_ID_SOURCE=THREAD_NAME} for master-slave)
     * @param joinedAtSecond MID-TEST-SCALING Phase C — seconds since {@code run.startedAt}
     *                       at which this worker joined. {@code 0} for original-fleet;
     *                       {@code > 0} for mid-test scale-up joiners. Stamped on every
     *                       emitted {@link WorkerMetricBatch}.
     */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds,
                                    boolean useThreadName, long joinedAtSecond) {
        this.workerId     = Objects.requireNonNull(workerId,  "workerId cannot be null");
        this.region       = Objects.requireNonNull(region,    "region cannot be null");
        this.runId        = Objects.requireNonNull(runId,     "runId cannot be null");
        if (graceSeconds < 0) {
            throw new IllegalArgumentException("graceSeconds must be >= 0, got: " + graceSeconds);
        }
        if (joinedAtSecond < 0) {
            throw new IllegalArgumentException("joinedAtSecond must be >= 0, got: " + joinedAtSecond);
        }
        this.graceSeconds       = graceSeconds;
        this.useThreadName      = useThreadName;
        this.joinedAtSecond     = joinedAtSecond;
        this.windows            = new TreeMap<>();
        this.latestEpochSecond  = Long.MIN_VALUE;
    }

    // -----------------------------------------------------------------------
    // Core API
    // -----------------------------------------------------------------------

    /**
     * Routes a parsed row into the correct {@link SecondBucket}.
     *
     * <p>If no bucket exists for this {@code (epochSecond, label)} pair, one is
     * created. If the bucket's window has already been closed and evicted (the row
     * arrived after the grace period), the row is dropped and a WARNING is logged.
     *
     * @param row a parsed JTL row; must not be null
     */
    public void record(JtlRow row) {
        Objects.requireNonNull(row, "row cannot be null");

        long   epochSecond = row.epochSecond();
        String label       = row.label();

        // Advance the leading edge before checking for late arrivals so that
        // a row exactly at the boundary is included in the current window,
        // not incorrectly classified as late.
        if (epochSecond > latestEpochSecond) {
            latestEpochSecond = epochSecond;
        }

        // Check whether this window has already been evicted
        if (isEvicted(epochSecond)) {
            lateRowWarnings.record(
                    () -> LOG.warning(() -> String.format(
                            "Dropping late-arriving row: label='%s' timestamp='%s' " +
                                    "(window at second %d was already closed; latestEpochSecond=%d, graceSeconds=%d). " +
                                    "Increase GRACE_PERIOD_SECONDS if this happens frequently.",
                            label, row.rawTimestamp(), epochSecond, latestEpochSecond, graceSeconds)),
                    suppressed -> LOG.warning(() -> String.format(
                            "Suppressed %d further late-arriving-row drops in the last 60s — many rows are " +
                            "arriving after their window closed. Increase GRACE_PERIOD_SECONDS or check clock skew.",
                            suppressed)));
            return;
        }

        String effectiveWorkerId = useThreadName
                ? workerIdFromThreadName(row.threadName())
                : this.workerId;
        String bucketKey = effectiveWorkerId + "|" + label;

        windows
                .computeIfAbsent(epochSecond, s -> new HashMap<>())
                .computeIfAbsent(bucketKey, k -> new SecondBucket(epochSecond, row.rawTimestamp(), label))
                .record(row);
    }

    /**
     * Returns and removes all {@link WorkerMetricBatch} envelopes for windows
     * that have passed the grace period.
     *
     * <p>A window at epoch second {@code W} is closeable when both:
     * <ul>
     *   <li>{@code latestEpochSecond >= W + graceSeconds} (grace period expired), and</li>
     *   <li>{@code W < latestEpochSecond} (not the leading edge)</li>
     * </ul>
     *
     * <p>The leading-edge protection is critical: with {@code graceSeconds=0},
     * the grace-period rule alone would close the current second's window the
     * moment another row arrives for it. Holding the leading edge open ensures
     * subsequent same-second rows are aggregated rather than dropped as late.
     *
     * <p>Call this after every successful {@link #record} (or batch of records)
     * to keep memory bounded. In the steady state (10h run, 200 endpoints),
     * the map holds at most {@code (graceSeconds + 1) * 200} buckets — the
     * current leading second plus up to {@code graceSeconds} buffered seconds,
     * each with one bucket per active label.
     *
     * @return unmodifiable list of envelopes; empty when no windows are
     *         closeable yet
     */
    public List<WorkerMetricBatch> drainCloseable() {
        if (windows.isEmpty() || latestEpochSecond == Long.MIN_VALUE) {
            return Collections.emptyList();
        }

        // Closeable: W < latestEpochSecond AND W <= latestEpochSecond - graceSeconds.
        // Combining: W <= min(latestEpochSecond - 1, latestEpochSecond - graceSeconds).
        // When graceSeconds >= 1, the grace rule dominates; when graceSeconds == 0,
        // the leading-edge guard dominates. Math.min handles both.
        long inclusiveUpperBound = Math.min(
                latestEpochSecond - 1,
                latestEpochSecond - graceSeconds
        );

        // Fast-path: nothing is old enough yet (firstKey() is always > bound)
        if (inclusiveUpperBound < windows.firstKey()) {
            return Collections.emptyList();
        }

        // headMap(bound, true) = keys <= bound. This is a live view of `windows`.
        NavigableMap<Long, Map<String, SecondBucket>> closeable =
                windows.headMap(inclusiveUpperBound, true);

        if (closeable.isEmpty()) {
            return Collections.emptyList();
        }

        List<WorkerMetricBatch> envelopes = flushAll(closeable);
        closeable.clear(); // atomically removes these entries from `windows` (it's a live view)
        return Collections.unmodifiableList(envelopes);
    }

    /**
     * Flushes and removes all remaining windows regardless of the grace period
     * and leading-edge protection.
     *
     * <p>Called during the DRAINING and DONE states when the test has finished
     * and no more rows will arrive. The grace period and leading-edge guard no
     * longer apply — every open window gets its final envelope published.
     *
     * @return unmodifiable list of all remaining envelopes; empty if no open windows
     */
    public List<WorkerMetricBatch> drainAll() {
        if (windows.isEmpty()) {
            return Collections.emptyList();
        }
        List<WorkerMetricBatch> envelopes = flushAll(windows);
        windows.clear();
        return envelopes.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(envelopes);
    }

    /**
     * Returns the number of currently open (not yet closed) second-windows.
     * Each window may contain multiple label buckets.
     * Intended for monitoring and test assertions.
     */
    public int openWindowCount() {
        return windows.size();
    }

    /**
     * Returns the total number of open {@link SecondBucket} objects across
     * all open windows. Each (label, second) pair is one bucket.
     * Intended for test assertions.
     */
    public int openBucketCount() {
        return windows.values().stream().mapToInt(Map::size).sum();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the window for {@code epochSecond} has already
     * been closed and evicted from the map.
     *
     * <p>A window is evicted when it satisfied the closeable rule in a previous
     * drain AND is no longer present in {@code windows}. Both checks are
     * required: an out-of-order row for an earlier second may still be within
     * grace and present in the map, in which case it is not evicted.
     *
     * <p>Using the same bound as {@link #drainCloseable()} keeps the
     * "late" definition consistent: any row the drain would have closed is
     * the same row we consider too late to accept.
     */
    private boolean isEvicted(long epochSecond) {
        if (latestEpochSecond == Long.MIN_VALUE) return false;
        long inclusiveUpperBound = Math.min(
                latestEpochSecond - 1,
                latestEpochSecond - graceSeconds
        );
        return epochSecond <= inclusiveUpperBound
                && !windows.containsKey(epochSecond);
    }

    /**
     * Converts all buckets in the given map view into {@link WorkerMetricBatch}
     * envelopes — one envelope per {@code (workerId, windowSecond)} pair.
     *
     * <p>The inner map key is {@code "workerId|label"} in both modes (THREAD_NAME
     * and POD_NAME). For each second, group buckets by workerId, then build one
     * envelope per group. Pathological pod-windows with more than
     * {@link #MAX_ENTRIES_PER_ENVELOPE} entries split into multiple envelopes
     * carrying the same envelope-level metadata; the consumer's idempotency
     * contract ({@code (runId, workerId, label, windowSecond)} PK) handles the
     * resulting per-row INSERTs identically.
     *
     * <p>{@link LinkedHashMap} preserves bucket insertion order within a group
     * so the resulting envelope's {@code entries[]} array has stable ordering —
     * useful for tests and for any consumer that wants to see labels in the
     * order they were first observed in the JTL.
     */
    private List<WorkerMetricBatch> flushAll(Map<Long, Map<String, SecondBucket>> view) {
        List<WorkerMetricBatch> envelopes = new ArrayList<>();
        for (Map.Entry<Long, Map<String, SecondBucket>> windowEntry : view.entrySet()) {
            long windowSec = windowEntry.getKey();
            Map<String, SecondBucket> bucketMap = windowEntry.getValue();

            // Group this window's buckets by workerId. Insertion order preserves
            // the order labels were first observed in the JTL.
            Map<String, List<SecondBucket>> byWorker = new LinkedHashMap<>();
            for (Map.Entry<String, SecondBucket> e : bucketMap.entrySet()) {
                String key = e.getKey(); // "workerId|label"
                String bucketWorkerId = key.substring(0, key.indexOf('|'));
                byWorker.computeIfAbsent(bucketWorkerId, k -> new ArrayList<>()).add(e.getValue());
            }

            for (Map.Entry<String, List<SecondBucket>> workerGroup : byWorker.entrySet()) {
                String bucketWorkerId = workerGroup.getKey();
                List<SecondBucket> buckets = workerGroup.getValue();

                // Pick the first bucket's windowTimestamp as the envelope's —
                // every bucket in the same windowSecond carries the same one
                // (JTL rows for that second all stamp the same epoch-aligned string).
                String windowTimestamp = buckets.get(0).windowTimestamp();

                // Split if entries exceed the cap — same envelope metadata, split
                // entries[] across multiple envelopes.
                for (int from = 0; from < buckets.size(); from += MAX_ENTRIES_PER_ENVELOPE) {
                    int to = Math.min(from + MAX_ENTRIES_PER_ENVELOPE, buckets.size());
                    List<WorkerMetricEntry> entries = new ArrayList<>(to - from);
                    for (int i = from; i < to; i++) {
                        entries.add(buckets.get(i).toMetricEntry());
                    }
                    envelopes.add(WorkerMetricBatch.newBuilder()
                            .setWindowSecond(windowSec)
                            .setWindowTimestamp(windowTimestamp)
                            .setRegion(region)
                            .setWorkerId(bucketWorkerId)
                            .setRunId(runId)
                            .setJoinedAtSecond(joinedAtSecond)
                            .setEntries(entries)
                            .build());
                }
            }
        }
        return envelopes;
    }

    /**
     * Extracts the pod name prefix from a JMeter thread name FQDN.
     *
     * <p>JMeter slave thread names follow the pattern:
     * {@code jmeter-slave-2.jmeter-workers.perf.svc.cluster.local-Thread Group 1-1}
     * The pod name is everything before the first {@code .}. When no {@code .} is
     * present (e.g. in non-FQDN deployments), the full thread name is used as-is.
     */
    private static String workerIdFromThreadName(String threadName) {
        int dot = threadName.indexOf('.');
        return dot < 0 ? threadName : threadName.substring(0, dot);
    }
}