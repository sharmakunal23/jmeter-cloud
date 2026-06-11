package com.perf.orchestrator.kafka;

/**
 * Strategy for producing the Kafka partition key for a {@code WorkerMetricBatch}
 * envelope.
 *
 * <p>The key drives two things simultaneously:
 * <ol>
 *   <li><b>Partition assignment</b> — envelopes with the same key land on the
 *       same partition. With 60 partitions and the standard key shape below,
 *       a single pod's traffic spreads naturally across all partitions over
 *       time (one key per windowSecond).</li>
 *   <li><b>Consumer filtering</b> — downstream consumers can route or filter by
 *       inspecting the key without deserialising the Avro payload.</li>
 * </ol>
 *
 * <p><b>Key shape changed twice in K-1</b> (2026-05-11):
 * <ol>
 *   <li>Initial K-1: {@code "{region}|{workerId}"} — preserved per-pod ordering
 *       but pinned every pod to a single partition. At low pod counts (4 pods,
 *       60 partitions) only 4 partitions saw traffic.</li>
 *   <li>Amended K-1: {@code "{region}|{workerId}|{windowSecond}"} — adds the
 *       window second so the same pod's envelopes spread across partitions
 *       over time. Per-pod ordering is sacrificed (envelopes for second N+1
 *       may land on a different partition than second N) but is not
 *       load-bearing: each envelope is self-describing (carries
 *       {@code windowSecond}), the consumer's INSERT is idempotent on
 *       {@code (runId, workerId, label, windowSecond)}, and time-series
 *       queries sort by {@code windowSecond} server-side.</li>
 * </ol>
 *
 * <p><b>Within-second envelope splits stay co-located.</b> When a pod-window
 * has > {@code MAX_ENTRIES_PER_ENVELOPE} (500) entries and splits into
 * multiple envelopes, all the splits share the same key (same windowSecond)
 * and land on the same partition. Consumer sees them adjacently in one poll.
 *
 * <p>Expressed as a {@link FunctionalInterface} so tests can substitute a
 * fixed-key lambda when partition assignment is irrelevant to what is under test.
 */
@FunctionalInterface
public interface MetricKeyStrategy {

    /**
     * Produces a Kafka message key for the given envelope identity.
     *
     * @param region       AWS region, e.g. {@code us-east-1}
     * @param workerId     Kubernetes pod name, e.g. {@code jmeter-worker-4}
     * @param windowSecond Unix epoch second of the envelope's aggregation window
     * @return non-null, non-empty key string
     */
    String keyFor(String region, String workerId, long windowSecond);

    /**
     * Returns the standard pipe-delimited key strategy:
     * {@code "{region}|{workerId}|{windowSecond}"}.
     *
     * <p>Pipe ({@code |}) was chosen as the separator because it does not
     * appear in AWS region names or Kubernetes pod names. Including
     * {@code windowSecond} makes the key cardinality grow with run duration —
     * a 1-hour run from one pod produces 3600 distinct keys, hash-distributed
     * across all 60 partitions for full fan-out.
     */
    static MetricKeyStrategy standard() {
        return (region, workerId, windowSecond) ->
                region + '|' + workerId + '|' + windowSecond;
    }
}
