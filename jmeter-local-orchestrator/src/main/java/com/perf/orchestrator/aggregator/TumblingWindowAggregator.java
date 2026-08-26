package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.model.WorkerMetricEntry;
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
 * Routes {@link JtlRow} records into one-second tumbling windows and drains
 * them as {@link WorkerMetricBatch} envelopes, one per
 * {@code (workerId, windowSecond)} — per-label aggregates ride in
 * {@code entries[]}, and a window with more than
 * {@link #MAX_ENTRIES_PER_ENVELOPE} labels splits across envelopes sharing the
 * same envelope-level metadata.
 *
 * <p><b>The leading-edge window is never closed</b>, even at
 * {@code graceSeconds == 0}: rows arrive in poll order, so more rows for the
 * newest second can still turn up in the next batch, and closing it early
 * drops them.
 *
 * <p>The grace period absorbs the reordering that JMeter's batched flushes
 * cause — a row stamped second {@code T} can arrive after rows from
 * {@code T+1}. A row arriving after its window was evicted is dropped with a
 * WARNING; sustained drops mean {@code GRACE_PERIOD_SECONDS} is too low.
 */
public final class TumblingWindowAggregator {

    private static final Logger LOG = Logger.getLogger(TumblingWindowAggregator.class.getName());

    /**
     * Hard cap on entries per envelope, so a 10k-endpoint test plan cannot emit
     * one oversized envelope. At ~300 B per entry, 500 entries land near 50 KB —
     * well under the consumer's body cap and the buffer's per-file cap.
     */
    static final int MAX_ENTRIES_PER_ENVELOPE = 500;

    /** Worker identity — constant for the orchestrator lifetime, injected into every metric. */
    private final String workerId;
    private final String region;
    private final String runId;
    /**
     * Seconds after {@code run.startedAt} at which this worker joined, stamped on
     * every envelope. {@code 0} for the original fleet, {@code > 0} for a
     * mid-test scale-up joiner.
     */
    private final long joinedAtSecond;

    /**
     * Seconds to wait after seeing rows from second {@code T+graceSeconds} before
     * closing the window at second {@code T}.
     */
    private final int graceSeconds;

    /**
     * When {@code true} ({@code WORKER_ID_SOURCE=THREAD_NAME}), each row's worker
     * ID comes from {@link JtlRow#threadName()} instead of the fixed
     * {@link #workerId}, so one JTL can carry rows from several workers.
     *
     * <p>This was built for the master-slave layout, which the platform no longer
     * runs — single-worker-per-pod is the only execution model. The code is live
     * and wired end to end, but nothing in the platform sets it.
     */
    private final boolean useThreadName;

    /**
     * Live windows, keyed by epoch second then by {@code "workerId|label"}. The
     * composite inner key is used in both modes so {@link #flushAll} never
     * branches, and the TreeMap gives O(log n) range queries for closeable
     * windows.
     */
    private final TreeMap<Long, Map<String, SecondBucket>> windows;

    /** Highest epoch second seen — the leading edge {@link #drainCloseable()} measures grace against. */
    private long latestEpochSecond;

    /**
     * Throttles the late-row WARNING, which would otherwise fire once per dropped
     * row when {@code GRACE_PERIOD_SECONDS} is too small for the workload.
     */
    private final WarningThrottle lateRowWarnings = new WarningThrottle();

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * @param workerId     pod name, e.g. {@code acaps-na-east-worker-1}
     * @param region       placement region, e.g. {@code na-east}
     * @param runId        run identifier (ULID)
     * @param graceSeconds how long to hold a non-leading-edge window open after
     *                     the leading edge passes it; must be >= 0
     */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds) {
        this(workerId, region, runId, graceSeconds, false, 0L);
    }

    /** Convenience overload for an original-fleet worker ({@code joinedAtSecond = 0}). */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds, boolean useThreadName) {
        this(workerId, region, runId, graceSeconds, useThreadName, 0L);
    }

    /**
     * @param workerId       pod name, used when {@code useThreadName=false}
     * @param region         placement region, e.g. {@code na-east}
     * @param runId          run identifier (ULID)
     * @param graceSeconds   how long to hold a non-leading-edge window open; must be >= 0
     * @param useThreadName  when {@code true}, take each row's worker ID from
     *                       {@code threadName} — see the field's Javadoc
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
                    envelopes.add(new WorkerMetricBatch(
                            windowSec, windowTimestamp, region,
                            bucketWorkerId, runId, joinedAtSecond, entries));
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