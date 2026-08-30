package com.perf.orchestrator.aggregator;

import com.perf.orchestrator.model.WorkerMetricBatch;
import com.perf.orchestrator.model.WorkerMetricEntry;
import com.perf.orchestrator.model.JtlRow;
import com.perf.orchestrator.observability.WarningThrottle;

import java.time.Instant;
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
 * Routes {@link JtlRow} records into tumbling windows {@code windowSeconds}
 * wide and drains them as {@link WorkerMetricBatch} envelopes, one per
 * {@code (workerId, windowSecond)} — per-label aggregates ride in
 * {@code entries[]}, and a window with more than
 * {@link #MAX_ENTRIES_PER_ENVELOPE} labels splits across envelopes sharing the
 * same envelope-level metadata.
 *
 * <p><b>Windows are grid-aligned</b>: a row at epoch second {@code t} lands in
 * the window starting at {@code floor(t / W) * W}, so every worker in a run
 * stamps the same {@code windowSecond} for the same interval (the hosted
 * {@code flush.window.seconds} behaviour) and the consumer's
 * {@code (RUN_ID, WORKER_ID, LABEL_ID, WINDOW_SECOND)} key lines up fleet-wide.
 *
 * <p><b>The leading-edge window is never closed</b>, even at
 * {@code graceSeconds == 0}: rows arrive in poll order, so more rows for the
 * newest window can still turn up in the next batch. The grace period is
 * measured from a window's <em>last</em> second and absorbs the reordering
 * that JMeter's batched flushes cause — a row stamped second {@code T} can
 * arrive after rows from {@code T+1}. A row arriving after its window was
 * evicted is dropped with a WARNING; sustained drops mean
 * {@code GRACE_PERIOD_SECONDS} is too low.
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

    /** Window width in seconds ({@code FLUSH_WINDOW_SECONDS}); {@code 1} = per-second windows. */
    private final int windowSeconds;

    /**
     * Seconds the leading edge must move past a window's last second before
     * that window closes.
     */
    private final int graceSeconds;

    /**
     * When {@code true} ({@code WORKER_ID_SOURCE=THREAD_NAME}), each row's worker
     * ID comes from {@link JtlRow#threadName()} instead of the fixed
     * {@link #workerId}, so one JTL can carry rows from several workers. Built
     * for the retired master-slave layout; live and wired, but nothing in the
     * platform sets it.
     */
    private final boolean useThreadName;

    /**
     * Live windows, keyed by grid-aligned window start then by
     * {@code "workerId|label"}. The composite inner key is used in both modes so
     * {@link #flushAll} never branches, and the TreeMap gives O(log n) range
     * queries for closeable windows.
     */
    private final TreeMap<Long, Map<String, WindowBucket>> windows;

    /** Highest row epoch second seen — the leading edge {@link #drainCloseable()} measures grace against. */
    private long latestEpochSecond;

    /** Throttles the late-row WARNING, which would otherwise fire once per dropped row. */
    private final WarningThrottle lateRowWarnings = new WarningThrottle();

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /** Per-second windows, original-fleet worker. */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds) {
        this(workerId, region, runId, graceSeconds, false, 0L, 1);
    }

    /** Per-second windows, original-fleet worker, explicit worker-id source. */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds, boolean useThreadName) {
        this(workerId, region, runId, graceSeconds, useThreadName, 0L, 1);
    }

    /** Per-second windows. */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds,
                                    boolean useThreadName, long joinedAtSecond) {
        this(workerId, region, runId, graceSeconds, useThreadName, joinedAtSecond, 1);
    }

    /**
     * @param workerId       pod name, used when {@code useThreadName=false}
     * @param region         placement region, e.g. {@code na-east}
     * @param runId          run identifier (ULID)
     * @param graceSeconds   seconds the leading edge must pass a window's last
     *                       second before it closes; must be >= 0
     * @param useThreadName  when {@code true}, take each row's worker ID from
     *                       {@code threadName} — see the field's Javadoc
     * @param joinedAtSecond seconds since {@code run.startedAt} at which this
     *                       worker joined; {@code 0} for the original fleet
     * @param windowSeconds  window width; must be >= 1
     */
    public TumblingWindowAggregator(String workerId, String region,
                                    String runId, int graceSeconds,
                                    boolean useThreadName, long joinedAtSecond,
                                    int windowSeconds) {
        this.workerId = Objects.requireNonNull(workerId, "workerId cannot be null");
        this.region   = Objects.requireNonNull(region,   "region cannot be null");
        this.runId    = Objects.requireNonNull(runId,    "runId cannot be null");
        if (graceSeconds < 0) {
            throw new IllegalArgumentException("graceSeconds must be >= 0, got: " + graceSeconds);
        }
        if (joinedAtSecond < 0) {
            throw new IllegalArgumentException("joinedAtSecond must be >= 0, got: " + joinedAtSecond);
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be >= 1, got: " + windowSeconds);
        }
        this.graceSeconds      = graceSeconds;
        this.useThreadName     = useThreadName;
        this.joinedAtSecond    = joinedAtSecond;
        this.windowSeconds     = windowSeconds;
        this.windows           = new TreeMap<>();
        this.latestEpochSecond = Long.MIN_VALUE;
    }

    // -----------------------------------------------------------------------
    // Core API
    // -----------------------------------------------------------------------

    /** Grid-aligned window start for a row's epoch second: {@code floor(t / W) * W}. */
    public long windowStartOf(long epochSecond) {
        return Math.floorDiv(epochSecond, windowSeconds) * (long) windowSeconds;
    }

    public int windowSeconds() {
        return windowSeconds;
    }

    /**
     * Routes a parsed row into the correct {@link WindowBucket}, creating the
     * window and bucket on first sight. A row whose window has already been
     * closed and evicted (it arrived after the grace period) is dropped with a
     * WARNING.
     *
     * @param row a parsed JTL row; must not be null
     */
    public void record(JtlRow row) {
        Objects.requireNonNull(row, "row cannot be null");

        long   epochSecond = row.epochSecond();
        long   windowStart = windowStartOf(epochSecond);
        String label       = row.label();

        // Advance the leading edge before checking for late arrivals so that
        // a row exactly at the boundary is included in the current window.
        if (epochSecond > latestEpochSecond) {
            latestEpochSecond = epochSecond;
        }

        if (isEvicted(windowStart)) {
            lateRowWarnings.record(
                    () -> LOG.warning(() -> String.format(
                            "Dropping late-arriving row: label='%s' timestamp='%s' " +
                                    "(window at second %d was already closed; latestEpochSecond=%d, graceSeconds=%d). " +
                                    "Increase GRACE_PERIOD_SECONDS if this happens frequently.",
                            label, row.rawTimestamp(), windowStart, latestEpochSecond, graceSeconds)),
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
                .computeIfAbsent(windowStart, s -> new HashMap<>())
                .computeIfAbsent(bucketKey, k -> new WindowBucket(windowStart, label))
                .record(row);
    }

    /**
     * Returns and removes all {@link WorkerMetricBatch} envelopes for windows
     * that have passed the grace period.
     *
     * <p>A window starting at {@code S} (last second {@code S + W - 1}) is
     * closeable when both {@code latestEpochSecond >= S + W - 1 + graceSeconds}
     * (grace expired) and {@code S + W - 1 < latestEpochSecond} (not the
     * leading edge). With {@code W = 1} this is the per-second rule exactly.
     *
     * <p>Call this after every batch of {@link #record}s to keep memory
     * bounded: the map holds the leading window plus at most
     * {@code ceil(graceSeconds / W) + 1} older ones, each with one bucket per
     * active label.
     *
     * @return unmodifiable list of envelopes; empty when no windows are
     *         closeable yet
     */
    public List<WorkerMetricBatch> drainCloseable() {
        if (windows.isEmpty() || latestEpochSecond == Long.MIN_VALUE) {
            return Collections.emptyList();
        }
        long inclusiveUpperBound = closeableBound();

        // Fast-path: nothing is old enough yet.
        if (inclusiveUpperBound < windows.firstKey()) {
            return Collections.emptyList();
        }

        NavigableMap<Long, Map<String, WindowBucket>> closeable =
                windows.headMap(inclusiveUpperBound, true);
        if (closeable.isEmpty()) {
            return Collections.emptyList();
        }

        List<WorkerMetricBatch> envelopes = flushAll(closeable);
        closeable.clear(); // live view — removes these windows from `windows`
        return Collections.unmodifiableList(envelopes);
    }

    /**
     * Flushes and removes every remaining window regardless of grace and the
     * leading-edge guard — the DRAINING/DONE path once no more rows will arrive.
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

    /** Number of open windows (monitoring and test assertions). */
    public int openWindowCount() {
        return windows.size();
    }

    /** Number of open {@link WindowBucket}s across all open windows (test assertions). */
    public int openBucketCount() {
        return windows.values().stream().mapToInt(Map::size).sum();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Highest window start that is closeable now. The leading edge must be past
     * the window's last second by {@code graceSeconds} — and strictly past it
     * even at zero grace ({@code min(latest - 1, latest - grace)}), then the
     * bound is shifted from the window's last second back to its start.
     */
    private long closeableBound() {
        long lastSecondBound = Math.min(latestEpochSecond - 1, latestEpochSecond - graceSeconds);
        return lastSecondBound - (windowSeconds - 1);
    }

    /**
     * {@code true} when the window at {@code windowStart} satisfied the
     * closeable rule in a previous drain and is no longer present — the same
     * bound as {@link #drainCloseable()}, so "late" means exactly "would have
     * been closed".
     */
    private boolean isEvicted(long windowStart) {
        if (latestEpochSecond == Long.MIN_VALUE) return false;
        return windowStart <= closeableBound() && !windows.containsKey(windowStart);
    }

    /**
     * Converts every bucket in the view into envelopes — one per
     * {@code (workerId, windowSecond)}, split at {@link #MAX_ENTRIES_PER_ENVELOPE}
     * with the same envelope metadata; the consumer's first-write-wins key
     * handles the split rows identically. {@code windowTimestamp} is the
     * window start as ISO-8601 UTC. {@link LinkedHashMap} keeps
     * {@code entries[]} in first-observed label order.
     */
    private List<WorkerMetricBatch> flushAll(Map<Long, Map<String, WindowBucket>> view) {
        List<WorkerMetricBatch> envelopes = new ArrayList<>();
        for (Map.Entry<Long, Map<String, WindowBucket>> windowEntry : view.entrySet()) {
            long windowSec = windowEntry.getKey();
            String windowTimestamp = Instant.ofEpochSecond(windowSec).toString();

            Map<String, List<WindowBucket>> byWorker = new LinkedHashMap<>();
            for (Map.Entry<String, WindowBucket> e : windowEntry.getValue().entrySet()) {
                String key = e.getKey(); // "workerId|label"
                String bucketWorkerId = key.substring(0, key.indexOf('|'));
                byWorker.computeIfAbsent(bucketWorkerId, k -> new ArrayList<>()).add(e.getValue());
            }

            for (Map.Entry<String, List<WindowBucket>> workerGroup : byWorker.entrySet()) {
                String bucketWorkerId = workerGroup.getKey();
                List<WindowBucket> buckets = workerGroup.getValue();
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
     * Pod-name prefix of a JMeter thread name FQDN
     * ({@code jmeter-slave-2.jmeter-workers.perf.svc.cluster.local-Thread Group 1-1}
     * → {@code jmeter-slave-2}); the whole name when there is no dot.
     */
    private static String workerIdFromThreadName(String threadName) {
        int dot = threadName.indexOf('.');
        return dot < 0 ? threadName : threadName.substring(0, dot);
    }
}
